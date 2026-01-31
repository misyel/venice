# Venice Server Configuration Reference

This document provides a comprehensive reference for Venice Server configuration options.

## Configuration Sources

Venice Server configuration is loaded from properties files:

```bash
# Startup with config directory
java -jar venice-server-all.jar /path/to/config/

# Or via environment variable
export VENICE_CONFIG_DIR=/path/to/config/
java -jar venice-server-all.jar
```

## Essential Configuration

### Network Settings

| Config Key | Type | Default | Description |
|------------|------|---------|-------------|
| `listener.hostname` | String | - | Server hostname for client connections |
| `listener.port` | int | - | Server port for client connections |
| `admin.port` | int | - | Admin/metrics port |
| `ssl.enabled` | boolean | false | Enable SSL/TLS |

### Cluster Settings

| Config Key | Type | Default | Description |
|------------|------|---------|-------------|
| `cluster.name` | String | - | Venice cluster name |
| `zookeeper.address` | String | - | ZooKeeper connection string |
| `kafka.bootstrap.servers` | String | - | Kafka bootstrap servers |

### Data Path Settings

| Config Key | Type | Default | Description |
|------------|------|---------|-------------|
| `data.base.path` | String | - | Base path for RocksDB data |
| `rocksdb.path` | String | - | Specific RocksDB storage path |
| `autocreate.data.path` | boolean | false | Auto-create data directory if missing |

## Thread Pool Configuration

### Read Path Thread Pools

| Config Key | Type | Default | Description |
|------------|------|---------|-------------|
| `server.database.lookup.queue.capacity` | int | 100 | Storage lookup queue capacity |
| `server.compute.thread.num` | int | 4 | Number of read compute threads |
| `server.compute.queue.capacity` | int | 100 | Read compute queue capacity |
| `server.enable.parallel.batch.get` | boolean | false | Enable parallel batch get processing |
| `parallel.batch.get.chunk.size` | int | 10 | Chunk size for parallel batch gets |

### Ingestion Thread Pools

| Config Key | Type | Default | Description |
|------------|------|---------|-------------|
| `max.leader.follower.state.transition.thread.number` | int | 20 | State transition executor threads |
| `max.future.version.leader.follower.state.transition.thread.number` | int | 4 | Future version transition threads |
| `leader.follower.state.transition.thread.pool.strategy` | String | BALANCED | Thread pool strategy |
| `server.ingestion.task.reusable.objects.strategy` | String | THREAD_LOCAL | Object reuse strategy |

### Consumer Pool Configuration

| Config Key | Type | Default | Description |
|------------|------|---------|-------------|
| `server.consumer.pool.size.per.kafka.cluster` | int | 5 | Base consumer pool size per cluster |
| `server.consumer.pool.size.for.current.version.aa.wc.leader` | int | - | Pool size for AA/WC leader current version |
| `server.consumer.pool.size.for.current.version.non.aa.wc.leader` | int | - | Pool size for non-AA/WC leader current version |
| `server.consumer.pool.size.for.non.current.version.aa.wc.leader` | int | - | Pool size for AA/WC leader non-current version |
| `server.consumer.pool.size.for.non.current.version.non.aa.wc.leader` | int | - | Pool size for non-AA/WC leader non-current version |
| `server.dedicated.consumer.pool.for.aa.wc.leader.enabled` | boolean | false | Enable dedicated pool for AA/WC leaders |
| `min.consumer.in.consumer.pool.per.kafka.cluster` | int | 1 | Minimum consumers to maintain |
| `server.consumer.pool.allocation.strategy` | String | - | Consumer pool allocation strategy |

## RocksDB Configuration

### General RocksDB Settings

| Config Key | Type | Default | Description |
|------------|------|---------|-------------|
| `server.database.memory.stats.enabled` | boolean | false | Enable RocksDB memory statistics |
| `server.database.checksum.verification.enabled` | boolean | true | Enable data integrity checks |
| `server.delete.unassigned.partitions.on.startup` | boolean | false | Delete unassigned partitions at startup |

### RocksDB Performance Tuning

| Config Key | Type | Default | Description |
|------------|------|---------|-------------|
| `rocksdb.plain.table.format.enabled` | boolean | false | Use plain table format (faster, no compression) |
| `server.database.sync.bytes.internal.for.deferred.write.mode` | long | - | Sync interval for deferred write mode |
| `server.database.sync.bytes.internal.for.transactional.mode` | long | - | Sync interval for transactional mode |
| `rocksdb.block.cache.size.in.bytes` | long | - | Block cache size |
| `rocksdb.block.cache.compress.enabled` | boolean | - | Enable compressed block cache |

