# Venice Server Data Flow

This document details the read and write data paths through the Venice Server, including the Leader/Follower replication model.

## Read Path

### Overview

```
┌─────────┐     ┌────────┐     ┌───────────────┐     ┌───────────────────────┐
│ Client  │────▶│ Router │────▶│ListenerService│────▶│StorageReadRequestHandler│
└─────────┘     └────────┘     └───────────────┘     └───────────┬───────────┘
                                                                  │
                                      ┌───────────────────────────┘
                                      ▼
                              ┌───────────────────┐
                              │PerStoreVersionState│
                              │  (cache lookup)   │
                              └─────────┬─────────┘
                                        │
                                        ▼
                              ┌───────────────────┐
                              │ StorageEngine     │
                              │   (RocksDB)       │
                              └─────────┬─────────┘
                                        │
                                        ▼
                                   Response
```

### Single Get Flow

**Code Path:** `StorageReadRequestHandler.handleSingleGetRequest()`

```java
// 1. Submit to executor with queue metrics
CompletableFuture.supplyAsync(() -> {
    // 2. Check for early termination
    if (request.shouldRequestBeTerminatedEarly()) {
        throw new VeniceRequestEarlyTerminationException();
    }

    // 3. Get cached store version state
    PerStoreVersionState state = getPerStoreVersionState(topic);

    // 4. Retrieve value from RocksDB
    ValueRecord valueRecord = SingleGetChunkingAdapter.get(
        storageEngine,
        partition,
        key,
        isChunked,
        stats
    );

    // 5. Build and return response
    return response;
}, executor);
```

**Key Metrics Recorded:**
- `storage_execution_handler_submission_wait_time` - Time in queue
- `storage_execution_queue_len` - Queue depth at submission
- `storage_engine_query_latency` - RocksDB lookup time
- `request_key_size` / `request_value_size` - Size profiling

### Multi-Get Flow

**Sequential Processing:** `handleMultiGetRequest()`

```java
// Process all keys in single thread
for (key : keys) {
    record = BatchGetChunkingAdapter.get(
        storageEngine,
        key.partitionId,
        key.keyBytes,
        isChunked,
        stats
    );
    response.addRecord(record);
}
```

**Parallel Processing:** `handleMultiGetRequestInParallel()`

```java
// Split keys into chunks
int chunkCount = (totalKeys / parallelBatchGetChunkSize) + 1;

// Process each chunk in parallel
for (int chunk = 0; chunk < chunkCount; chunk++) {
    CompletableFuture.runAsync(() -> {
        processMultiGet(startPos, endPos, keys, context, response);
    }, executor);
}

// Wait for all chunks and combine
CompletableFuture.allOf(chunkFutures);
```

**Configuration:**
```properties
# Enable parallel batch get
server.enable.parallel.batch.get=true

# Chunk size for parallel processing
parallel.batch.get.chunk.size=10
```

### Read Compute Flow

**Code Path:** `handleComputeRequest()` / `handleComputeRequestInParallel()`

Read compute allows executing computations (dot product, cosine similarity, etc.) on the server side.

```java
// 1. Validate compute is enabled for store
if (!metadataRepository.isReadComputationEnabled(storeName)) {
    throw new OperationNotAllowedException();
}

// 2. Build compute context
ComputeRequestContext ctx = new ComputeRequestContext(request, handler);

// 3. For each key
for (key : keys) {
    // 3a. Retrieve and decompress value
    valueRecord = GenericRecordChunkingAdapter.get(
        storageEngine, partition, key,
        byteBuffer, reusableValueRecord,
        decoder, isChunked, stats
    );

    // 3b. Execute compute operations
    resultRecord = ComputeUtils.computeResult(
        operations, operationResultFields,
        computeContext, valueRecord, resultRecord
    );

    // 3c. Serialize result
    response.addRecord(resultRecord);
}
```

