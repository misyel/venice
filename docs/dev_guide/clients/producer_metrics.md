# Venice Producer Metrics Reference

This document provides a comprehensive reference for all metrics emitted by the Venice Producer. These metrics are essential for monitoring write operations, throughput, latency, and error rates.

## Table of Contents

- [Overview](#overview)
- [Operation Count Metrics](#operation-count-metrics)
- [Success/Failure Metrics](#successfailure-metrics)
- [Latency Metrics](#latency-metrics)
- [Pending Operation Metrics](#pending-operation-metrics)
- [Metric Classes Reference](#metric-classes-reference)
- [Common Alerting Patterns](#common-alerting-patterns)

---

## Overview

Venice Producer emits metrics through Tehuti framework:

```
Metric naming: <store_name>--<metric_name>.<stat>
Example:       myStore--write_operation.OccurrenceRate
```

### Metrics Summary

| Category | Key Metrics |
|----------|-------------|
| **Operations** | `write_operation`, `put_operation`, `delete_operation`, `update_operation` |
| **Results** | `success_write_operation`, `failed_write_operation` |
| **Latency** | `produce_to_durable_buffer_latency`, `preprocessing_latency` |
| **Pending** | `pending_write_operation` |

---

## Operation Count Metrics

### Total Operations

| Metric | Type | Description |
|--------|------|-------------|
| `write_operation` | Rate | Total write operations (put + delete + update) |

### Operation by Type

| Metric | Type | Description |
|--------|------|-------------|
| `put_operation` | Rate | Put operations per second |
| `delete_operation` | Rate | Delete operations per second |
| `update_operation` | Rate | Update (write compute) operations per second |

### Operation Flow

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                      Operation Metrics Recording                                 │
├─────────────────────────────────────────────────────────────────────────────────┤
│                                                                                  │
│  producer.put(key, value)                                                       │
│     │                                                                           │
│     │ write_operation++ (total)                                                 │
│     │ put_operation++ (type-specific)                                           │
│     │                                                                           │
│     v                                                                           │
│  ┌───────────────────────────────────────────────────────────────┐              │
│  │                 Request Processing                             │              │
│  └───────────────────────────────────────────────────────────────┘              │
│                                                                                  │
│  producer.delete(key)                                                           │
│     │                                                                           │
│     │ write_operation++ (total)                                                 │
│     │ delete_operation++ (type-specific)                                        │
│     │                                                                           │
│     v                                                                           │
│  ...                                                                            │
│                                                                                  │
│  producer.update(key, ops)                                                      │
│     │                                                                           │
│     │ write_operation++ (total)                                                 │
│     │ update_operation++ (type-specific)                                        │
│     │                                                                           │
│     v                                                                           │
│  ...                                                                            │
│                                                                                  │
└─────────────────────────────────────────────────────────────────────────────────┘
```

---

## Success/Failure Metrics

### Result Metrics

| Metric | Type | Description |
|--------|------|-------------|
| `success_write_operation` | Rate | Successfully completed operations |
| `failed_write_operation` | Rate | Failed operations |

### Success/Failure Flow

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                     Success/Failure Recording                                    │
├─────────────────────────────────────────────────────────────────────────────────┤
│                                                                                  │
│  Write Request                                                                  │
│     │                                                                           │
│     │ pending_write_operation++                                                 │
│     v                                                                           │
│  ┌───────────────────────────────────────────────────────────────┐              │
│  │               Kafka Produce                                    │              │
│  └───────────────────────────────────────────────────────────────┘              │
│     │                                                                           │
│     ├──────────────────────────────────────┐                                    │
│     │                                      │                                     │
│     v                                      v                                     │
│  ┌──────────────────┐             ┌──────────────────┐                          │
│  │ ACK Received     │             │ Error/Timeout    │                          │
│  │ (success)        │             │ (failure)        │                          │
│  └────────┬─────────┘             └────────┬─────────┘                          │
│           │                                │                                     │
│           │ success_write_operation++      │ failed_write_operation++           │
│           │ pending_write_operation--      │ pending_write_operation--          │
│           v                                v                                     │
│  ┌──────────────────┐             ┌──────────────────┐                          │
│  │ Complete future  │             │ Complete future  │                          │
│  │ normally         │             │ exceptionally    │                          │
│  └──────────────────┘             └──────────────────┘                          │
│                                                                                  │
└─────────────────────────────────────────────────────────────────────────────────┘
```

### Calculating Success Rate

```
success_rate = success_write_operation / write_operation

// In monitoring:
// success_rate should be > 0.999 (99.9%) in healthy system
```

---

## Latency Metrics

### Latency Breakdown

| Metric | Type | Unit | Description |
|--------|------|------|-------------|
| `preprocessing_latency` | Histogram | ms | Time to preprocess (serialize, validate) |
| `produce_to_durable_buffer_latency` | Histogram | ms | Time for Kafka ACK |

### Latency Flow

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                       Latency Breakdown                                          │
├─────────────────────────────────────────────────────────────────────────────────┤
│                                                                                  │
│  Application Call: producer.put(key, value)                                     │
│     │                                                                           │
│     │ ┌────────────────────────────────────────────────────────────────────┐   │
│     │ │                  preprocessing_latency                              │   │
│     │ │                                                                     │   │
│     │ │  • Validate key/value                                               │   │
│     │ │  • Serialize to Avro                                                │   │
│     │ │  • Compress (if enabled)                                            │   │
│     │ │  • Set logical timestamp                                            │   │
│     │ └────────────────────────────────────────────────────────────────────┘   │
│     │                                                                           │
│     │ ┌────────────────────────────────────────────────────────────────────┐   │
│     │ │             produce_to_durable_buffer_latency                       │   │
│     │ │                                                                     │   │
│     │ │  • Send to Kafka                                                    │   │
│     │ │  • Wait for broker replication                                      │   │
│     │ │  • Receive ACK                                                      │   │
│     │ └────────────────────────────────────────────────────────────────────┘   │
│     │                                                                           │
│     v                                                                           │
│  CompletableFuture completes                                                    │
│                                                                                  │
│  Total latency ≈ preprocessing_latency + produce_to_durable_buffer_latency     │
│                                                                                  │
└─────────────────────────────────────────────────────────────────────────────────┘
```

### Histogram Percentiles

Each latency histogram provides:

| Statistic | Description |
|-----------|-------------|
| `50thPercentile` | Median latency |
| `95thPercentile` | 95th percentile |
| `99thPercentile` | 99th percentile |
| `999thPercentile` | 99.9th percentile |
| `9999thPercentile` | 99.99th percentile |

---

## Pending Operation Metrics

### Pending Operations

| Metric | Type | Description |
|--------|------|-------------|
| `pending_write_operation` | Min/Max | Current in-flight operations |

### Pending Operation Behavior

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                    Pending Operations Over Time                                  │
├─────────────────────────────────────────────────────────────────────────────────┤
│                                                                                  │
│  pending_write_operation                                                        │
│        │                                                                         │
│     50 │                    *                                                   │
│        │                   * *     *                                            │
│     40 │              *   *   *   * *                                           │
│        │         *   * * *       *   *                                          │
│     30 │    *   * * *                 *   *                                     │
│        │   * * *                       * * *   *                                │
│     20 │  *                                 * * * *                             │
│        │ *                                       * *                            │
│     10 │*                                          *                            │
│        │                                                                         │
│      0 └────────────────────────────────────────────────────────────────────    │
│         Time ──────────────────────────────────────────────────────────────►    │
│                                                                                  │
│  Healthy: Low, stable count                                                     │
│  Warning: Growing trend (Kafka may be slow)                                     │
│  Critical: Very high count (backpressure, possible OOM)                         │
│                                                                                  │
└─────────────────────────────────────────────────────────────────────────────────┘
```

### Statistics

| Statistic | Description |
|-----------|-------------|
| `Min` | Minimum pending operations observed |
| `Max` | Maximum pending operations observed |

---

## Metric Classes Reference

| Class | Location | Scope |
|-------|----------|-------|
| `VeniceProducerMetrics` | `clients/venice-producer/.../producer/` | All producer metrics |
| `AbstractVeniceStats` | `internal/venice-common/.../stats/` | Base stats class |

### Metric Registration

```java
// From VeniceProducerMetrics.java

// Operation sensors
operationSensor = registerSensor("write_operation", new OccurrenceRate());
putOperationSensor = registerSensor("put_operation", new OccurrenceRate());
deleteOperationSensor = registerSensor("delete_operation", new OccurrenceRate());
updateOperationSensor = registerSensor("update_operation", new OccurrenceRate());

// Success/failure sensors
successOperationSensor = registerSensor("success_write_operation", new OccurrenceRate());
failedOperationSensor = registerSensor("failed_write_operation", new OccurrenceRate());

// Latency sensors
produceLatencySensor = registerSensor("produce_to_durable_buffer_latency",
    TehutiUtils.getPercentileStat(...));
preprocessingLatencySensor = registerSensor("preprocessing_latency",
    TehutiUtils.getPercentileStat(...));

// Pending operations
pendingOperationSensor = registerSensor("pending_write_operation", new Min(), new Max());
```

---

## Common Alerting Patterns

### High Failure Rate

```
Alert: failed_write_operation rate > threshold OR
       success_rate < 0.999

Metrics:
  - failed_write_operation (OccurrenceRate)
  - success_write_operation (OccurrenceRate)
  - write_operation (OccurrenceRate)

Calculation:
  failure_rate = failed_write_operation / write_operation

Action:
  - Check Kafka broker health
  - Check network connectivity
  - Review producer logs for specific errors
```

### High Write Latency

```
Alert: produce_to_durable_buffer_latency.99thPercentile > threshold_ms

Metric: produce_to_durable_buffer_latency (Histogram)

Typical thresholds:
  - P50: < 10ms (healthy)
  - P99: < 100ms (acceptable)
  - P99: > 500ms (investigate)

Action:
  - Check Kafka broker load
  - Check network latency
  - Review batch.size and linger.ms settings
```

### Growing Pending Operations

```
Alert: pending_write_operation.Max > threshold AND
       growing trend over time

Metric: pending_write_operation (Min/Max gauge)

Typical thresholds:
  - Normal: < 100
  - Warning: > 500
  - Critical: > 1000

Action:
  - Check Kafka connectivity
  - Reduce write rate (backpressure)
  - Increase producer buffer.memory
  - Check for slow consumers causing broker backpressure
```

### Operation Type Imbalance

```
Alert: update_operation rate significantly different from expected

Metrics:
  - put_operation (OccurrenceRate)
  - delete_operation (OccurrenceRate)
  - update_operation (OccurrenceRate)

Action:
  - Verify application logic
  - Check for unexpected delete storms
  - Validate write compute usage
```

### Preprocessing Bottleneck

```
Alert: preprocessing_latency.99thPercentile > threshold_ms

Metric: preprocessing_latency (Histogram)

Typical thresholds:
  - Normal: < 5ms
  - Warning: > 20ms
  - Critical: > 100ms

Action:
  - Check serialization complexity
  - Review value sizes
  - Consider schema optimization
```

---

## Dashboard Recommendations

### Key Panels

| Panel | Metrics | Purpose |
|-------|---------|---------|
| **Write Throughput** | `write_operation` | Overall write rate |
| **Operation Breakdown** | `put_operation`, `delete_operation`, `update_operation` | Operation mix |
| **Success Rate** | `success_write_operation / write_operation` | Health indicator |
| **Latency** | `produce_to_durable_buffer_latency` percentiles | Performance |
| **Pending Operations** | `pending_write_operation.Max` | Backpressure |
| **Error Rate** | `failed_write_operation` | Failure detection |

### Example Queries (PromQL-style)

```promql
# Success rate
sum(rate(success_write_operation[5m])) / sum(rate(write_operation[5m]))

# P99 latency
histogram_quantile(0.99, rate(produce_to_durable_buffer_latency_bucket[5m]))

# Pending operations trend
max_over_time(pending_write_operation_max[1h])
```

---

## Summary Statistics

| Category | Metric Count |
|----------|--------------|
| Operation Count | 4 |
| Success/Failure | 2 |
| Latency | 2 |
| Pending | 1 |
| **Total** | **9** |

---

## See Also

- [Producer Architecture](producer_architecture.md) - Architecture documentation
- [Da-Vinci Metrics](da_vinci_metrics.md) - Consumer-side metrics
- [Clients Overview](clients_overview.md) - Client comparison
