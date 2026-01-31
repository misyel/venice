# Venice Thin Client Architecture

The Thin Client is Venice's minimal-dependency remote read client that routes all requests through the Venice Router.

## Table of Contents

- [Overview](#overview)
- [Architecture Diagram](#architecture-diagram)
- [Request Flow](#request-flow)
- [Key Classes](#key-classes)
- [Configuration Reference](#configuration-reference)
- [Usage Examples](#usage-examples)
- [Best Practices](#best-practices)

---

## Overview

The Thin Client provides:
- **Minimal dependencies**: No embedded storage, no Kafka client
- **Simple integration**: Route through Router, let Router handle routing decisions
- **Consistent interface**: Same API as other Venice read clients
- **Streaming support**: Efficient batch get with streaming responses

### When to Use Thin Client

| Use Case | Recommendation |
|----------|---------------|
| Minimal dependency footprint | ✅ Thin Client |
| Simple integration | ✅ Thin Client |
| Ultra-low latency required | ❌ Use Da-Vinci or Fast Client |
| Direct server access needed | ❌ Use Fast Client |
| Embedding data locally | ❌ Use Da-Vinci Client |

---

## Architecture Diagram

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                           Thin Client Architecture                               │
├─────────────────────────────────────────────────────────────────────────────────┤
│                                                                                  │
│  ┌─────────────────┐                                                            │
│  │   Application   │                                                            │
│  └────────┬────────┘                                                            │
│           │                                                                      │
│           │ get(key) / batchGet(keys)                                           │
│           v                                                                      │
│  ┌─────────────────────────────────────────────────────────┐                    │
│  │              Thin Client (AvroGenericStoreClient)       │                    │
│  │  ┌─────────────────┐  ┌───────────────┐  ┌───────────┐ │                    │
│  │  │ Request Builder │  │ Serialization │  │  Stats    │ │                    │
│  │  └────────┬────────┘  └───────┬───────┘  └─────┬─────┘ │                    │
│  │           │                   │                │       │                     │
│  │           └───────────────────┴────────────────┘       │                     │
│  │                           │                            │                     │
│  │                           v                            │                     │
│  │  ┌─────────────────────────────────────────────────┐   │                    │
│  │  │            TransportClient (D2/HTTP)            │   │                    │
│  │  └─────────────────────────────────────────────────┘   │                    │
│  └───────────────────────────┬─────────────────────────────┘                    │
│                              │                                                   │
│                              │ HTTP Request                                      │
│                              v                                                   │
│  ┌─────────────────────────────────────────────────────────┐                    │
│  │                      Venice Router                       │                    │
│  │  ┌─────────────┐  ┌────────────────┐  ┌─────────────┐  │                    │
│  │  │ Path Parser │  │ Routing Logic  │  │ Load Balance│  │                    │
│  │  └──────┬──────┘  └───────┬────────┘  └──────┬──────┘  │                    │
│  │         └─────────────────┴──────────────────┘         │                    │
│  └───────────────────────────┬────────────────────────────┘                    │
│                              │                                                   │
│                 ┌────────────┼────────────┐                                     │
│                 │            │            │                                      │
│                 v            v            v                                      │
│         ┌───────────┐ ┌───────────┐ ┌───────────┐                              │
│         │  Server 1 │ │  Server 2 │ │  Server 3 │                              │
│         └───────────┘ └───────────┘ └───────────┘                              │
│                                                                                  │
└─────────────────────────────────────────────────────────────────────────────────┘
```

---

## Request Flow

### Single Get Request

```
┌──────────────────────────────────────────────────────────────────────────────┐
│                        Single Get Request Flow                                │
├──────────────────────────────────────────────────────────────────────────────┤
│                                                                               │
│  1. Client calls get(key)                                                    │
│     │                                                                        │
│     v                                                                        │
│  2. Serialize key to Avro bytes                                              │
│     │   request_serialization_time metric                                    │
│     v                                                                        │
│  3. Build HTTP request with serialized key                                   │
│     │                                                                        │
│     v                                                                        │
│  4. Send request to Router via TransportClient                               │
│     │   request_submission_to_response_handling_time starts                  │
│     v                                                                        │
│  5. Router parses request, determines partition                              │
│     │                                                                        │
│     v                                                                        │
│  6. Router selects server replica for partition                              │
│     │                                                                        │
│     v                                                                        │
│  7. Router forwards request to server                                        │
│     │                                                                        │
│     v                                                                        │
│  8. Server reads from RocksDB, returns value                                 │
│     │                                                                        │
│     v                                                                        │
│  9. Router returns response to client                                        │
│     │   request_submission_to_response_handling_time ends                    │
│     v                                                                        │
│  10. Deserialize response bytes to value                                     │
│      │   response_deserialization_time metric                                │
│      v                                                                        │
│  11. Return value to application                                             │
│      │   healthy/unhealthy_request metrics recorded                          │
│      v                                                                        │
│  Done                                                                        │
│                                                                               │
└──────────────────────────────────────────────────────────────────────────────┘
```

### Batch Get Request (Streaming)

```
┌──────────────────────────────────────────────────────────────────────────────┐
│                     Batch Get Streaming Response Flow                         │
├──────────────────────────────────────────────────────────────────────────────┤
│                                                                               │
│  Time ───────────────────────────────────────────────────────────────────►   │
│                                                                               │
│  Request:  ┌────────────────────────────────────────────┐                    │
│            │ batchGet([key1, key2, key3, ..., keyN])    │                    │
│            └─────────────────────┬──────────────────────┘                    │
│                                  │                                            │
│                                  v                                            │
│  Response: ┌──────┬──────┬──────┬──────┬───────────────┐                    │
│            │ val1 │ val2 │ val3 │ ...  │     valN      │                    │
│            └──┬───┴──┬───┴──┬───┴──────┴───────┬───────┘                    │
│               │      │      │                  │                             │
│               │      │      │                  │                             │
│    TTFR ──────┘      │      │                  │                             │
│    (Time to First    │      │                  │                             │
│     Record)          │      │                  │                             │
│                      │      │                  │                             │
│    TT50PR ───────────┘      │                  │                             │
│    (Time to 50th            │                  │                             │
│     Percentile Record)      │                  │                             │
│                             │                  │                             │
│    TT90PR ──────────────────┘                  │                             │
│    (Time to 90th                               │                             │
│     Percentile Record)                         │                             │
│                                                │                             │
│    Total Latency ──────────────────────────────┘                             │
│                                                                               │
└──────────────────────────────────────────────────────────────────────────────┘
```

---

## Key Classes

| Class | Location | Responsibility |
|-------|----------|----------------|
| `AvroGenericStoreClient` | `clients/venice-thin-client/.../store/` | Primary interface for generic Avro reads |
| `AvroSpecificStoreClient` | Same directory | Specific Avro record reads |
| `ClientFactory` | Same directory | Creates thin-client instances |
| `TransportClient` | `.../transport/` | HTTP/D2 communication abstraction |
| `BasicClientStats` | `.../stats/` | Base metrics shared across clients |
| `ClientStats` | `.../stats/` | Extended thin client metrics |

### Class Hierarchy

```
┌───────────────────────────────────────────────────────────────────────────────┐
│                        Thin Client Class Hierarchy                            │
├───────────────────────────────────────────────────────────────────────────────┤
│                                                                               │
│  ┌─────────────────────────────────┐                                         │
│  │      StoreClient<K, V>          │  ← Public interface                     │
│  │  (get, batchGet, compute, etc.) │                                         │
│  └───────────────┬─────────────────┘                                         │
│                  │                                                            │
│                  │ implements                                                 │
│                  v                                                            │
│  ┌─────────────────────────────────┐                                         │
│  │   AbstractAvroStoreClient       │  ← Common Avro handling                 │
│  └───────────────┬─────────────────┘                                         │
│                  │                                                            │
│       ┌──────────┴──────────┐                                                │
│       │                     │                                                 │
│       v                     v                                                 │
│  ┌─────────────┐    ┌─────────────────┐                                      │
│  │AvroGeneric  │    │AvroSpecific     │                                      │
│  │StoreClient  │    │StoreClient<T>   │                                      │
│  └─────────────┘    └─────────────────┘                                      │
│                                                                               │
│  Transport Layer:                                                            │
│  ┌─────────────────────────────────┐                                         │
│  │       TransportClient           │  ← Abstract transport                   │
│  └───────────────┬─────────────────┘                                         │
│                  │                                                            │
│       ┌──────────┼──────────┐                                                │
│       │          │          │                                                 │
│       v          v          v                                                 │
│  ┌────────┐ ┌────────┐ ┌──────────┐                                          │
│  │  D2    │ │ HTTP   │ │ Apache   │                                          │
│  │Client  │ │Client  │ │HttpAsync │                                          │
│  └────────┘ └────────┘ └──────────┘                                          │
│                                                                               │
└───────────────────────────────────────────────────────────────────────────────┘
```

---

## Configuration Reference

### Client Configuration

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `storeName` | String | Required | Venice store name |
| `routerUrl` | String | Required | Router URL or D2 service name |
| `specifiedClass` | Class | null | Specific Avro record class (for type-safe access) |
| `statsEnabled` | boolean | true | Enable metrics collection |
| `statsPrefix` | String | "" | Prefix for metric names |
| `useFastAvro` | boolean | true | Use fast Avro serialization |

### Transport Configuration

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `maxConnectionsPerRoute` | int | 10 | Max HTTP connections per route |
| `maxConnectionsTotal` | int | 20 | Total max HTTP connections |
| `connectionTimeout` | long | 10000 | Connection timeout (ms) |
| `readTimeout` | long | 60000 | Read timeout (ms) |

### D2 Configuration (if using D2)

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `d2ServiceName` | String | null | D2 service name for Router |
| `d2ZkHosts` | String | null | ZooKeeper hosts for D2 |
| `d2BasePath` | String | "/d2" | D2 base path in ZooKeeper |

---

## Usage Examples

### Basic Single Get

```java
// Create client
ClientConfig config = ClientConfig.defaultGenericClientConfig("my-store")
    .setVeniceURL("http://router:1576");

AvroGenericStoreClient<String, GenericRecord> client =
    ClientFactory.getAndStartGenericAvroClient(config);

// Single get
GenericRecord value = client.get("my-key").get();

// Don't forget to close
client.close();
```

### Batch Get

```java
Set<String> keys = Set.of("key1", "key2", "key3");

// Blocking batch get
Map<String, GenericRecord> results = client.batchGet(keys).get();

// With timeout
Map<String, GenericRecord> results = client.batchGet(keys)
    .get(5, TimeUnit.SECONDS);
```

### Streaming Batch Get

```java
Set<String> keys = Set.of("key1", "key2", "key3");

// Streaming batch get - process results as they arrive
client.streamingBatchGet(keys, new StreamingCallback<String, GenericRecord>() {
    @Override
    public void onRecordReceived(String key, GenericRecord value) {
        // Process each result as it arrives
        System.out.println("Received: " + key + " = " + value);
    }

    @Override
    public void onCompletion(Optional<Exception> exception) {
        if (exception.isPresent()) {
            System.err.println("Error: " + exception.get());
        } else {
            System.out.println("All records received");
        }
    }
});
```

### Specific Record Client

```java
// For type-safe access with generated Avro classes
ClientConfig config = ClientConfig.defaultSpecificClientConfig("my-store")
    .setSpecificValueClass(MySpecificRecord.class)
    .setVeniceURL("http://router:1576");

AvroSpecificStoreClient<String, MySpecificRecord> client =
    ClientFactory.getAndStartSpecificAvroClient(config);

MySpecificRecord value = client.get("my-key").get();
```

---

## Best Practices

### Connection Management

```java
// ✅ Good: Create client once, reuse
AvroGenericStoreClient client = ClientFactory.getAndStartGenericAvroClient(config);
// ... use client for multiple requests ...
client.close(); // Close when done

// ❌ Bad: Creating client per request
for (String key : keys) {
    AvroGenericStoreClient client = ClientFactory.getAndStartGenericAvroClient(config);
    client.get(key).get();
    client.close(); // Expensive!
}
```

### Timeout Handling

```java
// Always set reasonable timeouts for production
try {
    GenericRecord value = client.get("key")
        .get(5, TimeUnit.SECONDS);
} catch (TimeoutException e) {
    // Handle timeout - consider retry or fallback
    log.warn("Request timed out for key: {}", key);
}
```

### Batch Size Guidelines

| Batch Size | Recommendation |
|------------|---------------|
| 1-100 keys | Standard batch get works well |
| 100-1000 keys | Use streaming batch get |
| 1000+ keys | Split into multiple requests |

### Error Handling

```java
try {
    GenericRecord value = client.get("key").get();
    if (value == null) {
        // Key not found - this is normal, not an error
        log.debug("Key not found: {}", key);
    }
} catch (VeniceClientException e) {
    // Venice-specific errors
    if (e.getHttpStatus() == 503) {
        // Service unavailable - retry with backoff
    }
    log.error("Venice error: {} (HTTP {})", e.getMessage(), e.getHttpStatus());
} catch (Exception e) {
    // Other errors (network, serialization, etc.)
    log.error("Unexpected error", e);
}
```

---

## See Also

- [Thin Client Metrics](thin_client_metrics.md) - Detailed metrics reference
- [Fast Client Architecture](fast_client_architecture.md) - Higher-performance alternative
- [Da-Vinci Architecture](da_vinci_architecture.md) - Embedded client option
- [Clients Overview](clients_overview.md) - Client comparison