### Compression Settings

| Config Key | Type | Default | Description |
|------------|------|---------|-------------|
| `zstd.dict.compression.level` | int | 3 | ZSTD compression level for dictionary |

## Ingestion Configuration

### General Ingestion

| Config Key | Type | Default | Description |
|------------|------|---------|-------------|
| `server.ingestion.mode` | String | BUILT_IN | Ingestion mode: BUILT_IN or ISOLATED |
| `server.ingestion.isolation.service.port` | int | - | Isolated ingestion service port |
| `server.ingestion.isolation.application.port` | int | - | Isolated ingestion application port |
| `server.ingestion.heartbeat.interval.ms` | long | 5000 | Heartbeat interval |
| `server.ingestion.checkpoint.during.graceful.shutdown.enabled` | boolean | true | Checkpoint on shutdown |
| `server.ingestion.info.log.line.limit` | int | - | Ingestion log line limit |
| `server.ingestion.task.max.idle.count` | int | - | Idle task cleanup threshold |

### Kafka Consumer Settings

| Config Key | Type | Default | Description |
|------------|------|---------|-------------|
| `server.kafka.max.poll.records` | int | 500 | Max records per poll |
| `kafka.fetch.min.size.per.sec` | int | - | Minimum fetch size |
| `kafka.fetch.max.wait.ms` | long | - | Maximum fetch wait time |

## Quota Configuration

### Read Quota

| Config Key | Type | Default | Description |
|------------|------|---------|-------------|
| `hybrid.quota.enforcement.enabled` | boolean | false | Enable real-time quota enforcement |
| `server.current.version.aa.wc.leader.quota.records.per.second` | int | - | Quota for AA/WC leader |
| `server.current.version.non.aa.wc.leader.quota.records.per.second` | int | - | Quota for non-AA/WC leader |

## Helix Configuration

| Config Key | Type | Default | Description |
|------------|------|---------|-------------|
| `helix.instance.name` | String | - | Helix instance identifier |
| `partition.graceful.drop.delay.seconds` | int | 30 | Graceful drop delay |
| `server.allow.list.enabled` | boolean | false | Enable server allowlist checking |

## Health Check Configuration

### Disk Health

| Config Key | Type | Default | Description |
|------------|------|---------|-------------|
| `server.disk.health.check.service.enabled` | boolean | true | Enable disk health checking |
| `server.disk.health.check.interval.in.seconds` | int | 60 | Check interval |
| `server.disk.health.check.timeout.in.seconds` | int | 30 | Check timeout |
| `server.disk.full.threshold` | double | 0.95 | Disk full threshold (0-1) |
| `ssd.health.check.shutdown.time.ms` | long | - | Shutdown time on SSD failure |

### Heartbeat Monitoring

| Config Key | Type | Default | Description |
|------------|------|---------|-------------|
| `server.lag.monitor.cleanup.cycle` | int | - | Heartbeat monitor cleanup cycle |

## HTTP/2 Configuration

| Config Key | Type | Default | Description |
|------------|------|---------|-------------|
| `server.http2.header.table.size` | int | - | HTTP/2 header table size |
| `server.http2.initial.window.size` | int | - | HTTP/2 initial window size |
| `server.http2.max.concurrent.streams` | int | - | HTTP/2 max concurrent streams |
| `server.http2.max.frame.size` | int | - | HTTP/2 max frame size |
| `server.http2.max.header.list.size` | int | - | HTTP/2 max header list size |
| `server.channel.option.write.buffer.watermark.high.bytes` | int | - | Netty write buffer high watermark |
| `server.channel.option.write.buffer.watermark.low.bytes` | int | - | Netty write buffer low watermark |

## Adaptive Throttling

| Config Key | Type | Default | Description |
|------------|------|---------|-------------|
| `server.adaptive.throttler.enabled` | boolean | false | Enable adaptive throttling |
| `server.adaptive.throttler.signal.idle.threshold` | double | - | Signal idle threshold |
| `server.adaptive.throttler.signal.refresh.interval.in.seconds` | int | - | Signal refresh interval |
| `server.adaptive.throttler.read.latency.threshold.ms` | long | - | Read latency threshold |
| `server.adaptive.throttler.compute.latency.threshold.ms` | long | - | Compute latency threshold |

## Blob Transfer Configuration

