# Venice Router Data Flow

This document details the request routing, scatter-gather, and long-tail retry mechanisms in the Venice Router.

## Request Routing Overview

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         Request Routing Flow                                │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│   Client                                                                    │
│     │                                                                       │
│     │ HTTP Request                                                          │
│     ▼                                                                       │
│   ┌─────────────────┐                                                       │
│   │ Netty Pipeline  │                                                       │
│   │ (health, ACL,   │                                                       │
│   │  throttle)      │                                                       │
│   └────────┬────────┘                                                       │
│            │                                                                │
│            ▼                                                                │
│   ┌─────────────────┐    ┌──────────────────┐                              │
│   │ VenicePathParser│───▶│ VeniceVersionFinder│──▶ Get current version     │
│   └────────┬────────┘    └──────────────────┘                              │
│            │                                                                │
│            ▼                                                                │
│   ┌─────────────────────┐                                                   │
│   │ VenicePartitionFinder│──▶ Determine partition(s) for key(s)           │
│   └────────┬────────────┘                                                   │
│            │                                                                │
│            ▼                                                                │
│   ┌─────────────────┐    ┌──────────────────────┐                          │
│   │ VeniceHostFinder│───▶│RoutingDataRepository │──▶ Get replica hosts     │
│   └────────┬────────┘    └──────────────────────┘                          │
│            │                                                                │
│            ▼                                                                │
│   ┌───────────────────┐                                                     │
│   │ VeniceDelegateMode│──▶ Select routing strategy & create scatter plan  │
│   └────────┬──────────┘                                                     │
│            │                                                                │
│            ▼                                                                │
│   ┌─────────────────┐    ┌──────────────────┐                              │
│   │ VeniceDispatcher│───▶│ StorageNodeClient │──▶ Send HTTP requests      │
│   └────────┬────────┘    └──────────────────┘                              │
│            │                                                                │
│            ▼                                                                │
│   ┌─────────────────────────┐                                               │
│   │ VeniceResponseAggregator│──▶ Aggregate & return response              │
│   └─────────────────────────┘                                               │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

## Single-GET Flow

### Request Format

```
GET /storage/{storeName}/{key}?f={format}
```

**Parameters:**
- `storeName`: Target store name
- `key`: Key value (URL-encoded or Base64)
- `f`: Format (`string` or `b64` for Base64)

### Processing Steps

```java
// 1. Parse request path
VenicePath path = pathParser.parseResourceUri(uri);
// Creates: VeniceSingleGetPath

// 2. Resolve current version
int version = versionFinder.getVersion(storeName);
String resourceName = storeName + "_v" + version;

// 3. Compute partition
int partition = partitionFinder.findPartitionNumber(key, resourceName, numPartitions);

// 4. Find replica hosts
List<Instance> hosts = hostFinder.findHosts(partition, resourceName);

// 5. Select best host (least loaded)
Instance selectedHost = scatterGatherMode.selectHost(hosts, path);

// 6. Dispatch request
dispatcher.dispatch(selectedHost, path);

// 7. Return response
return aggregator.buildResponse(responses);
```

### Routing Strategy for Single-GET

```java
// LEAST_LOADED_MODE_FOR_SINGLE_GET
// Uses weighted latency selection with 1.5x multiplier

double adjustedLatency = avgLatency * 1.5;  // Avoid over-steering
Instance bestHost = selectLowestLatencyHost(hosts, adjustedLatency);
```

## Multi-GET Flow

### Request Format

```
POST /storage/{storeName}
Content-Type: application/avro
X-Venice-Streaming: 1  (optional, for streaming response)

[Avro-encoded list of keys]
```

### Processing Steps

```java
// 1. Parse request and extract keys
VenicePath path = pathParser.parseResourceUri(uri);
// Creates: VeniceMultiGetPath with list of RouterKey

// 2. Group keys by partition
Map<Integer, List<RouterKey>> keysByPartition = groupByPartition(keys);

// 3. For each partition, find hosts
Map<Integer, List<Instance>> hostsByPartition = findHostsForPartitions(keysByPartition);

// 4. Apply routing strategy to create scatter plan
ScatterPlan plan = scatterGatherMode.scatter(hostsByPartition, path);

// 5. Dispatch to all selected hosts
List<Future<Response>> futures = dispatcher.dispatchAll(plan);

// 6. Aggregate responses
return aggregator.aggregate(futures);
```

### Scatter-Gather Strategies

#### GROUP_BY_PRIMARY_HOST

