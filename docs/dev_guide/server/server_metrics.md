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

| Metric | Type | Description |
|--------|------|-------------|
| `success_request` | OccurrenceRate | Rate of successful requests |
| `error_request` | OccurrenceRate | Rate of failed requests |
| `success_request_ratio` | RatioStat | Ratio of successes to total (successes + errors) |
| `early_terminated_request_count` | OccurrenceRate | Rate of requests terminated early due to timeout |
| `misrouted_store_version_request_count` | OccurrenceRate | Rate of requests for non-existent versions |

### Request Latency

| Metric | Stats | Description |
|--------|-------|-------------|
| `success_request_latency` | p99, avg, max | End-to-end latency for successful requests |
| `error_request_latency` | p99, avg, max | End-to-end latency for failed requests |
| `storage_engine_query_latency` | p99, avg, max | RocksDB lookup latency (all values) |
| `storage_engine_query_latency_for_small_value` | p99 | RocksDB latency for values < 1MB |
| `storage_engine_query_latency_for_large_value` | p99 | RocksDB latency for chunked values > 1MB |

### Queue and Submission Metrics

| Metric | Stats | Description |
|--------|-------|-------------|
| `storage_execution_handler_submission_wait_time` | p99, avg, max | Time waiting in executor queue before processing (total only) |
| `storage_execution_queue_len` | max, avg | Executor queue depth at submission time (total only) |

### Request Size Metrics

| Metric | Stats | Description |
|--------|-------|-------------|
| `request_key_count` | rate, avg, max | Keys per request (batch/compute only, not single-get) |
| `request_size_in_bytes` | avg, min, max | Request payload size |
| `request_key_size` | percentile, avg, max | Key size (if profiling enabled or single-get) |
| `request_value_size` | percentile, avg, max | Value size (if profiling enabled or single-get) |

### Large Value / Chunking Metrics

| Metric | Stats | Description |
|--------|-------|-------------|
| `storage_engine_large_value_lookup` | max, rate, avg | Multi-chunk large value assembly stats |

**For Single-Get:** max = 0 or 1
**For Batch-Get:** max = number of large values in batch, avg = average per batch

### Response Metrics

| Metric | Stats | Description |
|--------|-------|-------------|
| `response_size` | percentile | Response payload size |
| `flush_latency` | percentile | Response flush latency |

### Read Compute Metrics

| Metric | Stats | Description |
|--------|-------|-------------|
| `storage_engine_read_compute_latency` | percentile, avg, max | Total compute operation latency |
| `storage_engine_read_compute_latency_for_small_value` | percentile | Compute latency for small values |
| `storage_engine_read_compute_latency_for_large_value` | percentile | Compute latency for chunked values |
| `storage_engine_read_compute_deserialization_latency` | percentile, avg, max | Value deserialization time |
| `storage_engine_read_compute_serialization_latency` | percentile, avg, max | Result serialization time |
| `storage_engine_read_compute_efficiency` | avg, min, max | Compute efficiency ratio |

### Compute Operator Counts (COMPUTE requests only)

| Metric | Stats | Description |
|--------|-------|-------------|
| `dot_product_count` | avg, total | Dot product operations |
| `cosine_similarity_count` | avg, total | Cosine similarity operations |
| `hadamard_product_count` | avg, total | Hadamard product operations |
| `count_operator_count` | avg, total | Count operations |

## RocksDB Storage Metrics

**Class:** `RocksDBStats`
**Location:** `services/venice-server/src/main/java/com/linkedin/venice/stats/RocksDBStats.java`

All RocksDB metrics use `AsyncGauge` for lazy evaluation.

### Block Cache Metrics (Overall)

| Metric | Description |
|--------|-------------|
| `rocksdb_block_cache_hit` | Total block cache hits |
| `rocksdb_block_cache_miss` | Total block cache misses |
| `rocksdb_block_cache_add` | Blocks added to cache |
| `rocksdb_block_cache_add_failures` | Failed cache additions |
| `rocksdb_block_cache_bytes_read` | Bytes read from block cache |
| `rocksdb_block_cache_bytes_write` | Bytes written to block cache |
| `rocksdb_block_cache_hit_ratio` | DATA_HIT / (DATA_HIT + MISS) |

### Block Cache by Component

**Index Blocks:**

