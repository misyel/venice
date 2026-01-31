# Venice Server Architecture

This document provides a deep dive into the Venice Server's internal architecture, covering all major components and their interactions.

## High-Level Architecture

```
┌──────────────────────────────────────────────────────────────────────────────┐
│                              Venice Server                                   │
├──────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  ┌─────────────────────────────────────────────────────────────────────────┐ │
│  │                        Listener Layer                                   │ │
│  │  ┌──────────────┐  ┌───────────────────────┐  ┌──────────────────────┐  │ │
│  │  │ListenerService│  │StorageReadRequestHandler│ │ gRPC Service (opt)  │  │ │
│  │  └──────────────┘  └───────────────────────┘  └──────────────────────┘  │ │
│  └─────────────────────────────────────────────────────────────────────────┘ │
│                                    │                                         │
│                                    ▼                                         │
│  ┌─────────────────────────────────────────────────────────────────────────┐ │
│  │                        Storage Layer                                    │ │
│  │  ┌────────────────┐  ┌─────────────────────┐  ┌─────────────────────┐   │ │
│  │  │ StorageService │  │StorageEngineRepository│ │  RocksDBStorage    │   │ │
│  │  └────────────────┘  └─────────────────────┘  │      Engine         │   │ │
│  │                                               └─────────────────────┘   │ │
│  └─────────────────────────────────────────────────────────────────────────┘ │
│                                    │                                         │
│                                    ▼                                         │
│  ┌─────────────────────────────────────────────────────────────────────────┐ │
│  │                       Ingestion Layer                                   │ │
│  │  ┌─────────────────────────┐  ┌───────────────────────────────────────┐ │ │
│  │  │KafkaStoreIngestionService│  │      StoreIngestionTask(s)           │ │ │
│  │  └─────────────────────────┘  │  (LeaderFollowerStoreIngestionTask)   │ │ │
│  │                               └───────────────────────────────────────┘ │ │
│  └─────────────────────────────────────────────────────────────────────────┘ │
│                                    │                                         │
│                                    ▼                                         │
│  ┌─────────────────────────────────────────────────────────────────────────┐ │
│  │                        Helix Layer                                      │ │
│  │  ┌────────────────────────┐  ┌──────────────────────────────────────┐   │ │
│  │  │HelixParticipationService│  │LeaderFollowerPartitionStateModel   │   │ │
│  │  └────────────────────────┘  └──────────────────────────────────────┘   │ │
│  └─────────────────────────────────────────────────────────────────────────┘ │
│                                                                              │
│  ┌─────────────────────────────────────────────────────────────────────────┐ │
│  │                     Supporting Services                                 │ │
│  │  ┌───────────────┐ ┌────────────────────┐ ┌────────────────────┐       │ │
│  │  │DiskHealthCheck│ │HeartbeatMonitoring │ │AdaptiveThrottler   │       │ │
│  │  └───────────────┘ └────────────────────┘ └────────────────────┘       │ │
│  └─────────────────────────────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────────────────────────────┘
```

## Listener Layer

The Listener Layer handles incoming HTTP requests from routers and clients.

### ListenerService

**Location:** `services/venice-server/src/main/java/com/linkedin/venice/listener/ListenerService.java`

The `ListenerService` sets up Netty-based HTTP handlers for processing read requests.

**Key Responsibilities:**
- Initialize Netty channel pipeline
- Configure SSL/TLS if enabled
- Set up request routing to appropriate handlers
- Manage connection pooling and HTTP/2 settings

```java
// Pipeline setup includes:
HttpChannelInitializer
  ├── SSL Handler (optional)
  ├── HTTP Decoder
  ├── HTTP Encoder
  ├── StorageReadRequestHandler
  └── Response Handler
```

### StorageReadRequestHandler

**Location:** `services/venice-server/src/main/java/com/linkedin/venice/listener/StorageReadRequestHandler.java`

The central read path handler that processes all incoming read requests.

**Request Types Handled:**

| Request Type | Method | Description |
|--------------|--------|-------------|
| `SINGLE_GET` | `handleSingleGetRequest()` | Point lookup for single key |
| `MULTI_GET` | `handleMultiGetRequest()` | Batch lookup for multiple keys |
| `COMPUTE` | `handleComputeRequest()` | Read-compute operations |
| `HealthCheckRequest` | Direct | Server health status |
| `DictionaryFetchRequest` | `handleDictionaryFetchRequest()` | Compression dictionary |
| `AdminRequest` | `handleServerAdminRequest()` | Admin operations |
| `MetadataFetchRequest` | `handleMetadataFetchRequest()` | Read quota metadata |
| `CurrentVersionRequest` | `handleCurrentVersionRequest()` | Current store version |
| `HeartbeatRequest` | `handleHeartbeatRequest()` | Heartbeat lag queries |

