# Venice Router Architecture

This document provides a deep dive into the Venice Router's internal architecture, covering all major components and their interactions.

## High-Level Architecture

```
┌──────────────────────────────────────────────────────────────────────────────┐
│                              Venice Router                                   │
├──────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  ┌─────────────────────────────────────────────────────────────────────────┐ │
│  │                      Netty Pipeline Layer                               │ │
│  │  ┌───────────────┐ ┌────────────────┐ ┌──────────────┐ ┌─────────────┐  │ │
│  │  │HealthCheck    │ │RouterThrottle  │ │ ACL Handler  │ │ MetaData    │  │ │
│  │  │Handler        │ │Handler         │ │ (optional)   │ │ Handler     │  │ │
│  │  └───────────────┘ └────────────────┘ └──────────────┘ └─────────────┘  │ │
│  └─────────────────────────────────────────────────────────────────────────┘ │
│                                    │                                         │
│                                    ▼                                         │
│  ┌─────────────────────────────────────────────────────────────────────────┐ │
│  │                    Scatter-Gather Layer (Alpini)                        │ │
│  │  ┌──────────────┐ ┌─────────────────┐ ┌───────────────────────────────┐ │ │
│  │  │VenicePath    │ │VenicePartition  │ │   VeniceDelegateMode          │ │ │
│  │  │Parser        │ │Finder           │ │   (Routing Strategy)          │ │ │
│  │  └──────────────┘ └─────────────────┘ └───────────────────────────────┘ │ │
│  │  ┌──────────────┐ ┌─────────────────┐ ┌───────────────────────────────┐ │ │
│  │  │VeniceHost    │ │VeniceVersion    │ │   VeniceResponseAggregator    │ │ │
│  │  │Finder        │ │Finder           │ │   (Response Handling)         │ │ │
│  │  └──────────────┘ └─────────────────┘ └───────────────────────────────┘ │ │
│  └─────────────────────────────────────────────────────────────────────────┘ │
│                                    │                                         │
│                                    ▼                                         │
│  ┌─────────────────────────────────────────────────────────────────────────┐ │
│  │                      Dispatch Layer                                     │ │
│  │  ┌──────────────────┐  ┌───────────────────┐  ┌─────────────────────┐   │ │
│  │  │ VeniceDispatcher │  │ PendingRequest    │  │ StorageNodeClient   │   │ │
│  │  │                  │  │ Throttler         │  │ (HTTP Client)       │   │ │
│  │  └──────────────────┘  └───────────────────┘  └─────────────────────┘   │ │
│  └─────────────────────────────────────────────────────────────────────────┘ │
│                                                                              │
│  ┌─────────────────────────────────────────────────────────────────────────┐ │
│  │                     Supporting Services                                 │ │
│  │  ┌────────────────┐ ┌──────────────────┐ ┌────────────────────────────┐ │ │
│  │  │ReadRequest     │ │Dictionary        │ │HelixGroupSelector         │ │ │
│  │  │Throttler       │ │RetrievalService  │ │(optional)                 │ │ │
│  │  └────────────────┘ └──────────────────┘ └────────────────────────────┘ │ │
│  └─────────────────────────────────────────────────────────────────────────┘ │
│                                                                              │
│  ┌─────────────────────────────────────────────────────────────────────────┐ │
│  │                     Metadata Layer                                      │ │
│  │  ┌────────────────┐ ┌──────────────────┐ ┌────────────────────────────┐ │ │
│  │  │StoreRepository │ │SchemaRepository  │ │RoutingDataRepository      │ │ │
│  │  └────────────────┘ └──────────────────┘ └────────────────────────────┘ │ │
│  └─────────────────────────────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────────────────────────────┘
```

## Netty Pipeline Layer

The router uses Netty for HTTP handling with a configurable pipeline.

### Pipeline Order

```java
// Secure Router Pipeline (with ACL)
SSL Initializer
  → HealthCheckHandler
  → RouterSslVerificationHandler
  → MetaDataHandler
  → AdminOperationsHandler
  → RouterStoreAclHandler (if ACL enabled)
  → RouterThrottleHandler
  → VeniceChunkedWriteHandler
  → ScatterGatherHandler (Alpini)
```

### HealthCheckHandler

**Location:** `services/venice-router/src/main/java/com/linkedin/venice/router/HealthCheckHandler.java`

Handles health check requests from load balancers:

```java
// Health check detection
OPTIONS /* → 200 OK
GET /admin (no store name) → 200 OK
```

### RouterThrottleHandler

**Location:** `services/venice-router/src/main/java/com/linkedin/venice/router/RouterThrottleHandler.java`

Early-stage throttling before scatter-gather:

```java
// Throttle based on key count
if (!throttler.allow(keyCount)) {
    return 429 TOO_MANY_REQUESTS;
}
```

### MetaDataHandler

**Location:** `services/venice-router/src/main/java/com/linkedin/venice/router/MetaDataHandler.java`

