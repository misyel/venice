# Venice Router Configuration Reference

This document provides a comprehensive reference for Venice Router configuration options.

## Configuration Sources

Venice Router configuration is loaded from properties files:

```bash
# Startup with config file
java -jar venice-router-all.jar /path/to/config/router.properties
```

## Essential Configuration

### Network Settings

| Config Key | Type | Default | Description |
|------------|------|---------|-------------|
| `listener.port` | int | - | HTTP listener port |
| `listener.ssl.port` | int | - | HTTPS listener port |
| `listener.hostname` | String | - | Router hostname |
| `router.enforce.secure.only` | boolean | false | Force HTTPS only (disable HTTP) |
| `connection.limit` | int | 10000 | Max concurrent connections |
| `connection.handle.mode` | String | REJECT | Behavior when limit exceeded |

### Cluster Settings

| Config Key | Type | Default | Description |
|------------|------|---------|-------------|
| `cluster.name` | String | - | Venice cluster name |
| `zookeeper.address` | String | - | ZooKeeper connection string |
| `kafka.bootstrap.servers` | String | - | Kafka bootstrap servers |
| `system.schema.cluster.name` | String | - | System schema cluster |

### D2 Discovery Settings

| Config Key | Type | Default | Description |
|------------|------|---------|-------------|
| `cluster.to.d2` | Map | - | Cluster to D2 service mapping |
| `cluster.to.server.d2` | Map | - | Cluster to server D2 mapping |

## Thread Pool Configuration

### IO Workers

| Config Key | Type | Default | Description |
|------------|------|---------|-------------|
| `router.io.worker.count` | int | 24 | Netty IO worker threads |
| `router.netty.graceful.shutdown.period.seconds` | int | 30 | Graceful shutdown period |

### DNS Resolution

| Config Key | Type | Default | Description |
|------------|------|---------|-------------|
| `router.resolve.before.ssl.threads` | int | 0 | DNS resolution threads (0 = disabled) |
| `router.resolve.before.ssl.queue.capacity` | int | - | Resolution queue capacity |

## HTTP Client Configuration

### Client Type

| Config Key | Type | Default | Description |
|------------|------|---------|-------------|
| `storage.node.client.type` | String | APACHE_HTTP_ASYNC_CLIENT | Client implementation |

**Options:**
- `APACHE_HTTP_ASYNC_CLIENT` - Apache HttpAsyncClient
- `HTTP_CLIENT_5_CLIENT` - Apache HttpClient 5

### Apache HttpAsyncClient Settings

| Config Key | Type | Default | Description |
|------------|------|---------|-------------|
| `http.client.pool.size` | int | 12 | HTTP client pool size |
| `max.outgoing.conn.per.route` | int | 120 | Max connections per storage node |
| `max.outgoing.conn` | int | 1200 | Max total outgoing connections |
| `socket.timeout` | int | 5000 | Socket timeout (ms) |
| `connection.timeout` | int | 5000 | Connection timeout (ms) |
| `http.client.openssl.enabled` | boolean | true | Use OpenSSL |

### Apache HttpClient 5 Settings

| Config Key | Type | Default | Description |
|------------|------|---------|-------------|
| `http.client5.pool.size` | int | 1 | HttpClient5 pool size |
| `http.client5.total.io.thread.count` | int | - | Total IO threads |

## Long-Tail Retry Configuration

### Single-GET Retry

| Config Key | Type | Default | Description |
|------------|------|---------|-------------|
| `long.tail.retry.for.single.get.threshold.ms` | int | 15 | Retry threshold (ms) |

### Batch-GET Retry

| Config Key | Type | Default | Description |
|------------|------|---------|-------------|
| `long.tail.retry.for.batch.get.threshold.ms` | Map | - | Retry thresholds by key count |

**Default thresholds:**
```properties
long.tail.retry.for.batch.get.threshold.ms=1:15,5:25,20:50,100:100
# 1 key: 15ms, 2-5 keys: 25ms, 6-20 keys: 50ms, 21+ keys: 100ms
```

### Smart Retry

| Config Key | Type | Default | Description |
|------------|------|---------|-------------|
| `smart.long.tail.retry.enabled` | boolean | true | Enable smart retry |
| `smart.long.tail.retry.abort.threshold.ms` | int | 100 | Abort retry after (ms) |

### Retry Budget