Routes all keys for the same partition to the first replica:

```
Keys: [k1, k2, k3, k4, k5]
Partitions: {P0: [k1, k3], P1: [k2, k4, k5]}

P0 replicas: [Host-A, Host-B, Host-C]
P1 replicas: [Host-B, Host-A, Host-C]

Scatter:
  Host-A: [k1, k3]  (P0 primary)
  Host-B: [k2, k4, k5]  (P1 primary)
```

#### GREEDY_ROUTING

Minimizes total number of requests by aggregating keys:

```
Keys: [k1, k2, k3, k4, k5]

Host-A serves: P0, P1
Host-B serves: P0, P2
Host-C serves: P1, P2

Greedy selection:
  Host-A: [k1, k2, k3, k4, k5]  (covers all partitions)
```

#### LEAST_LOADED_ROUTING

Balances load across hosts based on recent latency:

```java
for (partition : partitions) {
    hosts = getHosts(partition);
    selectedHost = selectLowestLatencyHost(hosts);
    assignKeysToHost(selectedHost, keys);
}
```

#### HELIX_ASSISTED_ROUTING

Limits fanout using Helix groups:

```
Groups: [Group-0: {Host-A, Host-B}, Group-1: {Host-C, Host-D}]

1. Select a group (round-robin or least-loaded)
2. Route all keys to hosts within selected group
3. Fall back to other group if needed
```

## Compute Flow

### Request Format

```
POST /compute/{storeName}
Content-Type: application/avro
X-Venice-Streaming: 1  (optional)

[Avro-encoded ComputeRequest]
```

**ComputeRequest Structure:**
```avro
{
  "keys": [...],
  "operations": [
    {"type": "DOT_PRODUCT", "field": "vector", "param": [0.1, 0.2, ...]},
    {"type": "COSINE_SIMILARITY", "field": "embedding", "param": [...]}
  ]
}
```

### Supported Operations

| Operation | Description |
|-----------|-------------|
| `DOT_PRODUCT` | Vector dot product |
| `COSINE_SIMILARITY` | Cosine similarity between vectors |
| `HADAMARD_PRODUCT` | Element-wise vector multiplication |
| `COUNT` | Count operation |

### Processing

Same scatter-gather flow as Multi-GET, but:
- Operations are executed on storage nodes
- Results are pre-computed before returning
- Decompression happens on storage node (not router)

## Long-Tail Retry

### Overview

Long-tail retry sends duplicate requests to different replicas when responses are slow.

```
┌──────────────────────────────────────────────────────────────────────────┐
│                        Long-Tail Retry Flow                              │
├──────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│   Original Request                                                       │
│        │                                                                 │
│        ▼                                                                 │
│   ┌─────────────────┐                                                    │
│   │   Host-A        │                                                    │
│   │   (Primary)     │                                                    │
│   └────────┬────────┘                                                    │
│            │                                                             │
│            │ Wait for threshold (e.g., 15ms)                            │
│            │                                                             │
│   ┌────────┴────────┐                                                    │
│   │                 │                                                    │
│   ▼                 ▼                                                    │
│ Response        Timeout                                                  │
│ received?       triggered?                                               │
│   │                 │                                                    │
│   │ Yes             │ Yes                                                │
│   │                 │                                                    │
│   ▼                 ▼                                                    │
│ Return          ┌─────────────────┐                                      │
│ response        │   Host-B        │ (Retry)                              │
│                 │   (Different)   │                                      │
│                 └────────┬────────┘                                      │
│                          │                                               │
│                          ▼                                               │
│                 First response wins                                      │
│                                                                          │
└──────────────────────────────────────────────────────────────────────────┘
```

### Retry Thresholds

```java
// Single-GET
longTailRetryForSingleGetThresholdMs = 15  // Default: 15ms

// Multi-GET (by key count)
longTailRetryForBatchGetThresholdMs = {
    1: 15,    // 1 key: 15ms
    5: 25,    // 2-5 keys: 25ms
    20: 50,   // 6-20 keys: 50ms
    100: 100  // 21+ keys: 100ms
}
```

### Smart Long-Tail Retry

Smart retry adds additional logic to avoid wasteful retries:

```java
// Abort retry if already past threshold
smartLongTailRetryAbortThresholdMs = 100  // Don't retry after 100ms

// Budget enforcement
singleKeyLongTailRetryBudgetPercentDecimal = 0.03  // 3% of requests
multiKeyLongTailRetryBudgetPercentDecimal = 0.03
```

