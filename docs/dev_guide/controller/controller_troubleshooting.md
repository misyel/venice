# Venice Controller Troubleshooting Guide

This guide provides operational procedures for diagnosing and resolving common Venice Controller issues.

## Common Issues and Resolutions

### 1. Stuck Admin Messages

**Symptoms:**
- `failed_admin_messages` counter > 0
- New store operations hang
- Admin consumption lag increasing

**Diagnosis:**
```bash
# Check admin consumption metrics
java -jar venice-admin-tool.jar --admin-topic-metadata \
  --cluster prod-cluster

# Check failed message offset
curl "http://controller:5555/admin/admin_topic_metadata?cluster=prod-cluster"
```

**Resolution:**

Option A: Fix the underlying issue
```bash
# Check controller logs for error details
grep "AdminExecutionTask" /var/log/venice/controller.log | tail -100
```

Option B: Skip the stuck message
```bash
java -jar venice-admin-tool.jar --skip-admin-message \
  --cluster prod-cluster \
  --offset <failed_offset>
```

### 2. Push Job Failures

**Symptoms:**
- Push job status shows ERROR
- Version never reaches ONLINE status

**Diagnosis:**
```bash
# Get push status details
java -jar venice-admin-tool.jar --job-status \
  --cluster prod-cluster \
  --topic my-store_v4

# Check partition-level status
curl "http://controller:5555/offline_push_info?cluster=prod-cluster&topic=my-store_v4"
```

**Common Causes and Fixes:**

| Cause | Fix |
|-------|-----|
| Schema mismatch | Register correct schema, retry push |
| Kafka unavailable | Check Kafka health, retry |
| Server capacity | Add servers, retry |
| Timeout | Increase timeout, retry |

**Kill and Retry:**
```bash
# Kill failed push
java -jar venice-admin-tool.jar --kill-push \
  --cluster prod-cluster \
  --topic my-store_v4

# Retry push (from VPJ/producer)
```

### 3. Version Swap Failures

**Symptoms:**
- Version stuck in PUSHED status
- `deferred_version_swap_stalled_version_swap` > 0

**Diagnosis:**
```bash
# Check version status
java -jar venice-admin-tool.jar --describe-store \
  --cluster prod-cluster \
  --store my-store

# Check all partitions ready
curl "http://controller:5555/job-status?cluster=prod-cluster&store=my-store&version=4"
```

**Resolution:**
```bash
# Force version swap (if partitions are ready)
java -jar venice-admin-tool.jar --set-version \
  --cluster prod-cluster \
  --store my-store \
  --version 4

# Or rollback to previous version
java -jar venice-admin-tool.jar --rollback \
  --cluster prod-cluster \
  --store my-store
```

### 4. Helix Partition Issues

**Symptoms:**
- `underReplicatedPartition` > 0
- Some partitions not serving

**Diagnosis:**
```bash
# List partition assignments
java -jar venice-admin-tool.jar --describe-store \
  --cluster prod-cluster \
  --store my-store

# Check Helix ExternalView
curl "http://controller:5555/cluster_health_stores?cluster=prod-cluster"
```

**Resolution:**
```bash
# Reset stuck partitions
java -jar venice-admin-tool.jar --reset-partition \
  --cluster prod-cluster \
  --store my-store \
  --partition 5

# Disable/enable for rebalance
java -jar venice-admin-tool.jar --disable-store \
  --cluster prod-cluster \
  --store my-store

java -jar venice-admin-tool.jar --enable-store \
  --cluster prod-cluster \
  --store my-store
```

### 5. Controller Leadership Issues

**Symptoms:**
- Operations fail with "not leader" errors
- Multiple controllers claim leadership

**Diagnosis:**
```bash
# Check current leader
java -jar venice-admin-tool.jar --get-leader-controller \
  --cluster prod-cluster

# Check all controllers
curl "http://controller1:5555/leader_controller?cluster=prod-cluster"
curl "http://controller2:5555/leader_controller?cluster=prod-cluster"
```

**Resolution:**
```bash
# Restart non-leader controllers first
sudo systemctl restart venice-controller  # on standby

# If split-brain, restart all and let Helix re-elect
```

### 6. ZooKeeper Connection Issues

**Symptoms:**
- Controller startup fails
- Operations timeout

**Diagnosis:**
```bash
# Test ZK connectivity
zkCli.sh -server zk1:2181,zk2:2181 ls /venice

# Check ZK session status in logs
grep "ZooKeeper" /var/log/venice/controller.log | tail -50
```

**Resolution:**
- Verify ZK cluster health
- Check network connectivity
- Increase ZK timeout configs if needed

## Diagnostic Commands

### Admin Tool Quick Reference

