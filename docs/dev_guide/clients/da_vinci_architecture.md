# Venice Da-Vinci Client Architecture

The Da-Vinci Client is Venice's embedded, stateful client that stores data locally in RocksDB, providing ultra-low latency reads without network overhead.

## Table of Contents

- [Overview](#overview)
- [Architecture Diagram](#architecture-diagram)
- [Ingestion Pipeline](#ingestion-pipeline)
- [Subscription Modes](#subscription-modes)
- [Record Transformers](#record-transformers)
- [Large Record Handling](#large-record-handling)
- [Key Classes](#key-classes)
- [Configuration Reference](#configuration-reference)
- [Best Practices](#best-practices)

---

## Overview

The Da-Vinci Client provides:
- **Local storage**: Data stored in embedded RocksDB
- **Ultra-low latency**: Sub-millisecond reads with no network overhead
- **Eager caching**: Subscribes to data and keeps it up-to-date
- **Same API**: Compatible with Thin/Fast Client interfaces
- **Record transformation**: Custom processing hooks

### When to Use Da-Vinci Client

| Use Case | Recommendation |
|----------|---------------|
| Ultra-low latency (<1ms P99) | ✅ Da-Vinci Client |
| Memory/disk resources available | ✅ Da-Vinci Client |
| Full partition data needed locally | ✅ Da-Vinci Client |
| Minimal resource usage | ❌ Use Fast/Thin Client |
| Sparse key access pattern | ❌ Use Fast/Thin Client |
| Serverless/ephemeral workloads | ❌ Use Fast/Thin Client |

---

## Architecture Diagram

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                        Da-Vinci Client Architecture                              │
├─────────────────────────────────────────────────────────────────────────────────┤
│                                                                                  │
│  ┌─────────────────────────────────────────────────────────────────────────────┐│
│  │                         Application Process                                 ││
│  │                                                                             ││
│  │  ┌─────────────────────────────────────────────────────────────────────┐   ││
│  │  │                     Da-Vinci Client (Embedded)                       │   ││
│  │  │                                                                      │   ││
│  │  │  ┌─────────────────┐  ┌─────────────────┐  ┌─────────────────────┐  │   ││
│  │  │  │ Read Path       │  │ Subscription    │  │ Stats               │  │   ││
│  │  │  │ get() / batch() │  │ Management      │  │ Collection          │  │   ││
│  │  │  └────────┬────────┘  └────────┬────────┘  └─────────────────────┘  │   ││
│  │  │           │                    │                                     │   ││
│  │  │           v                    v                                     │   ││
│  │  │  ┌─────────────────────────────────────────────────────────────┐    │   ││
│  │  │  │                    Da-Vinci Backend                          │    │   ││
│  │  │  │                                                              │    │   ││
│  │  │  │  ┌───────────────┐  ┌─────────────────┐  ┌──────────────┐   │    │   ││
│  │  │  │  │ Storage       │  │ Ingestion       │  │ Metadata     │   │    │   ││
│  │  │  │  │ Engines       │  │ Service         │  │ Repository   │   │    │   ││
│  │  │  │  │ (RocksDB)     │  │ (SIT per store) │  │              │   │    │   ││
│  │  │  │  └───────┬───────┘  └────────┬────────┘  └──────────────┘   │    │   ││
│  │  │  │          │                   │                               │    │   ││
│  │  │  │          │                   │                               │    │   ││
│  │  │  └──────────┼───────────────────┼───────────────────────────────┘    │   ││
│  │  │             │                   │                                     │   ││
│  │  └─────────────┼───────────────────┼─────────────────────────────────────┘   ││
│  │                │                   │                                         ││
│  └────────────────┼───────────────────┼─────────────────────────────────────────┘│
│                   │                   │                                          │
│           ┌───────┴───────┐   ┌───────┴───────┐                                 │
│           │               │   │               │                                  │
│           v               v   v               v                                  │
│   ┌───────────────┐   ┌───────────────────────────┐                             │
│   │   RocksDB     │   │    Kafka Consumer         │                             │
│   │   (Local      │   │    (VT + RT Topics)       │                             │
│   │    Storage)   │   │                           │                             │
│   └───────────────┘   └───────────────────────────┘                             │
│                               │         │                                        │
│                       ┌───────┘         └───────┐                               │
│                       │                         │                                │
│                       v                         v                                │
│               ┌───────────────┐         ┌───────────────┐                       │
│               │   Kafka VT    │         │   Kafka RT    │                       │
│               │ (Version      │         │ (Real-Time    │                       │
│               │  Topic)       │         │  Topic)       │                       │
│               └───────────────┘         └───────────────┘                       │
│                       │                         │                                │
│                       │  Server produces        │  Producer writes               │
│                       v                         v                                │
│               ┌─────────────────────────────────────────┐                       │
│               │           Venice Server (Leader)        │                       │
│               └─────────────────────────────────────────┘                       │
│                                                                                  │
└─────────────────────────────────────────────────────────────────────────────────┘
```

---

## Ingestion Pipeline

Da-Vinci Client ingests data through the same pipeline as Venice Servers:

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                        Ingestion Pipeline                                        │
├─────────────────────────────────────────────────────────────────────────────────┤
│                                                                                  │
│  ┌─────────────────┐                                                            │
│  │  Kafka Topic    │  (VT = Version Topic, RT = Real-Time Topic)                │
│  │  VT/RT          │                                                            │
│  └────────┬────────┘                                                            │
│           │                                                                      │
│           │ Poll records                                                         │
│           v                                                                      │
│  ┌─────────────────────────────────────────────────────────────────────────────┐│
│  │                    Kafka Consumer Service                                   ││
│  │                                                                             ││
│  │  ┌───────────────────────────────────────────────────────────────────────┐ ││
│  │  │  consumer_poll_request_latency, consumer_poll_result_num              │ ││
│  │  └───────────────────────────────────────────────────────────────────────┘ ││
│  └────────────────────────────────┬────────────────────────────────────────────┘│
│                                   │                                              │
│                                   │ Batch of records                             │
│                                   v                                              │
│  ┌─────────────────────────────────────────────────────────────────────────────┐│
│  │               Store Ingestion Task (SIT)                                    ││
│  │                                                                             ││
│  │  ┌─────────────┐  ┌──────────────────┐  ┌─────────────────────────────┐    ││
│  │  │ Deserialize │  │ Apply Write      │  │ Conflict Resolution (DCR)  │    ││
│  │  │ Record      │  │ Compute          │  │ (for AA replication)       │    ││
│  │  └──────┬──────┘  └────────┬─────────┘  └──────────────┬──────────────┘    ││
│  │         │                  │                           │                    ││
│  │         └──────────────────┴───────────────────────────┘                    ││
│  │                                │                                            ││
│  │  ┌────────────────────────────────────────────────────────────────────────┐││
│  │  │  records_consumed, bytes_consumed, leader_*/follower_* metrics         │││
│  │  └────────────────────────────────────────────────────────────────────────┘││
│  └────────────────────────────────┬────────────────────────────────────────────┘│
│                                   │                                              │
│                                   │ Processed record                             │
│                                   v                                              │
│  ┌─────────────────────────────────────────────────────────────────────────────┐│
│  │                 Record Transformer (Optional)                               ││
│  │                                                                             ││
│  │  Transform record before storage (filter, enrich, project)                 ││
│  │                                                                             ││
│  │  ┌────────────────────────────────────────────────────────────────────────┐││
│  │  │  record_transformation_latency, transformed_record_count               │││
│  │  └────────────────────────────────────────────────────────────────────────┘││
│  └────────────────────────────────┬────────────────────────────────────────────┘│
│                                   │                                              │
│                                   │ Final record                                 │
│                                   v                                              │
│  ┌─────────────────────────────────────────────────────────────────────────────┐│
│  │                      RocksDB Storage Engine                                 ││
│  │                                                                             ││
│  │  ┌─────────────────────────────────────────────────────────────────────┐   ││
│  │  │  storage_engine_put_latency, disk_usage_in_bytes                    │   ││
│  │  └─────────────────────────────────────────────────────────────────────┘   ││
│  └─────────────────────────────────────────────────────────────────────────────┘│
│                                                                                  │
└─────────────────────────────────────────────────────────────────────────────────┘
```

### Ingestion Latency Breakdown

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                   Ingestion Latency Breakdown                                    │
├─────────────────────────────────────────────────────────────────────────────────┤
│                                                                                  │
│  Producer         Source      Leader         Local       Follower    Ready      │
│  Timestamp        Broker      Consumer       Broker      Consumer    to Serve   │
│     │               │            │             │            │           │        │
│     v               v            v             v            v           v        │
│  ───┼───────────────┼────────────┼─────────────┼────────────┼───────────┼────   │
│     │               │            │             │            │           │        │
│     │◄─────────────►│            │             │            │           │        │
│     │ producer_to_  │            │             │            │           │        │
│     │ source_broker │            │             │            │           │        │
│     │ _latency      │            │             │            │           │        │
│     │               │            │             │            │           │        │
│     │               │◄──────────►│             │            │           │        │
│     │               │source_     │             │            │           │        │
│     │               │broker_to_  │             │            │           │        │
│     │               │leader_     │             │            │           │        │
│     │               │consumer_   │             │            │           │        │
│     │               │latency     │             │            │           │        │
│     │               │            │             │            │           │        │
│     │               │            │◄───────────►│            │           │        │
│     │               │            │nearline_    │            │           │        │
│     │               │            │producer_to_ │            │           │        │
│     │               │            │local_broker │            │           │        │
│     │               │            │_latency     │            │           │        │
│     │               │            │             │            │           │        │
│     │               │            │             │◄──────────►│           │        │
│     │               │            │             │nearline_   │           │        │
│     │               │            │             │local_      │           │        │
│     │               │            │             │broker_to_  │           │        │
│     │               │            │             │ready_to_   │           │        │
│     │               │            │             │serve_      │           │        │
│     │               │            │             │latency     │           │        │
│                                                                                  │
│  Metrics track each segment of the ingestion path                               │
│                                                                                  │
└─────────────────────────────────────────────────────────────────────────────────┘
```

---

## Subscription Modes

Da-Vinci Client offers flexible subscription modes:

### Subscribe All

```java
// Subscribe to all partitions
DaVinciClient<String, GenericRecord> client = factory.getAndStartGenericAvroClient(
    storeName, new DaVinciConfig());

client.subscribeAll().get(); // Blocks until caught up

// Now serve reads
GenericRecord value = client.get("key").get();
```

### Subscribe to Specific Partitions

```java
// Subscribe to specific partitions (e.g., partition 0 and 1)
Set<Integer> partitions = Set.of(0, 1);

client.subscribe(partitions).get();

// Can only serve keys in subscribed partitions
GenericRecord value = client.get("key").get(); // Key must be in partition 0 or 1
```

### Subscription States

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                        Subscription State Machine                                │
├─────────────────────────────────────────────────────────────────────────────────┤
│                                                                                  │
│  ┌──────────────────┐                                                           │
│  │ NOT_SUBSCRIBED   │ ─── subscribe() ───►                                      │
│  └──────────────────┘                                                           │
│                                                                                  │
│  ┌──────────────────┐                                                           │
│  │   SUBSCRIBING    │ ─── data caught up ───►                                   │
│  │                  │                                                           │
│  │  (consuming from │                                                           │
│  │   VT, catching   │                                                           │
│  │   up to EOP)     │                                                           │
│  └──────────────────┘                                                           │
│                                                                                  │
│  ┌──────────────────┐                                                           │
│  │ ONLINE (Ready    │ ─── consuming RT for nearline updates                     │
│  │  to Serve)       │                                                           │
│  │                  │                                                           │
│  │  - Serves reads  │                                                           │
│  │  - Hybrid: VT+RT │                                                           │
│  │  - Batch: VT only│                                                           │
│  └──────────────────┘                                                           │
│          │                                                                       │
│          │ unsubscribe()                                                        │
│          v                                                                       │
│  ┌──────────────────┐                                                           │
│  │ UNSUBSCRIBING    │ ─── cleanup complete ───► NOT_SUBSCRIBED                  │
│  └──────────────────┘                                                           │
│                                                                                  │
└─────────────────────────────────────────────────────────────────────────────────┘
```

---

## Record Transformers

Da-Vinci supports custom record transformation during ingestion:

```java
// Example: Filter records during ingestion
DaVinciRecordTransformer<String, GenericRecord, GenericRecord> transformer =
    new DaVinciRecordTransformer<>() {
        @Override
        public DaVinciRecordTransformerResult<GenericRecord> transform(
            Lazy<String> key, Lazy<GenericRecord> value) {
            // Filter: only keep records where "active" is true
            if (value.get().get("active").equals(true)) {
                return new DaVinciRecordTransformerResult<>(value.get());
            }
            return DaVinciRecordTransformerResult.skip();
        }
    };

DaVinciConfig config = new DaVinciConfig()
    .setRecordTransformer(transformer);
```

### Transformer Use Cases

| Use Case | Description |
|----------|-------------|
| **Filtering** | Skip records that don't match criteria |
| **Projection** | Store only needed fields |
| **Enrichment** | Add computed fields |
| **Format conversion** | Convert between formats |

---

## Large Record Handling

Records larger than 1MB are automatically chunked:

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                      Large Record Chunking                                       │
├─────────────────────────────────────────────────────────────────────────────────┤
│                                                                                  │
│  Write Path (VeniceWriter):                                                     │
│                                                                                  │
│  ┌───────────────────────────────────────────────────────────────────────────┐  │
│  │                    Original Record (5MB)                                   │  │
│  └───────────────────────────────────────────────────────────────────────────┘  │
│                                      │                                           │
│                                      │ Automatic chunking                        │
│                                      v                                           │
│  ┌───────────────┐ ┌───────────────┐ ┌───────────────┐ ┌───────────────┐        │
│  │   Chunk 1     │ │   Chunk 2     │ │   Chunk 3     │ │   Chunk 4     │        │
│  │   (1MB)       │ │   (1MB)       │ │   (1MB)       │ │   (1MB)       │ ...    │
│  └───────────────┘ └───────────────┘ └───────────────┘ └───────────────┘        │
│                                      │                                           │
│                                      │                                           │
│                                      v                                           │
│  ┌───────────────────────────────────────────────────────────────────────────┐  │
│  │                  ChunkedValueManifest                                      │  │
│  │  - Total size: 5MB                                                        │  │
│  │  - Chunk count: 5                                                         │  │
│  │  - Chunk keys: [key__chunk_0, key__chunk_1, ...]                         │  │
│  └───────────────────────────────────────────────────────────────────────────┘  │
│                                                                                  │
│  Read Path (Da-Vinci):                                                          │
│                                                                                  │
│  client.get(key) ───► Detect manifest ───► Fetch all chunks ───► Reassemble    │
│                                                                                  │
│  Metrics:                                                                       │
│    assembled_record_size_in_bytes - Size after reassembly                       │
│    assembled_record_size_ratio - Ratio to storage size                          │
│                                                                                  │
└─────────────────────────────────────────────────────────────────────────────────┘
```

---

## Key Classes

| Class | Location | Responsibility |
|-------|----------|----------------|
| `DaVinciClient` | `clients/da-vinci-client/.../client/` | Public API for embedded store access |
| `DaVinciBackend` | Same directory | Backend managing ingestion, storage, metadata |
| `AvroGenericDaVinciClient` | Same directory | Generic Avro implementation |
| `AvroSpecificDaVinciClient` | Same directory | Specific record implementation |
| `StoreIngestionTask` | `.../kafka/consumer/` | Per-store ingestion from Kafka |
| `RocksDBStorageEngine` | `.../store/rocksdb/` | RocksDB storage implementation |
| `DaVinciRecordTransformer` | `.../client/` | Custom transformation hook |

### Class Hierarchy

```
┌───────────────────────────────────────────────────────────────────────────────┐
│                     Da-Vinci Client Class Hierarchy                           │
├───────────────────────────────────────────────────────────────────────────────┤
│                                                                               │
│  ┌─────────────────────────────────────────┐                                 │
│  │     DaVinciClient<K, V> (interface)     │  ← Public API                   │
│  │  (subscribe, get, batchGet, etc.)       │                                 │
│  └──────────────────┬──────────────────────┘                                 │
│                     │ implements                                              │
│                     v                                                         │
│  ┌─────────────────────────────────────────┐                                 │
│  │      AvroGenericDaVinciClient           │  ← Generic Avro reads           │
│  └──────────────────┬──────────────────────┘                                 │
│                     │ extends                                                 │
│                     v                                                         │
│  ┌─────────────────────────────────────────┐                                 │
│  │    AvroSpecificDaVinciClient<T>         │  ← Type-safe reads              │
│  └─────────────────────────────────────────┘                                 │
│                                                                               │
│  Backend Components:                                                          │
│                                                                               │
│  ┌─────────────────────────────────────────┐                                 │
│  │         DaVinciBackend                  │  ← Orchestrates all services    │
│  └──────────────────┬──────────────────────┘                                 │
│                     │ uses                                                    │
│       ┌─────────────┼─────────────┬─────────────┐                            │
│       │             │             │             │                             │
│       v             v             v             v                             │
│  ┌─────────┐  ┌──────────┐  ┌──────────┐  ┌──────────────┐                   │
│  │Kafka    │  │Ingestion │  │Storage   │  │Metadata      │                   │
│  │Consumer │  │Service   │  │Service   │  │Repository    │                   │
│  │Service  │  │(SITs)    │  │(RocksDB) │  │              │                   │
│  └─────────┘  └──────────┘  └──────────┘  └──────────────┘                   │
│                                                                               │
└───────────────────────────────────────────────────────────────────────────────┘
```

---

## Configuration Reference

### DaVinciConfig

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `storeName` | String | Required | Venice store name |
| `isolated` | boolean | false | Isolate from other DaVinci clients |
| `managedClients` | boolean | true | Backend manages client lifecycle |
| `recordTransformer` | DaVinciRecordTransformer | null | Custom transformation |

### Client Configuration

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `dataBasePath` | String | Required | Base path for RocksDB storage |
| `pushStatusStoreEnabled` | boolean | false | Enable push status tracking |
| `cacheEnabled` | boolean | false | Enable in-memory cache |
| `cacheConfig` | ObjectCacheConfig | null | Cache configuration |

### RocksDB Configuration

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `rocksDBBlockCacheSizeInBytes` | long | 128MB | Block cache size |
| `rocksDBWriteBufferSizeInBytes` | long | 64MB | Write buffer size |
| `rocksDBMaxOpenFiles` | int | 1000 | Max open file handles |
| `rocksDBPlainTableEnabled` | boolean | false | Use PlainTable format |

---

## Best Practices

### Resource Planning

```java
// Calculate storage requirements
// Storage = (partition_count * avg_partition_size)
// Memory = storage + block_cache + write_buffers + overhead

// Example: 10 partitions, 1GB each
// Disk: ~10GB
// Memory: ~10GB + 128MB (cache) + 64MB (buffers) + overhead
```

### Subscription Strategy

```java
// ✅ Good: Subscribe once at startup
DaVinciClient client = factory.getAndStartGenericAvroClient(storeName, config);
client.subscribeAll().get(); // Wait for initial sync
// Use client for the lifetime of the application

// ❌ Bad: Repeated subscribe/unsubscribe
for (Request request : requests) {
    client.subscribe(Set.of(getPartition(request.getKey()))).get();
    client.get(request.getKey()).get();
    client.unsubscribe(Set.of(getPartition(request.getKey())));
}
```

### Graceful Shutdown

```java
// Proper shutdown sequence
Runtime.getRuntime().addShutdownHook(new Thread(() -> {
    try {
        // Stop accepting new requests first
        stopAcceptingRequests();

        // Then close client (will unsubscribe and cleanup)
        client.close();
    } catch (Exception e) {
        log.error("Error during shutdown", e);
    }
}));
```

### Monitoring Subscription Health

```java
// Check if partitions are ready to serve
Set<Integer> readyPartitions = client.getReadyPartitions();
if (!readyPartitions.containsAll(expectedPartitions)) {
    log.warn("Some partitions not ready: expected={}, ready={}",
        expectedPartitions, readyPartitions);
}
```

### Handling Version Swaps

```java
// Da-Vinci automatically handles version swaps
// Monitor heartbeat metrics for lag during transitions
// heartbeat_delay metric shows time since last heartbeat

// If lag is high during version swap:
// 1. Check Kafka consumption rate
// 2. Verify disk I/O is not bottleneck
// 3. Consider increasing ingestion thread pool
```

---

## See Also

- [Da-Vinci Metrics](da_vinci_metrics.md) - Comprehensive metrics reference
- [Fast Client Architecture](fast_client_architecture.md) - Remote client alternative
- [Thin Client Architecture](thin_client_architecture.md) - Minimal-dependency option
- [Clients Overview](clients_overview.md) - Client comparison
