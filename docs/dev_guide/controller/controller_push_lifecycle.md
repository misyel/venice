# Venice Push Lifecycle

This document describes the complete lifecycle of a push job, from requesting a topic to version swap and cleanup.

## Push Lifecycle Phases

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                          Push Lifecycle Phases                               │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  Phase 1: Request Topic                                                     │
│  ──────────────────────                                                     │
│  Client: GET /request_topic?store=S&push_job_id=P                          │
│  Controller:                                                                │
│    • Creates push request topic: S_P                                        │
│    • Returns topic name and Kafka broker info                               │
│                                                                              │
│  Phase 2: Data Push                                                         │
│  ──────────────────                                                         │
│  Client: Writes records to S_P topic                                        │
│  (Venice Push Job or Samza producer)                                        │
│                                                                              │
│  Phase 3: Add Version & Start Ingestion                                     │
│  ─────────────────────────────────────                                      │
│  Client: POST /add_version_and_start_ingestion                             │
│  Controller:                                                                │
│    • Creates Version object (v1, v2, ...)                                  │
│    • Sets status = STARTED                                                  │
│    • Creates version topic: S_v{N}                                          │
│    • Creates Helix resource for S_v{N}                                      │
│    • Servers start consuming from version topic                             │
│                                                                              │
│  Phase 4: Ingestion Progress                                                │
│  ─────────────────────────                                                  │
│  Servers:                                                                   │
│    • Consume from S_v{N} topic                                              │
│    • Persist to RocksDB                                                     │
│    • Report progress via Helix CustomizedView                               │
│  Controller:                                                                │
│    • Aggregates partition progress                                          │
│    • Updates ExecutionStatus                                                │
│                                                                              │
│  Phase 5: End of Push                                                       │
│  ──────────────────                                                         │
│  Client: Sends END_OF_PUSH control message                                  │
│  Controller:                                                                │
│    • Marks version status = PUSHED                                          │
│    • Monitors partition readiness                                           │
│                                                                              │
│  Phase 6: Version Swap                                                      │
│  ────────────────────                                                       │
│  Trigger: All partitions ready OR timeout                                   │
│  Controller:                                                                │
│    • Marks version status = ONLINE                                          │
│    • setStoreCurrentVersion(vN)                                             │
│    • Previous version becomes backup                                        │
│                                                                              │
│  Phase 7: Cleanup                                                           │
│  ──────────────                                                             │
│  TopicCleanupService:                                                       │
│    • Deletes old version topics                                             │
│    • Removes old Helix resources                                            │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

## Version Status State Machine

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                       Version Status State Machine                           │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│                              ┌─────────┐                                    │
│                              │ CREATED │                                    │
│                              └────┬────┘                                    │
│                                   │ addVersionAndStartIngestion()           │
│                                   v                                          │
│                              ┌─────────┐                                    │
│                              │ STARTED │                                    │
│                              └────┬────┘                                    │
│                                   │ END_OF_PUSH received                    │
│                                   v                                          │
│                              ┌─────────┐                                    │
│                              │ PUSHED  │                                    │
│                              └────┬────┘                                    │
│                                   │ All partitions ready                    │
│            ┌──────────────────────┼──────────────────────┐                  │
│            │                      │                      │                   │
│            v                      v                      v                   │
│     ┌─────────────┐        ┌─────────┐          ┌─────────────┐            │
│     │ ERROR       │        │ ONLINE  │          │ KILLED      │            │
│     │             │        │(serving)│          │             │            │
│     │ (push       │        └────┬────┘          │ (push       │            │
│     │  failed)    │             │               │  cancelled) │            │
│     └─────────────┘             │ new version   └─────────────┘            │
│                                 │ becomes ONLINE                            │
│                                 v                                            │
│                           ┌──────────┐                                      │
│                           │ BACKUP   │                                      │
│                           │ (prev    │                                      │
│                           │  version)│                                      │
│                           └────┬─────┘                                      │
│                                │ cleanup                                     │
│                                v                                             │
│                           ┌──────────┐                                      │
│                           │ DELETED  │                                      │
│                           └──────────┘                                      │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

## Version Status Descriptions

