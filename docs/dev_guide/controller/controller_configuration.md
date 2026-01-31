# Venice Controller Configuration Reference

This document provides a comprehensive reference for Venice Controller configuration options.

## Essential Configuration

### Cluster Identity

```properties
# Cluster name for this Venice deployment
cluster.name=venice-prod-1

# Controller cluster name (Helix)
controller.cluster.name=venice-controller-cluster

# Instance name for this controller
controller.name=controller-1
```

### ZooKeeper

```properties
# ZooKeeper connection string (comma-separated)
zookeeper.address=zk1:2181,zk2:2181,zk3:2181

# ZK session timeout
zk.session.timeout.ms=30000

# ZK connection timeout
zk.connection.timeout.ms=10000
```

### Kafka

```properties
# Kafka bootstrap servers (comma-separated)
kafka.bootstrap.servers=kafka1:9092,kafka2:9092

# SSL bootstrap servers (for secure connections)
kafka.ssl.bootstrap.servers=kafka1:9093,kafka2:9093

# Kafka SSL settings
kafka.security.protocol=SSL
kafka.ssl.keystore.location=/path/to/keystore.jks
kafka.ssl.keystore.password=password
kafka.ssl.truststore.location=/path/to/truststore.jks
kafka.ssl.truststore.password=password
```

### API Ports

```properties
# REST API port (HTTP)
admin.port=5555

# REST API port (HTTPS)
admin.secure.port=5556

# gRPC API port
admin.grpc.port=5557

# gRPC API port (TLS)
admin.secure.grpc.port=5558
```

## Parent/Child Mode Configuration

### Single Region (Child Only)

```properties
controller.parent.mode=false
multi.region=false
```

### Multi-Region Parent Controller

```properties
# Enable parent mode
controller.parent.mode=true
multi.region=true

# Child cluster endpoints (region:url mapping)
child.cluster.url.map=us-west:https://us-west-controller:5556,us-east:https://us-east-controller:5556

# Fabric allowlist for native replication
native.replication.fabric.allowlist=us-west,us-east

# Parent metadata store cluster
parent.controller.metadata.store.cluster=venice-system
```

### Multi-Region Child Controller

```properties
controller.parent.mode=false
multi.region=true

# Enable remote admin topic consumption
admin.topic.remote.consumption.enabled=true

# Parent controller URL (for metadata queries)
parent.controller.url=https://parent-controller:5556
```

## Admin Topic Configuration

```properties
# Admin topic replication factor
admin.topic.replication.factor=3

# Enable remote consumption (for child controllers)
admin.topic.remote.consumption.enabled=false

# Admin topic retention (ms)
admin.topic.retention.ms=604800000

# Admin topic min ISR
admin.topic.min.insync.replicas=2
```

## Push Job Configuration

```properties
# Push job status store cluster
push.job.status.store.cluster=venice-system

# Default partition count for new stores
default.partition.count=12

# Default replication factor for new stores
default.replication.factor=3

# Maximum partition count allowed
max.partition.count=1024

# Push job timeout (ms)
push.job.timeout.ms=86400000

# Offline push status check interval (ms)
offline.push.status.check.interval.ms=10000
```

## Topic Management Configuration

```properties
# Topic deletion status poll interval
topic.deletion.status.poll.interval.ms=2000

# Minimum unused topics to preserve
min.number.of.unused.kafka.topics.to.preserve=2

# Sleep between topic list fetches (ms)
topic.cleanup.sleep.interval.between.topic.list.fetch.ms=30000

# Topic cleanup enabled
topic.cleanup.enabled=true

# Topic cleanup delay (ms)
topic.cleanup.delay.ms=3600000
```

## Helix Configuration

```properties
# Helix message send timeout
helix.send.message.timeout.ms=10000

# Helix state transition timeout
helix.state.transition.timeout.ms=120000

# Enable Helix maintenance mode on startup
helix.auto.maintenance.mode=false

# Helix rebalance delay (ms)
helix.rebalance.delay.ms=60000
```

## Version Management Configuration

```properties
# Maximum versions to retain per store
max.versions.to.keep=3

# Backup version retention time (ms)
backup.version.retention.ms=86400000

# Deferred version swap enabled
deferred.version.swap.enabled=true

# Deferred swap sleep interval (ms)
deferred.version.swap.sleep.interval.ms=60000
```

