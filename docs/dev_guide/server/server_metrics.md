# Venice Server Metrics Reference

This document provides a comprehensive reference for all metrics emitted by the Venice Server.

## Metrics Overview

Venice Server emits metrics through Tehuti (a metrics library) and optionally OpenTelemetry. Metrics are organized by component and typically include:

- **Per-store metrics**: Prefixed with store name
- **Aggregated total metrics**: Server-wide totals

## HTTP Request Metrics

**Class:** `ServerHttpRequestStats`
**Location:** `services/venice-server/src/main/java/com/linkedin/venice/stats/ServerHttpRequestStats.java`

### Request Counts

| Metric | Type | Granularity | Full Metric Name Example | Description |
|--------|------|-------------|--------------------------|-------------|
| `success_request` | OccurrenceRate | Per-store + Total | `MyStore--success_request`, `total--success_request` | Rate of successful requests |
| `error_request` | OccurrenceRate | Per-store + Total | `MyStore--error_request`, `total--error_request` | Rate of failed requests |
| `success_request_ratio` | RatioStat | Per-store + Total | `MyStore--success_request_ratio`, `total--success_request_ratio` | Ratio of successes to total (successes + errors) |
| `early_terminated_request_count` | OccurrenceRate | Per-store + Total | `MyStore--early_terminated_request_count`, `total--early_terminated_request_count` | Rate of requests terminated early due to timeout |
| `misrouted_store_version_request_count` | OccurrenceRate | Per-store + Total | `MyStore--misrouted_store_version_request_count`, `total--misrouted_store_version_request_count` | Rate of requests for non-existent versions |

### Request Latency

| Metric | Stats | Granularity | Full Metric Name Example | Description |
|--------|-------|-------------|--------------------------|-------------|
| `success_request_latency` | p99, avg, max | Per-store + Total | `MyStore--success_request_latency.99thPercentile`, `total--success_request_latency.Avg` | End-to-end latency for successful requests |
| `error_request_latency` | p99, avg, max | Per-store + Total | `MyStore--error_request_latency.99thPercentile`, `total--error_request_latency.Max` | End-to-end latency for failed requests |
| `storage_engine_query_latency` | p99, avg, max | Per-store + Total | `MyStore--storage_engine_query_latency.99thPercentile`, `total--storage_engine_query_latency.Avg` | RocksDB lookup latency (all values) |
| `storage_engine_query_latency_for_small_value` | p99 | Per-store + Total | `MyStore--storage_engine_query_latency_for_small_value.99thPercentile` | RocksDB latency for values < 1MB |
| `storage_engine_query_latency_for_large_value` | p99 | Per-store + Total | `MyStore--storage_engine_query_latency_for_large_value.99thPercentile` | RocksDB latency for chunked values > 1MB |

### Queue and Submission Metrics

| Metric | Stats | Granularity | Full Metric Name Example | Description |
|--------|-------|-------------|--------------------------|-------------|
| `storage_execution_handler_submission_wait_time` | p99, avg, max | Total only | `total--storage_execution_handler_submission_wait_time.99thPercentile` | Time waiting in executor queue before processing |
| `storage_execution_queue_len` | max, avg | Total only | `total--storage_execution_queue_len.Max`, `total--storage_execution_queue_len.Avg` | Executor queue depth at submission time |

### Request Size Metrics

| Metric | Stats | Granularity | Full Metric Name Example | Description |
|--------|-------|-------------|--------------------------|-------------|
| `request_key_count` | rate, avg, max | Per-store + Total | `MyStore--request_key_count.Rate`, `total--request_key_count.Avg` | Keys per request (batch/compute only, not single-get) |
| `request_size_in_bytes` | avg, min, max | Per-store + Total | `MyStore--request_size_in_bytes.Avg`, `total--request_size_in_bytes.Max` | Request payload size |
| `request_key_size` | percentile, avg, max | Per-store + Total | `MyStore--request_key_size.99thPercentile`, `total--request_key_size.Avg` | Key size (if profiling enabled or single-get) |
| `request_value_size` | percentile, avg, max | Per-store + Total | `MyStore--request_value_size.99thPercentile`, `total--request_value_size.Max` | Value size (if profiling enabled or single-get) |

