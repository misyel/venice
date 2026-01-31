# Venice Producer Architecture

The Venice Producer is an asynchronous write client for real-time data ingestion, allowing applications to write individual records to Venice stores.

## Table of Contents

- [Overview](#overview)
- [Architecture Diagram](#architecture-diagram)
- [Write Operations](#write-operations)
- [Data Flow](#data-flow)
- [Durability Guarantees](#durability-guarantees)
- [Conflict Resolution](#conflict-resolution)
- [Key Classes](#key-classes)
- [Configuration Reference](#configuration-reference)
- [Best Practices](#best-practices)

---

## Overview

The Venice Producer provides:
- **Real-time writes**: Put, Delete, and Update operations
- **Async API**: Non-blocking writes with CompletableFuture
- **Durability**: Writes to Kafka RT topic for persistence
- **CRDT support**: Logical timestamps for conflict-free replication

### When to Use Venice Producer

| Use Case | Recommendation |
|----------|---------------|
| Real-time/incremental updates | ✅ Venice Producer |
| Partial updates (write compute) | ✅ Venice Producer |
| Low-latency writes | ✅ Venice Producer |
| Full dataset replacement | ❌ Use Push Job |
| Batch data from Hadoop | ❌ Use Push Job |
| One-time data load | ❌ Use Push Job |

---

## Architecture Diagram

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                        Venice Producer Architecture                              │
├─────────────────────────────────────────────────────────────────────────────────┤
│                                                                                  │
│  ┌─────────────────┐                                                            │
│  │   Application   │                                                            │
│  └────────┬────────┘                                                            │
│           │                                                                      │
│           │ put(key, value) / delete(key) / update(key, ops)                    │
│           v                                                                      │
│  ┌─────────────────────────────────────────────────────────────────────────────┐│
│  │                         Venice Producer                                     ││
│  │                                                                             ││
│  │  ┌─────────────────┐  ┌─────────────────────┐  ┌─────────────────────────┐ ││
│  │  │ Operation       │  │ Serialization &     │  │ Metrics                 │ ││
│  │  │ Preprocessing   │  │ Compression         │  │ (VeniceProducerMetrics) │ ││
│  │  └────────┬────────┘  └──────────┬──────────┘  └─────────────────────────┘ ││
│  │           │                      │                                          ││
│  │           └──────────────────────┘                                          ││
│  │                      │                                                      ││
│  │                      v                                                      ││
│  │  ┌─────────────────────────────────────────────────────────────────────┐   ││
│  │  │                    VeniceWriter                                      │   ││
│  │  │  • Produces to Kafka RT topic                                       │   ││
│  │  │  • Handles chunking for large records                               │   ││
│  │  │  • Sets logical timestamps for DCR                                  │   ││
│  │  └──────────────────────────────────────────────────────────────────────┘   ││
│  │                      │                                                      ││
│  └──────────────────────┼──────────────────────────────────────────────────────┘│
│                         │                                                        │
│                         │ Kafka Produce                                          │
│                         v                                                        │
│  ┌─────────────────────────────────────────────────────────────────────────────┐│
│  │                         Kafka RT Topic                                      ││
│  │                    (Store_rt - Real-Time Topic)                             ││
│  │                                                                             ││
│  │  Partition 0: [key1:val1] [key2:val2] [key3:val3] ...                      ││
│  │  Partition 1: [key4:val4] [key5:val5] ...                                  ││
│  │  Partition N: ...                                                           ││
│  └─────────────────────────────────────────────────────────────────────────────┘│
│                         │                                                        │
│                         │ Server/Da-Vinci consumes                              │
│                         v                                                        │
│  ┌─────────────────────────────────────────────────────────────────────────────┐│
│  │                     Venice Server / Da-Vinci                                ││
│  │                                                                             ││
│  │  ┌─────────────────┐                                                       ││
│  │  │ StoreIngestion  │ ──► Apply to RocksDB ──► Available for reads          ││
│  │  │ Task            │                                                       ││
│  │  └─────────────────┘                                                       ││
│  └─────────────────────────────────────────────────────────────────────────────┘│
│                                                                                  │
└─────────────────────────────────────────────────────────────────────────────────┘
```

---

## Write Operations

### PUT Operation

Insert or update a key-value pair:

```java
// Async put
CompletableFuture<Void> future = producer.put(key, value);

// With callback
future.whenComplete((result, error) -> {
    if (error != null) {
        log.error("Put failed for key: {}", key, error);
    } else {
        log.info("Put succeeded for key: {}", key);
    }
});
```

### DELETE Operation

Remove a key from the store:

```java
// Async delete
CompletableFuture<Void> future = producer.delete(key);
```

### UPDATE Operation (Write Compute)

Partial update using write compute operations:

```java
// Update specific fields without reading full value
CompletableFuture<Void> future = producer.update(key, updateBuilder -> {
    updateBuilder.setField("count", Operations.add(1));
    updateBuilder.setField("lastUpdated", Operations.set(System.currentTimeMillis()));
});
```

### Operation Flow

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                        Write Operation Flow                                      │
├─────────────────────────────────────────────────────────────────────────────────┤
│                                                                                  │
│  Application Call:                                                              │
│  producer.put(key, value)                                                       │
│     │                                                                           │
│     │ (1) Preprocessing                                                         │
│     │     preprocessing_latency metric                                          │
│     v                                                                           │
│  ┌───────────────────────────────────────────────────────────────┐              │
│  │  Validate key/value                                            │              │
│  │  Set logical timestamp (for DCR)                               │              │
│  │  Serialize to Avro bytes                                       │              │
│  └───────────────────────────────────────────────────────────────┘              │
│     │                                                                           │
│     │ (2) Produce to Kafka                                                      │
│     │     pending_write_operation++                                             │
│     v                                                                           │
│  ┌───────────────────────────────────────────────────────────────┐              │
│  │  VeniceWriter.put(key, value, logicalTimestamp)                │              │
│  │                                                                │              │
│  │  If value > 1MB:                                               │              │
│  │    - Chunk into segments                                       │              │
│  │    - Write manifest                                            │              │
│  └───────────────────────────────────────────────────────────────┘              │
│     │                                                                           │
│     │ (3) Kafka ACK                                                             │
│     │     produce_to_durable_buffer_latency metric                              │
│     │     pending_write_operation--                                             │
│     v                                                                           │
│  ┌───────────────────────────────────────────────────────────────┐              │
│  │  On Success:                                                   │              │
│  │    success_write_operation++                                   │              │
│  │    Complete future                                             │              │
│  │                                                                │              │
│  │  On Failure:                                                   │              │
│  │    failed_write_operation++                                    │              │
│  │    Complete future exceptionally                               │              │
│  └───────────────────────────────────────────────────────────────┘              │
│                                                                                  │
└─────────────────────────────────────────────────────────────────────────────────┘
```

---

## Data Flow

### From Producer to Reader

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                    Producer to Reader Data Flow                                  │
├─────────────────────────────────────────────────────────────────────────────────┤
│                                                                                  │
│  Time ─────────────────────────────────────────────────────────────────────────►│
│                                                                                  │
│  Producer         Kafka            Server            Reader                     │
│     │               │                │                 │                         │
│  1. put()           │                │                 │                         │
│     │───────────────►│               │                 │                         │
│     │  produce      │                │                 │                         │
│     │               │                │                 │                         │
│  2. │◄──────────────│               │                 │                         │
│     │  ack          │                │                 │                         │
│     │               │                │                 │                         │
│     │               │  3. consume    │                 │                         │
│     │               │───────────────►│                 │                         │
│     │               │                │                 │                         │
│     │               │                │  4. persist     │                         │
│     │               │                │  to RocksDB     │                         │
│     │               │                │                 │                         │
│     │               │                │                 │  5. read                │
│     │               │                │◄────────────────│                         │
│     │               │                │                 │                         │
│     │               │                │  6. return      │                         │
│     │               │                │────────────────►│                         │
│     │               │                │                 │                         │
│                                                                                  │
│  Latency points:                                                                │
│    • 1-2: produce_to_durable_buffer_latency (Kafka ack)                         │
│    • 2-4: Server ingestion latency (nearline_*_latency metrics)                 │
│    • 4-5: Value available for reads                                             │
│                                                                                  │
└─────────────────────────────────────────────────────────────────────────────────┘
```

---

## Durability Guarantees

### What "Durable" Means

| Stage | Durability | Description |
|-------|------------|-------------|
| Kafka ACK | ✅ Durable | Replicated to Kafka brokers |
| Server Ingestion | ✅ Persistent | Written to RocksDB |
| Read Available | ✅ Visible | Can be read by clients |

### Important Notes

1. **Kafka ACK != Read Available**: A successful `put()` means data is in Kafka, not that it's immediately readable
2. **Eventual Consistency**: Readers may not see the write immediately
3. **Order Preservation**: Within a partition, writes are ordered by Kafka offset

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                      Durability Timeline                                         │
├─────────────────────────────────────────────────────────────────────────────────┤
│                                                                                  │
│  Producer:  put() completes ──────┬──────────────────────────────────────────   │
│                                   │                                              │
│  Kafka:     Data replicated ──────┘                                              │
│                                   │                                              │
│  Server:    Data persisted ───────┼─────────┬───────────────────────────────    │
│                                   │         │                                    │
│  Reader:    Data visible ─────────┼─────────┼──────────┬────────────────────    │
│                                   │         │          │                         │
│             |<--- Kafka ACK --->| |<- Ingestion ->|    |                         │
│                                                                                  │
│  The put() CompletableFuture completes at Kafka ACK, not at read visibility    │
│                                                                                  │
└─────────────────────────────────────────────────────────────────────────────────┘
```

---

## Conflict Resolution

For Active-Active replication, the Producer sets logical timestamps used for conflict resolution:

### Logical Timestamp

```java
// Producer automatically sets timestamp
// Based on system time or provided explicitly
producer.put(key, value); // Uses System.currentTimeMillis()

// Or with explicit timestamp
producer.put(key, value, timestamp);
```

### Conflict Resolution Rules

| Scenario | Resolution |
|----------|------------|
| Same key, different regions | Higher timestamp wins |
| Same timestamp | Tiebreaker (region ID, hash) |
| Delete vs Put | Timestamp comparison |

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                    Cross-Region Conflict Resolution                              │
├─────────────────────────────────────────────────────────────────────────────────┤
│                                                                                  │
│  Region A                                  Region B                             │
│     │                                         │                                  │
│  put(key, "A", t=100)                    put(key, "B", t=110)                   │
│     │                                         │                                  │
│     └────────────────┐   ┌────────────────────┘                                 │
│                      │   │                                                       │
│                      v   v                                                       │
│              ┌───────────────────┐                                              │
│              │ Conflict Detected │                                              │
│              │ t=100 vs t=110    │                                              │
│              └─────────┬─────────┘                                              │
│                        │                                                         │
│                        v                                                         │
│              ┌───────────────────┐                                              │
│              │ t=110 wins        │                                              │
│              │ value = "B"       │                                              │
│              └───────────────────┘                                              │
│                                                                                  │
│  Both regions eventually converge to value "B"                                  │
│                                                                                  │
└─────────────────────────────────────────────────────────────────────────────────┘
```

---

## Key Classes

| Class | Location | Responsibility |
|-------|----------|----------------|
| `VeniceProducer` | `clients/venice-producer/` | Public API interface |
| `OnlineVeniceProducer` | Same directory | Online producer implementation |
| `VeniceWriter` | `internal/venice-common/.../writer/` | Low-level Kafka producer |
| `VeniceWriterFactory` | Same directory | Creates VeniceWriter instances |
| `VeniceProducerMetrics` | `.../producer/` | Producer metrics |

### Class Hierarchy

```
┌───────────────────────────────────────────────────────────────────────────────┐
│                      Venice Producer Class Hierarchy                          │
├───────────────────────────────────────────────────────────────────────────────┤
│                                                                               │
│  ┌─────────────────────────────────────────┐                                 │
│  │     VeniceProducer<K, V> (interface)    │  ← Public API                   │
│  │  (put, delete, update, close)           │                                 │
│  └──────────────────┬──────────────────────┘                                 │
│                     │ implements                                              │
│                     v                                                         │
│  ┌─────────────────────────────────────────┐                                 │
│  │       OnlineVeniceProducer              │  ← Real-time writes             │
│  └──────────────────┬──────────────────────┘                                 │
│                     │ uses                                                    │
│                     v                                                         │
│  ┌─────────────────────────────────────────┐                                 │
│  │           VeniceWriter                  │  ← Kafka producer wrapper       │
│  │  • Produces to Kafka                    │                                 │
│  │  • Handles chunking                     │                                 │
│  │  • Control messages                     │                                 │
│  └──────────────────┬──────────────────────┘                                 │
│                     │ uses                                                    │
│                     v                                                         │
│  ┌─────────────────────────────────────────┐                                 │
│  │       ApacheKafkaProducer               │  ← Actual Kafka client          │
│  └─────────────────────────────────────────┘                                 │
│                                                                               │
└───────────────────────────────────────────────────────────────────────────────┘
```

---

## Configuration Reference

### Producer Configuration

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `storeName` | String | Required | Venice store name |
| `veniceURL` | String | Required | Venice controller/router URL |
| `producerConfig` | Properties | null | Kafka producer properties |

### Kafka Producer Properties

| Property | Default | Description |
|----------|---------|-------------|
| `acks` | `all` | Wait for all replicas |
| `retries` | `3` | Retry count for transient failures |
| `batch.size` | `16384` | Batch size in bytes |
| `linger.ms` | `0` | Time to wait for batch |
| `buffer.memory` | `33554432` | Buffer size |

### Write Compute Configuration

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `writeComputeEnabled` | boolean | false | Enable write compute |
| `writeComputeSchema` | Schema | null | Update schema |

---

## Best Practices

### Async Handling

```java
// ✅ Good: Handle completion properly
producer.put(key, value)
    .whenComplete((result, error) -> {
        if (error != null) {
            // Handle error (log, retry, alert)
            metrics.recordFailure();
        } else {
            metrics.recordSuccess();
        }
    });

// ❌ Bad: Ignoring the future
producer.put(key, value); // Fire and forget - no error handling!
```

### Batching Writes

```java
// ✅ Good: Batch multiple writes
List<CompletableFuture<Void>> futures = new ArrayList<>();
for (Record record : records) {
    futures.add(producer.put(record.getKey(), record.getValue()));
}
// Wait for all
CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get();

// ❌ Bad: Sync wait after each write
for (Record record : records) {
    producer.put(record.getKey(), record.getValue()).get(); // Slow!
}
```

### Error Handling

```java
try {
    producer.put(key, value).get(5, TimeUnit.SECONDS);
} catch (TimeoutException e) {
    // Kafka ack took too long
    log.warn("Write timeout for key: {}", key);
} catch (ExecutionException e) {
    Throwable cause = e.getCause();
    if (cause instanceof VeniceException) {
        // Venice-specific error
        handleVeniceError((VeniceException) cause);
    } else {
        // Kafka or other error
        handleOtherError(cause);
    }
}
```

### Resource Management

```java
// Create producer once, reuse
VeniceProducer producer = factory.createProducer(storeName, config);

try {
    // Use producer for many writes
    for (Request request : requests) {
        producer.put(request.getKey(), request.getValue());
    }
} finally {
    // Close when done
    producer.close();
}
```

### Monitoring Write Health

```java
// Monitor key metrics
// 1. pending_write_operation - should not grow unbounded
// 2. failed_write_operation - should be near zero
// 3. produce_to_durable_buffer_latency - should be stable

// If pending operations grow:
// - Check Kafka health
// - Reduce write rate
// - Increase producer buffer
```

---

## See Also

- [Producer Metrics](producer_metrics.md) - Metrics reference
- [Da-Vinci Architecture](da_vinci_architecture.md) - Consumer side
- [Clients Overview](clients_overview.md) - Client comparison