| Config Key | Type | Default | Description |
|------------|------|---------|-------------|
| `single.key.long.tail.retry.budget.percent.decimal` | double | 0.03 | Budget % for single key |
| `multi.key.long.tail.retry.budget.percent.decimal` | double | 0.03 | Budget % for multi key |
| `long.tail.retry.budget.enforcement.window.ms` | long | - | Budget window (ms) |

## Throttling Configuration

### Router-Level Throttling

| Config Key | Type | Default | Description |
|------------|------|---------|-------------|
| `router.max.read.capacity.cu` | long | 6000 | Max router read capacity (CU) |
| `router.quota.check.window` | long | 30000 | Quota check window (ms) |
| `read.throttling.enabled` | boolean | true | Enable read throttling |
| `early.throttle.enabled` | boolean | true | Enable early throttling |

### Pending Request Throttling

| Config Key | Type | Default | Description |
|------------|------|---------|-------------|
| `max.pending.request` | long | 30000 | Max pending requests (2500 * 12) |
| `router.unhealthy.pending.conn.threshold.per.route` | int | - | Unhealthy threshold per route |
| `router.pending.conn.resume.threshold.per.route` | int | - | Resume threshold per route |

### Store-Level Throttling

| Config Key | Type | Default | Description |
|------------|------|---------|-------------|
| `max.read.capacity.cu` | long | 100000 | Max total read capacity |
| `per.store.router.quota.buffer` | double | 1.5 | Per-store quota buffer |

## Tardy Detection Configuration

| Config Key | Type | Default | Description |
|------------|------|---------|-------------|
| `single.get.tardy.latency.threshold.ms` | long | 10000 | Single-GET tardy threshold |
| `multi.get.tardy.latency.threshold.ms` | long | 10000 | Multi-GET tardy threshold |
| `compute.tardy.latency.threshold.ms` | long | 10000 | Compute tardy threshold |

## Routing Strategy Configuration

### Multi-Key Routing

| Config Key | Type | Default | Description |
|------------|------|---------|-------------|
| `multi.key.routing.strategy` | String | LEAST_LOADED_ROUTING | Routing strategy |

**Options:**
- `GROUP_BY_PRIMARY_HOST_ROUTING` - Route to first replica
- `GREEDY_ROUTING` - Minimize request count
- `LEAST_LOADED_ROUTING` - Balance by load
- `HELIX_ASSISTED_ROUTING` - Use Helix groups

### Helix-Assisted Routing

| Config Key | Type | Default | Description |
|------------|------|---------|-------------|
| `helix.group.selection.strategy` | String | LEAST_LOADED | Group selection strategy |

**Options:**
- `ROUND_ROBIN` - Distribute evenly
- `LEAST_LOADED` - Route to lowest latency group

### Routing Computation

| Config Key | Type | Default | Description |
|------------|------|---------|-------------|
| `routing.computation.mode` | String | SEQUENTIAL | Routing computation mode |

**Options:**
- `SEQUENTIAL` - Sequential computation
- `PARALLEL` - Parallel computation

## SSL Configuration

| Config Key | Type | Default | Description |
|------------|------|---------|-------------|
| `router.ssl.to.storage.nodes` | boolean | false | Use SSL to storage nodes |
| `ssl.factory.class.name` | String | - | SSL factory class |
| `max.concurrent.ssl.handshakes` | int | 1000 | Max concurrent handshakes |
| `router.enable.ssl` | boolean | true | Enable SSL |
| `router.use.local.ssl.settings` | boolean | true | Use local SSL settings |

## Health Check Configuration

| Config Key | Type | Default | Description |
|------------|------|---------|-------------|
| `stateful.router.health.check.enabled` | boolean | false | Enable stateful health checks |
| `latency.based.routing.enabled` | boolean | false | Use latency for routing |
| `router.heartbeat.enabled` | boolean | false | Enable heartbeat checks |

## Dictionary/Compression Configuration

| Config Key | Type | Default | Description |
|------------|------|---------|-------------|
| `dictionary.retrieval.time.ms` | int | 30000 | Dictionary retrieval timeout |
| `router.dictionary.processing.threads` | int | 3 | Dictionary processing threads |
| `decompress.on.client` | boolean | false | Decompress on router |

## HTTP/2 Configuration