### Large Value / Chunking Metrics

| Metric | Stats | Granularity | Full Metric Name Example | Description |
|--------|-------|-------------|--------------------------|-------------|
| `storage_engine_large_value_lookup` | max, rate, avg | Per-store + Total | `MyStore--storage_engine_large_value_lookup.Max`, `total--storage_engine_large_value_lookup.Rate` | Multi-chunk large value assembly stats |

**For Single-Get:** max = 0 or 1
**For Batch-Get:** max = number of large values in batch, avg = average per batch

### Response Metrics

| Metric | Stats | Granularity | Full Metric Name Example | Description |
|--------|-------|-------------|--------------------------|-------------|
| `response_size` | percentile | Per-store + Total | `MyStore--response_size.99thPercentile`, `total--response_size.50thPercentile` | Response payload size |
| `flush_latency` | percentile | Per-store + Total | `MyStore--flush_latency.99thPercentile`, `total--flush_latency.95thPercentile` | Response flush latency |

### Read Compute Metrics

| Metric | Stats | Granularity | Full Metric Name Example | Description |
|--------|-------|-------------|--------------------------|-------------|
| `storage_engine_read_compute_latency` | percentile, avg, max | Per-store + Total | `MyStore--storage_engine_read_compute_latency.99thPercentile`, `total--storage_engine_read_compute_latency.Avg` | Total compute operation latency |
| `storage_engine_read_compute_latency_for_small_value` | percentile | Per-store + Total | `MyStore--storage_engine_read_compute_latency_for_small_value.99thPercentile` | Compute latency for small values |
| `storage_engine_read_compute_latency_for_large_value` | percentile | Per-store + Total | `MyStore--storage_engine_read_compute_latency_for_large_value.99thPercentile` | Compute latency for chunked values |
| `storage_engine_read_compute_deserialization_latency` | percentile, avg, max | Per-store + Total | `MyStore--storage_engine_read_compute_deserialization_latency.99thPercentile` | Value deserialization time |
| `storage_engine_read_compute_serialization_latency` | percentile, avg, max | Per-store + Total | `MyStore--storage_engine_read_compute_serialization_latency.Avg` | Result serialization time |
| `storage_engine_read_compute_efficiency` | avg, min, max | Per-store + Total | `MyStore--storage_engine_read_compute_efficiency.Avg`, `total--storage_engine_read_compute_efficiency.Min` | Compute efficiency ratio |

### Compute Operator Counts (COMPUTE requests only)

| Metric | Stats | Granularity | Full Metric Name Example | Description |
|--------|-------|-------------|--------------------------|-------------|
| `dot_product_count` | avg, total | Per-store + Total | `MyStore--dot_product_count.Avg`, `total--dot_product_count.Total` | Dot product operations |
| `cosine_similarity_count` | avg, total | Per-store + Total | `MyStore--cosine_similarity_count.Total`, `total--cosine_similarity_count.Avg` | Cosine similarity operations |
| `hadamard_product_count` | avg, total | Per-store + Total | `MyStore--hadamard_product_count.Avg`, `total--hadamard_product_count.Total` | Hadamard product operations |
| `count_operator_count` | avg, total | Per-store + Total | `MyStore--count_operator_count.Total`, `total--count_operator_count.Avg` | Count operations |

## RocksDB Storage Metrics

**Class:** `RocksDBStats`
**Location:** `services/venice-server/src/main/java/com/linkedin/venice/stats/RocksDBStats.java`

All RocksDB metrics use `AsyncGauge` for lazy evaluation.

### Block Cache Metrics (Overall)

