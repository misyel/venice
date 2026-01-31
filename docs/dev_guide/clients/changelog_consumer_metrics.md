# Venice Changelog Consumer Metrics Reference

This document provides a comprehensive reference for all metrics emitted by the Venice Changelog Consumer (CDC client).

## Table of Contents

- [Overview](#overview)
- [Poll Metrics](#poll-metrics)
- [Records Consumed Metrics](#records-consumed-metrics)
- [Version Metrics](#version-metrics)
- [Heartbeat/Lag Metrics](#heartbeatlag-metrics)
- [Chunked Record Metrics](#chunked-record-metrics)
- [Metric Classes Reference](#metric-classes-reference)
- [Common Alerting Patterns](#common-alerting-patterns)

---

## Overview

Venice Changelog Consumer emits metrics through both Tehuti and OpenTelemetry frameworks.

```
Metric naming: <consumer_name>--<metric_name>.<stat>
Example:       myStore_consumer--poll_success_count.Rate
```

### Metrics Summary

| Category | Key Metrics |
|----------|-------------|
| **Poll Operations** | `poll_success_count`, `poll_fail_count` |
| **Records** | `records_consumed` |
| **Version** | `minimum_consuming_version`, `maximum_consuming_version` |
| **Lag** | `max_partition_lag` (heartbeat delay) |
| **Version Swap** | `version_swap_success_count`, `version_swap_fail_count` |
| **Chunking** | `chunked_record_success_count`, `chunked_record_fail_count` |

### Dimensions

All metrics include these base dimensions:

| Dimension | Description |
|-----------|-------------|
| `VENICE_STORE_NAME` | The store being consumed |

Status-based metrics also include:

| Dimension | Description |
|-----------|-------------|
| `VENICE_RESPONSE_STATUS_CODE_CATEGORY` | `SUCCESS` or `FAIL` |

---

## Poll Metrics

Poll metrics track the success and failure rate of `poll()` operations.

### Poll Count Metrics

| Metric | Type | Unit | Statistics | Description |
|--------|------|------|------------|-------------|
| `poll_success_count` | Counter | Number | Rate | Successful poll operations per second |
| `poll_fail_count` | Counter | Number | Rate | Failed poll operations per second |

### Poll Flow

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                           Poll Metrics Recording                                 │
├─────────────────────────────────────────────────────────────────────────────────┤
│                                                                                  │
│  consumer.poll(timeoutMs)                                                       │
│     │                                                                           │
│     │                                                                           │
│     ├──────────────────────────────────────┐                                    │
│     │                                      │                                    │
│     v                                      v                                    │
│  ┌──────────────────────┐         ┌──────────────────────┐                     │
│  │   Success            │         │   Failure            │                     │
│  │   (records returned) │         │   (exception thrown) │                     │
│  └──────────┬───────────┘         └──────────┬───────────┘                     │
│             │                                │                                  │
│             │ poll_success_count++           │ poll_fail_count++               │
│             │ records_consumed += N          │                                  │
│             v                                v                                  │
│  ┌──────────────────────┐         ┌──────────────────────┐                     │
│  │ Return Collection    │         │ Throw Exception      │                     │
│  │ of ChangeEvents      │         │ to caller            │                     │
│  └──────────────────────┘         └──────────────────────┘                     │
│                                                                                  │
└─────────────────────────────────────────────────────────────────────────────────┘
```

### Calculating Poll Success Rate

```
poll_success_rate = poll_success_count / (poll_success_count + poll_fail_count)

// In monitoring:
// success_rate should be > 0.99 (99%) in healthy system
```

---

## Records Consumed Metrics

Tracks the number of records consumed per poll operation.

### Records Consumed

| Metric | Type | Unit | Statistics | Description |
|--------|------|------|------------|-------------|
| `records_consumed` | Counter | Number | Avg, Max, Rate | Records consumed per poll |

### Statistics Breakdown

| Statistic | Tehuti Name | Description |
|-----------|-------------|-------------|
| Avg | `records_consumed.Avg` | Average records per poll |
| Max | `records_consumed.Max` | Maximum records in a single poll |
| Rate | `records_consumed.Rate` | Records consumed per second |

### Throughput Calculation

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                        Records Throughput Analysis                               │
├─────────────────────────────────────────────────────────────────────────────────┤
│                                                                                  │
│  records_consumed.Rate = records per second                                     │
│                                                                                  │
│  Example metrics over time:                                                     │
│                                                                                  │
│  records_consumed                                                               │
│        │                                                                        │
│  5000  │        *                                                               │
│        │       * *     *                                                        │
│  4000  │      *   *   * *                                                       │
│        │     *     * *   *                                                      │
│  3000  │    *             *    *                                                │
│        │   *               *  * *                                               │
│  2000  │  *                 **   *                                              │
│        │ *                        *                                             │
│  1000  │*                          *                                            │
│        └────────────────────────────────────────────────────────────────────    │
│         Time ──────────────────────────────────────────────────────────────►    │
│                                                                                  │
│  Healthy: Consistent rate matching producer output                              │
│  Warning: Declining rate (consumer falling behind)                              │
│  Critical: Zero rate with non-zero lag                                          │
│                                                                                  │
└─────────────────────────────────────────────────────────────────────────────────┘
```

---

## Version Metrics

Version metrics track which store versions the consumer is currently consuming from.

### Version Tracking Metrics

| Metric | Type | Unit | Statistics | Description |
|--------|------|------|------------|-------------|
| `minimum_consuming_version` | Gauge | Number | Current | Lowest version number being consumed |
| `maximum_consuming_version` | Gauge | Number | Current | Highest version number being consumed |

### Version State Interpretation

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                         Version State Examples                                   │
├─────────────────────────────────────────────────────────────────────────────────┤
│                                                                                  │
│  Normal Operation (single version):                                             │
│    minimum_consuming_version = 5                                                │
│    maximum_consuming_version = 5                                                │
│    → Consumer is reading from Store_v5 only                                     │
│                                                                                  │
│  During Version Transition:                                                     │
│    minimum_consuming_version = 5                                                │
│    maximum_consuming_version = 6                                                │
│    → Consumer is transitioning from v5 to v6                                    │
│    → Some partitions still on v5, others moved to v6                            │
│                                                                                  │
│  After Version Swap Complete:                                                   │
│    minimum_consuming_version = 6                                                │
│    maximum_consuming_version = 6                                                │
│    → All partitions now on Store_v6                                             │
│                                                                                  │
└─────────────────────────────────────────────────────────────────────────────────┘
```

### Version Swap Metrics

| Metric | Type | Unit | Statistics | Description |
|--------|------|------|------------|-------------|
| `version_swap_success_count` | Up/Down Counter | Number | Total | Successful version transitions |
| `version_swap_fail_count` | Up/Down Counter | Number | Total | Failed version transitions |

### Version Swap Flow

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                       Version Swap Metrics Recording                             │
├─────────────────────────────────────────────────────────────────────────────────┤
│                                                                                  │
│  Version swap detected                                                          │
│     │                                                                           │
│     v                                                                           │
│  ┌───────────────────────────────────────────────────────────────┐              │
│  │ Attempt to transition to new version                          │              │
│  │  • Subscribe to new version topic                             │              │
│  │  • Seek to appropriate position                               │              │
│  │  • Update internal state                                      │              │
│  └───────────────────────────────────────────────────────────────┘              │
│     │                                                                           │
│     ├──────────────────────────────────────┐                                    │
│     │                                      │                                    │
│     v                                      v                                    │
│  ┌──────────────────────┐         ┌──────────────────────┐                     │
│  │   Success            │         │   Failure            │                     │
│  │   (clean transition) │         │   (exception/timeout)│                     │
│  └──────────┬───────────┘         └──────────┬───────────┘                     │
│             │                                │                                  │
│             │ version_swap_success_count++   │ version_swap_fail_count++       │
│             │ Update min/max version         │ (may retry)                      │
│             v                                v                                  │
│                                                                                  │
└─────────────────────────────────────────────────────────────────────────────────┘
```

---

## Heartbeat/Lag Metrics

Heartbeat metrics measure the delay between when events are produced and when they are consumed.

### Max Partition Lag

| Metric | Type | Unit | Statistics | Description |
|--------|------|------|------------|-------------|
| `max_partition_lag` | Aggregation | Milliseconds | Max | Maximum heartbeat delay across all partitions |

### Lag Interpretation

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                          Lag Measurement                                         │
├─────────────────────────────────────────────────────────────────────────────────┤
│                                                                                  │
│  heartbeat_delay = current_time - record_event_time                             │
│                                                                                  │
│  max_partition_lag reports the MAXIMUM delay across all subscribed partitions   │
│                                                                                  │
│  Example:                                                                       │
│                                                                                  │
│    Partition 0: lag = 100ms                                                     │
│    Partition 1: lag = 250ms                                                     │
│    Partition 2: lag = 50ms                                                      │
│                                                                                  │
│    max_partition_lag = 250ms (from partition 1)                                 │
│                                                                                  │
│                                                                                  │
│  Lag Over Time:                                                                 │
│                                                                                  │
│  max_partition_lag (ms)                                                         │
│        │                                                                        │
│  5000  │                              * Spike (backpressure)                    │
│        │                             * *                                        │
│  4000  │                            *   *                                       │
│        │                           *     *                                      │
│  3000  │                          *       *                                     │
│        │                         *         *                                    │
│  2000  │    *                   *           *                                   │
│        │   * *        *        *             *                                  │
│  1000  │  *   *      * *      *               *    *                            │
│        │ *     *    *   *    *                 *  * *  *   Normal              │
│    100 │*       *  *     *  *                   **   ** *  operation            │
│        └────────────────────────────────────────────────────────────────────    │
│         Time ──────────────────────────────────────────────────────────────►    │
│                                                                                  │
└─────────────────────────────────────────────────────────────────────────────────┘
```

### Lag Thresholds

| Threshold | Value | Interpretation |
|-----------|-------|----------------|
| Healthy | < 1 second | Consumer keeping up |
| Warning | 1-5 seconds | Consumer slightly behind |
| Critical | > 5 seconds | Consumer significantly lagging |
| Severe | > 30 seconds | Consumer needs attention |

---

## Chunked Record Metrics

Large records (>1MB) are split into chunks. These metrics track chunk reassembly.

### Chunked Record Metrics

| Metric | Type | Unit | Statistics | Description |
|--------|------|------|------------|-------------|
| `chunked_record_success_count` | Counter | Number | Rate | Successfully reassembled chunked records/sec |
| `chunked_record_fail_count` | Counter | Number | Rate | Failed chunk reassembly attempts/sec |

### Chunked Record Flow

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                      Chunked Record Processing                                   │
├─────────────────────────────────────────────────────────────────────────────────┤
│                                                                                  │
│  Large Record (>1MB) written to Venice:                                         │
│     │                                                                           │
│     v                                                                           │
│  ┌───────────────────────────────────────────────────────────────┐              │
│  │ Writer splits into chunks + manifest                          │              │
│  │                                                               │              │
│  │  Original: [─────────────────────────────────────────────]   │              │
│  │                           ↓                                   │              │
│  │  Chunks:   [Chunk 0][Chunk 1][Chunk 2][Chunk 3][Manifest]    │              │
│  └───────────────────────────────────────────────────────────────┘              │
│                              │                                                  │
│                              │ Kafka                                            │
│                              v                                                  │
│  ┌───────────────────────────────────────────────────────────────┐              │
│  │ Changelog Consumer receives chunks                            │              │
│  └───────────────────────────────────────────────────────────────┘              │
│     │                                                                           │
│     v                                                                           │
│  ┌───────────────────────────────────────────────────────────────┐              │
│  │ Chunk Assembler                                               │              │
│  │  • Collect all chunks for key                                │              │
│  │  • Read manifest for chunk count                             │              │
│  │  • Reassemble original record                                │              │
│  └───────────────────────────────────────────────────────────────┘              │
│     │                                                                           │
│     ├──────────────────────────────────────┐                                    │
│     │                                      │                                    │
│     v                                      v                                    │
│  ┌──────────────────────┐         ┌──────────────────────┐                     │
│  │   Success            │         │   Failure            │                     │
│  │   (all chunks found) │         │   (missing chunk)    │                     │
│  └──────────┬───────────┘         └──────────┬───────────┘                     │
│             │                                │                                  │
│             │ chunked_record_success_count++ │ chunked_record_fail_count++     │
│             v                                v                                  │
│  Return reassembled record         Log error, skip record                       │
│                                                                                  │
│  Note: DVRT CDC doesn't emit chunked record metrics                             │
│        (no context into chunked records)                                        │
│                                                                                  │
└─────────────────────────────────────────────────────────────────────────────────┘
```

---

## Metric Classes Reference

| Class | Location | Scope |
|-------|----------|-------|
| `BasicConsumerStats` | `clients/da-vinci-client/.../consumer/stats/` | All changelog consumer metrics |
| `AbstractVeniceStats` | `internal/venice-common/.../stats/` | Base stats class |

### Metric Registration

```java
// From BasicConsumerStats.java

// Heartbeat/lag metric
heartBeatDelayMetric = MetricEntityStateBase.create(
    BasicConsumerMetricEntity.HEART_BEAT_DELAY.getMetricEntity(),
    otelRepository,
    this::registerSensor,
    BasicConsumerTehutiMetricName.MAX_PARTITION_LAG,
    Collections.singletonList(new Max()),
    baseDimensionsMap,
    baseAttributes);

// Version tracking metrics
minimumConsumingVersionMetric = MetricEntityStateBase.create(
    BasicConsumerMetricEntity.CURRENT_CONSUMING_VERSION.getMetricEntity(),
    ...
    BasicConsumerTehutiMetricName.MINIMUM_CONSUMING_VERSION,
    Collections.singletonList(new Gauge()),
    ...);

maximumConsumingVersionMetric = MetricEntityStateBase.create(
    ...
    BasicConsumerTehutiMetricName.MAXIMUM_CONSUMING_VERSION,
    Collections.singletonList(new Gauge()),
    ...);

// Records consumed metric
recordsConsumedCountMetric = MetricEntityStateBase.create(
    BasicConsumerMetricEntity.RECORDS_CONSUMED_COUNT.getMetricEntity(),
    ...
    BasicConsumerTehutiMetricName.RECORDS_CONSUMED,
    Arrays.asList(new Avg(), new Max(), new Rate()),
    ...);

// Poll metrics
pollSuccessCountMetric = MetricEntityStateOneEnum.create(
    BasicConsumerMetricEntity.POLL_COUNT.getMetricEntity(),
    ...
    BasicConsumerTehutiMetricName.POLL_SUCCESS_COUNT,
    Collections.singletonList(new Rate()),
    ...
    VeniceResponseStatusCategory.class);

pollFailCountMetric = MetricEntityStateOneEnum.create(
    ...
    BasicConsumerTehutiMetricName.POLL_FAIL_COUNT,
    ...);

// Version swap metrics
versionSwapSuccessCountMetric = MetricEntityStateOneEnum.create(
    BasicConsumerMetricEntity.VERSION_SWAP_COUNT.getMetricEntity(),
    ...
    BasicConsumerTehutiMetricName.VERSION_SWAP_SUCCESS_COUNT,
    Collections.singletonList(new Total()),
    ...);

versionSwapFailCountMetric = MetricEntityStateOneEnum.create(
    ...
    BasicConsumerTehutiMetricName.VERSION_SWAP_FAIL_COUNT,
    ...);

// Chunked record metrics
chunkedRecordSuccessCountMetric = MetricEntityStateOneEnum.create(
    BasicConsumerMetricEntity.CHUNKED_RECORD_COUNT.getMetricEntity(),
    ...
    BasicConsumerTehutiMetricName.CHUNKED_RECORD_SUCCESS_COUNT,
    Collections.singletonList(new Rate()),
    ...);

chunkedRecordFailCountMetric = MetricEntityStateOneEnum.create(
    ...
    BasicConsumerTehutiMetricName.CHUNKED_RECORD_FAIL_COUNT,
    ...);
```

### OpenTelemetry Metric Entities

| Entity | Type | Unit | Dimensions |
|--------|------|------|------------|
| `HEART_BEAT_DELAY` | MIN_MAX_COUNT_SUM_AGGREGATIONS | Millisecond | store_name |
| `CURRENT_CONSUMING_VERSION` | MIN_MAX_COUNT_SUM_AGGREGATIONS | Number | store_name |
| `RECORDS_CONSUMED_COUNT` | COUNTER | Number | store_name |
| `POLL_COUNT` | COUNTER | Number | store_name, status_category |
| `VERSION_SWAP_COUNT` | UP_DOWN_COUNTER | Number | store_name, status_category |
| `CHUNKED_RECORD_COUNT` | COUNTER | Number | store_name, status_category |

---

## Common Alerting Patterns

### High Poll Failure Rate

```
Alert: poll_fail_count.Rate > 0 for extended period

Metrics:
  - poll_fail_count.Rate
  - poll_success_count.Rate

Calculation:
  failure_rate = poll_fail_count / (poll_fail_count + poll_success_count)

Thresholds:
  - Warning: failure_rate > 0.01 (1%)
  - Critical: failure_rate > 0.10 (10%)

Action:
  - Check Kafka broker connectivity
  - Check topic existence and permissions
  - Review consumer logs for specific errors
  - Verify schema registry availability
```

### Consumer Lag

```
Alert: max_partition_lag > threshold_ms

Metric: max_partition_lag (Max)

Typical thresholds:
  - Warning: > 5000ms (5 seconds)
  - Critical: > 30000ms (30 seconds)
  - Severe: > 300000ms (5 minutes)

Action:
  - Check consumer throughput (records_consumed.Rate)
  - Check for processing bottlenecks
  - Consider increasing consumer parallelism
  - Check for rebalancing issues
```

### Version Swap Failures

```
Alert: version_swap_fail_count > 0

Metric: version_swap_fail_count (Total)

Action:
  - Check for Kafka connectivity issues
  - Verify new version topic exists
  - Check for authorization problems
  - Review consumer logs for root cause
  - May need manual intervention to reset consumer
```

### Low Throughput

```
Alert: records_consumed.Rate significantly below expected

Metric: records_consumed.Rate

Typical analysis:
  - Compare with producer write rate
  - Check poll frequency
  - Verify partition assignment

Action:
  - Ensure all partitions are assigned
  - Check for consumer group rebalancing
  - Verify no partitions are stuck
  - Review batch size and poll interval settings
```

### Chunked Record Failures

```
Alert: chunked_record_fail_count.Rate > 0

Metric: chunked_record_fail_count.Rate

Possible causes:
  - Missing chunks due to retention
  - Corruption in transit
  - Schema incompatibility

Action:
  - Check if record was split across topic retention boundary
  - Verify all chunks are present in topic
  - Review producer chunking configuration
```

---

## Dashboard Recommendations

### Key Panels

| Panel | Metrics | Purpose |
|-------|---------|---------|
| **Poll Success Rate** | `poll_success_count`, `poll_fail_count` | Consumer health |
| **Records Throughput** | `records_consumed.Rate` | Consumption rate |
| **Consumer Lag** | `max_partition_lag` | Catch-up status |
| **Consuming Version** | `minimum/maximum_consuming_version` | Version tracking |
| **Version Swaps** | `version_swap_success/fail_count` | Transition health |

### Example Queries (PromQL-style)

```promql
# Poll success rate
sum(rate(poll_success_count[5m])) /
  (sum(rate(poll_success_count[5m])) + sum(rate(poll_fail_count[5m])))

# Records per second
rate(records_consumed_total[1m])

# Max lag across all consumers for a store
max(max_partition_lag{store_name="myStore"})

# Version swap count over last hour
increase(version_swap_success_count[1h])
```

---

## Summary Statistics

| Category | Metric Count |
|----------|--------------|
| Poll Operations | 2 |
| Records Consumed | 1 (with 3 statistics) |
| Version Tracking | 2 |
| Version Swap | 2 |
| Heartbeat/Lag | 1 |
| Chunked Records | 2 |
| **Total** | **10** |

---

## See Also

- [Changelog Consumer Architecture](changelog_consumer_architecture.md) - Architecture documentation
- [Da-Vinci Metrics](da_vinci_metrics.md) - Embedded client metrics
- [Clients Overview](clients_overview.md) - Client comparison
