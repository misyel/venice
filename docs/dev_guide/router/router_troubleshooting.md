# Venice Router Troubleshooting Guide

This guide provides operational procedures for diagnosing and resolving common Venice Router issues.

## High Latency Investigation

### Symptoms
- Elevated `healthy_request_latency`
- Client timeout errors
- Increased `tardy_request` count

### Diagnostic Steps

#### 1. Check Request Latency Breakdown

```
Metric: request_parsing_latency    → Time parsing request
Metric: request_routing_latency    → Time computing routing
Metric: response_waiting_time      → Time waiting for storage node
Metric: decompression_time         → Time decompressing response
```

**If parsing latency is high:**
- Large request payloads
- Complex Avro deserialization
- Consider reducing batch sizes

**If routing latency is high:**
- Slow metadata lookups
- Consider routing computation mode
- Check ZooKeeper latency

**If response waiting time is high:**
- Storage nodes are slow
- Network issues
- Check storage node metrics

#### 2. Check Storage Node Health

```
Metric: pending_request (per node)
Metric: unhealthy_host_count
Metric: host_heartbeat_failure
```

**High pending requests indicate:**
- Storage node overloaded
- Network congestion
- Slow RocksDB queries

**Action:**
```properties
# Lower threshold to trigger faster failover
router.unhealthy.pending.conn.threshold.per.route=50

# Enable stateful health checks
stateful.router.health.check.enabled=true
```

#### 3. Check Retry Impact

```
Metric: retry_count
Metric: allowed_retry_count
Metric: error_retry_count
```

**High retry count indicates:**
- Storage nodes timing out
- Network issues
- Need to tune retry thresholds

**Action:**
```properties
# Adjust retry threshold
long.tail.retry.for.single.get.threshold.ms=20

# Reduce retry budget if retries are wasteful
single.key.long.tail.retry.budget.percent.decimal=0.02
```

## Throttling Issues

### Symptoms
- Clients receiving 429 errors
- `throttled_request` increasing
- `request_throttled_by_router_capacity` increasing

### Diagnostic Steps

#### 1. Check Throttle Metrics

```
Metric: throttled_request
Metric: request_throttled_by_router_capacity
Metric: read_quota_usage
Metric: quota (per store)
```

#### 2. Identify Throttle Source

**Router-level throttling:**
```
Metric: request_throttled_by_router_capacity > 0
```

**Action:**
```properties
# Increase router capacity
router.max.read.capacity.cu=12000

# Or add more routers
```

**Store-level throttling:**
```
Metric: throttled_request (per store) > 0
```

**Action:**
```bash
# Increase store quota via admin tool
venice-admin --cluster <cluster> --update-store <store> --read-quota-in-cu <new_value>
```

#### 3. Check Quota Distribution

```
Metric: quota (per store)
```

**Verify quota is properly distributed:**
```
Expected: store_quota / number_of_routers * quota_buffer
```

## Connection Issues

### Symptoms
- `rejected_connection_count` increasing
- Client connection timeouts
- SSL handshake failures

### Diagnostic Steps

#### 1. Check Connection Metrics

```
Metric: live_connection_count
Metric: rejected_connection_count
Metric: ssl_handshake_failure
```

#### 2. Connection Limit

**If live connections near limit:**
```properties
# Current limit
connection.limit=10000

# Increase if needed
connection.limit=20000
```

#### 3. SSL Issues

**If SSL handshakes failing:**
```
Metric: ssl_handshake_failure
Metric: ssl_handshake_latency
```

**Check:**
- Certificate validity
- SSL configuration
- Max concurrent handshakes

```properties
max.concurrent.ssl.handshakes=2000
```

## Storage Node Communication Issues

### Symptoms
- `unhealthy_request` increasing
- `pending_request` per node increasing
- `leaked_pending_request_count` increasing

### Diagnostic Steps

#### 1. Check Per-Node Metrics

```
Metric: pending_request (per node)
Metric: response_waiting_time (per node)
Metric: finished_request (per node)
```

#### 2. Identify Problematic Nodes

**High pending requests on specific node:**
- Node may be overloaded
- Network issue to node
- Node may need restart

#### 3. Check Leaked Futures

```
Metric: leaked_pending_request_count
```

**Leaked futures indicate:**
- HTTP client issues
- Network timeouts not properly handled

**Action:**
```properties
# Reduce cleanup threshold
leaked.future.cleanup.threshold.ms=30000
leaked.future.cleanup.poll.interval.ms=5000
```

#### 4. HTTP Client Issues

**If using Apache HttpAsyncClient:**
- Check for connection pool exhaustion
- Verify socket timeouts

```properties
socket.timeout=3000
connection.timeout=3000
max.outgoing.conn.per.route=200
```

## Routing Issues

### Symptoms
- Requests going to wrong partitions
- `find_unhealthy_host_request` increasing
- Uneven load distribution

### Diagnostic Steps

#### 1. Check Routing Metadata

**Verify metadata is fresh:**
```bash
# Check ZooKeeper connectivity
zkCli.sh -server <zk_address>
get /venice/<cluster>/STORES/<store_name>
```