| Config Key | Type | Default | Description |
|------------|------|---------|-------------|
| `blob.transfer.manager.enabled` | boolean | false | Enable P2P blob transfer |
| `davinci.p2p.blob.transfer.server.port` | int | - | Blob transfer server port |
| `davinci.p2p.blob.transfer.client.port` | int | - | Blob transfer client port |
| `blob.transfer.read.limit.bytes.per.sec` | long | - | Read rate limit |
| `blob.transfer.write.limit.bytes.per.sec` | long | - | Write rate limit |
| `blob.transfer.adaptive.throttler.enabled` | boolean | false | Enable adaptive blob throttling |
| `blob.transfer.max.timeout.in.min` | int | - | Max transfer timeout |
| `blob.transfer.snapshot.retention.time.in.min` | int | - | Snapshot retention time |

## Miscellaneous

| Config Key | Type | Default | Description |
|------------|------|---------|-------------|
| `router.principal.name` | String | - | ACL principal for router access |
| `server.load.controller.enabled` | boolean | false | Enable request load controller |
| `server.load.controller.accept.multiplier` | double | - | Load controller accept multiplier |
| `key.value.profiling.enabled` | boolean | false | Enable K/V size profiling |
| `server.debug.logging.enabled` | boolean | false | Enable verbose debug logging |
| `fast.avro.field.limit.per.method` | int | - | Fast Avro field limit |
| `compute.fast.avro.enabled` | boolean | true | Enable fast Avro for compute |
| `router.connection.warming.delay.ms` | long | 0 | Delay startup for router warming |
| `leaked.resource.clean.up.enabled` | boolean | false | Enable leaked resource cleanup |
| `leaked.resource.clean.up.interval.ms` | long | - | Cleanup interval |

## Controller/Schema Initialization

| Config Key | Type | Default | Description |
|------------|------|---------|-------------|
| `system.schema.initialization.at.start.time.enabled` | boolean | false | Initialize system schemas at startup |
| `local.controller.url` | String | - | Local controller URL |
| `local.controller.d2.service.name` | String | - | Controller D2 service name |
| `local.d2.zk.host` | String | - | D2 ZooKeeper host |
| `system.schema.cluster.name` | String | - | System schema cluster name |
| `schema.presence.check.enabled` | boolean | true | Verify schema presence at startup |

## Example Configuration

### Minimal Production Configuration

```properties
# Cluster
cluster.name=my-venice-cluster
zookeeper.address=zk1:2181,zk2:2181,zk3:2181
kafka.bootstrap.servers=kafka1:9092,kafka2:9092

# Network
listener.hostname=server-hostname
listener.port=7777
admin.port=7778

# Storage
data.base.path=/data/venice
rocksdb.path=/data/venice/rocksdb

# Thread pools
server.compute.thread.num=8
server.database.lookup.queue.capacity=200

# Consumer pools
server.consumer.pool.size.per.kafka.cluster=10

# Health
server.disk.health.check.service.enabled=true
server.disk.health.check.interval.in.seconds=30

# Graceful operations
partition.graceful.drop.delay.seconds=30
server.ingestion.checkpoint.during.graceful.shutdown.enabled=true
```

### High-Performance Configuration

```properties
# ... basic config ...

# Enable parallel batch get
server.enable.parallel.batch.get=true
parallel.batch.get.chunk.size=10

# Larger thread pools
server.compute.thread.num=16
server.database.lookup.queue.capacity=500
max.leader.follower.state.transition.thread.number=40

# RocksDB tuning
rocksdb.block.cache.size.in.bytes=8589934592  # 8GB
server.database.memory.stats.enabled=true

# Adaptive throttling
server.adaptive.throttler.enabled=true
server.adaptive.throttler.read.latency.threshold.ms=50

# Profiling (enable if needed for debugging)
key.value.profiling.enabled=true
```

## Configuration Validation

The server validates configuration at startup. Common validation errors:

| Error | Cause | Solution |
|-------|-------|----------|
| `Port already in use` | Another process using port | Change port or kill process |
| `Cannot connect to ZK` | ZK address incorrect/unreachable | Verify ZK connectivity |
| `Data path not writable` | Permission issues | Check directory permissions |
| `Not in allowlist` | Server not in cluster allowlist | Add to allowlist via admin tool |

## Environment Variable Override

Configuration can be overridden via environment variables:

```bash
# Pattern: VENICE_{KEY_WITH_DOTS_REPLACED_BY_UNDERSCORES}
export VENICE_LISTENER_PORT=7777
export VENICE_CLUSTER_NAME=my-cluster
```