| Config Key | Type | Default | Description |
|------------|------|---------|-------------|
| `http2.inbound.enabled` | boolean | false | Enable HTTP/2 inbound |
| `http2.max.concurrent.streams` | int | - | Max concurrent streams |
| `http2.header.table.size` | int | - | Header table size |
| `http2.initial.window.size` | int | - | Initial window size |
| `http2.max.frame.size` | int | - | Max frame size |
| `http2.max.header.list.size` | int | - | Max header list size |

## ACL Configuration

| Config Key | Type | Default | Description |
|------------|------|---------|-------------|
| `acl.in.memory.cache.ttl.ms` | long | - | ACL cache TTL |
| `identity.parser.class.name` | String | - | Identity parser class |
| `client.ip.spoofing.check.enabled` | boolean | false | Check client IP spoofing |

## Metrics Configuration

| Config Key | Type | Default | Description |
|------------|------|---------|-------------|
| `key.value.profiling.enabled` | boolean | false | Enable KV profiling |
| `unregister.metric.for.deleted.store.enabled` | boolean | false | Auto-unregister metrics |

## Miscellaneous

| Config Key | Type | Default | Description |
|------------|------|---------|-------------|
| `refresh.attempts.for.zk.reconnect` | int | - | ZK reconnect attempts |
| `refresh.interval.for.zk.reconnect.ms` | long | - | ZK reconnect interval |
| `async.start.enabled` | boolean | false | Enable async startup |
| `leaked.future.cleanup.poll.interval.ms` | long | - | Cleanup poll interval |
| `leaked.future.cleanup.threshold.ms` | long | - | Cleanup threshold |
| `meta.store.shadow.read.enabled` | boolean | false | Shadow read meta store |
| `helix.hybrid.store.quota.enabled` | boolean | false | Enable hybrid quota |
| `retry.manager.core.pool.size` | int | - | Retry manager pool size |

## Example Configuration

### Minimal Production Configuration

```properties
# Cluster
cluster.name=my-venice-cluster
zookeeper.address=zk1:2181,zk2:2181,zk3:2181
kafka.bootstrap.servers=kafka1:9092,kafka2:9092

# Network
listener.hostname=router-hostname
listener.port=7000
listener.ssl.port=7443
router.enforce.secure.only=true

# IO
router.io.worker.count=24

# HTTP Client
storage.node.client.type=APACHE_HTTP_ASYNC_CLIENT
http.client.pool.size=12
max.outgoing.conn.per.route=120
max.outgoing.conn=1200

# Throttling
router.max.read.capacity.cu=6000
max.pending.request=30000
read.throttling.enabled=true

# Long-tail retry
long.tail.retry.for.single.get.threshold.ms=15
smart.long.tail.retry.enabled=true

# Routing
multi.key.routing.strategy=LEAST_LOADED_ROUTING
```

### High-Performance Configuration

```properties
# ... basic config ...

# More IO workers
router.io.worker.count=48

# Larger connection pool
http.client.pool.size=24
max.outgoing.conn.per.route=240
max.outgoing.conn=2400

# Higher capacity
router.max.read.capacity.cu=12000
max.pending.request=60000

# Aggressive retry
long.tail.retry.for.single.get.threshold.ms=10
long.tail.retry.for.batch.get.threshold.ms=1:10,5:20,20:40,100:80

# Helix-assisted routing for large clusters
multi.key.routing.strategy=HELIX_ASSISTED_ROUTING
helix.group.selection.strategy=LEAST_LOADED

# Enable profiling for debugging
key.value.profiling.enabled=true
```

### Low-Latency Configuration

```properties
# ... basic config ...

# Lower timeouts
socket.timeout=2000
connection.timeout=2000

# Aggressive retry
long.tail.retry.for.single.get.threshold.ms=5
smart.long.tail.retry.abort.threshold.ms=50

# Lower tardy thresholds
single.get.tardy.latency.threshold.ms=5000
multi.get.tardy.latency.threshold.ms=5000

# Stateful health checks
stateful.router.health.check.enabled=true
latency.based.routing.enabled=true
router.unhealthy.pending.conn.threshold.per.route=50
```

## Environment Variable Override

Configuration can be overridden via environment variables:

```bash
# Pattern: VENICE_{KEY_WITH_DOTS_REPLACED_BY_UNDERSCORES}
export VENICE_LISTENER_PORT=7000
export VENICE_CLUSTER_NAME=my-cluster
export VENICE_ROUTER_IO_WORKER_COUNT=48
```
