# Venice Thin Client Metrics Reference

This document provides a comprehensive reference for all metrics emitted by the Venice Thin Client. These metrics are essential for monitoring client health, performance, and debugging issues.

## Table of Contents

- [Overview](#overview)
- [Metric Frameworks](#metric-frameworks)
- [Metric Dimensions](#metric-dimensions)
- [Request Metrics](#request-metrics)
- [Latency Metrics](#latency-metrics)
- [Key Count Metrics](#key-count-metrics)
- [Retry Metrics](#retry-metrics)
- [Streaming Metrics](#streaming-metrics)
- [Timeout Metrics](#timeout-metrics)
- [Metric Classes Reference](#metric-classes-reference)
- [Common Alerting Patterns](#common-alerting-patterns)

---

## Overview

Venice Thin Client emits metrics through two systems:
- **Tehuti**: Legacy metrics framework (default)
- **OpenTelemetry**: Modern observability standard (when enabled)

Metrics are organized by store name and request type:
```
venice.client.<store_name>.<request_type>.<metric_name>
```

---

## Metric Frameworks

### Tehuti Metrics

Tehuti metrics are registered with a MetricsRepository and reported to configured reporters (e.g., JMX, InGraphs).

```
Metric naming: <prefix>.<store_name>.<request_type>--<metric_name>.<stat>
Example:       venice.client.myStore.single_get--healthy_request.OccurrenceRate
```

### OpenTelemetry Metrics

When enabled, OTel metrics use standard semantic conventions with dimensions/attributes.

```
Metric naming: venice.client.<metric_name>
Dimensions:    venice.store.name, venice.request.method, http.response.status_code, etc.
```

---

## Metric Dimensions

| Dimension | Description | Example Values |
|-----------|-------------|----------------|
| `venice.store.name` | Store name | `myStore`, `userFeatures` |
| `venice.request.method` | Request type | `SINGLE_GET`, `MULTI_GET`, `COMPUTE` |
| `http.response.status_code` | HTTP status | `200`, `404`, `500`, `503` |
| `http.response.status_code.category` | Status category | `2XX`, `4XX`, `5XX` |
| `venice.response.status_code.category` | Venice status | `SUCCESS`, `FAIL` |
| `venice.request.retry_type` | Retry type | `ERROR_RETRY` |
| `venice.stream.progress` | Streaming progress | `FIRST`, `PCT_50`, `PCT_90` |

---

## Request Metrics

### Primary Request Counts

| Metric | Type | Description |
|--------|------|-------------|
| `request` | Rate | Total requests per second |
| `healthy_request` | Rate | Successful requests per second |
| `unhealthy_request` | Rate | Failed requests per second |
| `success_request_ratio` | Ratio | Ratio of healthy to total requests |

### Request Classification

Requests are classified based on HTTP response status:

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                     Request Classification                                   │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│                         ┌────────────────┐                                   │
│                         │ Request Result │                                   │
│                         └───────┬────────┘                                   │
│                                 │                                            │
│              ┌──────────────────┼──────────────────┐                        │
│              │                  │                  │                         │
│              v                  v                  v                         │
│       ┌────────────┐     ┌────────────┐    ┌────────────┐                   │
│       │  Success   │     │   Error    │    │  Not Found │                   │
│       │ (200 OK)   │     │ (4XX/5XX)  │    │   (404)    │                   │
│       └─────┬──────┘     └─────┬──────┘    └─────┬──────┘                   │
│             │                  │                  │                          │
│             v                  v                  v                          │
│       healthy_request    unhealthy_request  healthy_request                 │
│       (keyCount > 0)                        (keyCount = 0)                  │
│                                                                              │
│  Note: 404 (key not found) is considered healthy in Venice                  │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### HTTP Status Tracking

| Metric | Type | Description |
|--------|------|-------------|
| `http_200_request` | Rate | 200 OK responses |
| `http_404_request` | Rate | 404 Not Found responses |
| `http_500_request` | Rate | 500 Internal Server Error |
| `http_503_request` | Rate | 503 Service Unavailable |

**Recording**: Dynamic sensors created per HTTP status code encountered.

---

## Latency Metrics

### Primary Latency

| Metric | Type | Unit | Description |
|--------|------|------|-------------|
| `healthy_request_latency` | Histogram | ms | End-to-end latency for successful requests |
| `unhealthy_request_latency` | Histogram | ms | End-to-end latency for failed requests |

### Latency Breakdown

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                          Latency Breakdown                                   │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  Total Request Latency (healthy_request_latency / unhealthy_request_latency)│
│  ├──────────────────────────────────────────────────────────────────────── │
│  │                                                                          │
│  │  ┌────────────────────┐  ┌──────────────────────────────────────────┐   │
│  │  │ request_           │  │ request_submission_to_response_          │   │
│  │  │ serialization_time │  │ handling_time                            │   │
│  │  │                    │  │                                          │   │
│  │  │ Serialize keys     │  │ Network round-trip + Router + Server    │   │
│  │  │ to Avro bytes      │  │ processing                               │   │
│  │  └────────────────────┘  └──────────────────────────────────────────┘   │
│  │                                                                          │
│  │  ┌────────────────────┐  ┌────────────────────┐                        │
│  │  │ response_          │  │ response_          │                        │
│  │  │ decompression_time │  │ deserialization_   │                        │
│  │  │                    │  │ time               │                        │
│  │  │ Decompress if      │  │ Deserialize bytes  │                        │
│  │  │ compressed         │  │ to Avro records    │                        │
│  │  └────────────────────┘  └────────────────────┘                        │
│  │                                                                          │
│  └──────────────────────────────────────────────────────────────────────── │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

| Metric | Type | Unit | Description |
|--------|------|------|-------------|
| `request_serialization_time` | Histogram | ms | Time to serialize request keys |
| `request_submission_to_response_handling_time` | Histogram | ms | Network + server processing time |
| `response_decompression_time` | Histogram | ms | Time to decompress response |
| `response_deserialization_time` | Histogram | ms | Time to deserialize response |

### Histogram Statistics

Each histogram metric provides:

| Statistic | Description |
|-----------|-------------|
| `Avg` | Average value |
| `Max` | Maximum value |
| `50thPercentile` | Median (P50) |
| `95thPercentile` | 95th percentile |
| `99thPercentile` | 99th percentile |
| `999thPercentile` | 99.9th percentile |

---

## Key Count Metrics

### Request/Response Key Counts

| Metric | Type | Description |
|--------|------|-------------|
| `request.key_count` | Histogram | Number of keys in the request |
| `response.key_count` | Histogram | Number of keys in successful response |
| `success_request_key_ratio` | Ratio | Ratio of response keys to request keys |
| `success_request_duplicate_key_count` | Rate | Duplicate keys in response |

### Key Count Statistics

| Statistic | Description |
|-----------|-------------|
| `Rate` | Keys per second |
| `Avg` | Average keys per request |
| `Max` | Maximum keys per request |

---

## Retry Metrics

### Retry Counts

| Metric | Type | Description |
|--------|------|-------------|
| `request_retry_count` | Rate | Error-triggered retries per second |
| `retry.request.key_count` | Histogram | Keys in retry requests |
| `retry.request.success_key_count` | Histogram | Keys completed via retry |
| `retry_key_success_ratio` | Ratio | Retry success rate |

### Retry Flow

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           Retry Flow                                         │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  ┌─────────────────┐                                                        │
│  │ Original Request│                                                        │
│  │   (N keys)      │                                                        │
│  └────────┬────────┘                                                        │
│           │                                                                  │
│           v                                                                  │
│  ┌─────────────────┐      ┌─────────────────┐                               │
│  │   Success?      │──No─►│  Error Response │                               │
│  └────────┬────────┘      └────────┬────────┘                               │
│          Yes                       │                                         │
│           │                        │ request_retry_count++                   │
│           v                        v                                         │
│  ┌─────────────────┐      ┌─────────────────┐                               │
│  │  Return Values  │      │  Retry Request  │                               │
│  │                 │      │  (M failed keys)│                               │
│  └─────────────────┘      └────────┬────────┘                               │
│                                    │ retry.request.key_count += M           │
│                                    v                                         │
│                           ┌─────────────────┐                               │
│                           │   Retry Success │                               │
│                           │   (K keys)      │                               │
│                           └────────┬────────┘                               │
│                                    │ retry.request.success_key_count += K   │
│                                    v                                         │
│                           ┌─────────────────┐                               │
│                           │  Merge Results  │                               │
│                           └─────────────────┘                               │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## Streaming Metrics

For streaming batch get operations, these metrics track progress:

### Time to Receive Metrics

| Metric | Type | Unit | Description |
|--------|------|------|-------------|
| `response_ttfr` | Histogram | ms | Time to First Record |
| `response_tt50pr` | Histogram | ms | Time to 50th Percentile Record |
| `response_tt90pr` | Histogram | ms | Time to 90th Percentile Record |

### Streaming Timeline

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                     Streaming Response Timeline                              │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  Time ────────────────────────────────────────────────────────────────────► │
│                                                                              │
│  Request   First      50th         90th           Last                      │
│   Start    Record   Percentile   Percentile      Record                     │
│     │        │          │            │              │                        │
│     ▼        ▼          ▼            ▼              ▼                        │
│  ───┼────────┼──────────┼────────────┼──────────────┼───                    │
│     │        │          │            │              │                        │
│     │◄──────►│          │            │              │                        │
│     │  TTFR  │          │            │              │                        │
│     │        │          │            │              │                        │
│     │◄─────────────────►│            │              │                        │
│     │      TT50PR       │            │              │                        │
│     │                   │            │              │                        │
│     │◄──────────────────────────────►│              │                        │
│     │            TT90PR              │              │                        │
│     │                                │              │                        │
│     │◄─────────────────────────────────────────────►│                        │
│     │              Total Latency                    │                        │
│                                                                              │
│  Metrics:                                                                   │
│    • response_ttfr    - Time to first record                                │
│    • response_tt50pr  - Time to 50th percentile record                      │
│    • response_tt90pr  - Time to 90th percentile record                      │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## Timeout Metrics

### Client-Side Timeout Tracking

| Metric | Type | Unit | Description |
|--------|------|------|-------------|
| `app_timed_out_request` | Rate | count/s | Requests that timed out at client |
| `app_timed_out_request_result_ratio` | Histogram | ratio | Partial results received before timeout |
| `client_future_timeout` | Histogram | ms | Configured timeout value |

### Timeout Context

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                          Timeout Tracking                                    │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  // Application code                                                        │
│  client.streamingBatchGet(keys).get(timeout, unit);                         │
│                                                                              │
│  If the operation times out:                                                │
│    • app_timed_out_request is incremented                                   │
│    • app_timed_out_request_result_ratio records                             │
│      (keys_received / keys_requested)                                       │
│    • client_future_timeout records the timeout value                        │
│                                                                              │
│  Note: This is client-side timeout, not D2/HTTP timeout                     │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## Metric Classes Reference

| Class | Location | Scope |
|-------|----------|-------|
| `BasicClientStats` | `clients/venice-thin-client/.../stats/` | Base metrics shared by all clients |
| `ClientStats` | Same directory | Extended thin client metrics |
| `BasicClientMetricEntity` | Same directory | OTel metric definitions (base) |
| `ClientMetricEntity` | Same directory | OTel metric definitions (extended) |

### Metric Entity Definitions

| Entity | Metric Name | Type | Dimensions |
|--------|-------------|------|------------|
| `CALL_COUNT` | `call_count` | Counter | store, method, http_status, category |
| `CALL_TIME` | `call_time` | Histogram | store, method, http_status, category |
| `REQUEST_KEY_COUNT` | `request.key_count` | Histogram | store, method |
| `RESPONSE_KEY_COUNT` | `response.key_count` | Histogram | store, method |
| `RETRY_CALL_COUNT` | `retry.call_count` | Counter | store, method, retry_type |
| `REQUEST_SERIALIZATION_TIME` | `request.serialization_time` | Histogram | store, method |
| `RESPONSE_DESERIALIZATION_TIME` | `response.deserialization_time` | Histogram | store, method |
| `RESPONSE_BATCH_STREAM_PROGRESS_TIME` | `response.batch_stream_progress_time` | Histogram | store, method, progress |
| `REQUEST_TIMEOUT_COUNT` | `request.timeout.count` | Counter | store, method |
| `REQUEST_TIMEOUT_PARTIAL_RESPONSE_RATIO` | `request.timeout.partial_response_ratio` | Histogram | store, method |

---

## Common Alerting Patterns

### High Error Rate

```
Alert: unhealthy_request rate > threshold
Metric: unhealthy_request (OccurrenceRate)
Dimensions: venice.store.name
Action: Check Router/Server health, investigate 5XX errors
```

### High Latency

```
Alert: healthy_request_latency.99thPercentile > threshold_ms
Metric: healthy_request_latency (Histogram)
Dimensions: venice.store.name, venice.request.method
Action: Check request_submission_to_response_handling_time breakdown
```

### Low Success Ratio

```
Alert: success_request_ratio < threshold
Metric: success_request_ratio
Dimensions: venice.store.name
Action: Compare request.key_count vs response.key_count
```

### High Retry Rate

```
Alert: request_retry_count rate > threshold
Metric: request_retry_count (OccurrenceRate)
Dimensions: venice.store.name
Action: Check server health, investigate transient failures
```

### Client Timeouts

```
Alert: app_timed_out_request rate > 0
Metric: app_timed_out_request (OccurrenceRate)
Dimensions: venice.store.name
Action: Check client timeout configuration, investigate slow requests
```

### Streaming Performance

```
Alert: response_ttfr.avg > threshold_ms
Metric: response_ttfr (Histogram)
Dimensions: venice.store.name
Action: Check Router/Server health, network latency
```

---

## Summary Statistics

| Category | Metric Count |
|----------|--------------|
| Request Metrics | 6 |
| Latency Metrics | 6 |
| Key Count Metrics | 4 |
| Retry Metrics | 4 |
| Streaming Metrics | 3 |
| Timeout Metrics | 3 |
| **Total** | **26** |

---

## See Also

- [Thin Client Architecture](thin_client_architecture.md) - Architecture documentation
- [Fast Client Metrics](fast_client_metrics.md) - Additional metrics in Fast Client
- [Router Metrics](../router_metrics.md) - Server-side routing metrics
- [Clients Overview](clients_overview.md) - Client comparison
