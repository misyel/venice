# Venice Controller Metrics Reference

This document provides a comprehensive reference for all metrics emitted by the Venice Controller. These metrics are essential for monitoring controller health, admin operations, push jobs, and cluster state.

## Table of Contents

- [Overview](#overview)
- [Metric Types](#metric-types)
- [Admin Consumption Metrics](#admin-consumption-metrics)
- [Admin Operation Metrics](#admin-operation-metrics)
- [Add Version Latency Metrics](#add-version-latency-metrics)
- [Push Job Status Metrics](#push-job-status-metrics)
- [Store Health Metrics](#store-health-metrics)
- [Partition Health Metrics](#partition-health-metrics)
- [Topic Management Metrics](#topic-management-metrics)
- [Version Swap Metrics](#version-swap-metrics)
- [System Store Health Metrics](#system-store-health-metrics)
- [HTTP API Metrics](#http-api-metrics)
- [Log Compaction Metrics](#log-compaction-metrics)
- [Error Recovery Metrics](#error-recovery-metrics)
- [Heartbeat Checker Metrics](#heartbeat-checker-metrics)
- [Protocol Version Metrics](#protocol-version-metrics)
- [OpenTelemetry Integration](#opentelemetry-integration)
- [Metric Classes Reference](#metric-classes-reference)
- [Common Alerting Patterns](#common-alerting-patterns)

---

## Overview

Venice Controller emits metrics through:
- **Tehuti**: Primary metrics framework
- **OpenTelemetry**: Modern observability (for selected metrics)

Metrics are organized by subsystem:
- **Admin Consumption**: Admin topic message processing
- **Admin Operations**: Store/version/schema operations
- **Push Jobs**: Push status and progress
- **Cluster Health**: Partition and store health
- **API Performance**: REST/gRPC endpoint metrics

---

## Metric Types

| Type | Description | Use Case |
|------|-------------|----------|
| **Counter** | Monotonically increasing count | Operation counts, errors |
| **Gauge** | Point-in-time value | Current state, counts |
| **AsyncGauge** | Lazily computed gauge | Dynamic values |
| **Histogram** | Distribution with percentiles | Latencies, durations |
| **Rate/OccurrenceRate** | Events per second | Throughput, error rates |
| **Total** | Up-down counter | In-flight counts |

---

## Admin Consumption Metrics

These metrics track admin message consumption from Kafka admin topics.

**Stats Class**: `AdminConsumptionStats`
**Location**: `services/venice-controller/.../stats/AdminConsumptionStats.java`

### Message Processing

| Metric | Type | Unit | Description |
|--------|------|------|-------------|
| `failed_admin_messages` | Counter | Count | Failed admin messages in past minute; resets when blocked message is processed |
| `failed_retriable_admin_messages` | Counter | Count | Failed messages that will be retried |
| `admin_message_div_error_report_count` | Counter | Count | Data Integrity Verification (DIV) errors reported |
| `failed_admin_message_offset` | AsyncGauge | Offset | Offset of the blocking failed message |

### Consumption Progress

| Metric | Type | Unit | Description |
|--------|------|------|-------------|
| `admin_consumption_offset_lag` | AsyncGauge | Offset | Current offset lag (end offset - consumed offset) |
| `max_admin_consumption_offset_lag` | AsyncGauge | Offset | Max offset lag (end offset - persisted offset) |
| `pending_admin_messages_count` | AsyncGauge | Count | Messages pending in internal queue |
| `stores_with_pending_admin_messages_count` | AsyncGauge | Count | Stores with pending messages |

### Latency Breakdown

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                  Admin Message Latency Breakdown                             │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  Total Latency (admin_message_total_latency_ms)                             │
│  ├─────────────────────────────────────────────────────────────────────────│
│  │                                                                          │
│  │  ┌────────────────┐  ┌─────────────────┐  ┌─────────────────────────┐  │
│  │  │ MM Latency     │  │ Delegate        │  │ Processing Latency      │  │
│  │  │                │  │ Latency         │  │                         │  │
│  │  │ Mirror Maker   │  │ Queue to        │  │ First attempt to        │  │
│  │  │ copy time      │  │ processing      │  │ completion              │  │
│  │  └────────────────┘  └─────────────────┘  └─────────────────────────┘  │
│  │                                                                          │
│  │  admin_message_   admin_message_        admin_message_process_          │
│  │  mm_latency_ms    delegate_latency_ms   latency_ms                      │
│  │                                                                          │
│  └─────────────────────────────────────────────────────────────────────────│
│                                                                              │
│  Additional:                                                                │
│    admin_message_start_processing_latency_ms - Time until first attempt    │
│    admin_message_add_version_process_latency_ms - ADD_VERSION specific     │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

| Metric | Type | Unit | Description |
|--------|------|------|-------------|
| `admin_consumption_cycle_duration_ms` | Histogram | ms | Duration of each consumption cycle |
| `admin_message_mm_latency_ms` | Histogram | ms | Mirror Maker latency (parent → child) |
| `admin_message_delegate_latency_ms` | Histogram | ms | Queue to processing latency |
| `admin_message_start_processing_latency_ms` | Histogram | ms | Time until first processing attempt |
| `admin_message_process_latency_ms` | Histogram | ms | Processing time (excl. ADD_VERSION) |
| `admin_message_add_version_process_latency_ms` | Histogram | ms | ADD_VERSION processing time |
| `admin_message_total_latency_ms` | Histogram | ms | End-to-end latency |

### Protocol Version

| Metric | Type | Description |
|--------|------|-------------|
| `admin_messages_with_future_protocol_version_count` | Counter | Messages with future protocol version |

---

## Admin Operation Metrics

Track administrative operation outcomes.

**Stats Class**: `VeniceAdminStats`
**Location**: `services/venice-controller/.../stats/VeniceAdminStats.java`

| Metric | Type | Description | Trigger |
|--------|------|-------------|---------|
| `unexpected_topic_absence_during_incremental_push_count` | Counter | Missing topics during incremental push | When version topic is missing |
| `successfully_started_user_batch_push_parent_admin_count` | Counter | Successful batch push starts | When batch push begins |
| `successful_started_user_incremental_push_parent_admin_count` | Counter | Successful incremental push starts | When incremental push begins |
| `failed_serializing_admin_operation_message_count` | Counter | Admin message serialization failures | When serialization fails |

---

## Add Version Latency Metrics

Detailed latency breakdown for version creation operations.

**Stats Class**: `AddVersionLatencyStats`
**Location**: `services/venice-controller/.../stats/AddVersionLatencyStats.java`

| Metric | Type | Unit | Description |
|--------|------|------|-------------|
| `add_version_retire_old_versions_latency` | Histogram | ms | Time to retire old versions |
| `add_version_resource_assignment_wait_latency` | Histogram | ms | Time waiting for Helix assignments |
| `add_version_creation_failure_latency` | Histogram | ms | Latency during failure handling |
| `add_version_existing_source_handling_latency` | Histogram | ms | Time handling existing source version |
| `add_version_start_of_push_latency` | Histogram | ms | Time to send start-of-push signal |
| `add_version_batch_topic_creation_latency` | Histogram | ms | Time to create batch topics |
| `add_version_helix_resource_creation_latency` | Histogram | ms | Time to create Helix resources |

### Version Creation Timeline

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    Add Version Latency Components                            │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  Request arrives                                                            │
│     │                                                                        │
│     ├─── existing_source_handling_latency                                   │
│     │    (check if source version exists)                                   │
│     │                                                                        │
│     ├─── batch_topic_creation_latency                                       │
│     │    (create Kafka version topic)                                       │
│     │                                                                        │
│     ├─── helix_resource_creation_latency                                    │
│     │    (create Helix resource for topic)                                  │
│     │                                                                        │
│     ├─── resource_assignment_wait_latency                                   │
│     │    (wait for Helix to assign partitions)                              │
│     │                                                                        │
│     ├─── start_of_push_latency                                              │
│     │    (send SOP control message)                                         │
│     │                                                                        │
│     └─── retire_old_versions_latency                                        │
│          (cleanup old versions)                                             │
│                                                                              │
│  On Failure:                                                                │
│     └─── creation_failure_latency                                           │
│          (cleanup and error handling)                                       │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## Push Job Status Metrics

Track push job outcomes by type and error category.

**Stats Class**: `PushJobStatusStats`
**Location**: `services/venice-controller/.../stats/PushJobStatusStats.java`

### Batch Push Jobs

| Metric | Type | Description |
|--------|------|-------------|
| `batch_push_job_success` | Counter + Rate | Successful batch pushes |
| `batch_push_job_failed_user_error` | Counter + Rate | Failed due to user error (bad data, schema) |
| `batch_push_job_failed_non_user_error` | Counter + Rate | Failed due to system error (infra, timeout) |

### Incremental Push Jobs

| Metric | Type | Description |
|--------|------|-------------|
| `incremental_push_job_success` | Counter + Rate | Successful incremental pushes |
| `incremental_push_job_failed_user_error` | Counter + Rate | Failed due to user error |
| `incremental_push_job_failed_non_user_error` | Counter + Rate | Failed due to system error |

### Error Categories

| Category | Examples |
|----------|----------|
| **User Error** | Invalid schema, bad data format, quota exceeded |
| **Non-User Error** | Kafka unavailable, Helix timeout, controller failure |

---

## Store Health Metrics

Track store-level health and data freshness.

**Stats Class**: `StoreStats`
**Location**: `services/venice-controller/.../stats/StoreStats.java`

| Metric | Type | Unit | Dimensions | Description |
|--------|------|------|------------|-------------|
| `data_age_ms` | AsyncGauge | ms | Store name | Age of oldest version (time since creation) |

**Notes**:
- Returns -1 on error
- Created per store via `AggStoreStats`
- High values indicate stale data or push issues

---

## Partition Health Metrics

Track partition replication health.

**Stats Class**: `PartitionHealthStats`, `AggPartitionHealthStats`
**Location**: `services/venice-controller/.../stats/PartitionHealthStats.java`

| Metric | Type | Scope | Description |
|--------|------|-------|-------------|
| `underReplicatedPartition` | Gauge (Max) | Per-resource | Under-replicated partitions for a resource |
| `underReplicatedPartition` | Gauge (Max) | Cluster-wide | Total under-replicated partitions |

### Under-Replication Definition

```
Under-replicated partition = actual_replicas < configured_replication_factor

Example:
  Store: MyStore_v3
  Replication Factor: 3
  Partition 5: Only 2 replicas online
  → underReplicatedPartition += 1
```

---

## Topic Management Metrics

Track Kafka topic cleanup operations.

**Stats Class**: `TopicCleanupServiceStats`
**Location**: `services/venice-controller/.../stats/TopicCleanupServiceStats.java`

| Metric | Type | Description |
|--------|------|-------------|
| `deletable_topics_count` | Gauge | Topics eligible for deletion |
| `topics_deleted_rate` | Rate | Topic deletion rate |
| `topic_deletion_error_rate` | Rate | Topic deletion error rate |

---

## Version Swap Metrics

Track deferred version swap operations.

**Stats Class**: `DeferredVersionSwapStats`
**Location**: `services/venice-controller/.../stats/DeferredVersionSwapStats.java`

| Metric | Type | Description |
|--------|------|-------------|
| `deferred_version_swap_error` | Counter | Errors during deferred swap |
| `deferred_version_swap_throwable` | Counter | Exceptions during swap |
| `deferred_version_swap_failed_roll_forward` | Counter | Failed roll-forward after swap failure |
| `deferred_version_swap_stalled_version_swap` | Gauge | Stalled swaps (no progress) |
| `deferred_version_swap_parent_child_status_mismatch` | Counter | Parent-child status mismatches |
| `deferred_version_swap_child_status_mismatch` | Counter | Child controller status mismatches |

### Version Swap State Machine

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                     Version Swap Monitoring                                  │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│                      ┌─────────────────┐                                    │
│                      │ Swap Requested  │                                    │
│                      └────────┬────────┘                                    │
│                               │                                              │
│            ┌──────────────────┼──────────────────┐                          │
│            │                  │                  │                           │
│            v                  v                  v                           │
│     ┌───────────┐      ┌───────────┐      ┌───────────┐                     │
│     │  Success  │      │  Stalled  │      │   Error   │                     │
│     │           │      │           │      │           │                     │
│     │ Swap      │      │ No        │      │ Exception │                     │
│     │ completes │      │ progress  │      │ thrown    │                     │
│     └───────────┘      └─────┬─────┘      └─────┬─────┘                     │
│                              │                  │                            │
│                              │                  v                            │
│              stalled_version_swap        deferred_version_swap_error        │
│                                          deferred_version_swap_throwable    │
│                                                                              │
│  Status Mismatch Detection:                                                 │
│    • parent_child_status_mismatch - Parent and child disagree               │
│    • child_status_mismatch - Children disagree with each other              │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## System Store Health Metrics

Track internal system store health.

**Stats Class**: `SystemStoreHealthCheckStats`
**Location**: `services/venice-controller/.../stats/SystemStoreHealthCheckStats.java`

| Metric | Type | Description |
|--------|------|-------------|
| `bad_meta_system_store_count` | AsyncGauge | Unhealthy meta system stores |
| `bad_push_status_system_store_count` | AsyncGauge | Unhealthy push status system stores |
| `not_repairable_system_store_count` | AsyncGauge | System stores that cannot be repaired |

**Used by**: `SystemStoreRepairService`

---

## HTTP API Metrics

Track REST/gRPC API performance.

**Stats Class**: `SparkServerStats`
**Location**: `services/venice-controller/.../stats/SparkServerStats.java`

### Request Counts

| Metric | Type | Dimensions | Description |
|--------|------|------------|-------------|
| `request` | Counter + Rate | None | Total requests received |
| `finished_request` | Counter + Rate | None | Completed requests |
| `current_in_flight_request` | Total | Endpoint | Current in-flight requests |
| `successful_request` | Counter | Endpoint, Status | Successful requests (2XX) |
| `failed_request` | Counter | Endpoint, Status | Failed requests |

### Request Latency

| Metric | Type | Unit | Dimensions | Description |
|--------|------|------|------------|-------------|
| `successful_request_latency` | Histogram | ms | Endpoint, Status | Latency for successful requests |
| `failed_request_latency` | Histogram | ms | Endpoint, Status | Latency for failed requests |

### Dimensions

| Dimension | Values |
|-----------|--------|
| `VENICE_CLUSTER_NAME` | Cluster name |
| `VENICE_CONTROLLER_ENDPOINT` | API endpoint path |
| `HTTP_RESPONSE_STATUS_CODE` | 200, 400, 500, etc. |
| `HTTP_RESPONSE_STATUS_CODE_CATEGORY` | 2XX, 4XX, 5XX |
| `VENICE_RESPONSE_STATUS_CATEGORY` | SUCCESS, FAIL |

---

## Log Compaction Metrics

Track scheduled log compaction (repush) operations.

**Stats Class**: `LogCompactionStats`
**Location**: `services/venice-controller/.../stats/LogCompactionStats.java`

| Metric | Type | Dimensions | Description |
|--------|------|------------|-------------|
| `store.repush.call_count` | Counter + Rate | Store, Status, Source | All repush requests |
| `store.compaction.nominated_count` | Counter + Rate | Store | Stores nominated for compaction |
| `store.compaction.eligible_state` | Gauge | Store | 1 = nominated, 0 = complete |
| `store.compaction.triggered_count` | Counter + Rate | Store, Status | Compaction repushes triggered |

### Compaction Workflow

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                      Log Compaction Workflow                                 │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  1. Store nominated for compaction                                          │
│     └─→ store.compaction.nominated_count++                                  │
│     └─→ store.compaction.eligible_state = 1                                 │
│                                                                              │
│  2. Compaction triggered (repush initiated)                                 │
│     └─→ store.compaction.triggered_count++                                  │
│     └─→ store.repush.call_count++ (source=SCHEDULED_FOR_LOG_COMPACTION)     │
│                                                                              │
│  3. Repush completes                                                        │
│     └─→ store.compaction.eligible_state = 0                                 │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## Error Recovery Metrics

Track error partition recovery operations.

**Stats Class**: `ErrorPartitionStats`
**Location**: `services/venice-controller/.../stats/ErrorPartitionStats.java`

| Metric | Type | Description |
|--------|------|-------------|
| `current_version_error_partition_reset_attempt` | Total | Reset attempts made |
| `current_version_error_partition_reset_attempt_errored` | Counter | Reset attempts that failed |
| `current_version_error_partition_recovered_from_reset` | Total | Partitions successfully recovered |
| `current_version_error_partition_unrecoverable_from_reset` | Total | Partitions that cannot be recovered |
| `error_partition_processing_time` | Histogram | Time to process error partitions |

### Disabled Partition Tracking

**Stats Class**: `DisabledPartitionStats`

| Metric | Type | Description |
|--------|------|-------------|
| `disabled_partition_count` | Total | Current count of disabled partitions |

---

## Heartbeat Checker Metrics

Track push job heartbeat monitoring.

**Stats Class**: `HeartbeatBasedCheckerStats`
**Location**: `services/venice-controller/.../lingeringjob/HeartbeatBasedCheckerStats.java`

| Metric | Type | Description |
|--------|------|-------------|
| `check_job_has_heartbeat_failed` | Counter | Jobs with no heartbeat (possibly dead) |
| `timeout_heartbeat_check` | Counter | Heartbeat checks that timed out |
| `non_timeout_heartbeat_check` | Counter | Heartbeat checks that completed |

---

## Protocol Version Metrics

Track protocol version auto-detection.

**Stats Class**: `ProtocolVersionAutoDetectionStats`
**Location**: `services/venice-controller/.../stats/ProtocolVersionAutoDetectionStats.java`

| Metric | Type | Unit | Description |
|--------|------|------|-------------|
| `protocol_version_auto_detection_error` | Gauge | Count | Detection errors |
| `protocol_version_auto_detection_latency` | Histogram | ms | Detection latency |

---

## Backup Version Cleanup Metrics

Track backup version cleanup operations.

**Stats Class**: `StoreBackupVersionCleanupServiceStats`
**Location**: `services/venice-controller/.../stats/StoreBackupVersionCleanupServiceStats.java`

| Metric | Type | Description |
|--------|------|-------------|
| `backup_version_cleanup_version_mismatch` | OccurrenceRate | Version mismatches during cleanup |

---

## OpenTelemetry Integration

Selected metrics support OpenTelemetry for modern observability.

### OTel Metric Entities

**Defined in**: `ControllerMetricEntity.java`

| Entity | Type | Unit | Description |
|--------|------|------|-------------|
| `INFLIGHT_CALL_COUNT` | UpDownCounter | Count | Current in-flight API calls |
| `CALL_COUNT` | Counter | Count | Total API calls |
| `CALL_TIME` | Histogram | ms | API call latency |
| `STORE_REPUSH_CALL_COUNT` | Counter | Count | Store repush requests |
| `STORE_COMPACTION_NOMINATED_COUNT` | Counter | Count | Compaction nominations |
| `STORE_COMPACTION_ELIGIBLE_STATE` | Gauge | State | Compaction eligibility (0/1) |
| `STORE_COMPACTION_TRIGGERED_COUNT` | Counter | Count | Compaction triggers |

### OTel Dimensions

| Dimension | Description |
|-----------|-------------|
| `VENICE_CLUSTER_NAME` | Cluster identifier |
| `VENICE_STORE_NAME` | Store name |
| `VENICE_CONTROLLER_ENDPOINT` | API endpoint |
| `HTTP_RESPONSE_STATUS_CODE` | HTTP status |
| `HTTP_RESPONSE_STATUS_CODE_CATEGORY` | Status category |
| `VENICE_RESPONSE_STATUS_CODE_CATEGORY` | Venice status |
| `STORE_REPUSH_TRIGGER_SOURCE` | Repush trigger source |

---

## Metric Classes Reference

| Class | Location | Description |
|-------|----------|-------------|
| `AdminConsumptionStats` | `.../stats/AdminConsumptionStats.java` | Admin message consumption |
| `VeniceAdminStats` | `.../stats/VeniceAdminStats.java` | Admin operation outcomes |
| `AddVersionLatencyStats` | `.../stats/AddVersionLatencyStats.java` | Version creation latency |
| `PushJobStatusStats` | `.../stats/PushJobStatusStats.java` | Push job outcomes |
| `StoreStats` | `.../stats/StoreStats.java` | Per-store health |
| `PartitionHealthStats` | `.../stats/PartitionHealthStats.java` | Partition replication |
| `AggPartitionHealthStats` | `.../stats/AggPartitionHealthStats.java` | Aggregated partition health |
| `TopicCleanupServiceStats` | `.../stats/TopicCleanupServiceStats.java` | Topic cleanup |
| `DeferredVersionSwapStats` | `.../stats/DeferredVersionSwapStats.java` | Version swaps |
| `SystemStoreHealthCheckStats` | `.../stats/SystemStoreHealthCheckStats.java` | System store health |
| `SparkServerStats` | `.../stats/SparkServerStats.java` | HTTP API metrics |
| `LogCompactionStats` | `.../stats/LogCompactionStats.java` | Log compaction |
| `ErrorPartitionStats` | `.../stats/ErrorPartitionStats.java` | Error recovery |
| `DisabledPartitionStats` | `.../stats/DisabledPartitionStats.java` | Disabled partitions |
| `HeartbeatBasedCheckerStats` | `.../lingeringjob/HeartbeatBasedCheckerStats.java` | Job heartbeats |
| `ProtocolVersionAutoDetectionStats` | `.../stats/ProtocolVersionAutoDetectionStats.java` | Protocol detection |
| `StoreBackupVersionCleanupServiceStats` | `.../stats/StoreBackupVersionCleanupServiceStats.java` | Backup cleanup |
| `ControllerMetricEntity` | `.../stats/ControllerMetricEntity.java` | OTel definitions |

---

## Common Alerting Patterns

### Failed Admin Messages

```
Alert: failed_admin_messages > 0 for > 5 minutes
Metric: failed_admin_messages (Counter)
Severity: Critical
Action: Check failed_admin_message_offset, investigate blocking message
```

### High Admin Consumption Lag

```
Alert: admin_consumption_offset_lag > threshold
Metric: admin_consumption_offset_lag (AsyncGauge)
Severity: Warning
Action: Check admin consumer health, Kafka connectivity
```

### Push Job Failures

```
Alert: batch_push_job_failed_non_user_error_rate > threshold
Metric: batch_push_job_failed_non_user_error (Rate)
Severity: High
Action: Investigate infrastructure issues, check Kafka/Helix health
```

### Under-Replicated Partitions

```
Alert: underReplicatedPartition > 0 for > 10 minutes
Metric: underReplicatedPartition (Gauge)
Severity: Warning
Action: Check server health, investigate failed replicas
```

### Stalled Version Swaps

```
Alert: deferred_version_swap_stalled_version_swap > 0
Metric: deferred_version_swap_stalled_version_swap (Gauge)
Severity: Warning
Action: Investigate swap progress, check child controller status
```

### API Errors

```
Alert: failed_request_rate > threshold
Metric: failed_request (Rate)
Dimensions: VENICE_CONTROLLER_ENDPOINT
Severity: High
Action: Check endpoint-specific logs, investigate error causes
```

### System Store Health

```
Alert: bad_meta_system_store_count > 0
Metric: bad_meta_system_store_count (AsyncGauge)
Severity: Warning
Action: Check SystemStoreRepairService logs, manual intervention may be needed
```

### Topic Cleanup Errors

```
Alert: topic_deletion_error_rate > threshold
Metric: topic_deletion_error_rate (Rate)
Severity: Low
Action: Check Kafka admin permissions, investigate stuck topics
```

### Missing Heartbeats

```
Alert: check_job_has_heartbeat_failed_rate > threshold
Metric: check_job_has_heartbeat_failed (Rate)
Severity: Warning
Action: Check push job health, investigate hung jobs
```

---

## Metric Recording Locations

### Admin Consumption

| Event | Location | Metrics |
|-------|----------|---------|
| Message consumed | `AdminConsumptionTask` | `admin_consumption_cycle_duration_ms` |
| Message delegated | `AdminConsumptionTask` | `admin_message_delegate_latency_ms` |
| Message processed | `AdminExecutionTask` | `admin_message_process_latency_ms` |
| Processing failed | `AdminExecutionTask` | `failed_admin_messages` |

### Push Job Status

| Event | Location | Metrics |
|-------|----------|---------|
| Push started | `VeniceParentHelixAdmin` | `successfully_started_*_push_*` |
| Push completed | `JobRoutes` | `*_push_job_success` |
| Push failed | `JobRoutes` | `*_push_job_failed_*` |

### API Requests

| Event | Location | Metrics |
|-------|----------|---------|
| Request received | `AdminSparkServer` | `request`, `current_in_flight_request++` |
| Request completed | `AdminSparkServer` | `finished_request`, `*_request_latency` |
| Request succeeded | `AdminSparkServer` | `successful_request` |
| Request failed | `AdminSparkServer` | `failed_request` |

---

## Summary Statistics

| Category | Metric Count |
|----------|--------------|
| Admin Consumption | 15+ |
| Admin Operations | 4 |
| Add Version Latency | 7 |
| Push Job Status | 6 |
| Store Health | 1 |
| Partition Health | 2 |
| Topic Management | 3 |
| Version Swap | 6 |
| System Store Health | 3 |
| HTTP API | 7+ |
| Log Compaction | 4 |
| Error Recovery | 6 |
| Heartbeat Checker | 3 |
| Protocol Version | 2 |
| Backup Cleanup | 1 |
| **Total** | **70+** |

---

## See Also

- [Controller Overview](controller_overview.md) - Quick entry point
- [Controller Architecture](controller_architecture.md) - Architecture documentation
- [Controller Troubleshooting](controller_troubleshooting.md) - Operational guide
- [Router Metrics Reference](../router/router_metrics.md) - Router metrics
- [Key Classes Map](../../../.claude/rules/key-classes.md) - Important classes