Serves metadata API endpoints:

| Endpoint | Response |
|----------|----------|
| `GET /key_schema/{store}` | Key schema |
| `GET /value_schema/{store}` | Value schema(s) |
| `GET /current_version/{store}` | Current version number |
| `GET /cluster_discovery` | Cluster topology |
| `GET /leader_controller` | Controller address |

## Scatter-Gather Layer (Alpini)

Venice uses LinkedIn's Alpini framework for scatter-gather routing.

### VenicePathParser

**Location:** `services/venice-router/src/main/java/com/linkedin/venice/router/api/VenicePathParser.java`

Parses incoming requests and creates `VenicePath` objects.

**URL Patterns:**

```
GET  /storage/{storeName}/{key}?f={format}  → Single GET
POST /storage/{storeName}                   → Multi-GET
POST /compute/{storeName}                   → Compute
```

**Request Type Mapping:**

| Path Pattern | Request Type |
|--------------|--------------|
| `GET /storage/{store}/{key}` | `SINGLE_GET` |
| `POST /storage/{store}` | `MULTI_GET` or `MULTI_GET_STREAMING` |
| `POST /compute/{store}` | `COMPUTE` or `COMPUTE_STREAMING` |

**Key Validation:**
```java
Pattern STORE_PATTERN = "\\A[a-zA-Z][a-zA-Z0-9_-]*\\z";
int STORE_MAX_LENGTH = 128;
```

### VenicePartitionFinder

**Location:** `services/venice-router/src/main/java/com/linkedin/venice/router/api/VenicePartitionFinder.java`

Determines the partition ID for a given key using the store's partitioner.

```java
int findPartitionNumber(RouterKey key, String resourceName, int numPartitions)
```

### VeniceHostFinder

**Location:** `services/venice-router/src/main/java/com/linkedin/venice/router/api/VeniceHostFinder.java`

Selects replica hosts for a partition from routing data.

**Key Responsibilities:**
- Query Helix CustomizedView for partition assignments
- Filter out unhealthy hosts
- Return list of available replicas

### VeniceVersionFinder

**Location:** `services/venice-router/src/main/java/com/linkedin/venice/router/api/VeniceVersionFinder.java`

Resolves the current version for a store.

```java
int getVersion(String storeName)  // Returns current version number
// Example: Store "MyStore" → "MyStore_v5"
```

### VeniceDelegateMode

**Location:** `services/venice-router/src/main/java/com/linkedin/venice/router/api/VeniceDelegateMode.java`

Selects the routing strategy based on request type and configuration.

**Single-GET Strategy:**
```java
LEAST_LOADED_MODE_FOR_SINGLE_GET
// Routes to replica with lowest recent latency
// Uses 1.5x multiplier to avoid skewing
```

**Multi-Key Strategies:**

| Strategy | Description |
|----------|-------------|
| `GROUP_BY_PRIMARY_HOST` | Routes all keys to first replica |
| `GREEDY_ROUTING` | Minimizes total request count |
| `LEAST_LOADED_ROUTING` | Balances by current load |
| `HELIX_ASSISTED_ROUTING` | Uses Helix groups to limit fanout |

### VeniceResponseAggregator

**Location:** `services/venice-router/src/main/java/com/linkedin/venice/router/api/VeniceResponseAggregator.java`

Aggregates responses from multiple storage nodes.

**Key Responsibilities:**
- Collect responses from scatter-gather
- Handle compression/decompression
- Detect tardy (slow) responses
- Trigger long-tail retries

**Tardy Detection:**
```java
singleGetTardyThresholdMs = 10000  // 10 seconds
multiGetTardyThresholdMs = 10000
computeTardyThresholdMs = 10000
```

## Dispatch Layer

### VeniceDispatcher

**Location:** `services/venice-router/src/main/java/com/linkedin/venice/router/api/VeniceDispatcher.java`

Dispatches individual requests to storage nodes.

**Key Responsibilities:**
- Send HTTP requests to storage nodes
- Handle response futures
- Manage per-node pending request counts
- Trigger retries on errors

**Error Handling:**

| Status Code | Action |
|-------------|--------|
| 429 (TOO_MANY_REQUESTS) | Pass through to client |
| 500 (INTERNAL_SERVER_ERROR) | Trigger retry |
| 503 (SERVICE_UNAVAILABLE) | Trigger retry |
| Other errors | Return 502 (BAD_GATEWAY) |

**Pending Request Throttling:**
```java
if (pendingRequestCount > routerUnhealthyPendingConnThresholdPerRoute) {
    // Trigger error retry or reject request
}
```

### StorageNodeClient

HTTP client implementations for storage node communication:

| Implementation | Description |
|----------------|-------------|
| `ApacheHttpAsyncStorageNodeClient` | Apache HttpAsyncClient |
| `HttpClient5StorageNodeClient` | Apache HttpClient 5 |

