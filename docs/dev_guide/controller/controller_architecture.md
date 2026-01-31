# Venice Controller Architecture

This document describes the internal architecture and components of the Venice Controller.

## Controller Internal Components

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                            VeniceController                                  │
│                        (Main Entry Point)                                    │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  ┌─────────────────────────────────────────────────────────────────────┐    │
│  │                    VeniceControllerService                           │    │
│  │                                                                       │    │
│  │   ┌─────────────────────────────────────────────────────────────┐   │    │
│  │   │                      Admin Interface                         │   │    │
│  │   │                                                               │   │    │
│  │   │  ┌─────────────────────┐    ┌─────────────────────────────┐ │   │    │
│  │   │  │  VeniceHelixAdmin   │    │  VeniceParentHelixAdmin     │ │   │    │
│  │   │  │  (Child Controller) │    │  (Parent Controller)        │ │   │    │
│  │   │  │                     │    │                             │ │   │    │
│  │   │  │  • Direct Helix ops │    │  • Wraps VeniceHelixAdmin   │ │   │    │
│  │   │  │  • ZK store mgmt    │    │  • Admin topic propagation  │ │   │    │
│  │   │  │  • Kafka topic ops  │    │  • Multi-region coord       │ │   │    │
│  │   │  │  • Schema registry  │    │  • System store mgmt        │ │   │    │
│  │   │  └─────────────────────┘    └─────────────────────────────┘ │   │    │
│  │   │                                                               │   │    │
│  │   └─────────────────────────────────────────────────────────────┘   │    │
│  │                                                                       │    │
│  │   ┌─────────────────────────────────────────────────────────────┐   │    │
│  │   │                 Supporting Services                          │   │    │
│  │   │                                                               │   │    │
│  │   │  ┌───────────────────┐  ┌───────────────────┐               │   │    │
│  │   │  │ AdminConsumer     │  │ TopicCleanup      │               │   │    │
│  │   │  │ Service           │  │ Service           │               │   │    │
│  │   │  └───────────────────┘  └───────────────────┘               │   │    │
│  │   │                                                               │   │    │
│  │   │  ┌───────────────────┐  ┌───────────────────┐               │   │    │
│  │   │  │ DeferredVersion   │  │ SystemStoreRepair │               │   │    │
│  │   │  │ SwapService       │  │ Service           │               │   │    │
│  │   │  └───────────────────┘  └───────────────────┘               │   │    │
│  │   │                                                               │   │    │
│  │   │  ┌───────────────────┐  ┌───────────────────┐               │   │    │
│  │   │  │ StoreBackup       │  │ ErrorPartition    │               │   │    │
│  │   │  │ CleanupService    │  │ ResetTask         │               │   │    │
│  │   │  └───────────────────┘  └───────────────────┘               │   │    │
│  │   │                                                               │   │    │
│  │   └─────────────────────────────────────────────────────────────┘   │    │
│  └─────────────────────────────────────────────────────────────────────┘    │
│                                                                              │
│  ┌─────────────────────────────────────────────────────────────────────┐    │
│  │                         API Servers                                  │    │
│  │                                                                       │    │
│  │   ┌───────────────────┐  ┌───────────────────┐                      │    │
│  │   │  AdminSparkServer │  │  VeniceGrpcServer │                      │    │
│  │   │  (REST API)       │  │  (gRPC API)       │                      │    │
│  │   │                   │  │                   │                      │    │
│  │   │  HTTP + HTTPS     │  │  gRPC + TLS       │                      │    │
│  │   └───────────────────┘  └───────────────────┘                      │    │
│  │                                                                       │    │
│  └─────────────────────────────────────────────────────────────────────┘    │
│                                                                              │
│  ┌─────────────────────────────────────────────────────────────────────┐    │
│  │                    External Dependencies                             │    │
│  │                                                                       │    │
│  │   ┌───────────────┐  ┌───────────────┐  ┌───────────────┐          │    │
│  │   │   ZooKeeper   │  │     Kafka     │  │     Helix     │          │    │
│  │   │               │  │               │  │               │          │    │
│  │   │ • Store meta  │  │ • Admin topic │  │ • Cluster     │          │    │
│  │   │ • Configs     │  │ • Version     │  │   management  │          │    │
│  │   │ • Leadership  │  │   topics      │  │ • Partition   │          │    │
│  │   │               │  │ • RT topics   │  │   assignment  │          │    │
│  │   └───────────────┘  └───────────────┘  └───────────────┘          │    │
│  │                                                                       │    │
│  └─────────────────────────────────────────────────────────────────────┘    │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

## Component Responsibilities

