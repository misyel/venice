# Venice Server Overview

The Venice Server is the stateful data storage and serving tier in Venice's three-tier architecture. It hosts data partitions, serves read requests, and handles data ingestion from Kafka topics.

## Role in Venice Architecture

```
                    ┌─────────────┐
                    │  Controller │  (Control Plane)
                    └──────┬──────┘
                           │
                           ▼
┌──────────┐       ┌─────────────┐       ┌──────────┐
│  Clients │◀─────▶│   Router    │◀─────▶│  Server  │  (Data Plane)
└──────────┘       └─────────────┘       └──────────┘
                                                │
                                                ▼
                                         ┌─────────────┐
                                         │    Kafka    │
                                         └─────────────┘
```

**Server Responsibilities:**
- **Data Storage**: Hosts partitions in RocksDB storage engines
- **Read Serving**: Handles single-get, multi-get, and read-compute requests
- **Data Ingestion**: Consumes from Kafka VT (Version Topics) and RT (Real-Time Topics)
- **Replication**: Leader/Follower model with active-active cross-region support
- **Helix Integration**: Participates in cluster management via Helix

## Service Initialization Order

The server initializes services in a specific dependency order:

```
┌─────────────────────────────────────────────────────────────────┐
│                    Startup Sequence                             │
├─────────────────────────────────────────────────────────────────┤
│  1. VeniceJVMStats                 (JVM metrics)                │
│  2. Schema Initialization          (System schema bootstrap)     │
│  3. Metadata Repositories          (Store/Schema repos)          │
│  4. StorageService                 (RocksDB engines)             │
│  5. StorageEngineMetadataService   (Partition state tracking)    │
│  6. RemoteIngestionRepairService   (Ingestion repair)            │
│  7. HeartbeatMonitoringService     (Lag monitoring)              │
│  8. AdaptiveThrottlerSignalService (Optional throttling)         │
│  9. KafkaStoreIngestionService     (Kafka consumption)           │
│ 10. DiskHealthCheckService         (Disk monitoring)             │
│ 11. BackupVersionOptimizationSvc   (Optional backup optimization)│
│ 12. StoreValueSchemasCacheService  (Schema caching)              │
│ 13. ServerReadMetadataRepository   (Read metadata)               │
│ 14. ListenerService                (HTTP request handling)       │
│ 15. BlobTransferManager            (Optional P2P transfer)       │
│ 16. HelixParticipationService      (Cluster membership) [LAST]   │
│ 17. Add KafkaStoreIngestionService (Consumer startup)            │
│ 18. Add HeartbeatMonitoringService (HB startup)                  │
│ 19. LeakedResourceCleaner          (Optional cleanup)            │
└─────────────────────────────────────────────────────────────────┘
```

**Key Ordering Principles:**
- Storage services start before ingestion services
- Helix participation starts last to ensure server is ready before advertising availability
- Shutdown occurs in reverse order

## Key Classes Quick Reference

| Class | Module | Responsibility |
|-------|--------|----------------|
| `VeniceServer` | venice-server | Main server entry point, service lifecycle |
| `StorageReadRequestHandler` | venice-server | Read path request handling |
| `ListenerService` | venice-server | HTTP/gRPC listener setup |
| `KafkaStoreIngestionService` | da-vinci-client | Manages all ingestion tasks |
| `StoreIngestionTask` | da-vinci-client | Per-version ingestion from Kafka |
| `LeaderFollowerStoreIngestionTask` | da-vinci-client | L/F replication logic |
| `StorageService` | da-vinci-client | RocksDB storage management |
| `RocksDBStorageEngine` | da-vinci-client | Individual store version storage |
| `HelixParticipationService` | da-vinci-client | Helix cluster membership |
| `LeaderFollowerPartitionStateModel` | da-vinci-client | Helix state transitions |
| `HeartbeatMonitoringService` | da-vinci-client | Ingestion lag monitoring |

## Related Documentation

- [Server Architecture](server_architecture.md) - Detailed component breakdown
- [Server Data Flow](server_data_flow.md) - Read and write path details
- [Server Metrics](server_metrics.md) - Complete metrics reference
- [Server Configuration](server_configuration.md) - Configuration options
- [Server State Transitions](server_state_transitions.md) - Helix state machine
- [Server Troubleshooting](server_troubleshooting.md) - Operational guide

## Quick Links to Source

| Component | Source Location |
|-----------|-----------------|
| Server Entry | `services/venice-server/src/main/java/com/linkedin/venice/server/VeniceServer.java` |
| Read Handler | `services/venice-server/src/main/java/com/linkedin/venice/listener/StorageReadRequestHandler.java` |
| Ingestion | `clients/da-vinci-client/src/main/java/com/linkedin/davinci/kafka/consumer/` |
| Storage | `clients/da-vinci-client/src/main/java/com/linkedin/davinci/store/` |
| Helix | `clients/da-vinci-client/src/main/java/com/linkedin/davinci/helix/` |
| Stats | `services/venice-server/src/main/java/com/linkedin/venice/stats/` |