| Metric | Granularity | Full Metric Name Example | Description |
|--------|-------------|--------------------------|-------------|
| `rocksdb_block_cache_hit` | Per-store + Total | `MyStore--rocksdb_block_cache_hit`, `total--rocksdb_block_cache_hit` | Total block cache hits |
| `rocksdb_block_cache_miss` | Per-store + Total | `MyStore--rocksdb_block_cache_miss`, `total--rocksdb_block_cache_miss` | Total block cache misses |
| `rocksdb_block_cache_add` | Per-store + Total | `MyStore--rocksdb_block_cache_add`, `total--rocksdb_block_cache_add` | Blocks added to cache |
| `rocksdb_block_cache_add_failures` | Per-store + Total | `MyStore--rocksdb_block_cache_add_failures`, `total--rocksdb_block_cache_add_failures` | Failed cache additions |
| `rocksdb_block_cache_bytes_read` | Per-store + Total | `MyStore--rocksdb_block_cache_bytes_read`, `total--rocksdb_block_cache_bytes_read` | Bytes read from block cache |
| `rocksdb_block_cache_bytes_write` | Per-store + Total | `MyStore--rocksdb_block_cache_bytes_write`, `total--rocksdb_block_cache_bytes_write` | Bytes written to block cache |
| `rocksdb_block_cache_hit_ratio` | Per-store + Total | `MyStore--rocksdb_block_cache_hit_ratio`, `total--rocksdb_block_cache_hit_ratio` | DATA_HIT / (DATA_HIT + MISS) |

### Block Cache by Component

**Index Blocks:**

| Metric | Granularity | Full Metric Name Example | Description |
|--------|-------------|--------------------------|-------------|
| `rocksdb_block_cache_index_hit` | Per-store + Total | `MyStore--rocksdb_block_cache_index_hit`, `total--rocksdb_block_cache_index_hit` | Index block cache hits |
| `rocksdb_block_cache_index_miss` | Per-store + Total | `MyStore--rocksdb_block_cache_index_miss`, `total--rocksdb_block_cache_index_miss` | Index block cache misses |
| `rocksdb_block_cache_index_add` | Per-store + Total | `MyStore--rocksdb_block_cache_index_add`, `total--rocksdb_block_cache_index_add` | Index blocks added |
| `rocksdb_block_cache_index_bytes_insert` | Per-store + Total | `MyStore--rocksdb_block_cache_index_bytes_insert`, `total--rocksdb_block_cache_index_bytes_insert` | Index bytes inserted |

**Filter Blocks (Bloom):**

| Metric | Granularity | Full Metric Name Example | Description |
|--------|-------------|--------------------------|-------------|
| `rocksdb_block_cache_filter_hit` | Per-store + Total | `MyStore--rocksdb_block_cache_filter_hit`, `total--rocksdb_block_cache_filter_hit` | Filter block cache hits |
| `rocksdb_block_cache_filter_miss` | Per-store + Total | `MyStore--rocksdb_block_cache_filter_miss`, `total--rocksdb_block_cache_filter_miss` | Filter block cache misses |
| `rocksdb_block_cache_filter_add` | Per-store + Total | `MyStore--rocksdb_block_cache_filter_add`, `total--rocksdb_block_cache_filter_add` | Filter blocks added |
| `rocksdb_block_cache_filter_bytes_insert` | Per-store + Total | `MyStore--rocksdb_block_cache_filter_bytes_insert`, `total--rocksdb_block_cache_filter_bytes_insert` | Filter bytes inserted |

**Data Blocks:**

| Metric | Granularity | Full Metric Name Example | Description |
|--------|-------------|--------------------------|-------------|
| `rocksdb_block_cache_data_hit` | Per-store + Total | `MyStore--rocksdb_block_cache_data_hit`, `total--rocksdb_block_cache_data_hit` | Data block cache hits |
| `rocksdb_block_cache_data_miss` | Per-store + Total | `MyStore--rocksdb_block_cache_data_miss`, `total--rocksdb_block_cache_data_miss` | Data block cache misses |
| `rocksdb_block_cache_data_add` | Per-store + Total | `MyStore--rocksdb_block_cache_data_add`, `total--rocksdb_block_cache_data_add` | Data blocks added |
| `rocksdb_block_cache_data_bytes_insert` | Per-store + Total | `MyStore--rocksdb_block_cache_data_bytes_insert`, `total--rocksdb_block_cache_data_bytes_insert` | Data bytes inserted |

### MemTable Metrics

