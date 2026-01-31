# Venice Controller Overview

The Venice Controller is the **control plane** of Venice, responsible for managing the lifecycle of stores, versions, schemas, and coordinating multi-region deployments.

## Role in Venice Architecture

```
                     ┌─────────────────────────────────────┐
                     │          CONTROLLER                  │
                     │        (Control Plane)               │
                     │                                      │
                     │  Store CRUD • Version lifecycle      │
                     │  Schema evolution • Helix partition  │
                     │  Push orchestration • Multi-region   │
                     └───────────────┬─────────────────────┘
                                     │
              ┌──────────────────────┼──────────────────────┐
              v                      v                      v
       ┌─────────────┐        ┌─────────────┐        ┌─────────────┐
       │   ROUTER    │        │   ROUTER    │        │   ROUTER    │
       │ (Stateless) │        │ (Stateless) │        │ (Stateless) │
       └──────┬──────┘        └──────┬──────┘        └──────┬──────┘
              │                      │                      │
              └──────────────────────┼──────────────────────┘
                                     │
              ┌──────────────────────┼──────────────────────┐
              v                      v                      v
       ┌─────────────┐        ┌─────────────┐        ┌─────────────┐
       │   SERVER    │        │   SERVER    │        │   SERVER    │
       │ (Stateful)  │        │ (Stateful)  │        │ (Stateful)  │
       │  [RocksDB]  │        │  [RocksDB]  │        │  [RocksDB]  │
       └─────────────┘        └─────────────┘        └─────────────┘
```

## Core Responsibilities

- **Store Lifecycle Management**: Create, update, delete stores
- **Version Management**: Create versions, manage push lifecycle, version swaps
- **Schema Evolution**: Register and validate key/value schemas
- **Partition Assignment**: Coordinate with Helix for partition distribution
- **Multi-Region Coordination**: Propagate changes across regions via admin topics
- **System Store Management**: Maintain internal system stores for metadata

## Service Initialization Order

1. Initialize ZooKeeper connection
2. Start Helix controller cluster participation
3. Create `VeniceHelixAdmin` (or `VeniceParentHelixAdmin` for parent mode)
4. Start `AdminConsumerService` for admin topic consumption
5. Start background services (cleanup, repair, monitoring)
6. Start API servers (REST via AdminSparkServer, gRPC via VeniceGrpcServer)

## Key Classes Quick Reference

| Class | Responsibility |
|-------|----------------|
| `VeniceController` | Main entry point; initializes all services |
| `VeniceHelixAdmin` | Child controller implementation; direct Helix/ZK/Kafka operations |
| `VeniceParentHelixAdmin` | Parent controller; wraps child admin for multi-region coordination |
| `AdminConsumerService` | Consumes admin messages from Kafka admin topic |
| `AdminExecutionTask` | Executes admin operations (store creation, version management, etc.) |
| `AdminSparkServer` | REST API server (HTTP/HTTPS) |
| `VeniceGrpcServer` | gRPC API server |

## External Dependencies

| Dependency | Purpose |
|------------|---------|
| **ZooKeeper** | Store metadata, configs, leadership election |
| **Kafka** | Admin topic (commands), Version topics (data), RT topics (real-time) |
| **Helix** | Cluster management, partition assignment, state transitions |

## Controller Modes

| Mode | Implementation | Use Case |
|------|----------------|----------|
| **Child Controller** | `VeniceHelixAdmin` | Single-region, executes operations directly |
| **Parent Controller** | `VeniceParentHelixAdmin` | Multi-region, propagates via admin topic |

## Related Documentation

- [Controller Architecture](controller_architecture.md) - Internal components and structure
- [Controller Data Flow](controller_data_flow.md) - Request handling and admin message flow
- [Controller Metrics](controller_metrics.md) - Comprehensive metrics reference
- [Controller Configuration](controller_configuration.md) - Configuration reference
- [Parent-Child Architecture](controller_parent_child.md) - Multi-region deployment
- [Push Lifecycle](controller_push_lifecycle.md) - Version management and push phases
- [Admin API Reference](controller_admin_api.md) - REST/gRPC API endpoints
- [Controller Troubleshooting](controller_troubleshooting.md) - Operational guide

## Directory Structure

```
services/venice-controller/
├── src/main/java/com/linkedin/venice/controller/
│   ├── VeniceController.java              # Main entry point
│   ├── Admin.java                         # Admin interface
│   ├── VeniceHelixAdmin.java              # Child controller impl
│   ├── VeniceParentHelixAdmin.java        # Parent controller impl
│   ├── VeniceControllerService.java       # Service initialization
│   │
│   ├── kafka/consumer/
│   │   ├── AdminConsumerService.java      # Admin topic consumer
│   │   ├── AdminConsumptionTask.java      # Consumption loop
│   │   └── AdminExecutionTask.java        # Message execution
│   │
│   ├── server/
│   │   ├── AdminSparkServer.java          # REST API server
│   │   ├── StoreRequestHandler.java       # Store operations
│   │   └── CreateVersion.java             # Version creation
│   │
│   └── stats/                             # Metrics classes
│
└── src/test/java/...                      # Tests
```