#### 2. Check Host Finding

```
Metric: find_unhealthy_host_request
Metric: unavailable_request
```

**High find_unhealthy_host indicates:**
- Many unhealthy storage nodes
- Stale routing data

#### 3. Verify Version Resolution

```bash
# Check current version
curl http://router:port/current_version/<store_name>
```

**If returning stale version:**
- Check metadata repository refresh
- Verify ZooKeeper connectivity

#### 4. Check Fanout

```
Metric: fanout_request_count
```

**High fanout indicates:**
- Keys spread across many partitions
- Consider Helix-assisted routing

```properties
multi.key.routing.strategy=HELIX_ASSISTED_ROUTING
```

## Memory Issues

### Symptoms
- OOM errors
- High GC pause times
- Degraded performance

### Diagnostic Steps

#### 1. Check JVM Metrics

```
Metric: jvm.heap.used
Metric: jvm.gc.count
Metric: jvm.gc.time
```

#### 2. Check Connection Pool Memory

Large connection pools consume memory:

```properties
# Reduce if memory constrained
http.client.pool.size=8
max.outgoing.conn.per.route=80
max.outgoing.conn=800
```

#### 3. Check Request Buffering

Large requests consume memory:

```
Metric: request_size
Metric: response_size
```

**Action:**
- Limit batch sizes
- Enable streaming for large responses

### Recommended JVM Settings

```bash
-Xmx8g -Xms8g
-XX:MaxDirectMemorySize=2g
-XX:+UseG1GC
-XX:MaxGCPauseMillis=50
```

## Metadata API Issues

### Symptoms
- Schema lookups failing
- Version resolution errors
- Cluster discovery issues

### Diagnostic Steps

#### 1. Test Metadata Endpoints

```bash
# Key schema
curl http://router:port/key_schema/<store_name>

# Value schema
curl http://router:port/value_schema/<store_name>

# Current version
curl http://router:port/current_version/<store_name>

# Cluster discovery
curl http://router:port/cluster_discovery
```

#### 2. Check Repository Refresh

If metadata is stale:
- Verify ZooKeeper connectivity
- Check refresh configuration

```properties
refresh.attempts.for.zk.reconnect=3
refresh.interval.for.zk.reconnect.ms=1000
```

## Admin Endpoints Reference

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/admin` | GET | Health check |
| `OPTIONS /*` | OPTIONS | Health check |
| `/admin/read_quota_throttle?action=enable` | GET | Enable throttling |
| `/admin/read_quota_throttle?action=disable` | GET | Disable throttling |
| `/key_schema/{store}` | GET | Get key schema |
| `/value_schema/{store}` | GET | Get value schemas |
| `/current_version/{store}` | GET | Get current version |
| `/cluster_discovery` | GET | Get cluster topology |
| `/leader_controller` | GET | Get controller address |
| `/resource_state/{store}` | GET | Get partition states |

## Common Error Patterns

### 429 Too Many Requests

**Causes:**
1. Store quota exceeded
2. Router capacity exceeded
3. Storage node throttling

**Resolution:**
1. Increase store quota
2. Add more routers
3. Check storage node capacity

### 502 Bad Gateway

**Causes:**
1. Storage node returned error
2. Network issue
3. Timeout

**Resolution:**
1. Check storage node logs
2. Verify network connectivity
3. Adjust timeouts

### 503 Service Unavailable

**Causes:**
1. No available replicas
2. All replicas unhealthy
3. Pending request limit reached

**Resolution:**
1. Check storage node health
2. Verify Helix assignments
3. Increase pending request limit

## Performance Tuning Checklist

### Latency
- [ ] `healthy_request_latency.99thPercentile` < 50ms
- [ ] `request_parsing_latency.Avg` < 5ms
- [ ] `response_waiting_time.Avg` < 20ms

### Error Rates
- [ ] `unhealthy_request / request` < 1%
- [ ] `throttled_request / request` < 5%
- [ ] `retry_count / request` < 10%

### Capacity
- [ ] `live_connection_count` < `connection.limit * 0.8`
- [ ] `pending_request` < `max.pending.request * 0.8`
- [ ] `in_flight_request_rate` within expected throughput

### Retry Efficiency
- [ ] `allowed_retry_count / retry_count` > 50%
- [ ] `error_retry_count` decreasing over time

## Escalation Procedures

### When to Escalate

1. **Cluster-Wide Issues**
   - All routers affected
   - Metadata inconsistencies
   - ZooKeeper issues

2. **Persistent High Error Rates**
   - Error rate > 5% for > 10 minutes
   - Not resolved by standard procedures

3. **Data Consistency Issues**
   - Wrong data being returned
   - Version mismatch errors

### Information to Collect

1. **Metrics snapshot** - All relevant metrics
2. **Router logs** - ERROR and WARN level
3. **Metadata state** - Store versions, schemas
4. **Network state** - Connectivity to storage nodes
5. **Timeline** - When issue started, recent changes