| Metric | Granularity | Full Metric Name Example | Description |
|--------|-------------|--------------------------|-------------|
| `rocksdb_memtable_hit` | Per-store + Total | `MyStore--rocksdb_memtable_hit`, `total--rocksdb_memtable_hit` | Hits served from MemTable |
| `rocksdb_memtable_miss` | Per-store + Total | `MyStore--rocksdb_memtable_miss`, `total--rocksdb_memtable_miss` | Misses requiring SST file lookup |

### LSM Tree Level Metrics

| Metric | Granularity | Full Metric Name Example | Description |
|--------|-------------|--------------------------|-------------|
| `rocksdb_get_hit_l0` | Per-store + Total | `MyStore--rocksdb_get_hit_l0`, `total--rocksdb_get_hit_l0` | Hits in Level 0 (most recent) |
| `rocksdb_get_hit_l1` | Per-store + Total | `MyStore--rocksdb_get_hit_l1`, `total--rocksdb_get_hit_l1` | Hits in Level 1 |
| `rocksdb_get_hit_l2_and_up` | Per-store + Total | `MyStore--rocksdb_get_hit_l2_and_up`, `total--rocksdb_get_hit_l2_and_up` | Hits in Level 2+ |

### Bloom Filter Metrics

| Metric | Granularity | Full Metric Name Example | Description |
|--------|-------------|--------------------------|-------------|
| `rocksdb_bloom_filter_useful` | Per-store + Total | `MyStore--rocksdb_bloom_filter_useful`, `total--rocksdb_bloom_filter_useful` | Bloom filter negative checks (avoided disk reads) |

### Amplification Metrics

| Metric | Granularity | Full Metric Name Example | Description |
|--------|-------------|--------------------------|-------------|
| `rocksdb_read_amplification_factor` | Per-store + Total | `MyStore--rocksdb_read_amplification_factor`, `total--rocksdb_read_amplification_factor` | TOTAL_READ_BYTES / USEFUL_BYTES |
| `rocksdb_compaction_cancelled` | Per-store + Total | `MyStore--rocksdb_compaction_cancelled`, `total--rocksdb_compaction_cancelled` | Cancelled compaction operations |

## Ingestion Metrics

**Class:** `IngestionStats`
**Location:** `clients/da-vinci-client/src/main/java/com/linkedin/davinci/stats/IngestionStats.java`

### Consumption Metrics

| Metric | Type | Granularity | Full Metric Name Example | Description |
|--------|------|-------------|--------------------------|-------------|
| `records_consumed` | LongAdderRateGauge | Per-store + Per-version | `MyStore_v1--records_consumed`, `MyStore--records_consumed.Rate` | Total record consumption rate |
| `bytes_consumed` | LongAdderRateGauge | Per-store + Per-version | `MyStore_v1--bytes_consumed`, `MyStore--bytes_consumed.Rate` | Total bytes consumption rate |
| `leader_records_consumed` | LongAdderRateGauge | Per-store + Per-version | `MyStore_v1--leader_records_consumed`, `MyStore--leader_records_consumed.Rate` | Records consumed as leader |
| `leader_bytes_consumed` | LongAdderRateGauge | Per-store + Per-version | `MyStore_v1--leader_bytes_consumed`, `MyStore--leader_bytes_consumed.Rate` | Bytes consumed as leader |
| `follower_records_consumed` | LongAdderRateGauge | Per-store + Per-version | `MyStore_v1--follower_records_consumed`, `MyStore--follower_records_consumed.Rate` | Records consumed as follower |
| `follower_bytes_consumed` | LongAdderRateGauge | Per-store + Per-version | `MyStore_v1--follower_bytes_consumed`, `MyStore--follower_bytes_consumed.Rate` | Bytes consumed as follower |
| `leader_records_produced` | LongAdderRateGauge | Per-store + Per-version | `MyStore_v1--leader_records_produced`, `MyStore--leader_records_produced.Rate` | Records produced by leader (to VT) |
| `leader_bytes_produced` | LongAdderRateGauge | Per-store + Per-version | `MyStore_v1--leader_bytes_produced`, `MyStore--leader_bytes_produced.Rate` | Bytes produced by leader |

### Multi-Region Metrics (per region)