**Thread Pools:**
```java
// Read executor for single/batch gets
ThreadPoolExecutor executor;

// Separate executor for compute operations
ThreadPoolExecutor computeExecutor;
```

**Key Internal Classes:**

```java
// Caches storage engine and deserializer per store version
class PerStoreVersionState {
    StoreDeserializerCache<GenericRecord> storeDeserializerCache;
    StorageEngine storageEngine;
}

// Thread-local reusable objects to minimize allocations
class ReusableObjects {
    ByteBuffer byteBuffer;           // Reused for RocksDB reads
    LinkedHashMap valueRecordMap;    // LRU cache (100 entries)
    LinkedHashMap resultRecordMap;   // LRU cache (100 entries)
    BinaryDecoder binaryDecoder;     // Avro decoder reuse
    Map computeContext;              // Compute state
}
```

**Parallel Processing:**
```java
// Batch gets can be processed in parallel chunks
parallelBatchGetChunkSize = serverConfig.getParallelBatchGetChunkSize();

// Handler selection based on config
if (serverConfig.isEnableParallelBatchGet()) {
    multiGetHandler = this::handleMultiGetRequestInParallel;
    computeHandler = this::handleComputeRequestInParallel;
}
```

## Storage Layer

The Storage Layer manages persistent data storage using RocksDB.

### StorageService

**Location:** `clients/da-vinci-client/src/main/java/com/linkedin/davinci/storage/StorageService.java`

Manages all storage engines for the server.

**Key Responsibilities:**
- Create and destroy storage engines per store version
- Manage storage engine repository
- Handle partition assignment/unassignment
- Configure RocksDB options

### StorageEngineRepository

A registry mapping store version names to their storage engines:

```java
// Map: "StoreName_v1" -> RocksDBStorageEngine
Map<String, StorageEngine> storageEngineMap;
```

### RocksDBStorageEngine

**Location:** `clients/da-vinci-client/src/main/java/com/linkedin/davinci/store/rocksdb/RocksDBStorageEngine.java`

The RocksDB-based storage implementation for a single store version.

**Key Features:**
- One storage engine per store version
- Partition-based data organization
- Supports both block-based and plain table formats
- Compression support (ZSTD, Snappy, etc.)

**Storage Hierarchy:**
```
RocksDBStorageEngine (StoreName_v1)
├── Partition 0
│   └── RocksDB instance
├── Partition 1
│   └── RocksDB instance
└── Partition N
    └── RocksDB instance
```

## Ingestion Layer

The Ingestion Layer consumes data from Kafka and persists it to storage.

### KafkaStoreIngestionService

**Location:** `clients/da-vinci-client/src/main/java/com/linkedin/davinci/kafka/consumer/KafkaStoreIngestionService.java`

Manages all ingestion tasks for the server.

**Key Responsibilities:**
- Create/destroy StoreIngestionTasks
- Manage Kafka consumer pools
- Coordinate with Helix for partition assignment
- Handle ingestion state reporting

**Consumer Pool Architecture:**
```java
// Separate pools for different use cases
consumerPoolForCurrentVersion;
consumerPoolForNonCurrentVersion;
consumerPoolForAAWCLeader;  // Active-Active Write Compute
```

### StoreIngestionTask

**Location:** `clients/da-vinci-client/src/main/java/com/linkedin/davinci/kafka/consumer/StoreIngestionTask.java`

Handles ingestion for a single store version.

**Key Concepts:**
- One task per store/version (e.g., `StoreName_v1`)
- Handles multiple partitions of the same version
- Consumes from Version Topic (VT) and Real-Time Topic (RT)

**Message Processing Flow:**
```
Kafka Consumer
     │
     ▼
┌────────────────────────────────┐
│    StoreIngestionTask          │
│  ┌──────────────────────────┐  │
│  │ Process Kafka Record     │  │
│  │  - Deserialize           │  │
│  │  - Validate              │  │
│  │  - Handle chunking       │  │
│  │  - Apply to storage      │  │
│  │  - Update metrics        │  │
│  │  - Report progress       │  │
│  └──────────────────────────┘  │
└────────────────────────────────┘
     │
     ▼
RocksDB Storage Engine
```

### LeaderFollowerStoreIngestionTask

