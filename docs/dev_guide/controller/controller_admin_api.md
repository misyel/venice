# Venice Controller Admin API Reference

This document provides a reference for all Venice Controller REST API endpoints.

## API Overview

The Venice Controller exposes REST APIs via `AdminSparkServer`:

| Protocol | Port Config | Description |
|----------|-------------|-------------|
| HTTP | `admin.port` | Plain HTTP (development) |
| HTTPS | `admin.secure.port` | TLS-encrypted (production) |
| gRPC | `admin.grpc.port` | gRPC API |
| gRPC + TLS | `admin.secure.grpc.port` | TLS-encrypted gRPC |

## Store Endpoints

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/admin/store` | POST | Create new store |
| `/admin/store` | GET | Get store info |
| `/admin/store` | PUT | Update store config |
| `/admin/store` | DELETE | Delete store |
| `/admin/stores` | GET | List all stores in cluster |

### Create Store

```
POST /admin/store

Parameters:
- cluster (required): Cluster name
- name (required): Store name
- owner (required): Store owner
- key_schema (required): Avro key schema
- value_schema (required): Avro value schema

Response:
{
  "cluster": "prod-cluster",
  "name": "my-store",
  "owner": "team@company.com"
}
```

### Get Store Info

```
GET /admin/store?cluster={cluster}&name={name}

Response:
{
  "name": "my-store",
  "owner": "team@company.com",
  "partitionCount": 12,
  "currentVersion": 3,
  "versions": [...],
  ...
}
```

### Update Store

```
PUT /admin/store

Parameters:
- cluster (required): Cluster name
- name (required): Store name
- owner: New owner
- partitionCount: New partition count
- enableReads: Enable/disable reads
- enableWrites: Enable/disable writes
- ... (many optional parameters)
```

### Delete Store

```
DELETE /admin/store?cluster={cluster}&name={name}
```

## Version Endpoints

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/request_topic` | GET | Get Kafka topic for push |
| `/add_version` | POST | Add version and start ingestion |
| `/end_of_push` | POST | Mark push complete |
| `/offline_push_info` | GET | Get push status |
| `/future_version` | GET | Get next version number |
| `/backup_version` | GET | Get backup version |
| `/set_version` | POST | Set current serving version |
| `/rollback_to_backup_version` | POST | Rollback to previous version |
| `/roll_forward_to_future_version` | POST | Skip to future version |

### Request Topic

```
GET /request_topic?cluster={cluster}&store={store}&push_job_id={id}

Response:
{
  "kafkaTopic": "my-store_push123",
  "kafkaBootstrapServers": "kafka1:9092,kafka2:9092",
  "partitionCount": 12
}
```

### Add Version

```
POST /add_version

Parameters:
- cluster (required): Cluster name
- name (required): Store name
- push_job_id (required): Push job ID
- version (optional): Specific version number

Response:
{
  "version": 4,
  "kafkaTopic": "my-store_v4"
}
```

### End of Push

```
POST /end_of_push

Parameters:
- cluster (required): Cluster name
- name (required): Store name
- version (required): Version number
```

### Get Push Status

```
GET /offline_push_info?cluster={cluster}&topic={kafkaTopic}

Response:
{
  "executionStatus": "COMPLETED",
  "partitionStatuses": {...}
}
```

### Rollback

```
POST /rollback_to_backup_version?cluster={cluster}&store={store}

Response:
{
  "currentVersion": 2,  // rolled back from 3
  "backupVersion": 3
}
```

## Schema Endpoints

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/add_value_schema` | POST | Register value schema |
| `/add_derived_schema` | POST | Add write-compute schema |
| `/key_schema/{store}` | GET | Get key schema |
| `/value_schema/{store}` | GET | Get all value schemas |
| `/value_schema/{store}/{id}` | GET | Get specific value schema |
| `/value_or_derived_schema_id/{store}` | GET | Get schema ID for string |

### Add Value Schema

```
POST /add_value_schema

Parameters:
- cluster (required): Cluster name
- name (required): Store name
- valueSchemaStr (required): Avro schema JSON