| Metric | Granularity | Full Metric Name Example | Description |
|--------|-------------|--------------------------|-------------|
| `{region}_rt_records_consumed` | Per-store + Per-region | `MyStore--us-east-1_rt_records_consumed.Rate`, `MyStore--us-west-2_rt_records_consumed.Rate` | RT records consumed from region |
| `{region}_rt_bytes_consumed` | Per-store + Per-region | `MyStore--us-east-1_rt_bytes_consumed.Rate`, `MyStore--us-west-2_rt_bytes_consumed.Rate` | RT bytes consumed from region |

### Write Path Latency Metrics

| Metric | Stats | Granularity | Full Metric Name Example | Description |
|--------|-------|-------------|--------------------------|-------------|
| `subscribe_action_prep_latency` | avg, max | Per-store + Per-version | `MyStore_v1--subscribe_action_prep_latency.Avg`, `MyStore--subscribe_action_prep_latency.Max` | Subscribe message preparation time |
| `consumed_record_end_to_end_processing_latency` | avg, max | Per-store + Per-version | `MyStore_v1--consumed_record_end_to_end_processing_latency.Max` | Total record processing time |
| `nearline_producer_to_local_broker_latency` | avg, max | Per-store + Per-version | `MyStore_v1--nearline_producer_to_local_broker_latency.Avg` | Producer to broker latency |
| `nearline_local_broker_to_ready_to_serve_latency` | avg, max | Per-store + Per-version | `MyStore_v1--nearline_local_broker_to_ready_to_serve_latency.Max` | Broker to ready-to-serve latency |
| `producer_callback_latency` | max | Per-store + Per-version | `MyStore_v1--producer_callback_latency.Max`, `MyStore--producer_callback_latency.Max` | Producer callback completion time |
| `leader_preprocessing_latency` | avg, max | Per-store + Per-version | `MyStore_v1--leader_preprocessing_latency.Avg`, `MyStore--leader_preprocessing_latency.Max` | Leader-side preprocessing time |
| `internal_preprocessing_latency` | avg, max | Per-store + Per-version | `MyStore_v1--internal_preprocessing_latency.Max` | Internal preprocessing time |

### Conflict Resolution Metrics (DCR)

| Metric | Type | Granularity | Full Metric Name Example | Description |
|--------|------|-------------|--------------------------|-------------|
| `update_ignored_dcr` | LongAdderRateGauge | Per-store + Per-version | `MyStore_v1--update_ignored_dcr.Rate`, `MyStore--update_ignored_dcr.Rate` | Updates ignored due to conflict resolution |
| `total_dcr` | LongAdderRateGauge | Per-store + Per-version | `MyStore_v1--total_dcr.Rate`, `MyStore--total_dcr.Rate` | Total conflict resolutions performed |
| `timestamp_regression_dcr_error` | LongAdderRateGauge | Per-store + Per-version | `MyStore_v1--timestamp_regression_dcr_error.Rate` | Timestamp regression errors |
| `offset_regression_dcr_error` | LongAdderRateGauge | Per-store + Per-version | `MyStore_v1--offset_regression_dcr_error.Rate` | Offset regression errors |
| `tombstone_creation_dcr` | LongAdderRateGauge | Per-store + Per-version | `MyStore_v1--tombstone_creation_dcr.Rate`, `MyStore--tombstone_creation_dcr.Rate` | Tombstones created via CRDT |
| `total_duplicate_key_update_count` | Count | Per-store + Per-version | `MyStore_v1--total_duplicate_key_update_count.Count` | Duplicate key updates received |

### Batch Processing Metrics

| Metric | Stats | Granularity | Full Metric Name Example | Description |
|--------|-------|-------------|--------------------------|-------------|
| `batch_processing_request` | rate | Per-store + Per-version | `MyStore_v1--batch_processing_request.Rate`, `MyStore--batch_processing_request.Rate` | Batch processing requests |
| `batch_processing_request_size` | avg, max | Per-store + Per-version | `MyStore_v1--batch_processing_request_size.Avg`, `MyStore--batch_processing_request_size.Max` | Batch request size |
| `batch_processing_request_records` | rate | Per-store + Per-version | `MyStore_v1--batch_processing_request_records.Rate` | Records in batch requests |
| `batch_processing_request_latency` | avg, max | Per-store + Per-version | `MyStore_v1--batch_processing_request_latency.Max` | Batch processing latency |
| `batch_processing_request_error` | rate | Per-store + Per-version | `MyStore_v1--batch_processing_request_error.Rate`, `MyStore--batch_processing_request_error.Rate` | Batch processing errors |

