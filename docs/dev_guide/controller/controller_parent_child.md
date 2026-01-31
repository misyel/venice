# Venice Parent-Child Controller Architecture

This document describes Venice's multi-region deployment model using parent and child controllers.

## Overview

Venice supports multi-region deployments with a parent-child controller architecture. The parent controller acts as the single source of truth, propagating changes to child controllers in each region via Kafka admin topics.

## Parent-Child Architecture Diagram

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    Parent-Child Controller Architecture                      │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│                    ┌─────────────────────────────────┐                      │
│                    │      Parent Controller          │                      │
│                    │   (VeniceParentHelixAdmin)      │                      │
│                    │                                 │                      │
│                    │  • Receives admin requests      │                      │
│                    │  • Writes to admin topic        │                      │
│                    │  • Manages system stores        │                      │
│                    │  • Coordinates regions          │                      │
│                    └───────────────┬─────────────────┘                      │
│                                    │                                         │
│                         Admin Topic (Kafka)                                  │
│                    ┌───────────────┴───────────────┐                        │
│                    │                               │                         │
│                    v                               v                         │
│     ┌──────────────────────────┐   ┌──────────────────────────┐            │
│     │   Child Controller       │   │   Child Controller       │            │
│     │   Region: us-west        │   │   Region: us-east        │            │
│     │   (VeniceHelixAdmin)     │   │   (VeniceHelixAdmin)     │            │
│     │                          │   │                          │            │
│     │  ┌────────────────────┐  │   │  ┌────────────────────┐  │            │
│     │  │ AdminConsumer      │  │   │  │ AdminConsumer      │  │            │
│     │  │ Service            │  │   │  │ Service            │  │            │
│     │  │                    │  │   │  │                    │  │            │
│     │  │ Consumes admin     │  │   │  │ Consumes admin     │  │            │
│     │  │ messages, executes │  │   │  │ messages, executes │  │            │
│     │  │ locally            │  │   │  │ locally            │  │            │
│     │  └────────────────────┘  │   │  └────────────────────┘  │            │
│     │                          │   │                          │            │
│     │  Manages:                │   │  Manages:                │            │
│     │  • Local Helix cluster   │   │  • Local Helix cluster   │            │
│     │  • Local Kafka topics    │   │  • Local Kafka topics    │            │
│     │  • Local ZK metadata     │   │  • Local ZK metadata     │            │
│     └──────────────────────────┘   └──────────────────────────┘            │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

## Parent vs Child Controller

| Aspect | Parent Controller | Child Controller |
|--------|-------------------|------------------|
| **Implementation** | `VeniceParentHelixAdmin` | `VeniceHelixAdmin` |
| **Request Handling** | Writes to admin topic | Executes directly |
| **Scope** | Multi-region | Single region |
| **System Stores** | Creates and manages | Receives via admin topic |
| **Leadership** | One per deployment | One per cluster |
| **Configuration** | `controller.parent.mode=true` | `controller.parent.mode=false` |

## Operation Flow Comparison

### Child Controller (Single Region)

```
Client Request → VeniceHelixAdmin → ZooKeeper/Helix/Kafka (immediate)
```

### Parent Controller (Multi Region)

```
Client Request → VeniceParentHelixAdmin → Admin Topic → Child Controllers → Local execution
```

## Admin Topic Structure

Each Venice deployment has one admin topic per store for propagating commands:

```
Admin Topic: {storeName}_admin

Partitions:
├── Partition 0 (us-west)
├── Partition 1 (us-east)
├── Partition 2 (eu-west)
└── ...

Each partition consumed by corresponding regional child controller
```

## Multi-Region Deployment Patterns

### Pattern 1: Single Parent, Multiple Children

```
                    ┌─────────────┐
                    │   Parent    │
                    │ (us-west)   │
                    └──────┬──────┘
                           │
           ┌───────────────┼───────────────┐
           v               v               v
    ┌──────────┐    ┌──────────┐    ┌──────────┐
    │  Child   │    │  Child   │    │  Child   │
    │ us-west  │    │ us-east  │    │ eu-west  │
    └──────────┘    └──────────┘    └──────────┘
```

### Pattern 2: Parent Colocated with Child

The parent controller can be colocated with a child controller in the same region:

```
┌─────────────────────────────────────────┐
│            us-west Region               │
│                                         │
│  ┌─────────────────────────────────┐   │
│  │  Controller Instance            │   │
│  │  ┌─────────────────────────┐   │   │
│  │  │ VeniceParentHelixAdmin  │   │   │
│  │  │ (Parent functions)      │   │   │
│  │  └───────────┬─────────────┘   │   │
│  │              │                  │   │
│  │              v                  │   │
│  │  ┌─────────────────────────┐   │   │
│  │  │ VeniceHelixAdmin        │   │   │
│  │  │ (Child functions)       │   │   │
│  │  └─────────────────────────┘   │   │
│  └─────────────────────────────────┘   │
└─────────────────────────────────────────┘
```

## Failure Scenarios

### Parent Controller Failure

| Scenario | Impact | Recovery |
|----------|--------|----------|
| Parent leader fails | Admin operations blocked | Standby becomes leader |
| All parent instances down | No new admin operations | Restore parent |
| Parent → Kafka disconnection | Admin operations fail | Reconnect, retry |

### Child Controller Failure

| Scenario | Impact | Recovery |
|----------|--------|----------|
| Child leader fails | Regional operations blocked | Standby becomes leader |
| All child instances down | Region unhealthy | Restore child |
| Admin topic lag | Stale configuration | Catch up on restart |

### Split Brain Prevention

- Only the parent controller accepts mutating requests
- Child controllers only execute commands from admin topic
- Execution IDs ensure idempotency across retries

## Configuration

### Parent Controller Settings

```properties
# Enable parent mode
controller.parent.mode=true
multi.region=true

# Child cluster endpoints
child.cluster.url.map=us-west:https://us-west-controller:5556,us-east:https://us-east-controller:5556

# Replication settings
native.replication.fabric.allowlist=us-west,us-east
```

### Child Controller Settings

```properties
# Disable parent mode (default)
controller.parent.mode=false
multi.region=true

# Admin topic consumption
admin.topic.remote.consumption.enabled=true
```

## System Store Management

System stores are special stores managed by the parent controller:

| System Store | Purpose | Management |
|--------------|---------|------------|
| Push Job Details | Push status tracking | Parent creates, children replicate |
| Heartbeat Store | Client liveness | Parent creates, children replicate |
| Metadata Store | Cross-region state | Parent only |

## Monitoring Multi-Region Deployments

Key metrics for multi-region health:

| Metric | Description | Alert Threshold |
|--------|-------------|-----------------|
| `admin_consumption_offset_lag` | Admin topic lag per child | > 100 messages |
| `deferred_version_swap_parent_child_status_mismatch` | Status disagreement | > 0 |
| `deferred_version_swap_child_status_mismatch` | Inter-child disagreement | > 0 |

## See Also

- [Controller Architecture](controller_architecture.md) - Internal components
- [Controller Data Flow](controller_data_flow.md) - Admin message flow details
- [Controller Configuration](controller_configuration.md) - Full configuration reference
- [Controller Troubleshooting](controller_troubleshooting.md) - Multi-region issues
