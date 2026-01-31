# Venice Router Metrics Reference

This document provides a comprehensive reference for all metrics emitted by the Venice Router.

## Metrics Overview

Venice Router emits metrics through Tehuti (a metrics library). Metrics are organized by component and typically include:

- **Per-store metrics**: Prefixed with store name
- **Aggregated total metrics**: Router-wide totals
- **Per-request-type metrics**: Separate stats per request type (SINGLE_GET, MULTI_GET, COMPUTE)

## HTTP Request Metrics

**Class:** `RouterHttpRequestStats` / `AggRouterHttpRequestStats`
**Location:** `services/venice-router/src/main/java/com/linkedin/venice/router/stats/`

### Request Counts

| Metric | Type | Description |
|--------|------|-------------|
| `request` | Rate | Incoming request rate |
| `healthy_request` | Rate | Successful requests |
| `unhealthy_request` | Rate | Failed requests (5xx) |
| `tardy_request` | Rate | Slow requests (exceeded tardy threshold) |
| `throttled_request` | Rate | Requests throttled due to quota |
| `bad_request` | Rate | Client errors (4xx) |
| `unavailable_request` | Rate | Requests failed due to no available replica |

### Request Latency

| Metric | Stats | Description |
|--------|-------|-------------|
| `healthy_request_latency` | avg, p50, p90, p95, p99, max | Successful request latency |
| `unhealthy_request_latency` | avg, p50, p90, p95, p99, max | Failed request latency |
| `tardy_request_latency` | avg, p50, p90, p95, p99, max | Slow request latency |
| `throttled_request_latency` | avg, p50, p90, p95, p99, max | Throttled request latency |
| `request_parsing_latency` | avg, max | Time to parse incoming request |
| `request_routing_latency` | avg, max | Time to compute routing |

### Retry Metrics

| Metric | Type | Description |
|--------|------|-------------|
| `retry_count` | Rate | Total retry attempts |
| `error_retry_count` | Rate | Retries triggered by error (5xx) |
| `allowed_retry_count` | Rate | Successful long-tail retries |
| `disallowed_retry_count` | Rate | Retries that were rejected |
| `retry_delay` | avg, max | Delay before retry |
| `delay_constraint_aborted_retry_count` | Rate | Retries aborted due to delay constraint |
| `slow_route_aborted_retry_count` | Rate | Retries aborted due to slow route |
| `retry_route_limit_aborted_retry_count` | Rate | Retries aborted due to route limit |
| `no_available_replica_aborted_retry_count` | Rate | Retries aborted due to no replica |

### Size Metrics

| Metric | Stats | Description |
|--------|-------|-------------|
| `request_size` | avg, max | Request payload size in bytes |
| `response_size` | avg, max | Response payload size in bytes |
| `compressed_response_size` | avg, max | Compressed response size |
| `decompressed_response_size` | avg, max | Decompressed response size |
| `key_size` | avg, max | Average key size |

### Key Count Metrics

| Metric | Stats | Description |
|--------|-------|-------------|
| `key_count` | avg, max | Keys per request |
| `bad_request_key_count` | avg | Keys in bad requests |
| `fanout_request_count` | avg, max | Number of storage nodes per request |

### Quota Metrics

| Metric | Type | Description |
|--------|------|-------------|
| `read_quota_usage` | Rate | Quota units consumed |
| `request_usage` | avg | CU usage per request |
| `quota` | Gauge | Allocated quota |
| `request_throttled_by_router_capacity` | Rate | Throttled at router level |

### Response Metrics

| Metric | Type | Description |
|--------|------|-------------|
| `response` | Rate | Responses sent |
| `response_waiting_time` | avg, max | Time waiting for storage node response |
| `decompression_time` | avg, max | Response decompression time |

### Miscellaneous

| Metric | Type | Description |
|--------|------|-------------|
| `find_unhealthy_host_request` | Rate | Requests that found unhealthy hosts |
| `unavailable_replica_streaming_request` | Rate | Streaming requests with no replica |
| `multi_get_fallback` | Rate | Multi-get fallback events |
| `meta_store_shadow_read` | Rate | Meta store shadow reads |
| `error_retry_attempt_triggered_by_pending_request_check` | Rate | Retries from pending request check |

## Per-Route Metrics

**Class:** `RouteHttpStats` / `RouteHttpRequestStats`

### Per-Storage-Node Metrics

| Metric | Type | Description |
|--------|------|-------------|
| `pending_request` | Gauge | Current pending requests to node |
| `finished_request` | Rate | Completed requests to node |
| `response_waiting_time` | avg, p99 | Response time per node |
| `unhealthy_queue_duration` | avg | Time node is unhealthy |
| `request_key_size` | avg | Key size per node |
| `response_size` | avg | Response size per node |

## Scatter-Gather Metrics

**Class:** `ScatterGatherStats` / `AggScatterGatherStats`

| Metric | Type | Description |
|--------|------|-------------|
| `total_retries` | Count | Total retry attempts |
| `total_retried_keys` | Count | Keys involved in retries |
| `total_retries_discarded` | Count | Retries that lost to original |
| `total_retries_winner` | Count | Retries that won |
| `total_retries_error` | Count | Retries that errored |

