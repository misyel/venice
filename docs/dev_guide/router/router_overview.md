# Venice Router Overview

The Venice Router is the stateless routing tier in Venice's three-tier architecture. It routes client requests to appropriate storage nodes, handles scatter-gather for multi-key requests, and manages long-tail retry logic.

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
     │                    │
     │                    ├── Metadata from ZooKeeper
     │                    ├── Routing via Helix CustomizedView
     │                    └── HTTP/HTTPS to Storage Nodes
     │
     └── HTTP/HTTPS requests
```

**Router Responsibilities:**
- **Request Routing**: Routes single-get, multi-get, and compute requests to storage nodes
- **Scatter-Gather**: Fans out multi-key requests to multiple partitions/nodes
- **Long-Tail Retry**: Retries slow requests to different replicas
- **Read Throttling**: Enforces per-store and router-level quotas
- **Metadata Serving**: Serves schema, version, and store metadata
- **Health Monitoring**: Tracks storage node health and availability

## Service Initialization Order

The router initializes services in a specific dependency order:

```
┌─────────────────────────────────────────────────────────────────┐
│                    Startup Sequence                             │
├─────────────────────────────────────────────────────────────────┤
│  1. VeniceJVMStats                 (JVM metrics)                │
│  2. ZkClient                       (ZooKeeper connection)       │
│  3. HelixManager                   (Cluster spectator)          │
│  4. Metadata Repositories          (Store/Schema repos)         │
│  5. Event Loop Groups              (Epoll/NIO)                  │
│  6. StorageNodeClient              (HTTP client pool)           │
│  7. LiveInstanceMonitor            (Storage node tracking)      │
│  8. VeniceDispatcher               (Request dispatching)        │
│  9. VeniceDelegateMode             (Routing strategy)           │
│ 10. DictionaryRetrievalService     (Compression dict)           │
│ 11. VenicePathParser               (Request parsing)            │
│ 12. ScatterGatherHelper            (Alpini framework)           │
│ 13. ReadRequestThrottler           (Quota management)           │
│ 14. HelixGroupSelector             (Optional group routing)     │
│ 15. HTTP Routers                   (Netty servers)              │
│ 16. Service Discovery              (Register with LB)           │
└─────────────────────────────────────────────────────────────────┘
```

**Key Ordering Principles:**
- Metadata repositories refresh before routing components initialize
- Storage node client starts after live instance monitor refreshes
- HTTP servers start last to ensure router is ready before accepting traffic
- Shutdown drains in-flight requests before closing connections

## Key Classes Quick Reference

| Class | Location | Responsibility |
|-------|----------|----------------|
| `RouterServer` | venice-router | Main router entry point, service lifecycle |
| `VeniceDispatcher` | venice-router | Dispatches requests to storage nodes |
| `VeniceDelegateMode` | venice-router | Selects routing strategy |
| `VenicePathParser` | venice-router | Parses request paths |
| `VenicePartitionFinder` | venice-router | Determines partition for keys |
| `VeniceHostFinder` | venice-router | Selects replica hosts |
| `VeniceVersionFinder` | venice-router | Resolves current store version |
| `VeniceResponseAggregator` | venice-router | Aggregates scatter-gather responses |
| `ReadRequestThrottler` | venice-router | Manages read quotas |
| `HelixGroupSelector` | venice-router | Helix-assisted replica selection |
| `StorageNodeClient` | venice-router | HTTP client to storage nodes |
| `MetaDataHandler` | venice-router | Serves metadata API endpoints |

## Related Documentation

- [Router Architecture](router_architecture.md) - Detailed component breakdown
- [Router Data Flow](router_data_flow.md) - Request routing and scatter-gather
- [Router Metrics](router_metrics.md) - Complete metrics reference
- [Router Configuration](router_configuration.md) - Configuration options
- [Router Troubleshooting](router_troubleshooting.md) - Operational guide

## Quick Links to Source

| Component | Source Location |
|-----------|-----------------|
| Router Entry | `services/venice-router/src/main/java/com/linkedin/venice/router/RouterServer.java` |
| Dispatcher | `services/venice-router/src/main/java/com/linkedin/venice/router/api/VeniceDispatcher.java` |
| Path Parser | `services/venice-router/src/main/java/com/linkedin/venice/router/api/VenicePathParser.java` |
| Delegate Mode | `services/venice-router/src/main/java/com/linkedin/venice/router/api/VeniceDelegateMode.java` |
| Throttler | `services/venice-router/src/main/java/com/linkedin/venice/router/throttle/ReadRequestThrottler.java` |
| Stats | `services/venice-router/src/main/java/com/linkedin/venice/router/stats/` |

## Request Types

| Type | Method | Path | Description |
|------|--------|------|-------------|
| Single GET | GET | `/storage/{store}/{key}` | Single key lookup |
| Multi-GET | POST | `/storage/{store}` | Batch key lookup |
| Compute | POST | `/compute/{store}` | Read-compute operations |
| Metadata | GET | `/key_schema/{store}`, etc. | Schema and metadata |