| Metric | Description |
|--------|-------------|
| `rocksdb_block_cache_index_hit` | Index block cache hits |
| `rocksdb_block_cache_index_miss` | Index block cache misses |
| `rocksdb_block_cache_index_add` | Index blocks added |
| `rocksdb_block_cache_index_bytes_insert` | Index bytes inserted |

**Filter Blocks (Bloom):**

| Metric | Description |
|--------|-------------|
| `rocksdb_block_cache_filter_hit` | Filter block cache hits |
| `rocksdb_block_cache_filter_miss` | Filter block cache misses |
| `rocksdb_block_cache_filter_add` | Filter blocks added |
| `rocksdb_block_cache_filter_bytes_insert` | Filter bytes inserted |

**Data Blocks:**

| Metric | Description |
|--------|-------------|
| `rocksdb_block_cache_data_hit` | Data block cache hits |
| `rocksdb_block_cache_data_miss` | Data block cache misses |
| `rocksdb_block_cache_data_add` | Data blocks added |
| `rocksdb_block_cache_data_bytes_insert` | Data bytes inserted |

### MemTable Metrics

| Metric | Description |
|--------|-------------|
| `rocksdb_memtable_hit` | Hits served from MemTable |
| `rocksdb_memtable_miss` | Misses requiring SST file lookup |

### LSM Tree Level Metrics

| Metric | Description |
|--------|-------------|
| `rocksdb_get_hit_l0` | Hits in Level 0 (most recent) |
| `rocksdb_get_hit_l1` | Hits in Level 1 |
| `rocksdb_get_hit_l2_and_up` | Hits in Level 2+ |

### Bloom Filter Metrics

| Metric | Description |
|--------|-------------|
| `rocksdb_bloom_filter_useful` | Bloom filter negative checks (avoided disk reads) |

### Amplification Metrics

| Metric | Description |
|--------|-------------|
| `rocksdb_read_amplification_factor` | TOTAL_READ_BYTES / USEFUL_BYTES |
| `rocksdb_compaction_cancelled` | Cancelled compaction operations |

## Ingestion Metrics

**Class:** `IngestionStats`
**Location:** `clients/da-vinci-client/src/main/java/com/linkedin/davinci/stats/IngestionStats.java`

### Consumption Metrics

| Metric | Type | Description |
|--------|------|-------------|
| `records_consumed` | LongAdderRateGauge | Total record consumption rate |
| `bytes_consumed` | LongAdderRateGauge | Total bytes consumption rate |
| `leader_records_consumed` | LongAdderRateGauge | Records consumed as leader |
| `leader_bytes_consumed` | LongAdderRateGauge | Bytes consumed as leader |
| `follower_records_consumed` | LongAdderRateGauge | Records consumed as follower |
| `follower_bytes_consumed` | LongAdderRateGauge | Bytes consumed as follower |
| `leader_records_produced` | LongAdderRateGauge | Records produced by leader (to VT) |
| `leader_bytes_produced` | LongAdderRateGauge | Bytes produced by leader |

### Multi-Region Metrics (per region)

| Metric | Description |
|--------|-------------|
| `{region}_rt_records_consumed` | RT records consumed from region |
| `{region}_rt_bytes_consumed` | RT bytes consumed from region |

### Write Path Latency Metrics

| Metric | Stats | Description |
|--------|-------|-------------|
| `subscribe_action_prep_latency` | avg, max | Subscribe message preparation time |
| `consumed_record_end_to_end_processing_latency` | avg, max | Total record processing time |
| `nearline_producer_to_local_broker_latency` | avg, max | Producer to broker latency |
| `nearline_local_broker_to_ready_to_serve_latency` | avg, max | Broker to ready-to-serve latency |
| `producer_callback_latency` | max | Producer callback completion time |
| `leader_preprocessing_latency` | avg, max | Leader-side preprocessing time |
| `internal_preprocessing_latency` | avg, max | Internal preprocessing time |

### Conflict Resolution Metrics (DCR)

| Metric | Type | Description |
|--------|------|-------------|
| `update_ignored_dcr` | LongAdderRateGauge | Updates ignored due to conflict resolution |
| `total_dcr` | LongAdderRateGauge | Total conflict resolutions performed |
| `timestamp_regression_dcr_error` | LongAdderRateGauge | Timestamp regression errors |
| `offset_regression_dcr_error` | LongAdderRateGauge | Offset regression errors |
| `tombstone_creation_dcr` | LongAdderRateGauge | Tombstones created via CRDT |
| `total_duplicate_key_update_count` | Count | Duplicate key updates received |