### Task State Metrics

| Metric | Type | Granularity | Full Metric Name Example | Description |
|--------|------|-------------|--------------------------|-------------|
| `ingestion_task_errored_gauge` | Gauge | Per-store + Per-version | `MyStore_v1--ingestion_task_errored_gauge.Gauge`, `MyStore--ingestion_task_errored_gauge.Gauge` | Count of errored ingestion partitions |
| `ingestion_task_push_timeout_gauge` | Gauge | Per-store + Per-version | `MyStore_v1--ingestion_task_push_timeout_gauge.Gauge` | Push timeout indicator |
| `write_compute_operation_failure` | Code | Per-store + Per-version | `MyStore_v1--write_compute_operation_failure.Code` | Write compute error code |
| `storage_quota_used` | Gauge | Per-store + Per-version | `MyStore_v1--storage_quota_used.Gauge`, `MyStore--storage_quota_used.Gauge` | Storage quota usage |
| `idle_time` | Rate | Per-store + Per-version + Per-partition | `MyStore_v1_0--idle_time.Rate`, `MyStore_v1_1--idle_time.Rate` | Partition idle time |

## Heartbeat / Lag Metrics

**Class:** `HeartbeatVersionedStats`
**Location:** `clients/da-vinci-client/src/main/java/com/linkedin/davinci/stats/ingestion/heartbeat/HeartbeatVersionedStats.java`

### Leader Lag Metrics

| Metric | Granularity | Full Metric Name Example | Description |
|--------|-------------|--------------------------|-------------|
| `ready_to_serve_leader_lag_{region}` | Per-store + Per-version + Per-region | `MyStore_v1--ready_to_serve_leader_lag_us-east-1.Gauge`, `MyStore_v1--ready_to_serve_leader_lag_us-west-2.Gauge` | Leader lag for ready-to-serve replicas |
| `leader_record_level_delay_{region}` | Per-store + Per-version + Per-region | `MyStore_v1--leader_record_level_delay_us-east-1.Gauge`, `MyStore_v1--leader_record_level_delay_us-west-2.Gauge` | Per-record leader delay |

### Follower Lag Metrics

| Metric | Granularity | Full Metric Name Example | Description |
|--------|-------------|--------------------------|-------------|
| `ready_to_serve_follower_lag_{region}` | Per-store + Per-version + Per-region | `MyStore_v1--ready_to_serve_follower_lag_us-east-1.Gauge`, `MyStore_v1--ready_to_serve_follower_lag_us-west-2.Gauge` | Follower lag for ready-to-serve replicas |
| `catching_up_follower_lag_{region}` | Per-store + Per-version + Per-region | `MyStore_v1--catching_up_follower_lag_us-east-1.Gauge`, `MyStore_v1--catching_up_follower_lag_us-west-2.Gauge` | Follower lag during catch-up phase |
| `follower_record_level_delay_{region}` | Per-store + Per-version + Per-region | `MyStore_v1--follower_record_level_delay_us-east-1.Gauge`, `MyStore_v1--follower_record_level_delay_us-west-2.Gauge` | Per-record follower delay |

### OpenTelemetry (OTel) Dimensions

When OTel is enabled, heartbeat metrics include these dimensions:

| Dimension | Description |
|-----------|-------------|
| `store` | Store name |
| `version` | Store version number |
| `region` | Source region |
| `replica_type` | LEADER or FOLLOWER |
| `replica_state` | READY_TO_SERVE or CATCHING_UP |

## Read Quota Metrics

**Class:** `ServerReadQuotaUsageStats`

