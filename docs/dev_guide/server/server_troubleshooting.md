# Venice Server Troubleshooting Guide

This guide provides operational procedures for diagnosing and resolving common Venice Server issues.

## High Latency Investigation

### Symptoms
- Elevated `success_request_latency`
- Client timeout errors
- Router reporting slow backend responses

### Diagnostic Steps

#### 1. Check Queue Metrics

```
Metric: storage_execution_queue_len
Metric: storage_execution_handler_submission_wait_time
```

**High queue length indicates:**
- Insufficient executor threads
- Slow RocksDB operations
- GC pressure

**Action:**
```properties
# Increase thread pool size
server.compute.thread.num=16
server.database.lookup.queue.capacity=500
```

#### 2. Check RocksDB Performance

```
Metric: storage_engine_query_latency
Metric: rocksdb_block_cache_hit_ratio
Metric: rocksdb_read_amplification_factor
```

**Low cache hit ratio indicates:**
- Insufficient block cache
- Working set larger than cache

**Action:**
```properties
# Increase block cache
rocksdb.block.cache.size.in.bytes=8589934592  # 8GB
```

**High read amplification indicates:**
- Too many SST levels
- Need compaction

**Action:**
- Trigger manual compaction via admin tool
- Review compaction settings

#### 3. Check Large Value Processing

```
Metric: storage_engine_query_latency_for_large_value
Metric: storage_engine_large_value_lookup
```

**High large value latency indicates:**
- Many chunked values being reassembled
- Consider reviewing data model

#### 4. Check Compute Operations

```
Metric: storage_engine_read_compute_latency
Metric: storage_engine_read_compute_deserialization_latency
Metric: storage_engine_read_compute_serialization_latency
```

**High compute latency:**
- Review compute operations being used
- Check value schema complexity

### Quick Diagnostics via Admin Endpoint

```bash
# Dump server configs
curl http://server:admin_port/admin/DUMP_SERVER_CONFIGS

# Check specific store ingestion state
curl "http://server:admin_port/admin/DUMP_INGESTION_STATE?store_version=MyStore_v1"
```

## Ingestion Lag Investigation

### Symptoms
- Stale data being served
- High `ready_to_serve_follower_lag`
- High `ready_to_serve_leader_lag`

### Diagnostic Steps

#### 1. Check Heartbeat Lag

```
Metric: ready_to_serve_leader_lag_{region}
Metric: ready_to_serve_follower_lag_{region}
Metric: catching_up_follower_lag_{region}
```

**Interpretation:**
- High leader lag: Leader not keeping up with RT production
- High follower lag: Follower not keeping up with VT consumption
- Catching-up lag: Replica still bootstrapping

#### 2. Check Consumption Rates

```
Metric: records_consumed
Metric: bytes_consumed
Metric: leader_records_consumed
Metric: follower_records_consumed
```

**Low consumption rate indicates:**
- Consumer pool exhausted
- Kafka connectivity issues
- Slow storage writes

#### 3. Check Consumer Pool

```properties
# Increase consumer pool if exhausted
server.consumer.pool.size.per.kafka.cluster=20
server.dedicated.consumer.pool.for.aa.wc.leader.enabled=true
```

#### 4. Check for Ingestion Errors

```
Metric: ingestion_task_errored_gauge
Metric: write_compute_operation_failure
```

**Non-zero error gauge indicates:**
- Schema compatibility issues
- Data corruption
- Write compute failures

### Heartbeat Lag Query API

```bash
# Query heartbeat lag for a partition
curl "http://server:port/heartbeat?topic=MyStore_v1&partition=0"
```

## Quota Rejection Debugging

### Symptoms
- Client receiving 429 errors
- `quota_rejected_request` increasing

### Diagnostic Steps

#### 1. Check Quota Metrics

```
Metric: quota_request_key_count
Metric: quota_rejected_request
Metric: quota_rejected_key_count
Metric: current_quota_request
Metric: backup_quota_request
```

#### 2. Check Store Quota Settings

```bash
# Via admin tool
venice-admin --cluster <cluster> --get-store <store_name>
# Look for: readQuotaInCU
```

#### 3. Increase Quota if Needed

```bash
# Via admin tool
venice-admin --cluster <cluster> --update-store <store_name> \
  --read-quota-in-cu <new_value>
```

## Memory Issues

### Symptoms
- OOM errors
- High GC pause times
- Degraded performance

### Diagnostic Steps

#### 1. Check JVM Metrics

```
Metric: jvm.memory.heap.used
Metric: jvm.gc.pause.time
Metric: jvm.gc.count
```

#### 2. Check RocksDB Memory

```properties
# Enable memory stats
server.database.memory.stats.enabled=true
```

```
Metric: rocksdb_block_cache_bytes_read
Metric: rocksdb_block_cache_bytes_write
```

#### 3. Review Memory Allocation