**Configuration:**
```properties
storage.node.client.type=APACHE_HTTP_ASYNC_CLIENT  # or HTTP_CLIENT_5_CLIENT
http.client.pool.size=12
max.outgoing.conn.per.route=120
max.outgoing.conn=1200
```

### PendingRequestThrottler

**Location:** `services/venice-router/src/main/java/com/linkedin/venice/router/throttle/PendingRequestThrottler.java`

Limits total pending requests across the router:

```java
maxPendingRequest = 2500 * 12  // Default: 30,000

boolean put()   // Try to add pending request
void take()     // Release pending request slot
```

## Supporting Services

### ReadRequestThrottler

**Location:** `services/venice-router/src/main/java/com/linkedin/venice/router/throttle/ReadRequestThrottler.java`

Manages per-store read quotas.

**Quota Hierarchy:**
```
Router-Level Quota
    └── Per-Store Quota
         └── Per-Storage-Node Quota
```

**Quota Calculation:**
```java
// Ideal quota per router
idealQuotaPerRouter = maxRouterReadCapacity / numberOfActiveRouters

// Per-store quota
perStoreQuota = storeQuota / numberOfRouters

// Storage node quota (with buffer)
storageNodeQuota = perStoreQuota * quotaBuffer / numberOfNodes
```

### DictionaryRetrievalService

**Location:** `services/venice-router/src/main/java/com/linkedin/venice/router/api/DictionaryRetrievalService.java`

Manages compression dictionaries:

- Pre-fetches dictionaries from storage nodes
- Caches dictionaries per store version
- Used for ZSTD dictionary compression

### HelixGroupSelector

**Location:** `services/venice-router/src/main/java/com/linkedin/venice/router/api/routing/helix/HelixGroupSelector.java`

Optional Helix-assisted routing to limit fanout:

**Selection Strategies:**

| Strategy | Description |
|----------|-------------|
| `ROUND_ROBIN` | Distribute evenly across groups |
| `LEAST_LOADED` | Route to group with lowest latency |

```java
int selectGroup(long requestId, int groupNum)
void finishRequest(long requestId, int groupId, double latency)
```

## Metadata Layer

### Metadata Repositories

| Repository | Purpose |
|------------|---------|
| `ReadOnlyStoreRepository` | Store configurations |
| `ReadOnlySchemaRepository` | Key/value schemas |
| `HelixCustomizedViewOfflinePushRepository` | Partition assignments |
| `HelixLiveInstanceMonitor` | Active storage nodes |
| `HelixInstanceConfigRepository` | Instance configurations |

**Data Flow:**
```
ZooKeeper
    │
    ├──▶ StoreRepository (store configs)
    │
    ├──▶ SchemaRepository (schemas)
    │
    └──▶ RoutingDataRepository (Helix CV)
              │
              ▼
         VeniceHostFinder
```

## Thread Architecture

### Event Loop Groups

| Group | Threads | Purpose |
|-------|---------|---------|
| `serverEventLoopGroup` | 1 (Boss) | Accept connections |
| `workerEventLoopGroup` | `routerIOWorkerCount` | Handle I/O |
| `sslResolverEventLoopGroup` | `resolveThreads` | DNS resolution (optional) |

### Executor Services

| Executor | Purpose |
|----------|---------|
| `workerExecutor` | Cached thread pool for routing |
| `retryManagerExecutorService` | Scheduled retry management |

## Component Interactions

### Request Flow

```
Client Request
      │
      ▼
Netty Pipeline
      │
      ├──▶ HealthCheckHandler (OPTIONS/admin)
      │
      ├──▶ RouterThrottleHandler (early throttle)
      │
      ├──▶ MetaDataHandler (metadata requests)
      │
      └──▶ ScatterGatherHandler
                │
                ▼
         VenicePathParser
                │
                ├──▶ Parse store, key(s)
                │
                └──▶ Create VenicePath
                          │
                          ▼
                   VenicePartitionFinder
                          │
                          ▼
                   VeniceHostFinder
                          │
                          ▼
                   VeniceDelegateMode
                          │
                          ├──▶ Select routing strategy
                          │
                          └──▶ Create scatter plan
                                    │
                                    ▼
                             VeniceDispatcher
                                    │
                                    ├──▶ Send to storage nodes
                                    │
                                    └──▶ Collect responses
                                              │
                                              ▼
                                    VeniceResponseAggregator
                                              │
                                              ├──▶ Aggregate responses
                                              │
                                              └──▶ Return to client
```

### Retry Flow

```
Original Request
      │
      ▼
VeniceDispatcher.sendRequest()
      │
      ├──▶ Success → Aggregate response
      │
      └──▶ Slow/Error
               │
               ▼
         retryFuture.setSuccess(status)
               │
               ▼
         ScatterGatherHandler
               │
               ├──▶ Check retry budget
               │
               ├──▶ Select different replica
               │
               └──▶ Retry request
```