**Location:** `clients/da-vinci-client/src/main/java/com/linkedin/davinci/kafka/consumer/LeaderFollowerStoreIngestionTask.java`

Extends `StoreIngestionTask` with Leader/Follower replication logic.

**Leader vs Follower Responsibilities:**

| Aspect | Leader | Follower |
|--------|--------|----------|
| Consume VT | Yes | Yes |
| Consume RT | Yes (nearline) | No (follows leader) |
| Produce to VT | Yes (for RT data) | No |
| Conflict Resolution | Yes | No |
| Write Compute | Yes | No |

## Helix Integration

Venice uses Apache Helix for cluster management and state coordination.

### HelixParticipationService

**Location:** `clients/da-vinci-client/src/main/java/com/linkedin/davinci/helix/HelixParticipationService.java`

Manages the server's participation in the Helix cluster.

**Key Responsibilities:**
- Connect to Helix controller
- Register state model factory
- Handle partition assignment callbacks
- Report partition status via CustomizedView

### LeaderFollowerPartitionStateModel

**Location:** `clients/da-vinci-client/src/main/java/com/linkedin/davinci/helix/LeaderFollowerPartitionStateModel.java`

Implements Helix state transitions for partitions.

**State Machine:**
```
OFFLINE (initial)
    │
    ▼ onBecomeStandbyFromOffline()
STANDBY (Follower)
    │
    ▼ onBecomeLeaderFromStandby()
LEADER
    │
    ▼ onBecomeStandbyFromLeader()
STANDBY
    │
    ▼ onBecomeOfflineFromStandby()
OFFLINE
    │
    ▼ onBecomeDroppedFromOffline()
DROPPED
```

See [Server State Transitions](server_state_transitions.md) for detailed state machine documentation.

## Supporting Services

### DiskHealthCheckService

**Location:** `clients/da-vinci-client/src/main/java/com/linkedin/davinci/storage/DiskHealthCheckService.java`

Monitors disk health and availability.

**Checks Performed:**
- Disk space availability
- Write operation latency
- File system responsiveness

### HeartbeatMonitoringService

**Location:** `clients/da-vinci-client/src/main/java/com/linkedin/davinci/stats/ingestion/heartbeat/HeartbeatMonitoringService.java`

Tracks ingestion lag via heartbeat timestamps.

**Key Features:**
- Per-partition lag tracking
- Leader and follower lag metrics
- Regional lag awareness
- OpenTelemetry integration

### AdaptiveThrottlerSignalService

**Location:** `clients/da-vinci-client/src/main/java/com/linkedin/davinci/kafka/consumer/AdaptiveThrottlerSignalService.java`

Provides signals for adaptive throttling based on server load.

**Signal Types:**
- Read latency signals
- Heartbeat lag signals
- Used by blob transfer throttling

## Thread Pool Architecture

The server uses multiple thread pools for different operations:

| Pool | Purpose | Configuration |
|------|---------|---------------|
| Read Executor | Single/batch get requests | `SERVER_COMPUTE_QUEUE_CAPACITY` |
| Compute Executor | Read compute operations | `SERVER_COMPUTE_THREAD_NUM` |
| Consumer Threads | Kafka consumption | `SERVER_CONSUMER_POOL_SIZE_*` |
| State Transition | Helix state changes | `MAX_LEADER_FOLLOWER_STATE_TRANSITION_THREAD_NUMBER` |
| Ingestion Threads | Record processing | Per-ingestion task |

## Component Interactions

### Read Path Interaction

```
Router Request
      │
      ▼
ListenerService
      │
      ▼
StorageReadRequestHandler
      │
      ├──▶ PerStoreVersionState cache lookup
      │
      ├──▶ StorageEngineRepository.getLocalStorageEngine()
      │
      └──▶ RocksDBStorageEngine.get()
                │
                ▼
           Response
```

### Ingestion Path Interaction

```
Kafka Topic (VT/RT)
      │
      ▼
KafkaStoreIngestionService
      │
      ▼
StoreIngestionTask
      │
      ├──▶ Deserialize/Validate
      │
      ├──▶ StorageEngine.put()
      │
      └──▶ HeartbeatMonitoringService.updateLag()
                │
                ▼
         Report to Helix CustomizedView
```

### State Transition Interaction

```
Helix Controller
      │
      ▼
HelixParticipationService
      │
      ▼
LeaderFollowerPartitionStateModel
      │
      ├──▶ KafkaStoreIngestionService.startConsumption()
      │
      ├──▶ HeartbeatMonitoringService.updateLagMonitor()
      │
      └──▶ Wait for ingestion completion (latch)
```