Common memory consumers:
1. **RocksDB block cache** - Configurable
2. **Netty direct buffers** - For network I/O
3. **Consumer buffers** - Kafka fetch buffers
4. **Reusable objects** - Thread-local caches

**Recommended JVM Settings:**
```bash
-Xmx16g -Xms16g
-XX:MaxDirectMemorySize=4g
-XX:+UseG1GC
-XX:MaxGCPauseMillis=100
```

## Disk Issues

### Symptoms
- Health check failures
- `disk_healthy` = 0
- Write errors

### Diagnostic Steps

#### 1. Check Disk Health Metrics

```
Metric: disk_healthy
Metric: disk_health_check_failure_count
```

#### 2. Check Disk Space

```bash
df -h /data/venice
```

#### 3. Check I/O Performance

```bash
iostat -x 1
```

**High await times indicate:**
- Disk saturation
- Hardware issues

### Disk Configuration

```properties
# Configure disk thresholds
server.disk.full.threshold=0.90  # Alert at 90% full
server.disk.health.check.interval.in.seconds=30
server.disk.health.check.timeout.in.seconds=10
```

## State Transition Issues

### Symptoms
- Replicas stuck in transitions
- High `thread_blocked_on_offline_to_dropped_count`
- Slow rebalancing

### Diagnostic Steps

#### 1. Check Transition Metrics

```
Metric: offline_to_standby_latency
Metric: standby_to_leader_latency
Metric: offline_to_dropped_latency
Metric: thread_blocked_on_offline_to_dropped_count
```

#### 2. Check Ingestion State

```bash
# Dump ingestion state for specific version
curl "http://server:admin_port/admin/DUMP_INGESTION_STATE?store_version=MyStore_v1"
```

#### 3. Review Helix State

```bash
# Check partition states in ZooKeeper
zkCli.sh -server <zk_address>
get /helix/<cluster>/EXTERNALVIEW/MyStore_v1
```

### Graceful Drop Delay

```properties
# Adjust if drops are too slow
partition.graceful.drop.delay.seconds=15  # Reduce from default 30
```

## Admin Endpoints Reference

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/health` | GET | Health check |
| `/admin/DUMP_INGESTION_STATE?store_version={version}` | GET | Ingestion state dump |
| `/admin/DUMP_INGESTION_STATE?store_version={version}&partition={partition}` | GET | Partition-specific state |
| `/admin/DUMP_SERVER_CONFIGS` | GET | Server configuration dump |
| `/metadata/{store_name}` | GET | Store metadata |
| `/current_version/{store_name}` | GET | Current version info |
| `/heartbeat?topic={topic}&partition={partition}` | GET | Heartbeat lag |
| `/dictionary/{resource_name}` | GET | Compression dictionary |

## Common Error Patterns

### VeniceNoStoreException

**Cause:** Request for non-existent store version

**Resolution:**
- Verify store version exists
- Check if version was recently deleted
- Review router routing configuration

### VeniceRequestEarlyTerminationException

**Cause:** Request timed out before processing

**Resolution:**
- Check queue depths
- Increase client timeouts
- Scale server capacity

### OperationNotAllowedException

**Cause:** Read compute disabled for store

**Resolution:**
- Enable read compute for store:
```bash
venice-admin --cluster <cluster> --update-store <store> \
  --read-computation-enabled true
```

### Session ID Validation Failures

**Cause:** Stale state transition commands

**Resolution:**
- Usually self-resolving
- Monitor for persistent failures
- Check Helix controller health

## Performance Tuning Checklist

### Read Path
- [ ] `storage_execution_queue_len` < 100
- [ ] `storage_execution_handler_submission_wait_time` < 10ms
- [ ] `storage_engine_query_latency` < 5ms (p99)
- [ ] `rocksdb_block_cache_hit_ratio` > 0.9

### Ingestion Path
- [ ] `records_consumed` meeting expected throughput
- [ ] `ready_to_serve_*_lag` < acceptable threshold
- [ ] `ingestion_task_errored_gauge` = 0

### Resource Usage
- [ ] JVM heap < 80% utilization
- [ ] GC pause < 100ms (p99)
- [ ] Disk utilization < 80%
- [ ] CPU utilization < 80%

## Escalation Procedures

### When to Escalate

1. **Data Consistency Issues**
   - Unexpected data loss
   - Replication divergence
   - Corruption indicators

2. **Persistent State Transitions**
   - Replicas stuck > 30 minutes
   - Repeated transition failures

3. **Cluster-Wide Issues**
   - Multiple servers affected
   - Controller connectivity loss
   - ZooKeeper issues

### Information to Collect

1. **Metrics snapshot** - All relevant metrics
2. **Server logs** - ERROR and WARN level
3. **Ingestion state dump** - For affected versions
4. **Helix state** - From ZooKeeper
5. **Timeline** - When issue started
