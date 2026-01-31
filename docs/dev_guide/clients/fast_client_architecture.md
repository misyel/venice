# Venice Fast Client Architecture

The Fast Client is Venice's optimized remote read client that bypasses the Router for data requests, communicating directly with Storage Servers while using the Router only for metadata.

## Table of Contents

- [Overview](#overview)
- [Architecture Diagram](#architecture-diagram)
- [Key Features](#key-features)
- [Long-Tail Retry Mechanism](#long-tail-retry-mechanism)
- [Dual Read Mode](#dual-read-mode)
- [Load Controller](#load-controller)
- [Key Classes](#key-classes)
- [Configuration Reference](#configuration-reference)
- [Best Practices](#best-practices)

---

## Overview

The Fast Client provides:
- **Direct server access**: Bypasses Router for data requests
- **Intelligent routing**: Client-side routing decisions based on metadata
- **Long-tail retries**: Speculative retries for slow requests
- **Load balancing**: Client-side load controller to avoid overloaded servers
- **Dual read mode**: Compare with Thin Client during migration

### When to Use Fast Client

| Use Case | Recommendation |
|----------|---------------|
| Low latency remote reads | ✅ Fast Client |
| Direct server communication | ✅ Fast Client |
| Advanced retry strategies | ✅ Fast Client |
| Minimal dependencies required | ❌ Use Thin Client |
| Ultra-low latency (<1ms P99) | ❌ Use Da-Vinci Client |
| Local data embedding | ❌ Use Da-Vinci Client |

---

## Architecture Diagram

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                           Fast Client Architecture                               │
├─────────────────────────────────────────────────────────────────────────────────┤
│                                                                                  │
│  ┌─────────────────┐                                                            │
│  │   Application   │                                                            │
│  └────────┬────────┘                                                            │
│           │                                                                      │
│           │ get(key) / batchGet(keys)                                           │
│           v                                                                      │
│  ┌─────────────────────────────────────────────────────────────────────────────┐│
│  │                        Fast Client                                          ││
│  │  ┌─────────────────────────────────────────────────────────────────────┐   ││
│  │  │                    Request Builder + Stats                           │   ││
│  │  └─────────────────────────────────────────────────────────────────────┘   ││
│  │                                    │                                        ││
│  │                                    v                                        ││
│  │  ┌─────────────────────────────────────────────────────────────────────┐   ││
│  │  │                     Client-Side Router                               │   ││
│  │  │  ┌───────────────┐  ┌───────────────┐  ┌─────────────────────────┐  │   ││
│  │  │  │  Partition    │  │   Replica     │  │    Load Controller      │  │   ││
│  │  │  │  Assignment   │  │   Selection   │  │  (throttle overloaded)  │  │   ││
│  │  │  └───────────────┘  └───────────────┘  └─────────────────────────┘  │   ││
│  │  └─────────────────────────────────────────────────────────────────────┘   ││
│  │                                    │                                        ││
│  │              ┌─────────────────────┼─────────────────────┐                 ││
│  │              │                     │                     │                  ││
│  │              v                     v                     v                  ││
│  │  ┌─────────────────┐   ┌─────────────────┐   ┌─────────────────┐          ││
│  │  │  Long-Tail      │   │  Original       │   │  Error Retry    │          ││
│  │  │  Retry Logic    │   │  Request        │   │  Logic          │          ││
│  │  └─────────────────┘   └─────────────────┘   └─────────────────┘          ││
│  │                                    │                                        ││
│  └────────────────────────────────────┼────────────────────────────────────────┘│
│                                       │                                         │
│                          Direct HTTP to Servers                                 │
│                    ┌──────────────────┼──────────────────┐                      │
│                    │                  │                  │                       │
│                    v                  v                  v                       │
│            ┌───────────┐      ┌───────────┐      ┌───────────┐                  │
│            │  Server 1 │      │  Server 2 │      │  Server 3 │                  │
│            └───────────┘      └───────────┘      └───────────┘                  │
│                                                                                  │
│  ┌────────────────────────────────────────────────────────────────────────────┐ │
│  │                     Metadata Channel (via Router)                          │ │
│  │  • Partition assignments                                                   │ │
│  │  • Server health status                                                    │ │
│  │  • Schema information                                                      │ │
│  └────────────────────────────────────────────────────────────────────────────┘ │
│                                                                                  │
└─────────────────────────────────────────────────────────────────────────────────┘
```

---

## Key Features

### Direct Server Communication

Unlike Thin Client which routes through Router, Fast Client:
1. Fetches metadata (partition assignments, schemas) from Router
2. Caches metadata locally
3. Sends data requests directly to Storage Servers
4. Reduces latency by eliminating Router hop for reads

### Benefits of Direct Access

| Aspect | Thin Client | Fast Client |
|--------|-------------|-------------|
| Request hops | App → Router → Server | App → Server |
| Latency | Higher | Lower |
| Router load | High | Low (metadata only) |
| Client complexity | Simple | More complex |

---

## Long-Tail Retry Mechanism

Fast Client implements speculative retries to handle slow responses:

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                       Long-Tail Retry Timing                                     │
├─────────────────────────────────────────────────────────────────────────────────┤
│                                                                                  │
│  Time ─────────────────────────────────────────────────────────────────────────►│
│                                                                                  │
│  Original Request:                                                              │
│  ─────────────────────────────────────────────────────────────────────────────  │
│  │ Send to Server A                                                       │    │
│  └───────────────────────────────────────────────────────────────────────►│    │
│                                                                      Response   │
│  │                                                                        │    │
│  │    Retry Threshold                                                     │    │
│  │         │                                                              │    │
│  │         ▼                                                              │    │
│  └─────────┼────────────────────────────────────────────────────────────►│    │
│            │                                                              │    │
│  Speculative Retry:                                                       │    │
│            │  Send to Server B (different replica)                        │    │
│            └───────────────────────────►│                                 │    │
│                                    Response (wins!)                       │    │
│                                                                                  │
│  Result: Client uses faster response, cancels slower one                        │
│                                                                                  │
│  Metrics:                                                                       │
│    • long_tail_retry_request - Speculative retries triggered                    │
│    • retry.request.win_count - Retries that returned faster                     │
│                                                                                  │
└─────────────────────────────────────────────────────────────────────────────────┘
```

### Retry Types

| Retry Type | Trigger | Metric |
|------------|---------|--------|
| **Long-Tail Retry** | Request exceeds threshold before response | `long_tail_retry_request` |
| **Error Retry** | Request returns error (5XX) | `error_retry_request` |

### Retry Configuration

| Property | Default | Description |
|----------|---------|-------------|
| `longTailRetryThresholdMs` | Varies | Threshold to trigger speculative retry |
| `longTailRetryBudgetPercent` | 3% | Max percentage of requests to retry |

---

## Dual Read Mode

During migration from Thin Client to Fast Client, dual read mode allows comparison:

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                           Dual Read Mode                                         │
├─────────────────────────────────────────────────────────────────────────────────┤
│                                                                                  │
│  ┌─────────────────┐                                                            │
│  │   Application   │                                                            │
│  └────────┬────────┘                                                            │
│           │ get(key)                                                            │
│           v                                                                      │
│  ┌─────────────────────────────────────────────────────────────────────────────┐│
│  │                    Dual Read Coordinator                                    ││
│  │                                                                             ││
│  │    ┌─────────────────┐              ┌─────────────────┐                    ││
│  │    │   Fast Client   │              │   Thin Client   │                    ││
│  │    │   (Primary)     │              │   (Shadow)      │                    ││
│  │    └────────┬────────┘              └────────┬────────┘                    ││
│  │             │                                │                              ││
│  │             v                                v                              ││
│  │    ┌─────────────────┐              ┌─────────────────┐                    ││
│  │    │    Result A     │              │    Result B     │                    ││
│  │    │ Latency: 5ms    │              │ Latency: 8ms    │                    ││
│  │    └────────┬────────┘              └────────┬────────┘                    ││
│  │             │                                │                              ││
│  │             └──────────┬─────────────────────┘                              ││
│  │                        │                                                    ││
│  │                        v                                                    ││
│  │              ┌──────────────────┐                                          ││
│  │              │ Compare Results  │                                          ││
│  │              │ Record Metrics   │                                          ││
│  │              └──────────────────┘                                          ││
│  │                                                                             ││
│  │  Return Primary (Fast Client) Result                                       ││
│  └─────────────────────────────────────────────────────────────────────────────┘│
│                                                                                  │
│  Dual Read Metrics:                                                             │
│    • dual_read_fastclient_slower_request_count - Fast was slower                │
│    • dual_read_fastclient_error_thinclient_succeed_request_count - Fast failed  │
│    • dual_read_thinclient_fastclient_latency_delta - Latency difference         │
│                                                                                  │
└─────────────────────────────────────────────────────────────────────────────────┘
```

---

## Load Controller

The Load Controller prevents overloading individual servers:

### Load Controller Flow

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                         Load Controller                                          │
├─────────────────────────────────────────────────────────────────────────────────┤
│                                                                                  │
│  For each request:                                                              │
│                                                                                  │
│  1. Calculate rejection ratio for target server                                 │
│     ┌───────────────────────────────────────────────────────────────┐          │
│     │ rejection_ratio = pending_requests / max_allowed_requests     │          │
│     └───────────────────────────────────────────────────────────────┘          │
│                                                                                  │
│  2. Decision:                                                                   │
│                                                                                  │
│     if (rejection_ratio >= 1.0) {                                              │
│         // Server overloaded                                                    │
│         record rejected_request_count_by_load_controller                        │
│         try alternative replica or fail request                                 │
│     } else {                                                                    │
│         // Server available                                                     │
│         send request to server                                                  │
│     }                                                                           │
│                                                                                  │
│  Metrics:                                                                       │
│    • rejected_request_count_by_load_controller - Requests blocked              │
│    • rejection_ratio - Current load ratio                                       │
│    • no_available_replica_request_count - No replicas available                │
│                                                                                  │
└─────────────────────────────────────────────────────────────────────────────────┘
```

---

## Key Classes

| Class | Location | Responsibility |
|-------|----------|----------------|
| `AvroGenericStoreClient` | `clients/venice-client/.../fastclient/` | Public API for Fast Client reads |
| `InternalAvroStoreClient` | Same directory | Internal implementation |
| `DispatchingAvroGenericStoreClient` | Same directory | Request dispatching logic |
| `RetriableAvroGenericStoreClient` | Same directory | Retry handling |
| `FastClientStats` | `.../fastclient/stats/` | Fast Client specific metrics |
| `ClientRoutingStrategy` | `.../fastclient/meta/` | Client-side routing decisions |

### Class Hierarchy

```
┌───────────────────────────────────────────────────────────────────────────────┐
│                       Fast Client Class Hierarchy                             │
├───────────────────────────────────────────────────────────────────────────────┤
│                                                                               │
│  ┌─────────────────────────────────────────┐                                 │
│  │     StoreClient<K, V> (interface)       │                                 │
│  └──────────────────┬──────────────────────┘                                 │
│                     │ implements                                              │
│                     v                                                         │
│  ┌─────────────────────────────────────────┐                                 │
│  │  AvroGenericStoreClient (Fast Client)   │  ← Entry point                  │
│  └──────────────────┬──────────────────────┘                                 │
│                     │ delegates                                               │
│                     v                                                         │
│  ┌─────────────────────────────────────────┐                                 │
│  │   RetriableAvroGenericStoreClient       │  ← Retry logic                  │
│  └──────────────────┬──────────────────────┘                                 │
│                     │ delegates                                               │
│                     v                                                         │
│  ┌─────────────────────────────────────────┐                                 │
│  │  DispatchingAvroGenericStoreClient      │  ← Routing + dispatch           │
│  └──────────────────┬──────────────────────┘                                 │
│                     │ uses                                                    │
│                     v                                                         │
│  ┌─────────────────────────────────────────┐                                 │
│  │       ClientRoutingStrategy             │  ← Partition/replica selection  │
│  └─────────────────────────────────────────┘                                 │
│                                                                               │
│  Stats Hierarchy:                                                            │
│  ┌─────────────────────────────────────────┐                                 │
│  │         BasicClientStats                │  ← Base metrics                 │
│  └──────────────────┬──────────────────────┘                                 │
│                     │ extends                                                 │
│                     v                                                         │
│  ┌─────────────────────────────────────────┐                                 │
│  │           ClientStats                   │  ← Thin client metrics          │
│  └──────────────────┬──────────────────────┘                                 │
│                     │ extends                                                 │
│                     v                                                         │
│  ┌─────────────────────────────────────────┐                                 │
│  │         FastClientStats                 │  ← Fast client specific         │
│  └─────────────────────────────────────────┘                                 │
│                                                                               │
└───────────────────────────────────────────────────────────────────────────────┘
```

---

## Configuration Reference

### Core Configuration

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `storeName` | String | Required | Venice store name |
| `r2Client` | R2Client | Required | R2 client for direct server access |
| `metadataRefreshIntervalInSeconds` | long | 60 | Metadata cache refresh interval |
| `routingLeakedRequestCleanupThresholdMs` | long | 5000 | Cleanup threshold for leaked requests |

### Retry Configuration

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `longTailRetryEnabledForSingleGet` | boolean | false | Enable long-tail retry for single get |
| `longTailRetryEnabledForBatchGet` | boolean | false | Enable long-tail retry for batch get |
| `longTailRetryThresholdForSingleGetInMicroSeconds` | long | 10000 | Single get retry threshold |
| `longTailRetryThresholdForBatchGetInMicroSeconds` | int[] | {10000} | Batch get retry thresholds |

### Load Controller Configuration

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `maxAllowedKeyCntInBatchGetReq` | int | 1000 | Max keys per batch request |
| `routingPendingRequestCounterInstanceBlockThreshold` | int | 50 | Per-instance pending request limit |

### Dual Read Configuration

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `dualReadEnabled` | boolean | false | Enable dual read mode |
| `thinClientForDualRead` | StoreClient | null | Thin client for shadow reads |

---

## Best Practices

### Metadata Caching

```java
// Fast Client caches metadata - watch for staleness
// Monitor metadata_staleness_high_watermark_ms metric

// If staleness is high, consider:
// 1. Reducing metadataRefreshIntervalInSeconds
// 2. Checking Router connectivity
```

### Long-Tail Retry Tuning

```java
// Start conservative, then tune based on metrics
ClientConfig config = new ClientConfig()
    .setLongTailRetryEnabledForSingleGet(true)
    .setLongTailRetryThresholdForSingleGetInMicroSeconds(50000); // 50ms

// Monitor:
// - long_tail_retry_request (should be ~1-3% of traffic)
// - retry.request.win_count (should show retries helping)
```

### Handling No Available Replicas

```java
try {
    GenericRecord value = fastClient.get("key").get();
} catch (VeniceClientException e) {
    if (isNoAvailableReplicaError(e)) {
        // All replicas overloaded or offline
        // Consider fallback to Thin Client or cached value
        log.warn("No available replicas for key: {}", key);
    }
}
```

### Graceful Degradation with Dual Read

```java
// During migration, use dual read to validate Fast Client
ClientConfig config = new ClientConfig()
    .setDualReadEnabled(true)
    .setThinClientForDualRead(thinClient);

// Monitor dual_read metrics:
// - fastclient_slower_request_ratio should be low
// - fastclient_error_thinclient_succeed ratio should be very low
```

---

## See Also

- [Fast Client Metrics](fast_client_metrics.md) - Detailed metrics reference
- [Thin Client Architecture](thin_client_architecture.md) - Simpler alternative
- [Da-Vinci Architecture](da_vinci_architecture.md) - Embedded client option
- [Clients Overview](clients_overview.md) - Client comparison