### Batch Processing Metrics

| Metric | Stats | Description |
|--------|-------|-------------|
| `batch_processing_request` | rate | Batch processing requests |
| `batch_processing_request_size` | avg, max | Batch request size |
| `batch_processing_request_records` | rate | Records in batch requests |
| `batch_processing_request_latency` | avg, max | Batch processing latency |
| `batch_processing_request_error` | rate | Batch processing errors |

### Task State Metrics

| Metric | Type | Description |
|--------|------|-------------|
| `ingestion_task_errored_gauge` | Gauge | Count of errored ingestion partitions |
| `ingestion_task_push_timeout_gauge` | Gauge | Push timeout indicator |
| `write_compute_operation_failure` | Code | Write compute error code |
| `storage_quota_used` | Gauge | Storage quota usage |
| `idle_time` | Rate | Partition idle time |

## Heartbeat / Lag Metrics

**Class:** `HeartbeatVersionedStats`
**Location:** `clients/da-vinci-client/src/main/java/com/linkedin/davinci/stats/ingestion/heartbeat/HeartbeatVersionedStats.java`

### Leader Lag Metrics

| Metric | Description |
|--------|-------------|
| `ready_to_serve_leader_lag_{region}` | Leader lag for ready-to-serve replicas |
| `leader_record_level_delay_{region}` | Per-record leader delay |

### Follower Lag Metrics

| Metric | Description |
|--------|-------------|
| `ready_to_serve_follower_lag_{region}` | Follower lag for ready-to-serve replicas |
| `catching_up_follower_lag_{region}` | Follower lag during catch-up phase |
| `follower_record_level_delay_{region}` | Per-record follower delay |

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

| Metric | Description |
|--------|-------------|
| `quota_request_key_count` | Keys in quota-checked requests |
| `quota_rejected_request` | Rejected requests due to quota |
| `quota_rejected_key_count` | Rejected keys due to quota |
| `current_quota_request` | Requests for current version |
| `backup_quota_request` | Requests for backup version |

## Connection Metrics

**Class:** `ServerConnectionStats`

| Metric | Description |
|--------|-------------|
| `router_connection_count` | Active router connections |
| `client_connection_count` | Active client connections |

## Disk Health Metrics

**Class:** `DiskHealthStats`

| Metric | Description |
|--------|-------------|
| `disk_healthy` | Boolean indicator (1=healthy, 0=unhealthy) |
| `disk_health_check_failure_count` | Consecutive health check failures |

## State Transition Metrics

**Class:** `ParticipantStateTransitionStats`

| Metric | Description |
|--------|-------------|
| `offline_to_standby_latency` | OFFLINE->STANDBY transition time |
| `standby_to_leader_latency` | STANDBY->LEADER transition time |
| `leader_to_standby_latency` | LEADER->STANDBY transition time |
| `standby_to_offline_latency` | STANDBY->OFFLINE transition time |
| `offline_to_dropped_latency` | OFFLINE->DROPPED transition time |
| `thread_blocked_on_offline_to_dropped_count` | Threads waiting on graceful drop |

## Blob Transfer Metrics

**Class:** `AggVersionedBlobTransferStats`

| Metric | Description |
|--------|-------------|
| `blob_transfer_file_receive_throughput` | File receive throughput |
| `blob_transfer_time` | Total transfer time |
| `blob_transfer_file_count` | Files transferred |

## Metric Naming Convention

Metrics follow this naming pattern:

```
{store_name}--{metric_name}     # Per-store metric
total--{metric_name}            # Aggregated total
```

**Examples:**
```
my_store--success_request
total--success_request
my_store--storage_engine_query_latency.99thPercentile
total--storage_engine_query_latency.Avg
```

## Metric Types Reference

| Type | Description |
|------|-------------|
| `OccurrenceRate` | Events per second |
| `Rate` | Values per second |
| `Avg` | Rolling average |
| `Max` | Rolling maximum |
| `Min` | Rolling minimum |
| `Count` | Cumulative count |
| `Gauge` | Current value |
| `AsyncGauge` | Lazily-evaluated gauge |
| `Percentile` | Distribution percentiles (p50, p95, p99) |
| `LongAdderRateGauge` | High-concurrency rate gauge |

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
