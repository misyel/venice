# Venice Fast Client Metrics Reference

This document provides a comprehensive reference for all metrics emitted by the Venice Fast Client. Fast Client extends Thin Client with additional metrics for direct server access, retries, and load balancing.

## Table of Contents

- [Overview](#overview)
- [Inherited Metrics](#inherited-metrics)
- [Retry Metrics](#retry-metrics)
- [Fanout Metrics](#fanout-metrics)
- [Rejection Metrics](#rejection-metrics)
- [Dual Read Metrics](#dual-read-metrics)
- [Metadata Metrics](#metadata-metrics)
- [Metric Classes Reference](#metric-classes-reference)
- [Common Alerting Patterns](#common-alerting-patterns)

---

## Overview

Fast Client emits metrics through two systems:
- **Tehuti**: Legacy metrics framework (default)
- **OpenTelemetry**: Modern observability standard (when enabled)

Fast Client extends `ClientStats` (Thin Client) with additional metrics for:
- Long-tail and error retries
- Request fanout tracking
- Load controller rejections
- Dual read comparison
- Metadata staleness monitoring

---

## Inherited Metrics

Fast Client inherits all metrics from Thin Client. See [Thin Client Metrics](thin_client_metrics.md) for:

| Category | Key Metrics |
|----------|-------------|
| **Request Metrics** | `request`, `healthy_request`, `unhealthy_request` |
| **Latency Metrics** | `healthy_request_latency`, `request_serialization_time`, etc. |
| **Key Count Metrics** | `request.key_count`, `response.key_count` |
| **Streaming Metrics** | `response_ttfr`, `response_tt50pr`, `response_tt90pr` |
| **Timeout Metrics** | `app_timed_out_request`, `client_future_timeout` |

---

## Retry Metrics

Fast Client implements two retry strategies with dedicated metrics:

### Retry Count Metrics

| Metric | Type | Description |
|--------|------|-------------|
| `long_tail_retry_request` | Rate | Speculative retries triggered by slow response |
| `error_retry_request` | Rate | Retries triggered by error responses |
| `retry.request.win_count` | Rate | Retries that returned faster than original |

### Retry Flow Visualization

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                         Retry Metrics Flow                                       │
├─────────────────────────────────────────────────────────────────────────────────┤
│                                                                                  │
│  ┌────────────────┐                                                             │
│  │Original Request│                                                             │
│  └───────┬────────┘                                                             │
│          │                                                                       │
│          v                                                                       │
│  ┌───────────────────────────────────────────────────────────────┐              │
│  │                    Response Check                              │              │
│  └───────────────────────────────────────────────────────────────┘              │
│          │                                                                       │
│    ┌─────┴─────────────────────────────┐                                        │
│    │                                   │                                         │
│    v                                   v                                         │
│  ┌──────────────┐             ┌──────────────────┐                              │
│  │ Taking too   │             │  Error Response  │                              │
│  │ long?        │             │  (5XX)           │                              │
│  └──────┬───────┘             └────────┬─────────┘                              │
│         │                              │                                         │
│         │ Yes (exceeds threshold)      │ error_retry_request++                  │
│         │ long_tail_retry_request++    │                                         │
│         v                              v                                         │
│  ┌──────────────────────────────────────────────┐                               │
│  │        Send Retry to Different Replica       │                               │
│  └────────────────────────┬─────────────────────┘                               │
│                           │                                                      │
│                           v                                                      │
│  ┌───────────────────────────────────────────────────────────────┐              │
│  │                  Compare Response Times                        │              │
│  └───────────────────────────────────────────────────────────────┘              │
│          │                                                                       │
│    ┌─────┴─────┐                                                                │
│    │           │                                                                 │
│    v           v                                                                 │
│  ┌──────────┐ ┌───────────────┐                                                 │
│  │ Retry    │ │ Original Wins │                                                 │
│  │ Wins     │ │               │                                                 │
│  └────┬─────┘ └───────────────┘                                                 │
│       │                                                                          │
│       │ retry.request.win_count++                                               │
│       v                                                                          │
│  ┌──────────────────────────────────────────────┐                               │
│  │        Use Faster Response                   │                               │
│  └──────────────────────────────────────────────┘                               │
│                                                                                  │
└─────────────────────────────────────────────────────────────────────────────────┘
```

### Retry Effectiveness Analysis

| Metric Ratio | Interpretation |
|--------------|----------------|
| `retry.request.win_count / long_tail_retry_request` | High = retries are effective |
| `long_tail_retry_request / request` | Should be ~1-3% (retry budget) |
| `error_retry_request / unhealthy_request` | Error retry coverage |

---

## Fanout Metrics

Track how many servers are contacted per request:

### Fanout Count Metrics

| Metric | Type | Description |
|--------|------|-------------|
| `fanout_size` | Histogram | Number of servers in original request |
| `retry_fanout_size` | Histogram | Number of servers in retry request |

### Fanout Dimensions

| Dimension | Values | Description |
|-----------|--------|-------------|
| `venice.request.fanout_type` | `ORIGINAL`, `RETRY` | Original vs retry fanout |

### Fanout Statistics

| Statistic | Description |
|-----------|-------------|
| `Avg` | Average servers per request |
| `Max` | Maximum servers contacted |

### Understanding Fanout

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                         Fanout Scenarios                                         │
├─────────────────────────────────────────────────────────────────────────────────┤
│                                                                                  │
│  Single Get (fanout_size = 1):                                                  │
│  ┌────────────┐                                                                 │
│  │   Client   │ ──────────────────────────► Server A                            │
│  └────────────┘                                                                 │
│                                                                                  │
│  Batch Get spanning 3 partitions (fanout_size = 3):                             │
│  ┌────────────┐      ┌──────────────────────► Server A (partition 1)           │
│  │   Client   │ ─────┼──────────────────────► Server B (partition 2)           │
│  └────────────┘      └──────────────────────► Server C (partition 3)           │
│                                                                                  │
│  Long-tail Retry (retry_fanout_size = 1):                                       │
│  ┌────────────┐      ┌──────────────────────► Server A (original)              │
│  │   Client   │ ─────┤                        (slow...)                         │
│  └────────────┘      └──────────────────────► Server A' (retry replica)        │
│                                                                                  │
└─────────────────────────────────────────────────────────────────────────────────┘
```

---

## Rejection Metrics

Track requests rejected by load controller or lack of replicas:

### Rejection Count Metrics

| Metric | Type | Description |
|--------|------|-------------|
| `no_available_replica_request_count` | Rate | Requests with no available replicas |
| `rejected_request_count_by_load_controller` | Rate | Requests rejected due to overload |
| `rejection_ratio` | Histogram | Current rejection ratio |

### Rejection Dimensions

| Dimension | Values | Description |
|-----------|--------|-------------|
| `venice.rejection_reason` | `NO_REPLICAS_AVAILABLE`, `THROTTLED_BY_LOAD_CONTROLLER` | Reason for rejection |

### Rejection Flow

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                         Rejection Decision Flow                                  │
├─────────────────────────────────────────────────────────────────────────────────┤
│                                                                                  │
│  ┌────────────────┐                                                             │
│  │ Incoming       │                                                             │
│  │ Request        │                                                             │
│  └───────┬────────┘                                                             │
│          │                                                                       │
│          v                                                                       │
│  ┌───────────────────────────────────────────────────────────────┐              │
│  │          Find Available Replicas for Partition                 │              │
│  └───────────────────────────────────────────────────────────────┘              │
│          │                                                                       │
│    ┌─────┴─────┐                                                                │
│    │           │                                                                 │
│    v           v                                                                 │
│  ┌──────────┐ ┌─────────────────────────────────────────┐                       │
│  │No        │ │ For each replica, check load controller: │                       │
│  │Replicas  │ │                                          │                       │
│  └────┬─────┘ │ pending_requests >= threshold?           │                       │
│       │       └──────────────────────┬──────────────────┘                       │
│       │                              │                                           │
│       │ no_available_replica_        │                                           │
│       │ request_count++              ├─────────────────────┐                    │
│       │                              │                     │                     │
│       v                        All Overloaded        Some Available              │
│  ┌──────────┐                        │                     │                     │
│  │  Fail    │                        │                     │                     │
│  │ Request  │                        v                     v                     │
│  └──────────┘             ┌──────────────────┐   ┌──────────────────┐           │
│                           │rejected_request  │   │  Send Request    │           │
│                           │_count_by_load_   │   │  to Available    │           │
│                           │controller++      │   │  Replica         │           │
│                           └──────────────────┘   └──────────────────┘           │
│                                                                                  │
└─────────────────────────────────────────────────────────────────────────────────┘
```

---

## Dual Read Metrics

Compare Fast Client with Thin Client during migration:

### Dual Read Comparison Metrics

| Metric | Type | Description |
|--------|------|-------------|
| `dual_read_fastclient_slower_request_count` | Rate | Fast Client was slower |
| `dual_read_fastclient_slower_request_ratio` | Ratio | Ratio of slower requests |
| `dual_read_fastclient_error_thinclient_succeed_request_count` | Rate | Fast failed, Thin succeeded |
| `dual_read_fastclient_error_thinclient_succeed_request_ratio` | Ratio | Ratio of error disparity |
| `dual_read_thinclient_fastclient_latency_delta` | Histogram | Latency difference (Thin - Fast) |

### Dual Read Analysis

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                      Dual Read Comparison Matrix                                 │
├─────────────────────────────────────────────────────────────────────────────────┤
│                                                                                  │
│  Fast Client Result:     Success              Error                             │
│                    ┌──────────────────┬──────────────────┐                      │
│  Thin Client      │                  │                  │                       │
│  Result:          │                  │                  │                       │
│     Success       │  Compare latency │  FC error, TC ok │                       │
│                   │  (normal case)   │  (investigate!)  │                       │
│                   │                  │                  │                       │
│     Error         │  FC ok, TC error │  Both error      │                       │
│                   │  (FC advantage)  │  (systemic issue)│                       │
│                   │                  │                  │                       │
│                   └──────────────────┴──────────────────┘                      │
│                                                                                  │
│  Metrics recorded for "FC error, TC ok":                                        │
│    dual_read_fastclient_error_thinclient_succeed_request_count                  │
│                                                                                  │
│  Metrics recorded when FC succeeds but slower:                                  │
│    dual_read_fastclient_slower_request_count                                    │
│    dual_read_thinclient_fastclient_latency_delta                                │
│                                                                                  │
└─────────────────────────────────────────────────────────────────────────────────┘
```

### Migration Health Indicators

| Metric | Healthy Range | Action if Out of Range |
|--------|---------------|------------------------|
| `fastclient_slower_request_ratio` | < 5% | Check server selection, metadata staleness |
| `fastclient_error_thinclient_succeed_ratio` | < 0.1% | Investigate Fast Client routing |
| `latency_delta` (negative = FC faster) | < 0 | Expected; FC should be faster |

---

## Metadata Metrics

Track metadata freshness and staleness:

### Metadata Staleness Metrics

| Metric | Type | Unit | Description |
|--------|------|------|-------------|
| `metadata_staleness_high_watermark_ms` | Gauge | ms | Time since last metadata refresh |

### Staleness Monitoring

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                     Metadata Staleness Timeline                                  │
├─────────────────────────────────────────────────────────────────────────────────┤
│                                                                                  │
│  Time ─────────────────────────────────────────────────────────────────────────►│
│                                                                                  │
│  Metadata     Metadata     Metadata      Current                                │
│  Refresh 1    Refresh 2    Refresh 3     Time                                   │
│     │            │            │            │                                     │
│     v            v            v            v                                     │
│  ───┼────────────┼────────────┼────────────┼───                                 │
│                                            │                                     │
│                                            │ metadata_staleness_high_watermark   │
│                               ◄────────────┤ = current_time - last_refresh_time │
│                               │            │                                     │
│                          staleness         │                                     │
│                                                                                  │
│  If staleness grows too high:                                                   │
│    • Routing decisions may be based on stale partition assignments              │
│    • Requests may be sent to wrong servers                                      │
│    • Recommendations: check Router connectivity, reduce refresh interval        │
│                                                                                  │
└─────────────────────────────────────────────────────────────────────────────────┘
```

---

## Metric Classes Reference

| Class | Location | Scope |
|-------|----------|-------|
| `FastClientStats` | `clients/venice-client/.../fastclient/stats/` | Fast Client specific metrics |
| `FastClientMetricEntity` | Same directory | OTel metric definitions |
| `ClientStats` | `clients/venice-thin-client/.../stats/` | Inherited from Thin Client |
| `BasicClientStats` | Same directory | Base client metrics |

### Fast Client Metric Entities

| Entity | Metric Name | Type | Dimensions |
|--------|-------------|------|------------|
| `RETRY_REQUEST_WIN_COUNT` | `retry.request.win_count` | Counter | store, method |
| `METADATA_STALENESS_DURATION` | `metadata.staleness_duration` | Gauge | store |
| `REQUEST_FANOUT_COUNT` | `request.fanout_count` | Histogram | store, method, fanout_type |
| `REQUEST_REJECTION_COUNT` | `request.rejection_count` | Counter | store, method, rejection_reason |
| `REQUEST_REJECTION_RATIO` | `request.rejection_ratio` | Histogram | store, method, rejection_reason |

---

## Common Alerting Patterns

### High Rejection Rate

```
Alert: no_available_replica_request_count rate > threshold
Metric: no_available_replica_request_count (OccurrenceRate)
Dimensions: venice.store.name
Action: Check server health, Helix partition assignment
```

### Load Controller Throttling

```
Alert: rejected_request_count_by_load_controller rate > threshold
Metric: rejected_request_count_by_load_controller (OccurrenceRate)
Dimensions: venice.store.name
Action: Check target server load, consider scaling or rebalancing
```

### Long-Tail Retry Budget Exceeded

```
Alert: long_tail_retry_request / request > 0.05 (5%)
Metric: long_tail_retry_request, request (Rates)
Dimensions: venice.store.name
Action: Review retry threshold, check for systemic slowness
```

### Metadata Staleness

```
Alert: metadata_staleness_high_watermark_ms > threshold_ms
Metric: metadata_staleness_high_watermark_ms (Gauge)
Dimensions: venice.store.name
Action: Check Router connectivity, metadata refresh configuration
```

### Dual Read Disparity (During Migration)

```
Alert: dual_read_fastclient_error_thinclient_succeed_request_ratio > 0.001
Metric: dual_read_fastclient_error_thinclient_succeed_request_ratio
Action: Investigate Fast Client routing, compare with Thin Client
```

### Retry Effectiveness Low

```
Alert: retry.request.win_count / long_tail_retry_request < 0.3
Metric: retry.request.win_count, long_tail_retry_request
Action: Review retry thresholds, may be retrying too early/late
```

---

## Summary Statistics

| Category | Metric Count |
|----------|--------------|
| Inherited (from Thin Client) | 26 |
| Retry Metrics | 3 |
| Fanout Metrics | 2 |
| Rejection Metrics | 3 |
| Dual Read Metrics | 5 |
| Metadata Metrics | 1 |
| **Total Fast Client Metrics** | **40** |

---

## See Also

- [Fast Client Architecture](fast_client_architecture.md) - Architecture documentation
- [Thin Client Metrics](thin_client_metrics.md) - Base client metrics
- [Da-Vinci Metrics](da_vinci_metrics.md) - Embedded client metrics
- [Clients Overview](clients_overview.md) - Client comparison