| Component | Responsibility |
|-----------|----------------|
| **VeniceController** | Main entry point; initializes all services and manages lifecycle |
| **VeniceControllerService** | Core service containing Admin implementation |
| **VeniceHelixAdmin** | Child controller implementation; direct Helix/ZK/Kafka operations |
| **VeniceParentHelixAdmin** | Parent controller; wraps child admin for multi-region |
| **AdminSparkServer** | REST API server (HTTP/HTTPS) |
| **VeniceGrpcServer** | gRPC API server |
| **AdminConsumerService** | Consumes admin messages from Kafka |
| **TopicCleanupService** | Deletes old Kafka topics |
| **DeferredVersionSwapService** | Handles delayed version transitions |
| **SystemStoreRepairService** | Maintains system store health |

## Key Classes

### Admin Interface

The `Admin` interface (`services/venice-controller/.../controller/Admin.java`) defines all administrative operations:

**Store Lifecycle**:
- `createStore()` - Create new store with schemas
- `deleteStore()` - Delete entire store
- `migrateStore()` / `completeMigration()` / `abortMigration()` - Store migration

**Version Management**:
- `addVersionAndStartIngestion()` - Create version and start ingestion
- `incrementVersionIdempotent()` - Increment version number
- `setStoreCurrentVersion()` - Mark version as serving
- `rollForwardToFutureVersion()` / `rollbackToBackupVersion()` - Version transitions

**Schema Management**:
- `addValueSchema()` - Register value schema
- `addDerivedSchema()` - Add write-compute schema
- `getDerivedSchemas()` - Retrieve derived schemas

### VeniceHelixAdmin

Core implementation for single-cluster operations (`services/venice-controller/.../controller/VeniceHelixAdmin.java`):

```java
public class VeniceHelixAdmin implements Admin {
    private final HelixAdmin admin;                    // Helix admin client
    private final HelixAdminClient helixAdminClient;   // HaaS-compatible wrapper
    private final Map<String, AdminConsumerService> adminConsumerServices;

    // Store operations
    public void createStore(...) { /* ZK + Helix */ }
    public void deleteStore(...) { /* Cleanup all resources */ }

    // Version operations
    public Version addVersionAndStartIngestion(...) { /* Create topic, Helix resource */ }
    public void setStoreCurrentVersion(...) { /* Update serving version */ }

    // Schema operations
    public SchemaEntry addValueSchema(...) { /* Register with schema repo */ }
}
```

### VeniceParentHelixAdmin

Parent controller wrapper (`services/venice-controller/.../controller/VeniceParentHelixAdmin.java`):

```java
public class VeniceParentHelixAdmin implements Admin {
    private final VeniceHelixAdmin internalAdmin;  // Delegated child admin

    // All operations write to admin topic for propagation
    public void createStore(...) {
        AdminOperation op = createStoreCreationMessage(...);
        sendAdminMessageAndWaitForConsumed(op);
    }
}
```

## Helix Integration

### Multi-Level Helix Structure

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                      Helix Multi-Level Architecture                          │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  Level 1: Controller Cluster                                                │
│  ─────────────────────────────                                              │
│  Resource: "controller-cluster"                                             │
│  Participants: All controller instances                                     │
│  Purpose: Elect leader controller                                           │
│                                                                              │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                    Controller Cluster (Helix)                        │   │
│  │                                                                       │   │
│  │   ┌──────────┐  ┌──────────┐  ┌──────────┐                          │   │
│  │   │Controller│  │Controller│  │Controller│                          │   │
│  │   │   #1     │  │   #2     │  │   #3     │                          │   │
│  │   │ (LEADER) │  │(STANDBY) │  │(STANDBY) │                          │   │
│  │   └──────────┘  └──────────┘  └──────────┘                          │   │
│  │                                                                       │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                          │                                                   │
│                          │ Leader manages                                    │
│                          v                                                   │
│  Level 2: Storage Clusters (one per Venice cluster)                         │
│  ─────────────────────────────────────────────────                          │
│  Resources: Kafka topic names (Store_v1, Store_v2, ...)                     │
│  Participants: Storage nodes (Venice Servers)                               │
│  Partitions: Map to Kafka partitions                                        │
│                                                                              │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                    Storage Cluster: prod-cluster                     │   │
│  │                                                                       │   │
│  │   Resource: MyStore_v3                                               │   │
│  │   ┌─────────────────────────────────────────────────────────────┐   │   │
│  │   │ P0: Server-A(L), Server-B(F)                                │   │   │
│  │   │ P1: Server-B(L), Server-C(F)                                │   │   │
│  │   │ P2: Server-C(L), Server-A(F)                                │   │   │
│  │   │ ...                                                          │   │   │
│  │   └─────────────────────────────────────────────────────────────┘   │   │
│  │                                                                       │   │
│  │   L = Leader, F = Follower                                          │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### HelixAdminClient Interface

Key operations for Helix management:

```java
interface HelixAdminClient {
    // Cluster management
    boolean isVeniceControllerClusterCreated();
    boolean isVeniceStorageClusterCreated(String clusterName);
    void createVeniceStorageCluster(String clusterName, ...);

    // Resource management
    void createVeniceStorageClusterResources(
        String clusterName,
        String kafkaTopic,         // e.g., "MyStore_v3"
        int numberOfPartitions,
        int replicationFactor
    );
    boolean containsResource(String clusterName, String resourceName);
    void dropResource(String clusterName, String resourceName);

    // Instance management
    List<String> getInstancesInCluster(String clusterName);
    void dropStorageInstance(String clusterName, String instanceId);
    void enablePartition(boolean enabled, ...);
    void resetPartition(String clusterName, String instanceId, ...);

    // Maintenance
    void manuallyEnableMaintenanceMode(String clusterName, boolean enabled, ...);
}
```

## Store Lifecycle

### Store Creation

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         Store Creation Flow                                  │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  Client                                                                      │
│    │                                                                         │
│    │  POST /admin/store                                                     │
│    │  {storeName, owner, keySchema, valueSchema}                            │
│    │                                                                         │
│    v                                                                         │
│  ┌────────────────────────────────────────────────────────────────────┐     │
│  │ AdminSparkServer                                                    │     │
│  │   │                                                                 │     │
│  │   └─> StoreRequestHandler.createStore()                            │     │
│  └────────────────────────────────────────────────────────────────────┘     │
│    │                                                                         │
│    v                                                                         │
│  ┌────────────────────────────────────────────────────────────────────┐     │
│  │ VeniceHelixAdmin.createStore()                                      │     │
│  │                                                                      │     │
│  │   1. Validate store doesn't exist                                   │     │
│  │   2. Validate schemas                                               │     │
│  │   3. Create ZK node: /venice/{cluster}/stores/{storeName}          │     │
│  │   4. Initialize store metadata:                                     │     │
│  │      • Owner                                                        │     │
│  │      • Partition count                                              │     │
│  │      • Replication factor                                           │     │
│  │      • Empty version list                                           │     │
│  │   5. Register key schema                                            │     │
│  │   6. Register value schema                                          │     │
│  │   7. Return NewStoreResponse                                        │     │
│  └────────────────────────────────────────────────────────────────────┘     │
│                                                                              │
│  [If Parent Controller]                                                     │
│    │                                                                         │
│    v                                                                         │
│  ┌────────────────────────────────────────────────────────────────────┐     │
│  │ Write AdminOperation to admin topic                                 │     │
│  │   → All child controllers receive and execute locally              │     │
│  └────────────────────────────────────────────────────────────────────┘     │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Store Deletion

```
Store Deletion Flow:
1. DELETE /admin/store?store={name}&cluster={cluster}
2. VeniceHelixAdmin.deleteStore()
   a. Kill any running push jobs
   b. Delete all versions (Kafka topics, Helix resources)
   c. Delete store ZK node
   d. Remove from schema registry
3. Return success response
```

## System Stores

The controller manages three internal system stores:

### 1. Push Job Details Store

- **Purpose**: Store push status, timestamps, progress
- **Used by**: Clients monitoring push progress
- **Scope**: Per-region

### 2. Heartbeat Store

- **Purpose**: DaVinci client liveness tracking
- **Used by**: Controller monitoring ingestion health
- **Scope**: Per-region

### 3. Parent Controller Metadata Store

- **Purpose**: Cross-region state synchronization
- **Used by**: Parent controller for coordination
- **Scope**: Parent controller only

## Key Classes Reference

| Class | Location | Description |
|-------|----------|-------------|
| `VeniceController` | `controller/VeniceController.java` | Main entry point |
| `Admin` | `controller/Admin.java` | Admin interface (800+ lines) |
| `VeniceHelixAdmin` | `controller/VeniceHelixAdmin.java` | Child controller impl (3000+ lines) |
| `VeniceParentHelixAdmin` | `controller/VeniceParentHelixAdmin.java` | Parent controller impl (2000+ lines) |
| `VeniceControllerService` | `controller/VeniceControllerService.java` | Service initialization |
| `AdminConsumerService` | `kafka/consumer/AdminConsumerService.java` | Admin topic consumption |
| `AdminConsumptionTask` | `kafka/consumer/AdminConsumptionTask.java` | Consumption loop |
| `AdminExecutionTask` | `kafka/consumer/AdminExecutionTask.java` | Message execution |
| `AdminSparkServer` | `server/AdminSparkServer.java` | REST API server |
| `CreateVersion` | `server/CreateVersion.java` | Version creation logic |
| `StoreRequestHandler` | `server/StoreRequestHandler.java` | Store operations |
| `HelixAdminClient` | `controller/HelixAdminClient.java` | Helix abstractions |

## See Also

- [Controller Overview](controller_overview.md) - Quick entry point
- [Controller Data Flow](controller_data_flow.md) - Request handling and admin message flow
- [Parent-Child Architecture](controller_parent_child.md) - Multi-region deployment
- [Controller Metrics](controller_metrics.md) - Metrics reference