### Retry Conditions

Retry is triggered when:
1. Response not received within threshold
2. Retry budget not exhausted
3. Different replica available
4. Request not already marked as slow route

Retry is aborted when:
1. Response already received
2. Budget exhausted
3. All replicas already tried
4. Route marked as slow

### Retry Metrics

| Metric | Description |
|--------|-------------|
| `retry_count` | Total retry attempts |
| `allowed_retry_count` | Retries that succeeded |
| `disallowed_retry_count` | Retries that failed |
| `delay_constraint_aborted_retry_count` | Aborted due to delay |
| `slow_route_aborted_retry_count` | Aborted due to slow route |
| `no_available_replica_aborted_retry_count` | No replica available |

## Error Retry

### Retriable Errors

```java
RETRIABLE_ERROR_CODES = {
    500,  // INTERNAL_SERVER_ERROR
    503   // SERVICE_UNAVAILABLE
}
```

When these errors occur, the request is retried on a different replica:

```java
if (RETRIABLE_ERROR_CODES.contains(statusCode)) {
    retryFuture.setSuccess(HttpResponseStatus.valueOf(statusCode));
    stats.recordErrorRetryCount(storeName);
}
```

### Pass-Through Errors

```java
PASS_THROUGH_ERROR_CODES = {
    429  // TOO_MANY_REQUESTS
}
```

These errors are returned directly to the client without retry.

## Response Handling

### Compression

The router handles compression/decompression:

```java
// Get compression strategy from response header
CompressionStrategy strategy = getCompressionStrategy(
    response.getHeader(VENICE_COMPRESSION_STRATEGY)
);

// For Single-GET: decompress on router
if (requestType == SINGLE_GET) {
    content = responseDecompressor.decompressSingleGetContent(strategy, content);
}

// For Multi-GET: decompress on router
if (requestType == MULTI_GET) {
    content = responseDecompressor.decompressMultiGetContent(strategy, content);
}

// For Compute: already decompressed on storage node
if (requestType == COMPUTE) {
    // No decompression needed
}
```

### Streaming Responses

For streaming requests (`X-Venice-Streaming: 1`):

```java
// Responses are chunked and streamed
VeniceChunkedResponse chunkedResponse = path.getChunkedResponse();

// Write chunks as they arrive
for (response : responses) {
    chunkedResponse.write(chunk, compressionStrategy);
}
```

### Response Aggregation

For multi-key requests, responses are aggregated:

```java
// Collect all responses
List<FullHttpResponse> responses = collectResponses(futures);

// Merge into single response
ByteBuf aggregatedContent = aggregateContent(responses);

// Build final response
return buildResponse(aggregatedContent);
```

## Quota Enforcement

### Throttling Points

```
┌─────────────────────────────────────────────────────────────────┐
│                    Quota Enforcement Points                     │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  1. Early Throttle (RouterThrottleHandler)                      │
│     └── Router-level capacity check                            │
│                                                                 │
│  2. Store Throttle (ReadRequestThrottler)                       │
│     └── Per-store quota check                                  │
│                                                                 │
│  3. Pending Request Throttle (PendingRequestThrottler)          │
│     └── Max pending requests check                             │
│                                                                 │
│  4. Per-Route Throttle (stateful health check)                  │
│     └── Per-storage-node pending request check                 │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

### Throttle Response

When throttled, the router returns:

```
HTTP/1.1 429 Too Many Requests
Content-Type: text/plain

Quota exceeded for store: {storeName}
```

## Request Context

### VenicePath Hierarchy

```
VenicePath (abstract)
├── VeniceSingleGetPath     (Single-GET)
├── VeniceMultiGetPath      (Multi-GET)
└── VeniceComputePath       (Compute)
```

### Path State

```java
class VenicePath {
    String storeName;
    String resourceName;
    RequestType requestType;
    int versionNumber;
    long requestTimestamp;

    // Retry tracking
    Set<String> slowStorageNodes;
    Set<String> requestedStorageNodes;

    // Response handling
    VeniceResponseDecompressor responseDecompressor;
    VeniceChunkedResponse chunkedResponse;
}
```

### Marking Slow Nodes

```java
// Mark node as slow for retry avoidance
path.markStorageNodeAsSlow(nodeId);

// Check if node is suitable for retry
boolean isSuitable = !path.isSlowStorageNode(nodeId)
                  && !path.hasRequestedStorageNode(nodeId);
```