**Supported Compute Operations:**
- `DOT_PRODUCT` - Vector dot product
- `COSINE_SIMILARITY` - Cosine similarity between vectors
- `HADAMARD_PRODUCT` - Element-wise vector multiplication
- `COUNT` - Count operation

### Request Context Caching

`PerStoreVersionState` caches per-version state to avoid repeated lookups:

```java
class PerStoreVersionState {
    // Cached deserializer for Avro records
    StoreDeserializerCache<GenericRecord> storeDeserializerCache;

    // Reference to storage engine (refreshed if closed)
    StorageEngine storageEngine;
}
```

**Cache Refresh Logic:**
```java
PerStoreVersionState s = perStoreVersionStateMap.computeIfAbsent(
    storeVersion, this::generatePerStoreVersionState
);

// Refresh if storage engine was closed (partition dropped)
if (s.storageEngine.isClosed()) {
    s.storageEngine = getStorageEngineOrThrow(storeVersion);
}
```

### Reusable Objects Pattern

To minimize GC pressure on the hot path, thread-local reusable objects are used:

```java
class ReusableObjects {
    // 1MB buffer for RocksDB values
    ByteBuffer byteBuffer = ByteBuffer.allocate(1024 * 1024);

    // LRU caches for Avro record reuse (100 entries each)
    LinkedHashMap<Schema, GenericRecord> valueRecordMap;
    LinkedHashMap<Schema, GenericRecord> resultRecordMap;

    // Reusable Avro decoder
    BinaryDecoder binaryDecoder;

    // Compute state
    Map<String, Object> computeContext;
}

// Access via ThreadLocal
ThreadLocal<ReusableObjects> threadLocalReusableObjects =
    ThreadLocal.withInitial(ReusableObjects::new);
```

## Write Path (Ingestion)

### Overview

```
┌─────────────────────┐
│ Version Topic (VT)  │  StoreName_v1
│ Real-Time Topic (RT)│  StoreName_rt
└──────────┬──────────┘
           │
           ▼
┌─────────────────────────────────────────┐
│      KafkaStoreIngestionService         │
│  ┌───────────────────────────────────┐  │
│  │     StoreIngestionTask (per v)    │  │
│  │  ┌─────────────────────────────┐  │  │
│  │  │ Partition Consumer State    │  │  │
│  │  │  - OffsetRecord (persisted) │  │  │
│  │  │  - In-memory state          │  │  │
│  │  └─────────────────────────────┘  │  │
│  └───────────────────────────────────┘  │
└──────────────────┬──────────────────────┘
                   │
                   ▼
           ┌───────────────┐
           │ StorageEngine │
           │   (RocksDB)   │
           └───────────────┘
```

### Message Processing

**Code Path:** `StoreIngestionTask` inner processing loop

```java
// For each consumed record
void processConsumerRecord(ConsumerRecord record) {
    // 1. Extract key and value
    byte[] key = record.key();
    byte[] value = record.value();

    // 2. Handle message type (Put, Delete, Update, Control)
    switch (messageType) {
        case PUT:
            handlePutMessage(key, value, partition);
            break;
        case DELETE:
            handleDeleteMessage(key, partition);
            break;
        case UPDATE:
            handleUpdateMessage(key, value, partition);
            break;
        case CONTROL_MESSAGE:
            handleControlMessage(controlMessage);
            break;
    }

    // 3. Update consumption state
    partitionConsumptionState.updateOffsets(record.offset());

    // 4. Record metrics
    recordMetrics(recordSize);

    // 5. Checkpoint if needed
    maybeCheckpoint();
}
```

### Control Messages

