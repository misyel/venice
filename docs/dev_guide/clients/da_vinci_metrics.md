# Venice Da-Vinci Client Metrics Reference

This document provides a comprehensive reference for all metrics emitted by the Venice Da-Vinci Client. These metrics are essential for monitoring ingestion health, storage utilization, replication lag, and read performance.

## Table of Contents

- [Overview](#overview)
- [Read Path Metrics](#read-path-metrics)
- [Ingestion Throughput Metrics](#ingestion-throughput-metrics)
- [Ingestion Latency Metrics](#ingestion-latency-metrics)
- [Heartbeat and Lag Metrics](#heartbeat-and-lag-metrics)
- [Conflict Resolution (DCR) Metrics](#conflict-resolution-dcr-metrics)
- [Consumer Metrics](#consumer-metrics)
- [Storage Metrics](#storage-metrics)
- [Batch Processing Metrics](#batch-processing-metrics)
- [Write Compute Metrics](#write-compute-metrics)
- [Metric Classes Reference](#metric-classes-reference)
- [Common Alerting Patterns](#common-alerting-patterns)

---

## Overview

Da-Vinci Client emits metrics through two systems:
- **Tehuti**: Legacy metrics framework (default)
- **OpenTelemetry**: Modern observability standard (when enabled)

Metrics are organized by:
- **Host-level**: Aggregated across all stores on the host
- **Store-level**: Per-store metrics
- **Version-level**: Per store version metrics

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                         Da-Vinci Metrics Hierarchy                               │
├─────────────────────────────────────────────────────────────────────────────────┤
│                                                                                  │
│  ┌─────────────────────────────────────────────────────────────────────────┐    │
│  │                    Host-Level (Total) Metrics                           │    │
│  │             davinci.total.*, ingestion.total.*                          │    │
│  └─────────────────────────────────────────────────────────────────────────┘    │
│                                    │                                             │
│              ┌─────────────────────┼─────────────────────┐                      │
│              v                     v                     v                       │
│  ┌───────────────────┐ ┌───────────────────┐ ┌───────────────────┐             │
│  │  Store: myStore   │ │ Store: features   │ │ Store: profiles   │             │
│  │  davinci.myStore.*│ │ davinci.features.*│ │ davinci.profiles.*│             │
│  └───────────────────┘ └───────────────────┘ └───────────────────┘             │
│              │                                                                   │
│              v                                                                   │
│  ┌─────────────────────────────────────────────────────────────────────────┐    │
│  │                    Version-Level Metrics                                │    │
│  │             myStore_v1, myStore_v2 (per version stats)                  │    │
│  └─────────────────────────────────────────────────────────────────────────┘    │
│                                                                                  │
└─────────────────────────────────────────────────────────────────────────────────┘
```

---

## Read Path Metrics

Da-Vinci inherits base read metrics from `BasicClientStats`:

### Request Metrics

| Metric | Type | Description |
|--------|------|-------------|
| `request` | Rate | Total read requests per second |
| `healthy_request` | Rate | Successful reads per second |
| `unhealthy_request` | Rate | Failed reads per second |
| `healthy_request_latency` | Histogram | Read latency for successful requests |
| `unhealthy_request_latency` | Histogram | Read latency for failed requests |

### Key Differences from Remote Clients

For Da-Vinci, read metrics do NOT include HTTP status codes (local reads):

| Dimension | Remote Clients | Da-Vinci |
|-----------|---------------|----------|
| `http.response.status_code` | ✅ Present | ❌ Not applicable |
| `venice.response.status_code.category` | ✅ Present | ✅ `SUCCESS` or `FAIL` |

---

## Ingestion Throughput Metrics

### Record and Byte Consumption

| Metric | Type | Unit | Description |
|--------|------|------|-------------|
| `records_consumed` | Rate | records/s | Total records consumed |
| `bytes_consumed` | Rate | bytes/s | Total bytes consumed |
| `leader_records_consumed` | Rate | records/s | Records consumed as leader |
| `leader_bytes_consumed` | Rate | bytes/s | Bytes consumed as leader |
| `follower_records_consumed` | Rate | records/s | Records consumed as follower |
| `follower_bytes_consumed` | Rate | bytes/s | Bytes consumed as follower |

### Production Metrics (Leader Only)

| Metric | Type | Unit | Description |
|--------|------|------|-------------|
| `leader_records_produced` | Rate | records/s | Records produced to local RT |
| `leader_bytes_produced` | Rate | bytes/s | Bytes produced to local RT |

### Region-Specific Hybrid Consumption

| Metric | Type | Description |
|--------|------|-------------|
| `<region>_rt_bytes_consumed` | Rate | RT bytes consumed from region |
| `<region>_rt_records_consumed` | Rate | RT records consumed from region |

---

## Ingestion Latency Metrics

### Write Path Latency Breakdown

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                    Ingestion Latency Breakdown Diagram                           │
├─────────────────────────────────────────────────────────────────────────────────┤
│                                                                                  │
│  Producer App                                                                   │
│     │                                                                           │
│     │ (1) producer_to_source_broker_latency                                     │
│     v                                                                           │
│  Source Kafka Broker (e.g., Corp Kafka)                                         │
│     │                                                                           │
│     │ (2) source_broker_to_leader_consumer_latency                              │
│     v                                                                           │
│  Venice Server (Leader) - consumes from source                                  │
│     │                                                                           │
│     │ (3) producer_to_local_broker_latency (leader produces to local RT)        │
│     v                                                                           │
│  Local Kafka Broker (Venice region)                                             │
│     │                                                                           │
│     │ (4) local_broker_to_follower_consumer_latency                             │
│     v                                                                           │
│  Venice Server/Da-Vinci (Follower) - consumes from local                        │
│     │                                                                           │
│     │ (5) leader_producer_completion_latency                                    │
│     v                                                                           │
│  Record persisted and ready to serve                                            │
│                                                                                  │
│  For Nearline (real-time) path:                                                 │
│     │                                                                           │
│     │ (6) nearline_producer_to_local_broker_latency                             │
│     v                                                                           │
│     │ (7) nearline_local_broker_to_ready_to_serve_latency                       │
│     v                                                                           │
│  Ready to serve                                                                 │
│                                                                                  │
└─────────────────────────────────────────────────────────────────────────────────┘
```

### Latency Metrics

| Metric | Type | Unit | Description |
|--------|------|------|-------------|
| `producer_to_source_broker_latency` | Histogram | ms | Time from producer to source broker |
| `source_broker_to_leader_consumer_latency` | Histogram | ms | Time for leader to consume from source |
| `producer_to_local_broker_latency` | Histogram | ms | Time from producer to local broker |
| `local_broker_to_follower_consumer_latency` | Histogram | ms | Time for follower to consume from local |
| `leader_producer_completion_latency` | Histogram | ms | Time for leader to complete producing |
| `nearline_producer_to_local_broker_latency` | Histogram | ms | Nearline producer to local broker |
| `nearline_local_broker_to_ready_to_serve_latency` | Histogram | ms | Nearline local broker to ready |
| `consumed_record_end_to_end_processing_latency` | Histogram | ms | Total processing latency |

### Processing Latency Metrics

| Metric | Type | Unit | Description |
|--------|------|------|-------------|
| `subscribe_action_prep_latency` | Histogram | ms | Subscription preparation time |
| `producer_callback_latency` | Histogram | ms | Kafka producer callback latency |
| `leader_preprocessing_latency` | Histogram | ms | Leader record preprocessing |
| `internal_preprocessing_latency` | Histogram | ms | Internal preprocessing time |

---

## Heartbeat and Lag Metrics

Heartbeat metrics track replication lag by monitoring heartbeat timestamps:

### Heartbeat Delay Metrics

| Metric | Type | Unit | Dimensions | Description |
|--------|------|------|------------|-------------|
| `heartbeat_delay` | Gauge | ms | replica_type, replica_state, region | Time since last heartbeat |
| `record_delay` | Gauge | ms | replica_type, replica_state, region | Time since record produced |

### Dimensions

| Dimension | Values | Description |
|-----------|--------|-------------|
| `replica_type` | `LEADER`, `FOLLOWER` | Role of the replica |
| `replica_state` | `READY_TO_SERVE`, `CATCHING_UP` | Current state |
| `region` | Region names | Source region |

### Heartbeat Monitoring Visualization

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                        Heartbeat Lag Monitoring                                  │
├─────────────────────────────────────────────────────────────────────────────────┤
│                                                                                  │
│  Heartbeat Producer                       Da-Vinci Consumer                     │
│  (Every N seconds)                        (Records delay)                       │
│                                                                                  │
│  Time ─────────────────────────────────────────────────────────────────────────►│
│                                                                                  │
│  Heartbeat   Heartbeat    Heartbeat    Current                                  │
│  Sent (t1)   Sent (t2)    Sent (t3)    Time                                     │
│     │           │            │            │                                      │
│     v           v            v            v                                      │
│  ───┼───────────┼────────────┼────────────┼───                                  │
│                              │            │                                      │
│                              │◄──────────►│                                      │
│                              │ heartbeat_ │                                      │
│                              │ delay      │                                      │
│                              │            │                                      │
│                              │ = current_time - heartbeat_timestamp             │
│                                                                                  │
│  Leader Lag: heartbeat_delay{replica_type=LEADER, region=X}                     │
│  Follower Lag: heartbeat_delay{replica_type=FOLLOWER, region=X}                 │
│                                                                                  │
│  Alert Thresholds:                                                              │
│    • Ready to serve: heartbeat_delay{replica_state=READY_TO_SERVE}              │
│    • Catching up: heartbeat_delay{replica_state=CATCHING_UP} (may be high)      │
│                                                                                  │
└─────────────────────────────────────────────────────────────────────────────────┘
```

---

## Conflict Resolution (DCR) Metrics

For Active-Active (A/A) replication, DCR metrics track conflict handling:

### DCR Count Metrics

| Metric | Type | Description |
|--------|------|-------------|
| `total_dcr` | Rate | Total conflict resolution operations |
| `update_ignored_dcr` | Rate | Updates ignored due to conflict resolution |
| `timestamp_regression_dcr_error` | Rate | Errors from timestamp going backwards |
| `offset_regression_dcr_error` | Rate | Errors from offset going backwards |
| `tombstone_creation_dcr` | Rate | Tombstones created due to DCR |

### DCR Flow

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                      Conflict Resolution (DCR) Flow                              │
├─────────────────────────────────────────────────────────────────────────────────┤
│                                                                                  │
│  Incoming Update from Region B                                                  │
│     │                                                                           │
│     v                                                                           │
│  ┌───────────────────────────────────────────────────────────────┐              │
│  │              Compare with Local State                          │              │
│  │  (timestamp, offset vector, value hash)                        │              │
│  └───────────────────────────────────────────────────────────────┘              │
│     │                                                                           │
│     │ total_dcr++                                                               │
│     │                                                                           │
│     ├─────────────────────────────────────┐                                     │
│     │                                     │                                      │
│     v                                     v                                      │
│  ┌──────────────────┐           ┌──────────────────┐                            │
│  │ Remote Wins      │           │ Local Wins       │                            │
│  │ (apply update)   │           │ (ignore update)  │                            │
│  └──────────────────┘           └────────┬─────────┘                            │
│                                          │                                       │
│                                          │ update_ignored_dcr++                  │
│                                          v                                       │
│                                 ┌──────────────────┐                            │
│                                 │  Log/Monitor     │                            │
│                                 └──────────────────┘                            │
│                                                                                  │
│  Error Cases:                                                                   │
│    • timestamp_regression_dcr_error - Timestamp went backwards                  │
│    • offset_regression_dcr_error - Offset vector regressed                      │
│                                                                                  │
└─────────────────────────────────────────────────────────────────────────────────┘
```

---

## Consumer Metrics

### Kafka Consumer Service Metrics

| Metric | Type | Unit | Description |
|--------|------|------|-------------|
| `consumer_poll_request` | Rate | polls/s | Kafka poll requests |
| `consumer_poll_request_latency` | Histogram | ms | Poll request latency |
| `consumer_poll_result_num` | Histogram | count | Records per poll |
| `consumer_poll_non_zero_result_num` | Rate | count/s | Non-empty polls |
| `consumer_poll_error` | Rate | errors/s | Poll errors |
| `max_elapsed_time_since_last_successful_poll` | Gauge | ms | Time since last successful poll |
| `bytes_per_poll` | Histogram | bytes | Bytes per poll request |
| `idle_time` | Gauge | ms | Consumer idle time |

### Consumer Assignment Metrics

| Metric | Type | Description |
|--------|------|-------------|
| `min_partitions_per_consumer` | Gauge | Minimum partition assignment |
| `max_partitions_per_consumer` | Gauge | Maximum partition assignment |
| `avg_partitions_per_consumer` | Gauge | Average partition assignment |
| `subscribed_partitions_num` | Gauge | Total subscribed partitions |

### Consumer Latency Metrics

| Metric | Type | Unit | Description |
|--------|------|------|-------------|
| `consumer_records_producing_to_write_buffer_latency` | Histogram | ms | Buffer write latency |
| `delegate_subscribe_latency` | Histogram | ms | Subscription latency |
| `update_current_assignment_latency` | Histogram | ms | Assignment update latency |

---

## Storage Metrics

### Disk Usage Metrics

| Metric | Type | Unit | Description |
|--------|------|------|-------------|
| `disk_usage_in_bytes` | Gauge | bytes | Total RocksDB storage used |
| `rmd_disk_usage_in_bytes` | Gauge | bytes | Replication metadata storage |
| `storage_quota_used` | Gauge | ratio | Percentage of quota used |

### Storage Engine Latency

| Metric | Type | Unit | Description |
|--------|------|------|-------------|
| `storage_engine_put_latency` | Histogram | ms | Write latency to RocksDB |
| `storage_engine_delete_latency` | Histogram | ms | Delete latency from RocksDB |

### Record Size Metrics

| Metric | Type | Unit | Description |
|--------|------|------|-------------|
| `record_key_size_in_bytes` | Histogram | bytes | Key sizes |
| `record_value_size_in_bytes` | Histogram | bytes | Value sizes |
| `assembled_record_size_in_bytes` | Histogram | bytes | Reassembled chunked record size |
| `assembled_record_size_ratio` | Histogram | ratio | Assembled vs stored ratio |
| `assembled_rmd_size_in_bytes` | Histogram | bytes | Assembled RMD size |

---

## Batch Processing Metrics

For batch ingestion operations:

| Metric | Type | Unit | Description |
|--------|------|------|-------------|
| `batch_processing_request` | Rate | requests/s | Batch processing requests |
| `batch_processing_request_size` | Histogram | count | Records per batch |
| `batch_processing_request_records` | Rate | records/s | Total batch records |
| `batch_processing_request_latency` | Histogram | ms | Batch processing latency |
| `batch_processing_request_error` | Rate | errors/s | Batch processing errors |

---

## Write Compute Metrics

For Write Compute (partial update) operations:

### Write Compute Latency

| Metric | Type | Unit | Description |
|--------|------|------|-------------|
| `leader_write_compute_lookup_latency` | Histogram | ms | Value lookup latency |
| `leader_write_compute_update_latency` | Histogram | ms | Update computation latency |
| `leader_ingestion_value_bytes_lookup_latency` | Histogram | ms | Value bytes lookup |
| `leader_ingestion_replication_metadata_lookup_latency` | Histogram | ms | RMD lookup |

### Write Compute Cache Metrics

| Metric | Type | Description |
|--------|------|-------------|
| `write_compute_cache_hit_count` | Rate | Cache hits during write compute |
| `leader_ingestion_value_bytes_cache_hit_count` | Rate | Value bytes cache hits |
| `leader_ingestion_replication_metadata_cache_hit_count` | Rate | RMD cache hits |

### Active-Active Operation Latency

| Metric | Type | Unit | Description |
|--------|------|------|-------------|
| `leader_ingestion_active_active_put_latency` | Histogram | ms | A/A put latency |
| `leader_ingestion_active_active_update_latency` | Histogram | ms | A/A update latency |
| `leader_ingestion_active_active_delete_latency` | Histogram | ms | A/A delete latency |

---

## Metric Classes Reference

| Class | Location | Scope |
|-------|----------|-------|
| `IngestionStats` | `clients/da-vinci-client/.../stats/` | Per-version ingestion metrics |
| `HostLevelIngestionStats` | Same directory | Host-level aggregated metrics |
| `AggVersionedIngestionStats` | Same directory | Aggregated version metrics |
| `HeartbeatVersionedStats` | `.../stats/ingestion/heartbeat/` | Heartbeat/lag metrics |
| `HeartbeatOtelStats` | Same directory | OTel heartbeat metrics |
| `RecordOtelStats` | Same directory | OTel record delay metrics |
| `KafkaConsumerServiceStats` | Same directory | Consumer metrics |
| `RocksDBStorageEngineStats` | `.../store/rocksdb/` | Storage metrics |

---

## Common Alerting Patterns

### High Ingestion Lag

```
Alert: heartbeat_delay{replica_state=READY_TO_SERVE} > threshold_ms
Metric: heartbeat_delay (Gauge)
Dimensions: replica_type, replica_state, region
Action: Check Kafka consumption rate, disk I/O, network
```

### DCR Errors

```
Alert: timestamp_regression_dcr_error rate > 0
Metric: timestamp_regression_dcr_error (Rate)
Action: Investigate clock skew, data ordering issues
```

### Consumer Stuck

```
Alert: max_elapsed_time_since_last_successful_poll > threshold_ms
Metric: max_elapsed_time_since_last_successful_poll (Gauge)
Action: Check Kafka connectivity, consumer health
```

### Storage Quota Exceeded

```
Alert: storage_quota_used > 0.9 (90%)
Metric: storage_quota_used (Gauge)
Action: Reduce data, increase quota, or scale out
```

### Ingestion Task Errors

```
Alert: ingestion_task_errored_gauge > 0
Metric: ingestion_task_errored_gauge (Gauge)
Action: Check ingestion logs, investigate data issues
```

### High Write Compute Latency

```
Alert: leader_write_compute_update_latency.p99 > threshold_ms
Metric: leader_write_compute_update_latency (Histogram)
Action: Check write compute complexity, cache hit rates
```

### Batch Processing Errors

```
Alert: batch_processing_request_error rate > threshold
Metric: batch_processing_request_error (Rate)
Action: Check batch data format, processing capacity
```

---

## Summary Statistics

| Category | Metric Count |
|----------|--------------|
| Read Path Metrics | 5 |
| Ingestion Throughput | 10 |
| Ingestion Latency | 12 |
| Heartbeat/Lag | 2 (with dimensions) |
| DCR Metrics | 5 |
| Consumer Metrics | 14 |
| Storage Metrics | 7 |
| Batch Processing | 5 |
| Write Compute | 10 |
| **Total** | **~70+** |

---

## See Also

- [Da-Vinci Architecture](da_vinci_architecture.md) - Architecture documentation
- [Thin Client Metrics](thin_client_metrics.md) - Remote client metrics
- [Fast Client Metrics](fast_client_metrics.md) - Optimized remote client metrics
- [Clients Overview](clients_overview.md) - Client comparison