Response:
{
  "schemaId": 2
}
```

### Get Key Schema

```
GET /key_schema/{store}?cluster={cluster}

Response:
{
  "schemaStr": "{\"type\": \"string\"}"
}
```

### Get Value Schemas

```
GET /value_schema/{store}?cluster={cluster}

Response:
{
  "schemas": [
    {"id": 1, "schemaStr": "..."},
    {"id": 2, "schemaStr": "..."}
  ]
}
```

## Cluster Endpoints

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/cluster_health_stores` | GET | Get store health |
| `/cluster_health_instances` | GET | Get instance health |
| `/list_nodes` | GET | List storage nodes |
| `/remove_node` | POST | Decommission server |
| `/update_cluster_config` | POST | Update cluster settings |

### List Nodes

```
GET /list_nodes?cluster={cluster}

Response:
{
  "nodes": [
    {"instanceId": "server-1", "host": "server1.example.com", "port": 7777},
    {"instanceId": "server-2", "host": "server2.example.com", "port": 7777}
  ]
}
```

### Cluster Health

```
GET /cluster_health_stores?cluster={cluster}

Response:
{
  "stores": [
    {"name": "my-store", "healthy": true, "currentVersion": 3},
    ...
  ]
}
```

## Admin Control Endpoints

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/leader_controller` | GET | Get current leader |
| `/admin_topic_metadata` | GET | Get admin topic info |
| `/skip_admin_message` | POST | Skip stuck message |
| `/clean_execution_ids` | POST | Clean execution ID tracking |

### Get Leader Controller

```
GET /leader_controller?cluster={cluster}

Response:
{
  "cluster": "prod-cluster",
  "url": "https://controller-1.example.com:5556"
}
```

### Skip Admin Message

```
POST /skip_admin_message

Parameters:
- cluster (required): Cluster name
- offset (required): Message offset to skip
- skip_data_integrity_check (optional): Skip DIV check

Note: Use with caution - skipping messages can cause inconsistency
```

## Push Job Endpoints

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/kill_offline_push_job` | POST | Kill running push |
| `/push_job_status` | GET | Get detailed push status |

### Kill Push Job

```
POST /kill_offline_push_job

Parameters:
- cluster (required): Cluster name
- topic (required): Kafka topic (e.g., "my-store_v4")
```

## Migration Endpoints

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/migrate_store` | POST | Start store migration |
| `/complete_migration` | POST | Complete migration |
| `/abort_migration` | POST | Cancel migration |

## Error Responses

All endpoints return errors in this format:

```json
{
  "error": true,
  "errorType": "STORE_NOT_FOUND",
  "message": "Store 'my-store' does not exist in cluster 'prod-cluster'"
}
```

Common error types:
- `STORE_NOT_FOUND` - Store doesn't exist
- `VERSION_NOT_FOUND` - Version doesn't exist
- `INVALID_SCHEMA` - Schema validation failed
- `RESOURCE_STILL_EXISTS` - Cannot delete resource in use
- `UNAUTHORIZED` - Permission denied
- `INTERNAL_ERROR` - Server error

## Authentication

Production deployments typically require authentication:

```
# Header-based authentication
Authorization: Bearer <token>

# Or via query parameter (legacy)
?access_token=<token>
```

## Rate Limiting

Some endpoints may be rate limited:
- Store creation: Limited per owner
- Schema registration: Limited per store
- Push job creation: Limited per store

## Admin Tool Usage

The `venice-admin-tool` CLI wraps these APIs:

```bash
# Create store
java -jar venice-admin-tool.jar --create-store \
  --cluster prod-cluster \
  --store my-store \
  --key-schema-file key.avsc \
  --value-schema-file value.avsc

# Get store info
java -jar venice-admin-tool.jar --describe-store \
  --cluster prod-cluster \
  --store my-store

# Kill push job
java -jar venice-admin-tool.jar --kill-push \
  --cluster prod-cluster \
  --topic my-store_v4
```

## See Also

- [Controller Architecture](controller_architecture.md) - Request handling internals
- [Controller Data Flow](controller_data_flow.md) - Request processing flow
- [Controller Troubleshooting](controller_troubleshooting.md) - API debugging
