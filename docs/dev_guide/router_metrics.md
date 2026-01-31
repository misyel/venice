# Venice Router Metrics Reference

This document provides a comprehensive reference for all metrics emitted by the Venice Router. These metrics are essential for monitoring router health, performance, and debugging issues.

## Table of Contents

- [Overview](#overview)
- [Metric Types](#metric-types)
- [Metric Dimensions](#metric-dimensions)
- [Request Metrics](#request-metrics)
- [Latency Metrics](#latency-metrics)
- [Retry Metrics](#retry-metrics)
- [Health Metrics](#health-metrics)
- [Throttling Metrics](#throttling-metrics)
- [Connection & Security Metrics](#connection--security-metrics)
- [Version Metrics](#version-metrics)
- [Compression Metrics](#compression-metrics)
- [Admin & Health Check Metrics](#admin--health-check-metrics)
- [Scatter-Gather Metrics](#scatter-gather-metrics)
- [OpenTelemetry Integration](#opentelemetry-integration)
- [Metric Classes Reference](#metric-classes-reference)
- [Common Alerting Patterns](#common-alerting-patterns)

---

## Overview

Venice Router emits metrics through two systems:
- **Tehuti**: Legacy metrics framework (default)
- **OpenTelemetry**: Modern observability standard (when enabled)

Metrics are organized hierarchically:
- **Total metrics**: Aggregated across all stores
- **Per-store metrics**: Scoped to individual stores
- **Per-host metrics**: Scoped to individual storage nodes (routes)

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         Metrics Hierarchy                                    │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  ┌─────────────────────────────────────────────────────────────────────┐    │
│  │                    Total (Aggregated) Metrics                        │    │
│  │           venice.router.total.*                                      │    │
│  └─────────────────────────────────────────────────────────────────────┘    │
│                                    │                                         │
│              ┌─────────────────────┼─────────────────────┐                  │
│              v                     v                     v                   │
│  ┌───────────────────┐ ┌───────────────────┐ ┌───────────────────┐         │
│  │  Per-Store        │ │  Per-Store        │ │  Per-Store        │         │
│  │  venice.router.   │ │  venice.router.   │ │  venice.router.   │         │
│  │  myStore.*        │ │  otherStore.*     │ │  anotherStore.*   │         │
│  └───────────────────┘ └───────────────────┘ └───────────────────┘         │
│              │                                                               │
│              v                                                               │
│  ┌─────────────────────────────────────────────────────────────────────┐    │
│  │                    Per-Host (Route) Metrics                          │    │
│  │           venice.router.myStore.host_192_168_1_1.*                   │    │
│  └─────────────────────────────────────────────────────────────────────┘    │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## Metric Types

| Type | Description | Use Case |
|------|-------------|----------|
| **Counter** | Monotonically increasing count | Request counts, error counts |
| **Gauge** | Point-in-time value (can go up/down) | In-flight requests, connection counts |
| **Histogram** | Distribution of values with percentiles | Latencies, sizes |
| **Rate** | Events per second | QPS, throughput |
| **OccurrenceRate** | Counter with rate calculation | Retry rates, error rates |

### Histogram Percentiles

Histograms provide these percentile statistics:
- `p50` - Median (50th percentile)
- `p95` - 95th percentile
- `p99` - 99th percentile
- `p999` - 99.9th percentile
- `p9999` - 99.99th percentile
- `avg` - Average
- `max` - Maximum value

---

## Metric Dimensions

Metrics are tagged with dimensions (labels) for filtering and grouping:

| Dimension | Description | Example Values |
|-----------|-------------|----------------|
| `venice_store_name` | Store name | `myStore`, `userFeatures` |
| `venice_cluster_name` | Cluster name | `venice-prod-1` |
| `venice_request_method` | Request type | `SINGLE_GET`, `MULTI_GET`, `COMPUTE` |
| `http_response_status_code` | HTTP status | `200`, `404`, `500`, `503` |
| `http_response_status_code_category` | Status category | `2XX`, `4XX`, `5XX` |
| `venice_response_status_code_category` | Venice status | `SUCCESS`, `FAIL` |
| `venice_message_type` | Message direction | `REQUEST`, `RESPONSE` |
| `venice_request_retry_type` | Retry type | `ERROR_RETRY`, `LONG_TAIL_RETRY` |
| `venice_request_retry_abort_reason` | Abort reason | `DELAY_CONSTRAINT`, `SLOW_ROUTE` |

### Request Methods

| Method | Description |
|--------|-------------|
| `SINGLE_GET` | Single key lookup |
| `MULTI_GET` | Batch key lookup |
| `COMPUTE` | Read-compute operation |
| `MULTI_GET_STREAMING` | Streaming batch lookup |
| `COMPUTE_STREAMING` | Streaming compute |

---

## Request Metrics

### Incoming Request Tracking

| Metric | Type | Description |
|--------|------|-------------|
| `request` | Counter | Total incoming requests received |
| `request_call_count` | Counter | Total single-get requests (QPS tracking) |
| `in_flight_request_count` | Gauge | Current number of in-flight requests |
| `request_usage` | Rate | Incoming keys per second |

**Recording**: At request arrival in `recordIncomingRequest()`

### Request Status Classification

Requests are classified into categories based on response status and latency:

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    Request Classification Flow                               │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│                         ┌────────────────┐                                   │
│                         │ Request Result │                                   │
│                         └───────┬────────┘                                   │
│                                 │                                            │
│            ┌────────────────────┼────────────────────┐                      │
│            │                    │                    │                       │
│            v                    v                    v                       │
│     ┌──────────┐         ┌──────────┐         ┌──────────┐                  │
│     │ Success  │         │  Error   │         │ Throttled│                  │
│     │ (2XX)    │         │(4XX/5XX) │         │  (429)   │                  │
│     └────┬─────┘         └────┬─────┘         └────┬─────┘                  │
│          │                    │                    │                         │
│     ┌────┴────┐               │                    │                         │
│     │         │               │                    │                         │
│     v         v               v                    v                         │
│ ┌───────┐ ┌───────┐     ┌──────────┐        ┌──────────┐                    │
│ │Healthy│ │ Tardy │     │Unhealthy │        │Throttled │                    │
│ │Request│ │Request│     │ Request  │        │ Request  │                    │
│ │(fast) │ │(slow) │     │          │        │          │                    │
│ └───────┘ └───────┘     └──────────┘        └──────────┘                    │
│                                                                              │
│  Tardy threshold: configurable per request type (default 10s)               │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

| Metric | Type | Description | Condition |
|--------|------|-------------|-----------|
| `healthy_request` | Counter + Rate | Successful fast requests | 2XX status, latency < tardy threshold |
| `tardy_request` | Counter + Rate | Successful slow requests | 2XX status, latency >= tardy threshold |
| `unhealthy_request` | Counter | Failed requests | 4XX or 5XX status (except 429) |
| `throttled_request` | Counter | Throttled requests | 429 (Too Many Requests) |
| `bad_request` | Counter | Invalid requests | 400 or 413 status |
| `unavailable_request` | Counter | Service unavailable | 503 status |

### Key Count Metrics

| Metric | Type | Description |
|--------|------|-------------|
| `key_num` | Histogram (Avg/Max) | Keys per multi-get request |
| `key_count` | Histogram | Keys with response categorization |
| `bad_request_key_count` | Histogram | Keys in failed requests |

### Size Metrics

| Metric | Type | Unit | Description |
|--------|------|------|-------------|
| `request_size` | Histogram | Bytes | Incoming request body size |
| `response_size` | Histogram | Bytes | Outgoing response body size |
| `key_size_in_byte` | Histogram | Bytes | Individual key size (when profiling enabled) |

---

## Latency Metrics

### Primary Latency

| Metric | Type | Unit | Description |
|--------|------|------|-------------|
| `latency` | Histogram | ms | End-to-end request latency |
| `healthy_request_latency` | Histogram | ms | Latency for successful fast requests |
| `tardy_request_latency` | Histogram | ms | Latency for successful slow requests |
| `unhealthy_request_latency` | Histogram | ms | Latency for failed requests |
| `throttled_request_latency` | Histogram | ms | Latency for throttled requests |

### Latency Breakdown

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         Latency Breakdown                                    │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  Total Latency (latency)                                                    │
│  ├─────────────────────────────────────────────────────────────────────────│
│  │                                                                          │
│  │  ┌──────────────────┐  ┌─────────────────┐  ┌─────────────────────────┐ │
│  │  │ request_parse_   │  │ request_route_  │  │ response_waiting_time   │ │
│  │  │ latency          │  │ latency         │  │                         │ │
│  │  │                  │  │                 │  │ (backend processing)    │ │
│  │  │ Parse URI,       │  │ Find partition, │  │                         │ │
│  │  │ deserialize keys │  │ select hosts    │  │                         │ │
│  │  └──────────────────┘  └─────────────────┘  └─────────────────────────┘ │
│  │                                                                          │
│  │  |<-- Parsing -->|<-- Routing -->|<----- Server Processing ----->|      │
│  │                                                                          │
│  └─────────────────────────────────────────────────────────────────────────│
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

| Metric | Type | Unit | Description |
|--------|------|------|-------------|
| `request_parse_latency` | Histogram | ms | Time to parse incoming request |
| `request_route_latency` | Histogram | ms | Time to compute routing decision |
| `response_waiting_time` | Histogram | ms | Time waiting for storage node response |

---

## Retry Metrics

### Retry Counts

| Metric | Type | Description |
|--------|------|-------------|
| `error_retry` | Counter | Error-triggered retries (5XX responses) |
| `allowed_retry_request_count` | Rate | Retries that were permitted |
| `disallowed_retry_request_count` | Rate | Retries that were blocked |
| `retry_delay` | Histogram | Actual delay between original and retry |

### Retry Abort Reasons

When a retry is aborted, one of these metrics is incremented:

| Metric | Reason | Description |
|--------|--------|-------------|
| `delay_constraint_aborted_retry_request` | `DELAY_CONSTRAINT` | Retry would exceed time budget |
| `slow_route_aborted_retry_request` | `SLOW_ROUTE` | All available routes are slow |
| `retry_route_limit_aborted_retry_request` | `MAX_RETRY_ROUTE_LIMIT` | Max retry routes exceeded |
| `no_available_replica_aborted_retry_request` | `NO_AVAILABLE_REPLICA` | No healthy replicas available |

### Retry Triggering

| Metric | Type | Description |
|--------|------|-------------|
| `error_retry_attempt_triggered_by_pending_request_check` | Rate | Retries triggered by pending queue health |

---

## Health Metrics

### Host Health Status

| Metric | Type | Description |
|--------|------|-------------|
| `unhealthy_host_offline_instance` | Counter | Host marked offline (Helix) |
| `unhealthy_host_too_many_pending_request` | Counter | Host marked unhealthy (queue overflow) |
| `unhealthy_host_heart_beat_failure` | Counter | Host marked unhealthy (heartbeat) |
| `unhealthy_host_delay_join` | Rate | Host delayed joining (not ready) |

### Aggregated Health Counts (Total Only)

| Metric | Type | Description |
|--------|------|-------------|
| `unhealthy_host_count_caused_by_pending_queue` | Gauge | Current count of hosts unhealthy due to queue |
| `unhealthy_host_count_caused_by_router_heart_beat` | Gauge | Current count of hosts unhealthy due to heartbeat |

### Pending Queue Health

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    Pending Queue Health Metrics                              │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  Per-Route:                                                                  │
│  ┌─────────────────────────────────────────────────────────────────────┐    │
│  │ pending_request_count          - Current in-flight to this host     │    │
│  │ unhealthy_pending_queue_duration_per_route - Time in unhealthy state│    │
│  │ unhealthy_pending_queue_per_route - Occurrences of unhealthy state  │    │
│  └─────────────────────────────────────────────────────────────────────┘    │
│                                                                              │
│  Per-Host:                                                                   │
│  ┌─────────────────────────────────────────────────────────────────────┐    │
│  │ leaked_pending_request_count   - Orphaned request futures           │    │
│  │ unhealthy_pending_queue_duration - Time in unhealthy state          │    │
│  │ unhealthy_pending_queue        - Occurrences of unhealthy state     │    │
│  └─────────────────────────────────────────────────────────────────────┘    │
│                                                                              │
│  Thresholds:                                                                 │
│  • Unhealthy: pending_request_count >= unhealthy_threshold (default: 10)    │
│  • Recovery: pending_request_count <= resume_threshold (default: 5)         │
│              AND elapsed time > OOR duration (default: 30s)                 │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

| Metric | Type | Unit | Description |
|--------|------|------|-------------|
| `pending_request_count` | Gauge | Count | In-flight requests per route |
| `unhealthy_pending_queue_duration` | Histogram | ms | Duration of unhealthy state |
| `unhealthy_pending_queue` | Rate | Count | Unhealthy state occurrences |
| `unhealthy_pending_queue_duration_per_route` | Histogram | ms | Per-route unhealthy duration |
| `unhealthy_pending_queue_per_route` | Rate | Count | Per-route unhealthy occurrences |
| `leaked_pending_request_count` | Counter | Count | Orphaned (leaked) request futures |

---

## Throttling Metrics

### Request Throttling

| Metric | Type | Description |
|--------|------|-------------|
| `router_throttled_request` | Counter | Requests throttled at router level |
| `request_throttled_by_router_capacity` | Counter | Requests rejected due to capacity |

### Quota Metrics

| Metric | Type | Unit | Description |
|--------|------|------|-------------|
| `read_quota_per_router` | Gauge | Units | Current read quota allocation |
| `read_quota_usage_kps` | Rate | Keys/sec | Current quota consumption rate |
| `read_quota_throttle` | Gauge | 0/1 | Throttling enabled flag |

---

## Connection & Security Metrics

### Connection Management

| Metric | Type | Description |
|--------|------|-------------|
| `connection_count` | Gauge | Current active connections |
| `connection_count_gauge` | AsyncGauge | Sampled connection count |
| `rejected_connection_count` | Rate | Connections rejected by rate limiter |

### SSL/TLS Metrics

| Metric | Type | Description |
|--------|------|-------------|
| `ssl_success` | Counter | Successful SSL handshakes |
| `ssl_error` | Counter | Failed SSL handshakes |
| `non_ssl_request_count` | Gauge | Non-SSL (HTTP) requests |
| `pending_ssl_handshake_count` | AsyncGauge | SSL handshakes in progress |
| `total_failed_ssl_handshake_count` | AsyncGauge | Total failed handshakes |

---

## Version Metrics

### Current Version Tracking

| Metric | Type | Description |
|--------|------|-------------|
| `current_version` | Gauge | Current serving version number |

### Stale Version Detection

| Metric | Type | Description |
|--------|------|-------------|
| `stale_version_delta` | Gauge (Max) | Version difference (current - serving) |
| `stale_version_reason_offline_partitions` | Rate | Stale due to offline partitions |
| `stale_version_reason_dictionary_not_downloaded` | Rate | Stale due to missing dictionary |

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    Stale Version Scenarios                                   │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  stale_version_delta = 0:                                                   │
│    Router serving the latest version                                        │
│                                                                              │
│  stale_version_delta > 0:                                                   │
│    Router serving an older version                                          │
│    Check stale_version_reason_* for cause:                                  │
│      • offline_partitions: Some partitions not ready                        │
│      • dictionary_not_downloaded: Compression dictionary missing            │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## Compression Metrics

| Metric | Type | Unit | Description |
|--------|------|------|-------------|
| `compressed_response_size` | Histogram | Bytes | Response size after compression |
| `decompressed_response_size` | Histogram | Bytes | Response size after decompression |
| `decompression_time` | Histogram | ms | Time to decompress response |

### Compression Ratio Calculation

```
compression_ratio = decompressed_response_size / compressed_response_size
```

---

## Admin & Health Check Metrics

### Admin Operations

| Metric | Type | Description |
|--------|------|-------------|
| `admin_request` | Counter | Total admin requests |
| `error_admin_request` | Counter | Failed admin requests |

### Health Check Endpoint

| Metric | Type | Description |
|--------|------|-------------|
| `healthcheck_request` | Counter | Total health check requests |
| `error_healthcheck_request` | Counter | Failed health check requests |

---

## Scatter-Gather Metrics

These metrics track the scatter-gather layer (from Alpini framework):

| Metric | Type | Description |
|--------|------|-------------|
| `retry_count` | Gauge | Total scatter-gather retries |
| `retry_key_count` | Gauge | Total keys in retried requests |
| `retry_slower_than_original_count` | Gauge | Retries slower than original |
| `retry_faster_than_original_count` | Gauge | Retries faster than original |
| `retry_error_count` | Gauge | Retries that resulted in errors |

### Fanout Metrics

| Metric | Type | Description |
|--------|------|-------------|
| `fanout_request_count` | Histogram (Avg/Max) | Number of hosts contacted per request |
| `find_unhealthy_host_request` | Rate | Unhealthy host lookups |

### Meta Store Metrics

| Metric | Type | Description |
|--------|------|-------------|
| `meta_store_shadow_read` | Rate | Meta store shadow read operations |
| `multiget_fallback` | Rate | Compute requests falling back to multi-get |

---

## OpenTelemetry Integration

When OpenTelemetry is enabled, metrics are emitted with standard OTel semantics:

### OTel Metric Names

OTel metrics use dot notation: `venice.router.<metric_name>`

### OTel Attributes (Dimensions)

| OTel Attribute | Tehuti Dimension |
|----------------|------------------|
| `venice.store.name` | `venice_store_name` |
| `venice.cluster.name` | `venice_cluster_name` |
| `http.response.status_code` | `http_response_status_code` |
| `venice.request.method` | `venice_request_method` |

### Multi-Dimension Metrics

Some metrics support multiple enum dimensions using specialized classes:
- `MetricEntityStateThreeEnums`: 3 dimensions (status, category, response category)
- `MetricEntityStateOneEnum`: 1 enum dimension

---

## Metric Classes Reference

| Class | Location | Scope |
|-------|----------|-------|
| `RouterHttpRequestStats` | `.../router/stats/RouterHttpRequestStats.java` | Per-store request metrics |
| `AggRouterHttpRequestStats` | `.../router/stats/AggRouterHttpRequestStats.java` | Aggregated request metrics |
| `HostHealthStats` | `.../router/stats/HostHealthStats.java` | Per-host health metrics |
| `AggHostHealthStats` | `.../router/stats/AggHostHealthStats.java` | Aggregated health metrics |
| `RouteHttpStats` | `.../router/stats/RouteHttpStats.java` | Per-route response timing |
| `RouteHttpRequestStats` | `.../router/stats/RouteHttpRequestStats.java` | Per-route queue monitoring |
| `RouterThrottleStats` | `.../router/stats/RouterThrottleStats.java` | Throttling metrics |
| `SecurityStats` | `.../router/stats/SecurityStats.java` | SSL/connection metrics |
| `StaleVersionStats` | `.../router/stats/StaleVersionStats.java` | Version staleness |
| `AdminOperationsStats` | `.../router/stats/AdminOperationsStats.java` | Admin request metrics |
| `HealthCheckStats` | `.../router/stats/HealthCheckStats.java` | Health check metrics |
| `RouterCurrentVersionStats` | `.../router/stats/RouterCurrentVersionStats.java` | Version tracking |

---

## Common Alerting Patterns

### High Error Rate

```
Alert: unhealthy_request_rate > threshold
Metric: unhealthy_request (as rate)
Dimensions: venice_store_name
Action: Check storage node health, investigate 5XX errors
```

### High Latency

```
Alert: latency.p99 > threshold_ms
Metric: latency (histogram)
Dimensions: venice_store_name, venice_request_method
Action: Check response_waiting_time, investigate slow storage nodes
```

### Host Health Issues

```
Alert: unhealthy_host_count_caused_by_pending_queue > 0
Metric: unhealthy_host_count_caused_by_pending_queue (gauge)
Action: Check storage node load, investigate slow partitions
```

### Throttling

```
Alert: throttled_request_rate > 0
Metric: throttled_request (as rate)
Dimensions: venice_store_name
Action: Check quota limits, scale router or storage
```

### Stale Version

```
Alert: stale_version_delta > 0 for > threshold_minutes
Metric: stale_version_delta (gauge)
Dimensions: venice_store_name
Action: Check partition availability, dictionary download status
```

### Connection Pool Exhaustion

```
Alert: pending_request_count.max > threshold
Metric: pending_request_count (gauge)
Dimensions: host
Action: Check connection pool size, investigate slow hosts
```

### SSL Handshake Failures

```
Alert: ssl_error_rate > threshold
Metric: ssl_error (as rate)
Action: Check certificates, client compatibility
```

---

## Metric Recording Call Sites

This section documents where metrics are recorded in the codebase:

### Request Lifecycle

| Event | Method | Metrics Recorded |
|-------|--------|------------------|
| Request received | `recordIncomingRequest()` | `request`, `in_flight_request_count++` |
| Request parsed | `recordRequestParsingLatency()` | `request_parse_latency`, `request_size`, `key_num` |
| Routing computed | `recordRequestRoutingLatency()` | `request_route_latency` |
| Response received | Various | `response_waiting_time`, `latency`, status metrics |
| Request complete | `recordResponse()` | `in_flight_request_count--`, `response_size` |

### Retry Lifecycle

| Event | Method | Metrics Recorded |
|-------|--------|------------------|
| Retry triggered | `recordErrorRetryCount()` | `error_retry` |
| Retry allowed | `recordAllowedRetryRequest()` | `allowed_retry_request_count` |
| Retry blocked | `recordDisallowedRetryRequest()` | `disallowed_retry_request_count` |
| Retry aborted | `record*AbortedRetryCountMetric()` | Abort reason metric |

### Health Updates

| Event | Method | Metrics Recorded |
|-------|--------|------------------|
| Host offline | `recordUnhealthyHostOfflineInstance()` | `unhealthy_host_offline_instance` |
| Queue overflow | `recordUnhealthyHostTooManyPendingRequest()` | `unhealthy_host_too_many_pending_request` |
| Heartbeat failure | `recordUnhealthyHostHeartBeatFailure()` | `unhealthy_host_heart_beat_failure` |
| Queue recovery | `recordUnhealthyQueueDuration()` | `unhealthy_pending_queue_duration` |

---

## Summary Statistics

| Category | Metric Count |
|----------|--------------|
| Request Metrics | 25+ |
| Latency Metrics | 10+ |
| Retry Metrics | 10+ |
| Health Metrics | 15+ |
| Throttling Metrics | 5+ |
| Connection/Security | 10+ |
| Version Metrics | 5+ |
| Compression Metrics | 3 |
| Admin/Health Check | 4 |
| Scatter-Gather | 7+ |
| **Total** | **80+** |

---

## See Also

- [Router Architecture](router_architecture.md) - Architecture and request flow documentation
- [Configuration Reference](router_architecture.md#configuration-reference) - Router configuration options
- [Key Classes Map](../../.claude/rules/key-classes.md) - Overview of important classes