| Metric | Granularity | Full Metric Name Example | Description |
|--------|-------------|--------------------------|-------------|
| `quota_request_key_count` | Per-store | `MyStore--quota_request_key_count.Rate`, `MyStore--quota_request_key_count.Count` | Keys in quota-checked requests |
| `quota_rejected_request` | Per-store | `MyStore--quota_rejected_request.Rate`, `MyStore--quota_rejected_request.Count` | Rejected requests due to quota |
| `quota_rejected_key_count` | Per-store | `MyStore--quota_rejected_key_count.Rate`, `MyStore--quota_rejected_key_count.Count` | Rejected keys due to quota |
| `current_quota_request` | Per-store | `MyStore--current_quota_request.Rate`, `MyStore--current_quota_request.Count` | Requests for current version |
| `backup_quota_request` | Per-store | `MyStore--backup_quota_request.Rate`, `MyStore--backup_quota_request.Count` | Requests for backup version |

## Connection Metrics

**Class:** `ServerConnectionStats`

| Metric | Granularity | Full Metric Name Example | Description |
|--------|-------------|--------------------------|-------------|
| `router_connection_count` | Per-host | `total--router_connection_count.Gauge` | Active router connections |
| `client_connection_count` | Per-host | `total--client_connection_count.Gauge` | Active client connections |

## Disk Health Metrics

**Class:** `DiskHealthStats`

| Metric | Granularity | Full Metric Name Example | Description |
|--------|-------------|--------------------------|-------------|
| `disk_healthy` | Per-host | `total--disk_healthy.Gauge` | Boolean indicator (1=healthy, 0=unhealthy) |
| `disk_health_check_failure_count` | Per-host | `total--disk_health_check_failure_count.Count` | Consecutive health check failures |

## State Transition Metrics

**Class:** `ParticipantStateTransitionStats`

| Metric | Granularity | Full Metric Name Example | Description |
|--------|-------------|--------------------------|-------------|
| `offline_to_standby_latency` | Per-store + Per-version | `MyStore_v1--offline_to_standby_latency.Avg`, `MyStore_v1--offline_to_standby_latency.Max` | OFFLINE->STANDBY transition time |
| `standby_to_leader_latency` | Per-store + Per-version | `MyStore_v1--standby_to_leader_latency.Max`, `MyStore_v1--standby_to_leader_latency.95thPercentile` | STANDBY->LEADER transition time |
| `leader_to_standby_latency` | Per-store + Per-version | `MyStore_v1--leader_to_standby_latency.Max`, `MyStore_v1--leader_to_standby_latency.Avg` | LEADER->STANDBY transition time |
| `standby_to_offline_latency` | Per-store + Per-version | `MyStore_v1--standby_to_offline_latency.Max`, `MyStore_v1--standby_to_offline_latency.Avg` | STANDBY->OFFLINE transition time |
| `offline_to_dropped_latency` | Per-store + Per-version | `MyStore_v1--offline_to_dropped_latency.Max`, `MyStore_v1--offline_to_dropped_latency.95thPercentile` | OFFLINE->DROPPED transition time |
| `thread_blocked_on_offline_to_dropped_count` | Per-store + Per-version | `MyStore_v1--thread_blocked_on_offline_to_dropped_count.Gauge` | Threads waiting on graceful drop |

## Blob Transfer Metrics

**Class:** `AggVersionedBlobTransferStats`

| Metric | Granularity | Full Metric Name Example | Description |
|--------|-------------|--------------------------|-------------|
| `blob_transfer_file_receive_throughput` | Per-store + Per-version | `MyStore_v1--blob_transfer_file_receive_throughput.Rate`, `MyStore--blob_transfer_file_receive_throughput.Avg` | File receive throughput |
| `blob_transfer_time` | Per-store + Per-version | `MyStore_v1--blob_transfer_time.Max`, `MyStore--blob_transfer_time.95thPercentile` | Total transfer time |
| `blob_transfer_file_count` | Per-store + Per-version | `MyStore_v1--blob_transfer_file_count.Count`, `MyStore--blob_transfer_file_count.Rate` | Files transferred |

## Metric Naming Convention

Venice Server metrics follow specific naming patterns based on their granularity:

### Granularity Patterns

