# Venice Router Architecture

This document provides comprehensive documentation for the Venice Router layer, including architecture, request flows, component interactions, and configuration.

## Table of Contents

- [Overview](#overview)
- [Architecture](#architecture)
- [Request Flow](#request-flow)
- [Scatter-Gather Implementation](#scatter-gather-implementation)
- [Health Management](#health-management)
- [Retry Logic](#retry-logic)
- [Metadata Services](#metadata-services)
- [Connection Management](#connection-management)
- [Configuration Reference](#configuration-reference)
- [Key Classes Reference](#key-classes-reference)
- [Monitoring & Metrics](#monitoring--metrics)

---

## Overview

The Venice Router is a **stateless routing tier** that sits between clients and storage nodes (Venice Servers). It is responsible for:

- **Request Routing**: Directing client requests to appropriate storage nodes based on store/partition
- **Scatter-Gather**: Distributing batch queries across multiple servers and aggregating responses
- **Metadata Services**: Providing schema, version, and cluster discovery information
- **Load Balancing**: Distributing load across healthy replicas
- **Long-Tail Retry**: Automatically retrying slow requests to improve tail latency
- **Health Monitoring**: Tracking storage node health and routing around failures

### Three-Tier Venice Architecture

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              Venice Architecture                             │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  ┌──────────────┐    ┌──────────────┐    ┌──────────────┐                   │
│  │ Thin Client  │    │ Fast Client  │    │  Da-Vinci    │                   │
│  │   (Remote)   │    │  (Optimized) │    │  (Embedded)  │                   │
│  └──────┬───────┘    └──────┬───────┘    └──────┬───────┘                   │
│         │                   │                   │                            │
│         │                   │                   │ (direct storage access)    │
│         v                   v                   │                            │
│  ┌─────────────────────────────────────┐        │                            │
│  │         ROUTER (Stateless)          │        │                            │
│  │  • Request parsing & routing        │        │                            │
│  │  • Scatter-gather for batch ops     │        │                            │
│  │  • Long-tail retry                  │        │                            │
│  │  • Health monitoring                │        │                            │
│  │  • Metadata queries                 │        │                            │
│  └─────────────────┬───────────────────┘        │                            │
│                    │                            │                            │
│         ┌──────────┼──────────┬─────────────────┘                            │
│         v          v          v                                              │
│  ┌────────────┐ ┌────────────┐ ┌────────────┐                               │
│  │  SERVER 1  │ │  SERVER 2  │ │  SERVER N  │   (Stateful Data Tier)        │
│  │ ┌────────┐ │ │ ┌────────┐ │ │ ┌────────┐ │                               │
│  │ │RocksDB │ │ │ │RocksDB │ │ │ │RocksDB │ │                               │
│  │ └────────┘ │ │ └────────┘ │ │ └────────┘ │                               │
│  └────────────┘ └────────────┘ └────────────┘                               │
│         ^              ^              ^                                      │
│         │              │              │                                      │
│         └──────────────┴──────────────┘                                      │
│                        │                                                     │
│  ┌─────────────────────┴────────────────────┐                               │
│  │           CONTROLLER (Control Plane)      │                               │
│  │  • Store lifecycle management             │                               │
│  │  • Schema evolution                       │                               │
│  │  • Partition assignment (Helix)           │                               │
│  │  • Push job orchestration                 │                               │
│  └───────────────────────────────────────────┘                               │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## Architecture

### Router Internal Components

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                            RouterServer                                      │
│                     (Main Orchestrator Class)                                │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  ┌─────────────────────────────────────────────────────────────────────┐    │
│  │                        Netty Pipeline                                │    │
│  │  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐  │    │
│  │  │  SSL     │→│  HTTP    │→│ Throttle │→│   ACL    │→│ Scatter  │  │    │
│  │  │ Handler  │ │ Decoder  │ │ Handler  │ │ Handler  │ │ Gather   │  │    │
│  │  └──────────┘ └──────────┘ └──────────┘ └──────────┘ └──────────┘  │    │
│  └─────────────────────────────────────────────────────────────────────┘    │
│                                                                              │
│  ┌───────────────────────┐    ┌───────────────────────┐                     │
│  │   VenicePathParser    │    │  VeniceVersionFinder  │                     │
│  │  • Parse request URI  │    │  • Resolve store ver  │                     │
│  │  • Extract store/key  │    │  • Version validation │                     │
│  │  • Create VenicePath  │    │  • Migration checks   │                     │
│  └───────────────────────┘    └───────────────────────┘                     │
│                                                                              │
│  ┌───────────────────────┐    ┌───────────────────────┐                     │
│  │ VenicePartitionFinder │    │   VeniceHostFinder    │                     │
│  │  • Hash key → part ID │    │  • Get ready replicas │                     │
│  │  • Cache partitioners │    │  • Filter unhealthy   │                     │
│  └───────────────────────┘    └───────────────────────┘                     │
│                                                                              │
│  ┌───────────────────────┐    ┌───────────────────────┐                     │
│  │   VeniceDispatcher    │    │VeniceResponseAggregator│                    │
│  │  • Send to servers    │    │  • Combine responses  │                     │
│  │  • Track in-flight    │    │  • Decompress data    │                     │
│  │  • Handle retries     │    │  • Build final resp   │                     │
│  └───────────────────────┘    └───────────────────────┘                     │
│                                                                              │
│  ┌───────────────────────┐    ┌───────────────────────┐                     │
│  │   VeniceHostHealth    │    │  StorageNodeClient    │                     │
│  │  • Track node health  │    │  • HTTP/HTTP2 client  │                     │
│  │  • Pending queue mon  │    │  • Connection pool    │                     │
│  │  • Heartbeat results  │    │  • Request execution  │                     │
│  └───────────────────────┘    └───────────────────────┘                     │
│                                                                              │
│  ┌───────────────────────┐    ┌───────────────────────┐                     │
│  │  ReadRequestThrottler │    │   MetaDataHandler     │                     │
│  │  • Quota enforcement  │    │  • Schema queries     │                     │
│  │  • Per-store limits   │    │  • Cluster discovery  │                     │
│  │  • Backpressure       │    │  • Version info       │                     │
│  └───────────────────────┘    └───────────────────────┘                     │
│                                                                              │
│  ┌─────────────────────────────────────────────────────────────────────┐    │
│  │                     Helix Integration                                │    │
│  │  ┌─────────────────┐  ┌─────────────────┐  ┌─────────────────────┐  │    │
│  │  │ LiveInstance    │  │ CustomizedView  │  │ InstanceConfig      │  │    │
│  │  │ Monitor         │  │ Repository      │  │ Repository          │  │    │
│  │  │ (alive/dead)    │  │ (partition state)│  │ (grouping info)     │  │    │
│  │  └─────────────────┘  └─────────────────┘  └─────────────────────┘  │    │
│  └─────────────────────────────────────────────────────────────────────┘    │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Component Responsibilities

| Component | Responsibility |
|-----------|----------------|
| **RouterServer** | Main entry point; initializes all services, manages lifecycle |
| **VenicePathParser** | Parses HTTP requests into VenicePath objects (store, keys, version) |
| **VeniceVersionFinder** | Resolves current version for a store; handles version swaps |
| **VenicePartitionFinder** | Maps keys to partition numbers using store's partitioner |
| **VeniceHostFinder** | Selects healthy, ready-to-serve replicas for each partition |
| **VeniceDispatcher** | Sends requests to storage nodes; tracks in-flight requests |
| **VeniceResponseAggregator** | Combines responses from multiple servers for batch requests |
| **VeniceHostHealth** | Monitors storage node health (pending queues, heartbeats) |
| **StorageNodeClient** | HTTP/HTTP2 client for server communication |
| **MetaDataHandler** | Handles metadata queries (schemas, clusters, versions) |
| **ReadRequestThrottler** | Enforces read quota limits per store |

---

## Request Flow

### Single-Get Request

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                        Single-Get Request Flow                               │
└─────────────────────────────────────────────────────────────────────────────┘

  Client                    Router                          Storage Node
    │                         │                                   │
    │  GET /storage/myStore/key                                   │
    │ ─────────────────────> │                                    │
    │                         │                                   │
    │                   ┌─────┴─────┐                              │
    │                   │ PathParser │                             │
    │                   │ • Extract store = "myStore"              │
    │                   │ • Extract key bytes                      │
    │                   │ • Create VeniceSingleGetPath             │
    │                   └─────┬─────┘                              │
    │                         │                                   │
    │                   ┌─────┴─────┐                              │
    │                   │ VersionFinder │                          │
    │                   │ • Lookup current version (v3)           │
    │                   │ • Check partition resources ready       │
    │                   │ • Validate decompressor available       │
    │                   └─────┬─────┘                              │
    │                         │                                   │
    │                   ┌─────┴─────┐                              │
    │                   │ PartitionFinder │                        │
    │                   │ • hash(key) % partitionCount = 7        │
    │                   └─────┬─────┘                              │
    │                         │                                   │
    │                   ┌─────┴─────┐                              │
    │                   │ HostFinder │                             │
    │                   │ • Get replicas for partition 7          │
    │                   │ • Filter unhealthy nodes                │
    │                   │ • Select: [Server-A, Server-B]          │
    │                   └─────┬─────┘                              │
    │                         │                                   │
    │                   ┌─────┴─────┐                              │
    │                   │ Dispatcher │                             │
    │                   │ • Check pending request quota           │
    │                   │ • Select Server-A (first healthy)       │
    │                   │ • Send HTTP request                     │
    │                   └─────┬─────┘                              │
    │                         │                                   │
    │                         │  GET /storage/myStore_v3/key      │
    │                         │ ─────────────────────────────────>│
    │                         │                                   │
    │                         │                    ┌───────────────┤
    │                         │                    │ RocksDB lookup│
    │                         │                    └───────────────┤
    │                         │                                   │
    │                         │ <─────────────────────────────────│
    │                         │   200 OK + value (compressed)     │
    │                         │                                   │
    │                   ┌─────┴─────┐                              │
    │                   │ Aggregator │                             │
    │                   │ • Decompress if needed                  │
    │                   │ • Record metrics                        │
    │                   │ • Build final response                  │
    │                   └─────┬─────┘                              │
    │                         │                                   │
    │ <───────────────────────│                                   │
    │   200 OK + value        │                                   │
    │                         │                                   │
```

### Multi-Get (Batch) Request

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                        Multi-Get Request Flow                                │
│                        (Scatter-Gather Pattern)                              │
└─────────────────────────────────────────────────────────────────────────────┘

  Client                    Router                         Storage Nodes
    │                         │                                   │
    │  POST /storage/myStore  │                                   │
    │  Body: [key1,key2,key3,key4,key5]                          │
    │ ─────────────────────> │                                    │
    │                         │                                   │
    │                   ┌─────┴─────┐                              │
    │                   │ PathParser │                             │
    │                   │ • Deserialize keys from body            │
    │                   │ • Create VeniceMultiGetPath             │
    │                   └─────┬─────┘                              │
    │                         │                                   │
    │                   ┌─────┴─────┐                              │
    │                   │ SCATTER   │                              │
    │                   │           │                              │
    │                   │ Keys → Partitions:                      │
    │                   │   key1 → P0, key2 → P0                  │
    │                   │   key3 → P1                             │
    │                   │   key4 → P2, key5 → P2                  │
    │                   │                                         │
    │                   │ Partitions → Hosts:                     │
    │                   │   P0 → Server-A                         │
    │                   │   P1 → Server-B                         │
    │                   │   P2 → Server-A                         │
    │                   │                                         │
    │                   │ Optimized Groups:                       │
    │                   │   Server-A: [key1,key2,key4,key5]       │
    │                   │   Server-B: [key3]                      │
    │                   └─────┬─────┘                              │
    │                         │                                   │
    │                         │ ┌─────────────────────────────────┐
    │                         │ │         PARALLEL DISPATCH       │
    │                         │ └─────────────────────────────────┘
    │                         │                                   │
    │                         │──> Server-A: [key1,key2,key4,key5]│
    │                         │                            ┌──────┤
    │                         │                            │Lookup│
    │                         │                            └──────┤
    │                         │<── values for [key1,key2,key4,key5]
    │                         │                                   │
    │                         │──> Server-B: [key3]               │
    │                         │                            ┌──────┤
    │                         │                            │Lookup│
    │                         │                            └──────┤
    │                         │<── value for [key3]               │
    │                         │                                   │
    │                   ┌─────┴─────┐                              │
    │                   │ GATHER    │                              │
    │                   │           │                              │
    │                   │ • Wait for all responses                │
    │                   │ • Handle partial failures               │
    │                   │ • Decompress each response              │
    │                   │ • Aggregate into single result          │
    │                   └─────┬─────┘                              │
    │                         │                                   │
    │ <───────────────────────│                                   │
    │  200 OK + all values    │                                   │
    │                         │                                   │
```

---

## Scatter-Gather Implementation

The router uses the **Alpini framework** for scatter-gather operations. Multiple routing strategies are available:

### Routing Strategies

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                      Multi-Key Routing Strategies                            │
└─────────────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────────┐
│ 1. GROUP_BY_PRIMARY_HOST_ROUTING                                            │
│    Route all keys for a partition to the primary (leader) replica           │
│                                                                              │
│    Keys: [k1,k2,k3,k4,k5]                                                   │
│    Partitions: P0=[k1,k2], P1=[k3], P2=[k4,k5]                              │
│                                                                              │
│         P0 Leader: Server-A  ───>  Server-A receives [k1,k2]                │
│         P1 Leader: Server-B  ───>  Server-B receives [k3]                   │
│         P2 Leader: Server-C  ───>  Server-C receives [k4,k5]                │
│                                                                              │
│    Requests: 3 (one per partition)                                          │
└─────────────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────────┐
│ 2. GREEDY_ROUTING                                                           │
│    Minimize total number of requests by grouping keys to servers            │
│                                                                              │
│    Replicas: P0=[A,B], P1=[B,C], P2=[A,C]                                   │
│                                                                              │
│    Greedy selection:                                                        │
│      Server-A hosts P0, P2  ───>  Server-A receives [k1,k2,k4,k5]          │
│      Server-B hosts P1      ───>  Server-B receives [k3]                   │
│                                                                              │
│    Requests: 2 (minimized from 3)                                           │
└─────────────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────────┐
│ 3. LEAST_LOADED_ROUTING                                                     │
│    Route to replicas with lowest current load                               │
│                                                                              │
│    Server Load: A=100 req, B=50 req, C=75 req                               │
│    Replicas: P0=[A,B], P1=[B,C], P2=[A,C]                                   │
│                                                                              │
│    Selection (prefer lower load):                                           │
│      P0: B (50) < A (100)   ───>  Server-B                                 │
│      P1: B (50) < C (75)    ───>  Server-B                                 │
│      P2: C (75) < A (100)   ───>  Server-C                                 │
│                                                                              │
│    Requests sent to least loaded servers                                    │
└─────────────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────────┐
│ 4. HELIX_ASSISTED_ROUTING                                                   │
│    Use Helix instance groups/zones to limit fanout                          │
│                                                                              │
│    Helix Groups: Group-1=[A,B], Group-2=[C,D]                               │
│    Strategy: LEAST_LOADED or ROUND_ROBIN                                    │
│                                                                              │
│    Request 1: Select Group-1 ───> Route within [A,B]                       │
│    Request 2: Select Group-2 ───> Route within [C,D]                       │
│                                                                              │
│    Limits cross-zone/cross-rack traffic                                     │
└─────────────────────────────────────────────────────────────────────────────┘
```

### VeniceDelegateMode

The `VeniceDelegateMode` class implements `ScatterGatherMode` and orchestrates the scatter phase:

```java
// services/venice-router/src/main/java/com/linkedin/venice/router/api/VeniceDelegateMode.java

public class VeniceDelegateMode implements ScatterGatherMode<...> {

    // Strategy selection based on config
    private final ScatterGatherMode<...> delegateMode;  // GREEDY, LEAST_LOADED, etc.

    @Override
    public Scatter<...> scatter(
        ScatterGatherRequest request,
        String requestMethod,
        String resourceName,
        PartitionFinder<RouterKey> partitionFinder,
        HostFinder<Instance, VeniceRole> hostFinder,
        ...) {

        // 1. Group keys by partition
        // 2. Find healthy hosts for each partition
        // 3. Apply routing strategy to minimize requests
        // 4. Create sub-requests for each host
        return delegateMode.scatter(...);
    }
}
```

---

## Health Management

### Health Check Flow

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                        Health Monitoring Architecture                        │
└─────────────────────────────────────────────────────────────────────────────┘

  ┌─────────────────────────────────────────────────────────────────────────┐
  │                         VeniceHostHealth                                 │
  │                    (HostHealthMonitor Implementation)                    │
  └───────────────────────────────┬─────────────────────────────────────────┘
                                  │
          ┌───────────────────────┼───────────────────────┐
          │                       │                       │
          v                       v                       v
  ┌───────────────┐     ┌───────────────┐     ┌───────────────────────┐
  │ LiveInstance  │     │ Heartbeat     │     │ Pending Request       │
  │ Monitor       │     │ Results       │     │ Queue Health          │
  │               │     │               │     │                       │
  │ • Helix ZK    │     │ • Periodic    │     │ • Per-route tracking  │
  │   watches     │     │   health      │     │ • Threshold-based     │
  │ • Instance    │     │   checks      │     │ • OOR duration        │
  │   alive/dead  │     │ • Marks       │     │                       │
  │               │     │   unhealthy   │     │                       │
  └───────┬───────┘     └───────┬───────┘     └───────────┬───────────┘
          │                     │                         │
          v                     v                         v
  ┌─────────────────────────────────────────────────────────────────────────┐
  │                       isHostHealthy(Instance)                            │
  │  ┌─────────────────────────────────────────────────────────────────┐    │
  │  │  1. Is instance in LiveInstanceMonitor? (ZK alive)              │    │
  │  │  2. Is instance NOT in unhealthyHostSet? (heartbeat passed)     │    │
  │  │  3. Has post-join delay elapsed? (instance ready)               │    │
  │  │  4. Is pending queue healthy? (not overloaded)                  │    │
  │  │                                                                  │    │
  │  │  ALL conditions must be true → Host is healthy                  │    │
  │  └─────────────────────────────────────────────────────────────────┘    │
  └─────────────────────────────────────────────────────────────────────────┘
```

### Pending Request Queue Health

The router tracks pending (in-flight) requests per storage node to prevent overwhelming unhealthy servers:

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                     Pending Request Queue State Machine                      │
└─────────────────────────────────────────────────────────────────────────────┘

                    ┌──────────────────────────────────────┐
                    │              HEALTHY                  │
                    │    pendingRequests < threshold       │
                    └──────────────────┬───────────────────┘
                                       │
                                       │ pendingRequests >= unhealthyThreshold
                                       │ (default: 10 per route)
                                       v
                    ┌──────────────────────────────────────┐
                    │             UNHEALTHY                 │
                    │    • Stop routing to this host       │
                    │    • Track unhealthy start time      │
                    │    • Wait for OOR duration           │
                    └──────────────────┬───────────────────┘
                                       │
                                       │ pendingRequests <= resumeThreshold
                                       │ AND elapsed > OOR duration
                                       v
                    ┌──────────────────────────────────────┐
                    │              HEALTHY                  │
                    │    Resume routing to host            │
                    └──────────────────────────────────────┘

Configuration:
  • router.unhealthy.pending.connection.threshold.per.route (default: 10)
  • router.pending.connection.resume.threshold.per.route (default: 5)
  • router.full.pending.queue.server.oor.ms (default: 30000)
```

---

## Retry Logic

### Long-Tail Retry

Venice Router implements **long-tail retry** to improve P99 latency by automatically retrying slow requests:

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                        Long-Tail Retry Mechanism                             │
└─────────────────────────────────────────────────────────────────────────────┘

  Time ──────────────────────────────────────────────────────────────────────>

  Request to Server-A:
    │
    ├───────────────────────────────────────────────────────────────> (slow)
    │                                                                Response
    │
    │         Long-tail threshold (15ms)
    │         │
    │         v
    ├─────────┤
    │         │
    │         └──── Retry to Server-B:
    │                    │
    │                    ├───────────> Response (faster!)
    │                    │
    │                    v
    │              ┌───────────┐
    │              │ Return    │
    │              │ first     │
    │              │ response  │
    │              └───────────┘
    │
    └──────────────────────────────────────────────────────> (cancelled)


  Timeline:
    T=0ms    : Send request to Server-A
    T=15ms   : Threshold reached, send retry to Server-B
    T=25ms   : Server-B responds → return to client
    T=100ms  : Server-A finally responds → discarded
```

### Smart Long-Tail Retry

The **smart retry** feature avoids retrying to nodes that are known to be slow:

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         Smart Long-Tail Retry                                │
└─────────────────────────────────────────────────────────────────────────────┘

  During Original Request:
  ┌─────────────────────────────────────────────────────────────────────────┐
  │ VenicePath tracks "slowStorageNodeSet"                                  │
  │                                                                          │
  │ At long-tail threshold:                                                 │
  │   • Servers that haven't responded → added to slowStorageNodeSet        │
  │   • Example: {Server-A, Server-C}                                       │
  └─────────────────────────────────────────────────────────────────────────┘
                                       │
                                       v
  During Retry:
  ┌─────────────────────────────────────────────────────────────────────────┐
  │ VeniceHostFinder.findHosts() considers slowStorageNodeSet               │
  │                                                                          │
  │ Available replicas: [Server-A, Server-B, Server-C]                      │
  │ Slow nodes:         {Server-A, Server-C}                                │
  │                                                                          │
  │ Retry target:       Server-B (not in slow set)                          │
  └─────────────────────────────────────────────────────────────────────────┘

  Exception: On 5xx errors, retry anywhere (not a slowness issue)
```

### Retry Configuration

```java
// Retry thresholds by request type and batch size
router.long.tail.retry.for.single.get.threshold.ms = 15        // Single-get: 15ms
router.long.tail.retry.for.batch.get.threshold.ms = "1-10:15,11-50:25,51-:40"
                                                                // Batch 1-10 keys: 15ms
                                                                // Batch 11-50 keys: 25ms
                                                                // Batch 51+ keys: 40ms

router.smart.long.tail.retry.enabled = true                     // Enable smart retry
router.smart.long.tail.retry.abort.threshold.ms = 100           // Abort after 100ms
router.long.tail.retry.max.route.for.multi.keys.req = 2         // Max 2 retry routes
```

---

## Metadata Services

The router provides metadata endpoints via `MetaDataHandler`:

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         Metadata Endpoints                                   │
└─────────────────────────────────────────────────────────────────────────────┘

┌────────────────────────────────┬────────────────────────────────────────────┐
│ Endpoint                       │ Description                                │
├────────────────────────────────┼────────────────────────────────────────────┤
│ /leader_controller             │ Returns current leader controller URL      │
│ /master_controller             │ (Alias for leader_controller)              │
├────────────────────────────────┼────────────────────────────────────────────┤
│ /key_schema/{storeName}        │ Returns key schema for store              │
├────────────────────────────────┼────────────────────────────────────────────┤
│ /value_schema/{storeName}      │ Returns all value schemas for store       │
├────────────────────────────────┼────────────────────────────────────────────┤
│ /value_schema/{store}/{id}     │ Returns specific value schema by ID       │
├────────────────────────────────┼────────────────────────────────────────────┤
│ /cluster_discovery/{store}     │ Returns cluster info (name, routers, etc) │
├────────────────────────────────┼────────────────────────────────────────────┤
│ /request_topic/{storeName}     │ Returns real-time topic for writes        │
├────────────────────────────────┼────────────────────────────────────────────┤
│ /resource_state/{store}/{v}/{p}│ Returns partition state (replicas, etc)   │
└────────────────────────────────┴────────────────────────────────────────────┘

Example Response for /cluster_discovery/myStore:
{
  "clusterName": "venice-cluster-1",
  "storeName": "myStore",
  "kafkaBootstrapServers": "kafka1:9092,kafka2:9092",
  "d2Service": "venice-router"
}
```

---

## Connection Management

### StorageNodeClient Architecture

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                      StorageNodeClient Architecture                          │
└─────────────────────────────────────────────────────────────────────────────┘

                        ┌────────────────────────────┐
                        │    StorageNodeClient       │
                        │      (Interface)           │
                        └─────────────┬──────────────┘
                                      │
                  ┌───────────────────┴───────────────────┐
                  │                                       │
                  v                                       v
  ┌───────────────────────────────┐     ┌───────────────────────────────┐
  │ ApacheHttpAsyncStorageNode    │     │ HttpClient5StorageNodeClient  │
  │ Client                        │     │                               │
  │                               │     │                               │
  │ • Apache HttpAsyncClient      │     │ • Apache HttpClient 5         │
  │ • HTTP/1.1                    │     │ • HTTP/1.1 and HTTP/2         │
  │ • Connection pooling          │     │ • Connection pooling          │
  │ • Async callbacks             │     │ • Async callbacks             │
  └───────────────────────────────┘     └───────────────────────────────┘


  Connection Pool Configuration:
  ┌─────────────────────────────────────────────────────────────────────────┐
  │                                                                          │
  │  router.http.client.pool.size = 12                  (total pool size)   │
  │  router.max.outgoing.connection.per.route = 2       (per-host limit)    │
  │  router.max.outgoing.connection = 1000              (global limit)      │
  │                                                                          │
  │  ┌─────────────────────────────────────────────────────────────────┐    │
  │  │                    Connection Pool                               │    │
  │  │  ┌─────────┐  ┌─────────┐  ┌─────────┐       ┌─────────┐       │    │
  │  │  │Server-A │  │Server-A │  │Server-B │  ...  │Server-N │       │    │
  │  │  │ Conn 1  │  │ Conn 2  │  │ Conn 1  │       │ Conn 2  │       │    │
  │  │  └─────────┘  └─────────┘  └─────────┘       └─────────┘       │    │
  │  └─────────────────────────────────────────────────────────────────┘    │
  │                                                                          │
  │  Per-route limit prevents single slow server from exhausting pool       │
  │                                                                          │
  └─────────────────────────────────────────────────────────────────────────┘
```

### HTTP/2 Support

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           HTTP/2 Configuration                               │
└─────────────────────────────────────────────────────────────────────────────┘

  Inbound (Client → Router):
    router.http2.inbound.enabled = false              (default: disabled)
    router.http2.max.concurrent.streams = 100         (streams per connection)
    router.http2.initial.window.size = 65535          (flow control window)
    router.http2.max.frame.size = 16384               (max frame size)
    router.http2.header.table.size = 4096             (HPACK table size)
    router.http2.max.header.list.size = 8192          (max header size)

  Outbound (Router → Server):
    router.storage.node.client.type = HTTP_CLIENT_5_CLIENT   (HTTP/2 capable)
```

---

## Configuration Reference

### Essential Configuration

```properties
# === Cluster & Network ===
cluster.name=venice-cluster-1
zookeeper.address=zk1:2181,zk2:2181,zk3:2181
listener.port=7777
listener.ssl.port=7778
listener.hostname=0.0.0.0

# === Threading ===
router.io.worker.count=8                              # Netty worker threads

# === Connection Management ===
router.http.client.pool.size=12                       # HTTP client pool size
router.max.outgoing.connection.per.route=2            # Per-host connection limit
router.max.outgoing.connection=1000                   # Global connection limit
router.connection.timeout=10000                       # Connection timeout (ms)
router.socket.timeout=30000                           # Socket read timeout (ms)

# === Request Limits ===
router.max.pending.request=2500                       # Max in-flight requests
router.connection.limit=10000                         # Max total connections

# === Long-Tail Retry ===
router.long.tail.retry.for.single.get.threshold.ms=15
router.long.tail.retry.for.batch.get.threshold.ms=1-10:15,11-50:25,51-:40
router.long.tail.retry.max.route.for.multi.keys.req=2
router.smart.long.tail.retry.enabled=true
router.smart.long.tail.retry.abort.threshold.ms=100

# === Routing Strategy ===
router.multi.key.routing.strategy=LEAST_LOADED_ROUTING
# Options: GROUP_BY_PRIMARY_HOST_ROUTING, GREEDY_ROUTING,
#          LEAST_LOADED_ROUTING, HELIX_ASSISTED_ROUTING

# === Health Monitoring ===
router.stateful.healthcheck.enabled=true
router.unhealthy.pending.connection.threshold.per.route=10
router.pending.connection.resume.threshold.per.route=5
router.full.pending.queue.server.oor.ms=30000

# === Quota & Throttling ===
router.read.quota.throttling.enabled=true
router.max.read.capacity.cu=100000                    # Total router read capacity
router.quota.check.window=10                          # Quota window (seconds)

# === SSL/TLS ===
router.enable.ssl=true
router.ssl.to.storage.nodes=true
router.enforce.secure.only=false                      # Disable HTTP if true
router.max.concurrent.ssl.handshakes=512

# === Timeouts ===
router.singleget.tardy.latency.ms=10000               # Single-get timeout
router.multiget.tardy.latency.ms=10000                # Multi-get timeout
router.compute.tardy.latency.ms=10000                 # Compute timeout
```

### Full Configuration Reference

See `VeniceRouterConfig.java` for all available options:
`services/venice-router/src/main/java/com/linkedin/venice/router/VeniceRouterConfig.java`

---

## Key Classes Reference

### Core Classes

| Class | Path | Responsibility |
|-------|------|----------------|
| `RouterServer` | `services/venice-router/.../RouterServer.java` | Main entry point, lifecycle management |
| `VeniceDispatcher` | `services/venice-router/.../api/VeniceDispatcher.java` | Request dispatch to storage nodes |
| `VenicePathParser` | `services/venice-router/.../api/VenicePathParser.java` | HTTP request parsing |
| `VeniceVersionFinder` | `services/venice-router/.../api/VeniceVersionFinder.java` | Store version resolution |
| `VenicePartitionFinder` | `services/venice-router/.../api/VenicePartitionFinder.java` | Key to partition mapping |
| `VeniceHostFinder` | `services/venice-router/.../api/VeniceHostFinder.java` | Healthy replica selection |
| `VeniceHostHealth` | `services/venice-router/.../api/VeniceHostHealth.java` | Host health monitoring |
| `VeniceResponseAggregator` | `services/venice-router/.../api/VeniceResponseAggregator.java` | Response aggregation |
| `VeniceDelegateMode` | `services/venice-router/.../api/VeniceDelegateMode.java` | Scatter-gather strategies |

### Path Classes

| Class | Path | Description |
|-------|------|-------------|
| `VenicePath` | `.../api/path/VenicePath.java` | Base class for all request paths |
| `VeniceSingleGetPath` | `.../api/path/VeniceSingleGetPath.java` | Single key GET request |
| `VeniceMultiGetPath` | `.../api/path/VeniceMultiGetPath.java` | Batch GET request |
| `VeniceComputePath` | `.../api/path/VeniceComputePath.java` | Read-compute request |

### HTTP Client Classes

| Class | Path | Description |
|-------|------|-------------|
| `StorageNodeClient` | `.../httpclient/StorageNodeClient.java` | Interface for server communication |
| `ApacheHttpAsyncStorageNodeClient` | `.../httpclient/ApacheHttpAsync...` | Apache HttpAsyncClient impl |
| `HttpClient5StorageNodeClient` | `.../httpclient/HttpClient5...` | Apache HttpClient 5 impl |

### Throttling Classes

| Class | Path | Description |
|-------|------|-------------|
| `ReadRequestThrottler` | `.../throttle/ReadRequestThrottler.java` | Per-store quota enforcement |
| `PendingRequestThrottler` | `.../throttle/PendingRequestThrottler.java` | In-flight request limiting |

---

## Monitoring & Metrics

### Key Metrics

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                            Router Metrics                                    │
└─────────────────────────────────────────────────────────────────────────────┘

Request Metrics (per request type):
  • request_count              - Total requests
  • success_count              - Successful requests
  • error_count                - Failed requests
  • latency_p50/p90/p99        - Latency percentiles
  • key_count                  - Keys per multi-get request

Health Metrics:
  • unhealthy_host_count       - Number of unhealthy storage nodes
  • unhealthy_host_by_pending  - Hosts marked unhealthy due to pending queue
  • unhealthy_host_by_heartbeat- Hosts marked unhealthy due to heartbeat failure

Throttling Metrics:
  • throttled_request_count    - Requests rejected due to quota
  • pending_request_count      - Current in-flight requests
  • rejected_request_count     - Requests rejected due to pending limit

Retry Metrics:
  • long_tail_retry_count      - Long-tail retries triggered
  • error_retry_count          - Error-based retries triggered
  • retry_success_count        - Retries that succeeded

Connection Metrics:
  • connection_pool_size       - Current connection pool size
  • active_connections         - Currently active connections
  • connection_timeout_count   - Connection timeouts
```

### Health Check Endpoint

```
GET /health

Response (healthy):
{
  "status": "healthy",
  "version": "0.4.xxx",
  "uptime": "12h 34m 56s"
}

Response (unhealthy):
{
  "status": "unhealthy",
  "reason": "High pending request queue",
  "details": {...}
}
```

---

## Directory Structure

```
services/venice-router/
├── src/main/java/com/linkedin/venice/router/
│   ├── RouterServer.java                    # Main orchestrator
│   ├── VeniceRouterConfig.java              # Configuration
│   ├── MetaDataHandler.java                 # Metadata endpoints
│   ├── api/
│   │   ├── VeniceDispatcher.java            # Request dispatch
│   │   ├── VenicePathParser.java            # Request parsing
│   │   ├── VeniceVersionFinder.java         # Version resolution
│   │   ├── VenicePartitionFinder.java       # Partition mapping
│   │   ├── VeniceHostFinder.java            # Replica selection
│   │   ├── VeniceHostHealth.java            # Health monitoring
│   │   ├── VeniceResponseAggregator.java    # Response assembly
│   │   ├── VeniceDelegateMode.java          # Scatter-gather
│   │   ├── path/
│   │   │   ├── VenicePath.java              # Base path class
│   │   │   ├── VeniceSingleGetPath.java     # Single-get path
│   │   │   ├── VeniceMultiGetPath.java      # Multi-get path
│   │   │   └── VeniceComputePath.java       # Compute path
│   │   └── routing/helix/
│   │       ├── HelixGroupSelector.java      # Group selection
│   │       └── HelixGroup*Strategy.java     # Strategy impls
│   ├── httpclient/
│   │   ├── StorageNodeClient.java           # Client interface
│   │   └── *StorageNodeClient.java          # Client impls
│   ├── throttle/
│   │   ├── ReadRequestThrottler.java        # Quota enforcement
│   │   └── PendingRequestThrottler.java     # In-flight limiting
│   ├── stats/
│   │   ├── AggRouterHttpRequestStats.java   # Request stats
│   │   ├── RouteHttpRequestStats.java       # Per-route stats
│   │   └── HostHealthStats.java             # Health stats
│   └── streaming/
│       ├── VeniceChunkedWriteHandler.java   # Chunked responses
│       └── VeniceChunkedResponse.java       # Response state
└── src/test/java/...                        # Tests
```

---

## See Also

- [Venice Architecture Overview](../user_guide/architecture.md)
- [Key Classes Map](../../.claude/rules/key-classes.md)
- [Code Patterns Guide](../../.claude/rules/code-patterns.md)
- [Router Source Code](../../services/venice-router/src/main/java/com/linkedin/venice/router/)