```bash
# Store operations
java -jar venice-admin-tool.jar --describe-store --cluster C --store S
java -jar venice-admin-tool.jar --list-stores --cluster C

# Version operations
java -jar venice-admin-tool.jar --job-status --cluster C --topic T
java -jar venice-admin-tool.jar --kill-push --cluster C --topic T
java -jar venice-admin-tool.jar --set-version --cluster C --store S --version V
java -jar venice-admin-tool.jar --rollback --cluster C --store S

# Cluster operations
java -jar venice-admin-tool.jar --list-nodes --cluster C
java -jar venice-admin-tool.jar --get-leader-controller --cluster C
java -jar venice-admin-tool.jar --cluster-health --cluster C

# Admin message operations
java -jar venice-admin-tool.jar --admin-topic-metadata --cluster C
java -jar venice-admin-tool.jar --skip-admin-message --cluster C --offset O
```

### REST API Quick Reference

```bash
# Health checks
curl "http://controller:5555/leader_controller?cluster=C"
curl "http://controller:5555/cluster_health_stores?cluster=C"
curl "http://controller:5555/cluster_health_instances?cluster=C"

# Store info
curl "http://controller:5555/admin/store?cluster=C&name=S"

# Push status
curl "http://controller:5555/offline_push_info?cluster=C&topic=T"

# Admin topic
curl "http://controller:5555/admin_topic_metadata?cluster=C"
```

## Log Analysis Patterns

### Finding Errors

```bash
# Recent errors
grep -i "error\|exception" /var/log/venice/controller.log | tail -100

# Admin execution errors
grep "AdminExecutionTask.*ERROR" /var/log/venice/controller.log

# Helix errors
grep "Helix\|helix" /var/log/venice/controller.log | grep -i error
```

### Tracking Operations

```bash
# Store operations for specific store
grep "store=my-store" /var/log/venice/controller.log

# Push job lifecycle
grep "my-store_v4" /var/log/venice/controller.log

# Admin message processing
grep "executionId" /var/log/venice/controller.log | tail -50
```

## Metric-Based Debugging

### Key Metrics to Monitor

| Metric | Normal | Alert |
|--------|--------|-------|
| `failed_admin_messages` | 0 | > 0 for 5m |
| `admin_consumption_offset_lag` | < 10 | > 100 |
| `underReplicatedPartition` | 0 | > 0 for 10m |
| `deferred_version_swap_stalled_version_swap` | 0 | > 0 |
| `batch_push_job_failed_non_user_error` rate | < 0.01 | > 0.05 |

### Metric Queries

```promql
# Failed admin messages
sum(failed_admin_messages{cluster="prod-cluster"})

# Admin lag
max(admin_consumption_offset_lag{cluster="prod-cluster"})

# Under-replicated partitions
sum(underReplicatedPartition{cluster="prod-cluster"})

# Push job error rate
rate(batch_push_job_failed_non_user_error_total[5m])
```

## Runbooks

### Runbook: Recovering from Stuck Admin Messages

1. **Identify the stuck message**
   ```bash
   curl "http://controller:5555/admin_topic_metadata?cluster=C"
   # Note the failed_admin_message_offset
   ```

2. **Check error details in logs**
   ```bash
   grep "offset=<offset>" /var/log/venice/controller.log
   ```

3. **Determine resolution**
   - If transient error: restart controller
   - If data error: fix data and skip message
   - If permanent: skip message

4. **Execute skip (if needed)**
   ```bash
   java -jar venice-admin-tool.jar --skip-admin-message \
     --cluster C --offset <offset>
   ```

5. **Verify recovery**
   ```bash
   # Check lag decreasing
   curl "http://controller:5555/admin_topic_metadata?cluster=C"
   ```

### Runbook: Emergency Version Rollback

1. **Identify current and backup versions**
   ```bash
   java -jar venice-admin-tool.jar --describe-store \
     --cluster C --store S
   ```

2. **Verify backup version is healthy**
   ```bash
   # Check backup version has replicas online
   ```

3. **Execute rollback**
   ```bash
   java -jar venice-admin-tool.jar --rollback \
     --cluster C --store S
   ```

4. **Verify rollback**
   ```bash
   java -jar venice-admin-tool.jar --describe-store \
     --cluster C --store S
   # Verify currentVersion changed
   ```

### Runbook: Adding Controller Capacity

1. **Deploy new controller instance**
2. **Configure with same cluster settings**
3. **Start controller**
   - Will join Helix cluster automatically
   - Will become STANDBY
4. **Verify joined**
   ```bash
   curl "http://new-controller:5555/leader_controller?cluster=C"
   ```

## Emergency Contacts

For production issues:
1. Check #venice-oncall channel
2. Page Venice on-call via PagerDuty
3. Escalate to Venice team leads

## See Also

- [Controller Architecture](controller_architecture.md) - Understanding components
- [Controller Data Flow](controller_data_flow.md) - Request/message flow
- [Controller Metrics](controller_metrics.md) - Metrics reference
- [Controller Configuration](controller_configuration.md) - Config tuning