| Granularity | Pattern | Example |
|-------------|---------|----------|
| **Per-store** | `{store_name}--{metric_name}` | `MyStore--success_request` |
| **Per-version** | `{store_name}_v{version}--{metric_name}` | `MyStore_v3--records_consumed` |
| **Per-partition** | `{store_name}_v{version}_{partition}--{metric_name}` | `MyStore_v3_0--idle_time.Rate` |
| **Per-region** | `{store_name}--{region}_{metric_name}` | `MyStore--us-east-1_rt_records_consumed` |
| **Server-wide total** | `total--{metric_name}` | `total--success_request` |
| **Host-level** | `total--{metric_name}` | `total--disk_healthy.Gauge` |

### Metric Stat Suffixes

When metrics include statistical aggregations, they use dot notation:

| Stat Type | Suffix | Example |
|-----------|--------|----------|
| **Rate** | `.Rate` | `MyStore--bytes_consumed.Rate` |
| **Average** | `.Avg` | `MyStore--request_latency.Avg` |
| **Maximum** | `.Max` | `MyStore--request_latency.Max` |
| **Percentiles** | `.{N}thPercentile` | `MyStore--request_latency.99thPercentile` |
| **Count** | `.Count` | `MyStore--error_request.Count` |
| **Gauge** | `.Gauge` | `MyStore--storage_quota_used.Gauge` |

### Complete Examples by Category

**HTTP Request Metrics:**
```
# Per-store request success rate
MyStore--success_request.Rate

# Server-wide request latency 99th percentile
total--success_request_latency.99thPercentile

# Per-store storage engine query latency average
MyStore--storage_engine_query_latency.Avg
```

**Ingestion Metrics:**
```
# Per-version bytes consumed rate
MyStore_v3--bytes_consumed.Rate

# Per-store aggregated leader records consumed
MyStore--leader_records_consumed.Rate

# Per-partition idle time
MyStore_v3_0--idle_time.Rate
MyStore_v3_1--idle_time.Rate
```

**Multi-Region Metrics:**
```
# RT consumption from specific regions
MyStore--us-east-1_rt_records_consumed.Rate
MyStore--us-west-2_rt_bytes_consumed.Rate

# Heartbeat lag by region and version
MyStore_v3--ready_to_serve_leader_lag_us-east-1.Gauge
MyStore_v3--follower_record_level_delay_us-west-2.Gauge
```

**RocksDB Metrics:**
```
# Per-store RocksDB block cache hits
MyStore--rocksdb_block_cache_hit

# Server-wide RocksDB read amplification
total--rocksdb_read_amplification_factor
```

**State Transition Metrics:**
```
# Per-version Helix state transition latencies
MyStore_v3--offline_to_standby_latency.Max
MyStore_v3--standby_to_leader_latency.95thPercentile
```

## Metric Types Reference

| Type | Description | Granularity Notes |
|------|-------------|------------------|
| `OccurrenceRate` | Events per second | Available per-store and total |
| `Rate` | Values per second | Available per-store, per-version, and total |
| `Avg` | Rolling average | Available per-store, per-version, and total |
| `Max` | Rolling maximum | Available per-store, per-version, and total |
| `Min` | Rolling minimum | Available per-store, per-version, and total |
| `Count` | Cumulative count | Available per-store, per-version, and total |
| `Gauge` | Current value | Available per-store, per-version, per-partition, and total |
| `AsyncGauge` | Lazily-evaluated gauge | Primarily used for RocksDB metrics |
| `Percentile` | Distribution percentiles (p50, p95, p99) | Available per-store and total |
| `LongAdderRateGauge` | High-concurrency rate gauge | Used for ingestion metrics per-version |

## Enabling Additional Metrics

### Key-Value Profiling

Enable detailed key/value size profiling:

```properties
# In server config
key.value.profiling.enabled=true
```

This enables fine-grained percentile stats for:
- `request_key_size`
- `request_value_size`

### RocksDB Statistics

RocksDB metrics require statistics collection enabled:

```properties
# Already enabled by default
rocksdb.statistics.enabled=true
```

### OpenTelemetry

Enable OTel metrics export:

```properties
otel.metrics.enabled=true
otel.exporter.endpoint=http://otel-collector:4317
```
