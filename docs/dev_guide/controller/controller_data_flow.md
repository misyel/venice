# Venice Controller Data Flow

This document describes how requests flow through the Venice Controller, including admin message propagation and request handling.

## Request Handling Flow

### REST API Request Flow

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         Request Processing Flow                              │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  Client Request                                                             │
│     │                                                                        │
│     │  HTTP/HTTPS Request                                                   │
│     │                                                                        │
│     v                                                                        │
│  ┌────────────────────────────────────────────────────────────────────┐     │
│  │ AdminSparkServer                                                    │     │
│  │                                                                      │     │
│  │   • Parse request                                                   │     │
│  │   • Authenticate/authorize                                          │     │
│  │   • Route to handler                                                │     │
│  └────────────────────────────────────────────────────────────────────┘     │
│     │                                                                        │
│     v                                                                        │
│  ┌────────────────────────────────────────────────────────────────────┐     │
│  │ Request Handlers                                                    │     │
│  │                                                                      │     │
│  │   • StoreRequestHandler (store CRUD)                               │     │
│  │   • CreateVersion (version management)                             │     │
│  │   • ClusterAdminOpsRequestHandler (cluster ops)                    │     │
│  │   • VeniceControllerRequestHandler (metadata queries)              │     │
│  └────────────────────────────────────────────────────────────────────┘     │
│     │                                                                        │
│     v                                                                        │
│  ┌────────────────────────────────────────────────────────────────────┐     │
│  │ Admin Interface                                                     │     │
│  │                                                                      │     │
│  │   VeniceHelixAdmin (child) OR VeniceParentHelixAdmin (parent)      │     │
│  └────────────────────────────────────────────────────────────────────┘     │
│     │                                                                        │
│     │  [Child Controller]        [Parent Controller]                        │
│     │  Execute directly          Write to admin topic                       │
│     │         │                          │                                   │
│     v         v                          v                                   │
│  ┌──────────────────┐         ┌──────────────────────────┐                 │
│  │ ZooKeeper/Helix  │         │ Kafka Admin Topic        │                 │
│  │ (immediate)      │         │ (async propagation)      │                 │
│  └──────────────────┘         └──────────────────────────┘                 │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

## Admin Message Flow

### Parent to Child Propagation

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                      Admin Message Propagation                               │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  1. Client Request                                                          │
│     │                                                                        │
│     v                                                                        │
│  ┌──────────────────────────────────────────────────────────────────────┐   │
│  │ Parent Controller                                                     │   │
│  │                                                                        │   │
│  │  VeniceParentHelixAdmin.createStore(...)                              │   │
│  │      │                                                                 │   │
│  │      ├─── Create AdminOperation message                               │   │
│  │      │    • operationType = STORE_CREATION                            │   │
│  │      │    • executionId = unique ID                                   │   │
│  │      │    • payload = StoreCreation{name, schemas, ...}               │   │
│  │      │                                                                 │   │
│  │      └─── Write to admin topic: myStore_admin                         │   │
│  │                                                                        │   │
│  └──────────────────────────────────────────────────────────────────────┘   │
│                                                                              │
│  2. Kafka Propagation                                                       │
│     │                                                                        │
│     │    Admin Topic (myStore_admin)                                        │
│     │    ┌─────────────────────────────────────────────────────────────┐   │
│     │    │ Partition 0 (us-west) │ Partition 1 (us-east) │ ...        │   │
│     │    └─────────────────────────────────────────────────────────────┘   │
│     │                                                                        │
│     v                                                                        │
│  ┌──────────────────────────────────────────────────────────────────────┐   │
│  │ Child Controllers (each region)                                       │   │
│  │                                                                        │   │
│  │  AdminConsumerService                                                 │   │
│  │      │                                                                 │   │
│  │      └─── AdminConsumptionTask (polling Kafka)                        │   │
│  │               │                                                        │   │
│  │               └─── AdminExecutionTask                                 │   │
│  │                        │                                               │   │
│  │                        ├─── Check executionId (idempotency)           │   │
│  │                        │                                               │   │
│  │                        └─── handleStoreCreation(...)                  │   │
│  │                                  │                                     │   │
│  │                                  └─── VeniceHelixAdmin.createStore()  │   │
│  │                                                                        │   │
│  └──────────────────────────────────────────────────────────────────────┘   │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

## AdminExecutionTask Operations

The `AdminExecutionTask` handles all admin message types:

| Operation Type | Handler Method | Description |
|----------------|----------------|-------------|
| `STORE_CREATION` | `handleStoreCreation()` | Create new store |
| `VALUE_SCHEMA_CREATION` | `handleValueSchemaCreation()` | Register value schema |
| `DERIVED_SCHEMA_CREATION` | `handleDerivedSchemaCreation()` | Register write-compute schema |
| `ADD_VERSION` | `handleAddVersion()` | Create new version |
| `SET_STORE_CURRENT_VERSION` | `handleSetStoreCurrentVersion()` | Change serving version |
| `UPDATE_STORE` | `handleSetStore()` | Update store config |
| `DELETE_STORE` | `handleDeleteStore()` | Delete store |
| `DELETE_ALL_VERSIONS` | `handleDeleteAllVersions()` | Delete all versions |
| `DELETE_OLD_VERSION` | `handleDeleteOldVersion()` | Delete specific version |
| `DISABLE_STORE_WRITE` | `handleDisableStoreWrite()` | Disable writes |
| `ENABLE_STORE_WRITE` | `handleEnableStoreWrite()` | Enable writes |
| `KILL_OFFLINE_PUSH_JOB` | `handleKillOfflinePush()` | Kill running push |
| `MIGRATE_STORE` | `handleStoreMigration()` | Start migration |
| `ABORT_MIGRATION` | `handleAbortMigration()` | Cancel migration |
| `ROLLBACK_CURRENT_VERSION` | `handleRollbackCurrentVersion()` | Rollback version |
| `ROLLFORWARD_CURRENT_VERSION` | `handleRollforwardCurrentVersion()` | Rollforward version |

## Idempotency

Admin messages include a unique `executionId`:
- Controller tracks last succeeded execution ID in ZooKeeper
- Duplicate messages with lower IDs are skipped
- Prevents double-execution on retries or redelivery

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                      Idempotency Check Flow                                  │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  AdminExecutionTask receives message                                        │
│     │                                                                        │
│     ├─── Get message.executionId                                            │
│     │                                                                        │
│     ├─── Read lastSucceededExecutionId from ZK                              │
│     │                                                                        │
│     ├─── if (executionId <= lastSucceededId)                                │
│     │        → Skip (already processed)                                     │
│     │                                                                        │
│     ├─── Execute operation                                                  │
│     │                                                                        │
│     └─── Update lastSucceededExecutionId in ZK                              │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

## Admin Message Latency Breakdown

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                  Admin Message Latency Components                            │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  Total Latency (admin_message_total_latency_ms)                             │
│  ├─────────────────────────────────────────────────────────────────────────│
│  │                                                                          │
│  │  ┌────────────────┐  ┌─────────────────┐  ┌─────────────────────────┐  │
│  │  │ MM Latency     │  │ Delegate        │  │ Processing Latency      │  │
│  │  │                │  │ Latency         │  │                         │  │
│  │  │ Mirror Maker   │  │ Queue to        │  │ First attempt to        │  │
│  │  │ copy time      │  │ processing      │  │ completion              │  │
│  │  └────────────────┘  └─────────────────┘  └─────────────────────────┘  │
│  │                                                                          │
│  │  admin_message_   admin_message_        admin_message_process_          │
│  │  mm_latency_ms    delegate_latency_ms   latency_ms                      │
│  │                                                                          │
│  └─────────────────────────────────────────────────────────────────────────│
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

## Admin Consumption Flow

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    Admin Consumption Pipeline                                │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  AdminConsumerService                                                       │
│     │                                                                        │
│     └─── AdminConsumptionTask                                               │
│              │                                                               │
│              ├─── Poll Kafka admin topic                                    │
│              │                                                               │
│              ├─── Deserialize AdminOperation                                │
│              │                                                               │
│              ├─── Route to internal queue (per-store)                       │
│              │                                                               │
│              └─── AdminExecutionTask (per-store)                            │
│                       │                                                      │
│                       ├─── Check idempotency (executionId)                  │
│                       │                                                      │
│                       ├─── Execute operation                                │
│                       │    (calls VeniceHelixAdmin)                         │
│                       │                                                      │
│                       ├─── Record metrics                                   │
│                       │                                                      │
│                       └─── Update offset checkpoint                         │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

## Error Handling

### Failed Admin Messages

When an admin message fails:
1. `failed_admin_messages` counter increments
2. `failed_admin_message_offset` records the blocking offset
3. Subsequent messages for the same store are blocked
4. Operator intervention may be required (skip message or fix issue)

### Recovery Options

| Option | Command | Use Case |
|--------|---------|----------|
| **Skip Message** | POST `/skip_admin_message` | Message is unrecoverable |
| **Retry** | Restart controller | Transient failure |
| **Manual Fix** | Fix data, then skip | Data corruption |

## gRPC Request Flow

The gRPC server (`VeniceGrpcServer`) follows a similar pattern:

```
gRPC Request → VeniceGrpcServer → Request Handler → Admin Interface
```

Key differences:
- Uses Protocol Buffers instead of JSON
- Supports streaming for some operations
- TLS required by default

## See Also

- [Controller Architecture](controller_architecture.md) - Internal components
- [Controller Metrics](controller_metrics.md) - Monitoring admin operations
- [Controller Troubleshooting](controller_troubleshooting.md) - Handling stuck messages