| Message Type | Purpose |
|--------------|---------|
| `START_OF_PUSH` | Marks beginning of batch push |
| `END_OF_PUSH` | Marks end of batch push, triggers completion |
| `START_OF_INCREMENTAL_PUSH` | Marks start of incremental push |
| `END_OF_INCREMENTAL_PUSH` | Marks end of incremental push |
| `START_OF_SEGMENT` | Segment boundary marker |
| `END_OF_SEGMENT` | Segment boundary marker |
| `VERSION_SWAP` | Triggers version swap for deferred swap |
| `TOPIC_SWITCH` | Indicates topic source change |

### Large Record Chunking

Records larger than 1MB are automatically chunked:

```
┌─────────────────────────────────────────────────┐
│            Large Value (> 1MB)                  │
└─────────────────────────────────────────────────┘
                      │
                      ▼ VeniceWriter chunks at write time
┌─────────────────────────────────────────────────┐
│  Chunk 0  │  Chunk 1  │  Chunk 2  │  Manifest  │
└─────────────────────────────────────────────────┘
                      │
                      ▼ Server reconstructs at read time
┌─────────────────────────────────────────────────┐
│            Reassembled Value                    │
└─────────────────────────────────────────────────┘
```

**ChunkedValueManifest:**
```java
// Manifest record stored with the key
ChunkedValueManifest {
    int schemaId;
    List<byte[]> keysWithChunkIdSuffix;
    int size;  // Total uncompressed size
}
```

**Read-side Reconstruction:**
```java
// SingleGetChunkingAdapter / BatchGetChunkingAdapter
ValueRecord get(storageEngine, partition, key, isChunked, stats) {
    if (isChunked) {
        // 1. Read manifest
        ChunkedValueManifest manifest = readManifest(key);

        // 2. Read each chunk
        for (chunkKey : manifest.keysWithChunkIdSuffix) {
            chunks.add(storageEngine.get(partition, chunkKey));
        }

        // 3. Reassemble
        return reassembleChunks(chunks, manifest);
    } else {
        return storageEngine.get(partition, key);
    }
}
```

## Leader/Follower Replication

### Overview

```
┌──────────────────────────────────────────────────────────────────────┐
│                         Partition P0                                 │
│                                                                      │
│  ┌──────────────────────┐        ┌──────────────────────┐           │
│  │      Server A        │        │      Server B        │           │
│  │      (Leader)        │        │     (Follower)       │           │
│  └──────────┬───────────┘        └──────────┬───────────┘           │
│             │                               │                        │
│             │ Consumes RT                   │ Consumes VT only       │
│             │ Produces to VT                │                        │
│             ▼                               ▼                        │
│  ┌──────────────────────┐        ┌──────────────────────┐           │
│  │   Real-Time Topic    │───────▶│   Version Topic      │           │
│  │    (StoreName_rt)    │        │   (StoreName_v1)     │           │
│  └──────────────────────┘        └──────────────────────┘           │
└──────────────────────────────────────────────────────────────────────┘
```

### Leader Responsibilities

```java
// LeaderFollowerStoreIngestionTask

// 1. Consume from both VT and RT
subscribeToVT(partition);
subscribeToRT(partition);  // Only leader

// 2. Process RT messages
void processRTMessage(message) {
    // Apply conflict resolution (CRDT for active-active)
    if (shouldApplyUpdate(message)) {
        // Write to local storage
        storageEngine.put(key, value);

        // Produce to VT for followers
        vtProducer.produce(key, value);
    }
}

// 3. Handle write compute
void processWriteComputeMessage(message) {
    // Read current value
    currentValue = storageEngine.get(key);

    // Apply partial update
    newValue = applyWriteCompute(currentValue, message);

    // Store and produce
    storageEngine.put(key, newValue);
    vtProducer.produce(key, newValue);
}
```

### Follower Responsibilities

```java
// Follower only consumes from VT
subscribeToVT(partition);

// Simply apply messages from VT (already conflict-resolved by leader)
void processVTMessage(message) {
    storageEngine.put(key, value);
    updateConsumptionState(offset);
}
```

### State Transitions During L/F Changes