## Security Metrics

**Class:** `SecurityStats`

| Metric | Type | Description |
|--------|------|-------------|
| `live_connection_count` | Gauge | Current active connections |
| `rejected_connection_count` | Rate | Rejected connections (limit exceeded) |
| `ssl_handshake_success` | Rate | Successful SSL handshakes |
| `ssl_handshake_failure` | Rate | Failed SSL handshakes |
| `ssl_handshake_latency` | avg, max | SSL handshake time |

## Health Check Metrics

**Class:** `HealthCheckStats`

| Metric | Type | Description |
|--------|------|-------------|
| `health_check_request` | Rate | Health check requests received |

## Throttle Metrics

**Class:** `RouterThrottleStats`

| Metric | Type | Description |
|--------|------|-------------|
| `router_throttled_request` | Rate | Requests throttled by early throttler |
| `router_allowed_request` | Rate | Requests allowed by early throttler |

## Host Health Metrics

**Class:** `AggHostHealthStats`

| Metric | Type | Description |
|--------|------|-------------|
| `unhealthy_host_count` | Gauge | Number of unhealthy hosts |
| `pending_request_count` | Gauge | Total pending requests |
| `leaked_pending_request_count` | Rate | Leaked (orphaned) pending requests |
| `host_heartbeat_failure` | Rate | Heartbeat failures per host |

## Stale Version Metrics

**Class:** `StaleVersionStats`

| Metric | Type | Description |
|--------|------|-------------|
| `stale_version_request` | Rate | Requests for stale versions |

## Admin Operations Metrics

**Class:** `AdminOperationsStats`

| Metric | Type | Description |
|--------|------|-------------|
| `admin_request` | Rate | Admin API requests |
| `read_quota_throttle_enabled` | Gauge | Throttling enabled status |

## In-Flight Request Metrics

**Class:** `InFlightRequestStat`

| Metric | Type | Description |
|--------|------|-------------|
| `in_flight_request` | Gauge | Current in-flight requests |
| `in_flight_request_rate` | Rate | In-flight request rate |

## Dictionary Metrics

**Class:** `DictionaryRetrievalStats`

| Metric | Type | Description |
|--------|------|-------------|
| `dictionary_fetch_request` | Rate | Dictionary fetch requests |
| `dictionary_fetch_success` | Rate | Successful dictionary fetches |
| `dictionary_fetch_failure` | Rate | Failed dictionary fetches |
| `dictionary_fetch_latency` | avg, max | Dictionary fetch time |

## JVM Metrics

**Class:** `VeniceJVMStats`

| Metric | Type | Description |
|--------|------|-------------|
| `jvm.heap.used` | Gauge | Heap memory used |
| `jvm.heap.max` | Gauge | Max heap memory |
| `jvm.gc.count` | Rate | GC count |
| `jvm.gc.time` | Rate | GC time |
| `jvm.threads.count` | Gauge | Thread count |

## Metric Naming Convention

Metrics follow this naming pattern:

```
{store_name}--{request_type}--{metric_name}     # Per-store, per-type
total--{request_type}--{metric_name}            # Total, per-type
{store_name}--{metric_name}                     # Per-store (non-type-specific)
total--{metric_name}                            # Total (non-type-specific)
```

**Examples:**
```
my_store--single_get--healthy_request_latency.Avg
total--multi_get--retry_count.Rate
my_store--quota
total--in_flight_request.Gauge
```

## Request Types

Metrics are segmented by request type:

| Type | Description |
|------|-------------|
| `single_get` | Single key lookup |
| `multi_get` | Batch key lookup |
| `multi_get_streaming` | Streaming batch lookup |
| `compute` | Read-compute |
| `compute_streaming` | Streaming compute |

## Metric Types Reference

| Type | Description |
|------|-------------|
| `Rate` | Events per second |
| `Avg` | Rolling average |
| `Max` | Rolling maximum |
| `Min` | Rolling minimum |
| `Count` | Cumulative count |
| `Gauge` | Current value |
| `p50, p90, p95, p99` | Percentiles |

## Key Operational Metrics

### Latency Health

```
healthy_request_latency.99thPercentile < 50ms    # Target
healthy_request_latency.Avg < 10ms               # Target
```

### Error Rates

```
unhealthy_request / request < 0.01               # <1% error rate
throttled_request / request < 0.05               # <5% throttle rate
```

### Retry Efficiency

```
allowed_retry_count / retry_count > 0.5          # >50% retry success
error_retry_count / request < 0.05               # <5% error retries
```

### Capacity

```
pending_request < max_pending_request * 0.8      # <80% capacity
in_flight_request_rate < expected_throughput     # Within capacity
```

## Enabling Additional Metrics

### Key-Value Profiling

Enable detailed key/value size metrics:

```properties
key.value.profiling.enabled=true
```

### Per-Store Metrics

By default, non-streaming multi-get per-store stats are disabled:

```java
// Only total stats are reported for MULTI_GET to reduce metric cardinality
isStoreStatsEnabled = !RequestType.MULTI_GET.equals(requestType);
```

### Metric Cleanup

Auto-unregister metrics for deleted stores:

```properties
unregister.metric.for.deleted.store.enabled=true
```