| Status | Description | Next States |
|--------|-------------|-------------|
| `CREATED` | Version metadata created | `STARTED` |
| `STARTED` | Ingestion in progress | `PUSHED`, `ERROR`, `KILLED` |
| `PUSHED` | Data push complete, awaiting readiness | `ONLINE`, `ERROR`, `KILLED` |
| `ONLINE` | Actively serving traffic | `BACKUP` |
| `BACKUP` | Previous version, ready for rollback | `ONLINE`, `DELETED` |
| `ERROR` | Push failed | `DELETED` |
| `KILLED` | Push cancelled by operator | `DELETED` |
| `DELETED` | Version removed | (terminal) |

## Push Status Tracking

The controller tracks push progress via `ExecutionStatus`:

| Status | Meaning |
|--------|---------|
| `NOT_STARTED` | Push not yet begun |
| `NEW` | Version created |
| `STARTED` | Ingestion in progress |
| `PROGRESS` | Receiving partition updates |
| `END_OF_PUSH_RECEIVED` | EOP signal received |
| `COMPLETED` | All partitions ready |
| `ERROR` | Push failed |
| `UNKNOWN` | Status cannot be determined |

## Version Rollback/Rollforward

### Rollback to Backup Version

```
POST /rollback_to_backup_version?store=S&cluster=C

Flow:
1. Current version → marked as failed
2. Backup version → becomes current (ONLINE)
3. Traffic shifts to backup version
```

### Rollforward to Future Version

```
POST /roll_forward_to_future_version?store=S&cluster=C

Flow:
1. Skip current version
2. Future version (if ready) → becomes current
3. Used when a version is partially ready but acceptable
```

## Version Creation Timeline

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

## Push Monitoring

### Key Metrics

| Metric | Description |
|--------|-------------|
| `batch_push_job_success` | Successful batch pushes |
| `batch_push_job_failed_user_error` | Failed due to user error |
| `batch_push_job_failed_non_user_error` | Failed due to system error |
| `incremental_push_job_success` | Successful incremental pushes |

### Error Categories

| Category | Examples |
|----------|----------|
| **User Error** | Invalid schema, bad data format, quota exceeded |
| **Non-User Error** | Kafka unavailable, Helix timeout, controller failure |

## Failure Handling

### Common Push Failures

| Failure | Cause | Resolution |
|---------|-------|------------|
| **Schema mismatch** | Data doesn't match registered schema | Fix data or add schema |
| **Partition timeout** | Server can't complete ingestion | Check server health |
| **Quota exceeded** | Store record count/size exceeded | Increase quota |
| **Topic creation failed** | Kafka unavailable | Check Kafka health |

### Push Recovery Options

1. **Retry**: Kill current push and restart
2. **Force Complete**: Mark version ready despite errors (use with caution)
3. **Rollback**: Revert to backup version
4. **Delete Version**: Remove failed version and retry

## Deferred Version Swap

For stores with specific requirements, version swap can be deferred:

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                     Deferred Version Swap                                    │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  Normal: Version swap happens immediately when all partitions ready         │
│                                                                              │
│  Deferred: Version swap waits for:                                          │
│    • Specific time window                                                   │
│    • Multi-region readiness                                                 │
│    • Manual approval                                                        │
│                                                                              │
│  Managed by: DeferredVersionSwapService                                     │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

## Hybrid Store Push Lifecycle

Hybrid stores support both batch and real-time data:

```
Batch Push (Version Topic)     Real-Time Updates (RT Topic)
          │                              │
          v                              v
    ┌──────────┐                 ┌──────────────┐
    │ Version  │                 │ Real-Time    │
    │ Topic    │                 │ Topic        │
    │ S_v{N}   │                 │ S_rt         │
    └────┬─────┘                 └──────┬───────┘
         │                              │
         └──────────┬───────────────────┘
                    │
                    v
             ┌──────────────┐
             │   Server     │
             │  (merges)    │
             └──────────────┘

Rewind time determines how far back RT data is replayed
```

## See Also

- [Controller Architecture](controller_architecture.md) - Version management internals
- [Controller Metrics](controller_metrics.md) - Push job metrics
- [Controller Admin API](controller_admin_api.md) - Push-related endpoints
- [Controller Troubleshooting](controller_troubleshooting.md) - Push failure debugging