```
┌─────────────┐     STANDBY→LEADER      ┌─────────────┐
│   Follower  │ ────────────────────▶   │   Leader    │
│  (STANDBY)  │     promoteToLeader()   │  (LEADER)   │
└─────────────┘                         └─────────────┘
                                              │
                                              │ LEADER→STANDBY
                                              │ demoteToStandby()
                                              ▼
                                        ┌─────────────┐
                                        │   Follower  │
                                        │  (STANDBY)  │
                                        └─────────────┘
```

**Session ID Validation:**
```java
// Each L/F transition increments session ID
AtomicLong leaderSessionId = new AtomicLong(0L);

// Transition command
LeaderSessionIdChecker checker = new LeaderSessionIdChecker(
    leaderSessionId.incrementAndGet(),
    leaderSessionId
);

// Before processing, validate session is still valid
if (!checker.isSessionIdValid()) {
    // Skip stale transition command
    return;
}
```

### Conflict Resolution (Active-Active)

For active-active replication, Venice uses timestamp-based CRDT resolution:

```java
// DCR = Deterministic Conflict Resolution
void processWithDCR(incomingMessage, currentValue) {
    // Compare timestamps
    if (incomingMessage.timestamp > currentValue.timestamp) {
        // Incoming wins - apply update
        apply(incomingMessage);
        metrics.recordTotalDCR();
    } else if (incomingMessage.timestamp < currentValue.timestamp) {
        // Current wins - ignore update
        metrics.recordUpdateIgnoredDCR();
    } else {
        // Tie-breaker: compare producer IDs, colo IDs, etc.
        resolveTie(incomingMessage, currentValue);
    }
}
```

**DCR Metrics:**
- `total_dcr` - Total conflict resolutions
- `update_ignored_dcr` - Updates ignored (current value wins)
- `timestamp_regression_dcr_error` - Timestamp regression errors
- `offset_regression_dcr_error` - Offset regression errors
- `tombstone_creation_dcr` - Tombstones created via CRDT

## Compression

### Compression Strategies

| Strategy | Description |
|----------|-------------|
| `NO_OP` | No compression |
| `GZIP` | GZIP compression |
| `ZSTD` | Zstandard compression (default) |
| `ZSTD_WITH_DICT` | ZSTD with dictionary |

### Compression in Data Flow

**Write Path:**
```java
// VeniceWriter compresses before sending to Kafka
byte[] compressed = compressor.compress(value);
producer.send(key, compressed);
```

**Read Path:**
```java
// StorageReadRequestHandler determines compression from StoreVersionState
StoreVersionState svs = storageEngine.getStoreVersionState();
CompressionStrategy strategy = StoreVersionStateUtils.getCompressionStrategy(svs);

// Response includes compression strategy for client decompression
response.setCompressionStrategy(strategy);
```

**Dictionary Fetch:**
```java
// Clients can fetch compression dictionary
DictionaryFetchRequest request = new DictionaryFetchRequest(resourceName);

// Server returns dictionary from ingestion metadata
ByteBuffer dictionary = ingestionMetadataRetriever
    .getStoreVersionCompressionDictionary(resourceName);
```

## Checkpointing

### OffsetRecord

Persisted checkpoint tracking consumption progress:

```java
// Stored in RocksDB metadata partition
OffsetRecord {
    long localVersionTopicOffset;
    long upstreamOffset;      // For remote consumption
    GUID producerGUID;
    int segment;
    int sequenceNumber;
    String leaderTopic;       // Current topic being consumed
}
```

### Checkpoint Strategy

```java
// Checkpoint on:
// 1. Every N records (configurable)
// 2. End of segment
// 3. Control messages
// 4. Graceful shutdown

void maybeCheckpoint() {
    if (shouldCheckpoint()) {
        OffsetRecord record = buildOffsetRecord();
        storageMetadataService.put(
            storeName,
            partition,
            record
        );
    }
}
```
