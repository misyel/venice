# Venice Changelog Consumer Architecture

This document describes the architecture of Venice Changelog Consumer (CDC client) for consuming change events from Venice stores.

## Table of Contents

- [Overview](#overview)
- [Consumer Types](#consumer-types)
- [Architecture Diagram](#architecture-diagram)
- [Subscription Flow](#subscription-flow)
- [Seek Operations](#seek-operations)
- [Version Swap Handling](#version-swap-handling)
- [Checkpointing](#checkpointing)
- [Key Classes Reference](#key-classes-reference)
- [Configuration Reference](#configuration-reference)
- [Usage Examples](#usage-examples)

---

## Overview

Venice Changelog Consumer provides Change Data Capture (CDC) capabilities, allowing applications to consume a stream of change events from Venice stores. Unlike Da-Vinci which maintains a local copy of the data, Changelog Consumer streams events directly from Kafka topics.

### Key Capabilities

| Feature | Description |
|---------|-------------|
| **Change Events** | Consume put/delete operations as they occur |
| **Checkpointing** | Save and restore consumption position |
| **Seek Operations** | Jump to specific positions (timestamp, checkpoint, beginning, tail) |
| **Version Handling** | Automatically transitions across store versions |
| **Chunked Records** | Transparently reassembles large records (>1MB) |

### When to Use Changelog Consumer

| Use Case | Fit |
|----------|-----|
| Stream processing | Excellent |
| Data replication to external systems | Excellent |
| Change event auditing | Excellent |
| Real-time analytics | Good |
| Point-in-time queries | Poor (use Da-Vinci) |
| Low-latency lookups | Poor (use Fast/Thin Client) |

---

## Consumer Types

Venice provides several changelog consumer implementations:

### 1. VeniceChangelogConsumer (Basic)

Basic changelog consumer that streams events from version topics (VT).

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                    VeniceChangelogConsumer                                       │
├─────────────────────────────────────────────────────────────────────────────────┤
│                                                                                  │
│  • Consumes from Version Topics (VT): Store_v1, Store_v2, ...                   │
│  • Provides change events: PUT, DELETE                                          │
│  • Supports seeking to checkpoint, timestamp, beginning, tail                   │
│  • Automatically handles version transitions                                    │
│                                                                                  │
└─────────────────────────────────────────────────────────────────────────────────┘
```

### 2. VeniceAfterImageConsumerImpl

Consumer that provides the "after image" - the final value after a change.

### 3. LocalBootstrappingVeniceChangelogConsumer

Optimized consumer that uses local Da-Vinci storage for bootstrap and Kafka for real-time events.

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│              LocalBootstrappingVeniceChangelogConsumer                           │
├─────────────────────────────────────────────────────────────────────────────────┤
│                                                                                  │
│  Bootstrap Phase:                                                               │
│    • Reads existing data from local Da-Vinci RocksDB                            │
│    • Fast initial catch-up without Kafka replay                                 │
│                                                                                  │
│  Streaming Phase:                                                               │
│    • Switches to Kafka consumption for real-time events                         │
│    • Maintains consistency across transition                                    │
│                                                                                  │
└─────────────────────────────────────────────────────────────────────────────────┘
```

---

## Architecture Diagram

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                     Changelog Consumer Architecture                              │
├─────────────────────────────────────────────────────────────────────────────────┤
│                                                                                  │
│                                                                                  │
│  ┌─────────────────┐                                                            │
│  │   Application   │                                                            │
│  └────────┬────────┘                                                            │
│           │ poll()                                                              │
│           │ seekToCheckpoint()                                                  │
│           │ seekToTimestamp()                                                   │
│           v                                                                     │
│  ┌─────────────────────────────────────────────────────────────────────────┐   │
│  │                    VeniceChangelogConsumer                               │   │
│  │  ┌─────────────┐  ┌─────────────────┐  ┌─────────────────────────────┐  │   │
│  │  │ Subscription│  │ Version Tracker │  │ Chunked Record Assembler    │  │   │
│  │  │ Manager     │  │ (VT switching)  │  │ (records > 1MB)             │  │   │
│  │  └─────────────┘  └─────────────────┘  └─────────────────────────────┘  │   │
│  └───────────────────────────────┬─────────────────────────────────────────┘   │
│                                  │                                              │
│                                  v                                              │
│  ┌─────────────────────────────────────────────────────────────────────────┐   │
│  │                         Kafka Consumer                                   │   │
│  └───────────────────────────────┬─────────────────────────────────────────┘   │
│                                  │                                              │
│           ┌──────────────────────┼──────────────────────┐                      │
│           │                      │                      │                       │
│           v                      v                      v                       │
│  ┌────────────────┐    ┌────────────────┐    ┌────────────────┐                │
│  │  Store_v1 (VT) │    │  Store_v2 (VT) │    │  Store_rt (RT) │                │
│  │  (historical)  │    │   (current)    │    │  (real-time)   │                │
│  └────────────────┘    └────────────────┘    └────────────────┘                │
│                                                                                  │
└─────────────────────────────────────────────────────────────────────────────────┘
```

### Components

| Component | Responsibility |
|-----------|----------------|
| **Subscription Manager** | Tracks subscribed partitions and their state |
| **Version Tracker** | Monitors current store version, handles VT transitions |
| **Chunked Record Assembler** | Reassembles large records from multiple chunks |
| **Kafka Consumer** | Low-level Kafka consumption |

---

## Subscription Flow

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                         Subscription Lifecycle                                   │
├─────────────────────────────────────────────────────────────────────────────────┤
│                                                                                  │
│  1. SUBSCRIBE                                                                   │
│     │                                                                           │
│     │  consumer.subscribe(Set.of(0, 1, 2))                                      │
│     │                                                                           │
│     v                                                                           │
│  ┌───────────────────────────────────────────────────────────────┐              │
│  │ Subscription Initialization                                    │              │
│  │  • Fetch current store version from metadata                  │              │
│  │  • Assign Kafka partitions for current version topic          │              │
│  │  • Seek to beginning of version topic (or specified position) │              │
│  └───────────────────────────────────────────────────────────────┘              │
│     │                                                                           │
│     v                                                                           │
│  2. POLL LOOP                                                                   │
│     │                                                                           │
│     │  while (running) {                                                        │
│     │      Collection<ChangeEvent> events = consumer.poll(1000);                │
│     │      process(events);                                                     │
│     │      saveCheckpoint(events.getLastCoordinate());                          │
│     │  }                                                                        │
│     │                                                                           │
│     v                                                                           │
│  ┌───────────────────────────────────────────────────────────────┐              │
│  │ Version Swap Detection                                         │              │
│  │  • Monitor for new store version via metadata                 │              │
│  │  • When detected: subscribe to new VT, transition smoothly    │              │
│  └───────────────────────────────────────────────────────────────┘              │
│     │                                                                           │
│     v                                                                           │
│  3. UNSUBSCRIBE                                                                 │
│     │                                                                           │
│     │  consumer.unsubscribe()                                                   │
│     │                                                                           │
│     v                                                                           │
│  ┌───────────────────────────────────────────────────────────────┐              │
│  │ Cleanup                                                        │              │
│  │  • Close Kafka consumers                                      │              │
│  │  • Release partition assignments                              │              │
│  └───────────────────────────────────────────────────────────────┘              │
│                                                                                  │
└─────────────────────────────────────────────────────────────────────────────────┘
```

---

## Seek Operations

Changelog Consumer supports multiple seek operations to position the consumer at different points in the stream.

### Seek Operation Summary

| Operation | Description | Use Case |
|-----------|-------------|----------|
| `seekToCheckpoint()` | Resume from saved checkpoint | Failure recovery |
| `seekToTimestamp()` | Jump to records at/after timestamp | Time-based replay |
| `seekToBeginningOfPush()` | Start of current version | Full version replay |
| `seekToTail()` | Latest position | Real-time only |
| `seekToEndOfPush()` | End of batch push data | Skip to real-time |

### Seek Operation Flow

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                          Seek Operations                                         │
├─────────────────────────────────────────────────────────────────────────────────┤
│                                                                                  │
│  seekToCheckpoint(Set<VeniceChangeCoordinate>)                                  │
│  ────────────────────────────────────────────                                   │
│     │                                                                           │
│     │  For each coordinate:                                                     │
│     │    • Extract topic, partition, position                                   │
│     │    • Seek Kafka consumer to that position                                 │
│     │                                                                           │
│     │  Note: Checkpoints are per-partition. If the checkpoint                  │
│     │        references an old version topic (Store_v1), consumer              │
│     │        will start there and transition to current version.               │
│     v                                                                           │
│                                                                                  │
│  seekToTimestamp(Map<Integer, Long>)                                            │
│  ───────────────────────────────────                                            │
│     │                                                                           │
│     │  For each partition:                                                      │
│     │    • Use Kafka offsetsForTimes() to find offset at timestamp             │
│     │    • Seek consumer to that offset                                        │
│     │                                                                           │
│     │  Note: If timestamp is before current VT, may need to seek               │
│     │        to older version topic.                                           │
│     v                                                                           │
│                                                                                  │
│  seekToBeginningOfPush(Set<Integer>)                                            │
│  ───────────────────────────────────                                            │
│     │                                                                           │
│     │  For each partition:                                                      │
│     │    • Find current version topic                                          │
│     │    • Seek to offset 0 (start of version)                                 │
│     │                                                                           │
│     │  Returns all data for current store version.                             │
│     v                                                                           │
│                                                                                  │
│  seekToTail(Set<Integer>)                                                       │
│  ────────────────────────                                                       │
│     │                                                                           │
│     │  For each partition:                                                      │
│     │    • Seek to end of current topic                                        │
│     │                                                                           │
│     │  Skip historical data, receive only new events.                          │
│     v                                                                           │
│                                                                                  │
└─────────────────────────────────────────────────────────────────────────────────┘
```

### Seek Operation Return Values

All seek operations return `CompletableFuture<Void>`:

```java
// Async seek - wait for completion
consumer.seekToBeginningOfPush(partitions).get();

// Chain with other operations
consumer.seekToTimestamp(timestamps)
    .thenRun(() -> startPolling())
    .exceptionally(e -> handleError(e));
```

---

## Version Swap Handling

When a new store version becomes current, the changelog consumer transparently transitions to the new version topic.

### Version Swap Flow

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                         Version Swap Handling                                    │
├─────────────────────────────────────────────────────────────────────────────────┤
│                                                                                  │
│  Timeline:                                                                      │
│                                                                                  │
│  ──────┬───────────────────────┬───────────────────────┬──────────────────►    │
│        │                       │                       │                        │
│     Store_v1               Store_v2               Store_v3                      │
│     current                becomes                becomes                       │
│                            current                current                       │
│                                                                                  │
│                                                                                  │
│  Consumer Behavior at Version Swap:                                             │
│  ──────────────────────────────────                                             │
│                                                                                  │
│  ┌────────────────────────────────────────────────────────────────────────┐    │
│  │  BEFORE SWAP                                                           │    │
│  │                                                                        │    │
│  │  Consumer subscribed to: Store_v1 (partitions 0,1,2)                  │    │
│  │  Current position: {p0: offset 1000, p1: offset 2000, p2: offset 1500}│    │
│  └────────────────────────────────────────────────────────────────────────┘    │
│                                  │                                              │
│                                  │ Version swap detected                        │
│                                  │ (via metadata polling)                       │
│                                  v                                              │
│  ┌────────────────────────────────────────────────────────────────────────┐    │
│  │  TRANSITION                                                            │    │
│  │                                                                        │    │
│  │  1. Subscribe to Store_v2 partitions 0,1,2                            │    │
│  │  2. Seek to beginning of Store_v2 (or appropriate position)           │    │
│  │  3. Continue delivering events from new topic                         │    │
│  │  4. Eventually unsubscribe from Store_v1 (after it's no longer needed)│    │
│  └────────────────────────────────────────────────────────────────────────┘    │
│                                  │                                              │
│                                  v                                              │
│  ┌────────────────────────────────────────────────────────────────────────┐    │
│  │  AFTER SWAP                                                            │    │
│  │                                                                        │    │
│  │  Consumer subscribed to: Store_v2 (partitions 0,1,2)                  │    │
│  │  Current position: {p0: offset 0, p1: offset 0, p2: offset 0}         │    │
│  │                                                                        │    │
│  │  Note: Checkpoint coordinates include topic name, so recovery         │    │
│  │        works correctly across version boundaries.                     │    │
│  └────────────────────────────────────────────────────────────────────────┘    │
│                                                                                  │
└─────────────────────────────────────────────────────────────────────────────────┘
```

### Version Swap Metrics

| Metric | Description |
|--------|-------------|
| `version_swap_success_count` | Successful version transitions |
| `version_swap_fail_count` | Failed version transitions |
| `minimum_consuming_version` | Lowest version being consumed |
| `maximum_consuming_version` | Highest version being consumed |

---

## Checkpointing

Checkpointing allows saving and restoring consumption position for fault tolerance.

### VeniceChangeCoordinate Structure

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                        VeniceChangeCoordinate                                    │
├─────────────────────────────────────────────────────────────────────────────────┤
│                                                                                  │
│  ┌─────────────────────────────────────────────────────────────────────────┐   │
│  │  Fields                                                                  │   │
│  │                                                                          │   │
│  │  topic: String              "MyStore_v2"                                │   │
│  │  partition: Integer         2                                            │   │
│  │  pubSubPosition: Position   Kafka offset (e.g., 12345)                  │   │
│  │  consumerSequenceId: long   Monotonic sequence for ordering             │   │
│  │                                                                          │   │
│  └─────────────────────────────────────────────────────────────────────────┘   │
│                                                                                  │
│  Key Properties:                                                                │
│  ───────────────                                                                │
│  • Topic name includes version: enables recovery across version swaps          │
│  • Partition identifies the shard                                              │
│  • Position is the underlying Kafka offset                                     │
│  • Sequence ID enables ordering events from same partition                     │
│                                                                                  │
│  Serialization:                                                                 │
│  ─────────────                                                                  │
│  • Implements Externalizable for efficient serialization                       │
│  • Can be Base64 encoded for string storage                                    │
│  • Backward compatible across versions (v1, v2, v3 formats)                    │
│                                                                                  │
└─────────────────────────────────────────────────────────────────────────────────┘
```

### Checkpoint Lifecycle

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                         Checkpoint Lifecycle                                     │
├─────────────────────────────────────────────────────────────────────────────────┤
│                                                                                  │
│  1. OBTAIN CHECKPOINT (from poll result)                                        │
│     │                                                                           │
│     │  Collection<PubSubMessage<K, ChangeEvent<V>, VeniceChangeCoordinate>>     │
│     │      events = consumer.poll(1000);                                        │
│     │                                                                           │
│     │  for (PubSubMessage event : events) {                                     │
│     │      // Each event has a coordinate                                       │
│     │      VeniceChangeCoordinate coord = event.getOffset();                    │
│     │      process(event);                                                      │
│     │  }                                                                        │
│     │                                                                           │
│     v                                                                           │
│  2. SAVE CHECKPOINT (application responsibility)                                │
│     │                                                                           │
│     │  // Serialize and store                                                   │
│     │  String encoded = VeniceChangeCoordinate                                  │
│     │      .convertVeniceChangeCoordinateToStringAndEncode(coord);              │
│     │  saveToExternalStorage(partition, encoded);                               │
│     │                                                                           │
│     v                                                                           │
│  3. RESTORE CHECKPOINT (on restart)                                             │
│     │                                                                           │
│     │  // Load and deserialize                                                  │
│     │  String encoded = loadFromExternalStorage(partition);                     │
│     │  VeniceChangeCoordinate coord = VeniceChangeCoordinate                    │
│     │      .decodeStringAndConvertToVeniceChangeCoordinate(deserializer,        │
│     │          encoded);                                                        │
│     │                                                                           │
│     │  // Seek to checkpoint                                                    │
│     │  consumer.seekToCheckpoint(Set.of(coord)).get();                          │
│     │                                                                           │
│     v                                                                           │
│  4. RESUME CONSUMPTION                                                          │
│     │                                                                           │
│     │  // Continue from saved position                                          │
│     │  while (running) {                                                        │
│     │      events = consumer.poll(1000);                                        │
│     │      // ...                                                               │
│     │  }                                                                        │
│     v                                                                           │
│                                                                                  │
└─────────────────────────────────────────────────────────────────────────────────┘
```

### Checkpoint Considerations

| Consideration | Description |
|---------------|-------------|
| **Granularity** | One checkpoint per partition |
| **Frequency** | Balance between durability and performance |
| **Storage** | Application responsibility (database, file, etc.) |
| **Retention** | Must outlive topic retention to be useful |
| **Cross-version** | Coordinates include topic name for version awareness |

### Recovery Scenarios

```
Scenario 1: Recovery within same version
──────────────────────────────────────────
  Checkpoint: {topic: Store_v2, partition: 0, offset: 5000}
  Current version: v2

  Result: Seek directly to offset 5000 in Store_v2

Scenario 2: Recovery after version swap
──────────────────────────────────────────
  Checkpoint: {topic: Store_v1, partition: 0, offset: 5000}
  Current version: v2

  Result:
    - Seek to offset 5000 in Store_v1
    - Consume remaining v1 data
    - Automatically transition to v2
    - Continue with v2 data

Scenario 3: Checkpoint older than retention
──────────────────────────────────────────
  Checkpoint: {topic: Store_v1, partition: 0, offset: 100}
  Store_v1 deleted (past retention)
  Current version: v3

  Result: Error - cannot seek to deleted topic
  Solution: Seek to beginning of current version instead
```

---

## Key Classes Reference

| Class | Location | Responsibility |
|-------|----------|----------------|
| `VeniceChangelogConsumer` | `clients/da-vinci-client/.../consumer/` | Public interface |
| `VeniceChangelogConsumerImpl` | Same directory | Main implementation |
| `VeniceAfterImageConsumerImpl` | Same directory | After-image consumer |
| `LocalBootstrappingVeniceChangelogConsumer` | Same directory | Local bootstrap consumer |
| `VeniceChangeCoordinate` | Same directory | Checkpoint coordinate |
| `ChangelogClientConfig` | Same directory | Configuration builder |
| `BasicConsumerStats` | `.../consumer/stats/` | Consumer metrics |

### Interface Methods

```java
public interface VeniceChangelogConsumer<K, V> {

  // Subscription
  CompletableFuture<Void> subscribe(Set<Integer> partitions);
  CompletableFuture<Void> unsubscribe(Set<Integer> partitions);
  void unsubscribeAll();

  // Seek operations
  CompletableFuture<Void> seekToBeginningOfPush(Set<Integer> partitions);
  CompletableFuture<Void> seekToTail(Set<Integer> partitions);
  CompletableFuture<Void> seekToEndOfPush(Set<Integer> partitions);
  CompletableFuture<Void> seekToCheckpoint(Set<VeniceChangeCoordinate> checkpoints);
  CompletableFuture<Void> seekToTimestamps(Map<Integer, Long> timestamps);

  // Consumption
  Collection<PubSubMessage<K, ChangeEvent<V>, VeniceChangeCoordinate>> poll(long timeoutMs);

  // Lifecycle
  void close();
}
```

---

## Configuration Reference

### ChangelogClientConfig Options

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `setStoreName` | String | Required | Store to consume from |
| `setLocalD2ZkHosts` | String | Required | ZooKeeper connection string |
| `setControllerD2ServiceName` | String | Required | D2 service name for controller |
| `setConsumerProperties` | Properties | - | Kafka consumer properties |
| `setSchemaReader` | SchemaReader | - | Custom schema reader |
| `setViewName` | String | - | View name for view-based consumption |

### Example Configuration

```java
ChangelogClientConfig config = ChangelogClientConfig.cloneConfig(baseConfig)
    .setStoreName("myStore")
    .setLocalD2ZkHosts("zk.example.com:2181")
    .setControllerD2ServiceName("VeniceController");

VeniceChangelogConsumer<String, MyRecord> consumer =
    ChangelogClientConfig.getChangelogConsumer(config);
```

---

## Usage Examples

### Basic Consumption

```java
// Create consumer
VeniceChangelogConsumer<String, MyRecord> consumer =
    ChangelogClientConfig.getChangelogConsumer(config);

// Subscribe to partitions
consumer.subscribe(Set.of(0, 1, 2)).get();

// Seek to beginning for full replay
consumer.seekToBeginningOfPush(Set.of(0, 1, 2)).get();

// Poll loop
while (running) {
    Collection<PubSubMessage<String, ChangeEvent<MyRecord>, VeniceChangeCoordinate>>
        events = consumer.poll(1000);

    for (var event : events) {
        String key = event.getKey();
        ChangeEvent<MyRecord> change = event.getValue();
        VeniceChangeCoordinate checkpoint = event.getOffset();

        if (change.getCurrentValue() != null) {
            // PUT operation
            processUpdate(key, change.getCurrentValue());
        } else {
            // DELETE operation
            processDelete(key);
        }
    }
}

// Cleanup
consumer.close();
```

### With Checkpointing

```java
// Load saved checkpoints
Map<Integer, VeniceChangeCoordinate> savedCheckpoints = loadCheckpoints();

// Subscribe
consumer.subscribe(savedCheckpoints.keySet()).get();

// Seek to checkpoints
consumer.seekToCheckpoint(new HashSet<>(savedCheckpoints.values())).get();

// Consume with periodic checkpointing
VeniceChangeCoordinate lastCheckpoint = null;
int recordCount = 0;

while (running) {
    var events = consumer.poll(1000);

    for (var event : events) {
        process(event);
        lastCheckpoint = event.getOffset();
        recordCount++;

        // Checkpoint every 1000 records
        if (recordCount % 1000 == 0) {
            saveCheckpoint(lastCheckpoint.getPartition(), lastCheckpoint);
        }
    }
}
```

### Time-Based Replay

```java
// Seek to specific timestamp (e.g., 1 hour ago)
long oneHourAgo = System.currentTimeMillis() - 3600_000;

Map<Integer, Long> timestamps = new HashMap<>();
for (int partition : partitions) {
    timestamps.put(partition, oneHourAgo);
}

consumer.seekToTimestamps(timestamps).get();

// Consume from that point forward
while (running) {
    var events = consumer.poll(1000);
    // ...
}
```

---

## Integration with Apache Beam

Venice provides a Beam connector for batch and streaming pipelines. See `integrations/venice-beam/` for:

- `VeniceCheckpointMark` - Checkpoint implementation for Beam
- `VeniceChangelogSource` - Beam source for changelog consumption

---

## See Also

- [Changelog Consumer Metrics](changelog_consumer_metrics.md) - Metrics reference
- [Da-Vinci Architecture](da_vinci_architecture.md) - Embedded client with local storage
- [Clients Overview](clients_overview.md) - Client comparison