## System Store Configuration

```properties
# Enable system stores
system.stores.enabled=true

# System store cluster
system.store.cluster=venice-system

# Push job details system store enabled
push.job.details.store.enabled=true

# Heartbeat system store enabled
heartbeat.store.enabled=true
```

## Quota Configuration

```properties
# Default read quota (requests per second)
default.read.quota.per.router=10000

# Default storage quota (bytes)
default.storage.quota.per.store.in.bytes=10737418240

# Max record size (bytes)
max.record.size.bytes=1048576

# Store quota enforcement enabled
store.quota.enforcement.enabled=true
```

## Security Configuration

```properties
# Enable SSL for admin API
admin.ssl.enabled=true

# SSL keystore
admin.ssl.keystore.location=/path/to/keystore.jks
admin.ssl.keystore.password=password

# SSL truststore
admin.ssl.truststore.location=/path/to/truststore.jks
admin.ssl.truststore.password=password

# Enable authentication
authentication.enabled=true

# Authentication service class
authentication.service.class=com.linkedin.venice.authentication.AuthenticationService

# Enable authorization
authorization.enabled=true

# Authorization service class
authorization.service.class=com.linkedin.venice.authorization.AuthorizationService
```

## Monitoring Configuration

```properties
# Enable metrics
metrics.enabled=true

# Metrics reporter class
metrics.reporter.class=com.linkedin.venice.stats.TehutiMetricsReporter

# OpenTelemetry enabled
otel.enabled=false

# OpenTelemetry endpoint
otel.exporter.otlp.endpoint=http://otel-collector:4317
```

## High Availability Configuration

```properties
# Controller HA enabled
controller.ha.enabled=true

# Number of standby controllers
controller.standby.count=2

# Leader election timeout (ms)
leader.election.timeout.ms=60000

# Failover timeout (ms)
failover.timeout.ms=300000
```

## Performance Tuning

```properties
# Admin consumption thread count
admin.consumption.thread.count=4

# Admin execution thread count
admin.execution.thread.count=8

# Topic manager thread count
topic.manager.thread.count=4

# Helix transition thread count
helix.transition.thread.count=8

# HTTP server threads
admin.http.threads=50
```

## Logging Configuration

```properties
# Log level
log.level=INFO

# Admin operation logging
admin.operation.log.enabled=true

# Audit logging enabled
audit.log.enabled=true

# Audit log file
audit.log.file=/var/log/venice/audit.log
```

## Configuration Categories Summary

| Category | Key Configs |
|----------|-------------|
| **Identity** | `cluster.name`, `controller.name` |
| **Connectivity** | `zookeeper.address`, `kafka.bootstrap.servers` |
| **API** | `admin.port`, `admin.secure.port` |
| **Mode** | `controller.parent.mode`, `multi.region` |
| **Admin Topic** | `admin.topic.replication.factor` |
| **Push Job** | `default.partition.count`, `push.job.timeout.ms` |
| **Topic Cleanup** | `topic.cleanup.enabled`, `min.number.of.unused.kafka.topics.to.preserve` |
| **Helix** | `helix.send.message.timeout.ms` |
| **Security** | `admin.ssl.enabled`, `authentication.enabled` |
| **Monitoring** | `metrics.enabled`, `otel.enabled` |

## Environment Variable Override

All configurations can be overridden via environment variables:

```bash
# Format: VENICE_<CONFIG_KEY> (underscores instead of dots)
export VENICE_CLUSTER_NAME=prod-cluster
export VENICE_KAFKA_BOOTSTRAP_SERVERS=kafka1:9092
```

## Configuration File Loading

The controller loads configuration in this order:
1. Default values (hardcoded)
2. Configuration file (`cluster.properties`)
3. System properties (`-Dkey=value`)
4. Environment variables

Later sources override earlier ones.

## See Also

- [Controller Architecture](controller_architecture.md) - How configs are used
- [Parent-Child Architecture](controller_parent_child.md) - Multi-region config
- [Controller Troubleshooting](controller_troubleshooting.md) - Config-related issues
