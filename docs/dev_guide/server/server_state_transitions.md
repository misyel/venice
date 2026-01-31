# Venice Server State Transitions

This document details the Helix state machine used by Venice Server for partition management.

## State Model Overview

Venice uses the Leader/Follower (Standby) state model, defined in `LeaderFollowerPartitionStateModel`.

```
                    ┌──────────────────────────────────────────┐
                    │           Helix State Machine            │
                    │                                          │
                    │    ┌──────────┐                          │
                    │    │  ERROR   │◀─── (Any state on error) │
                    │    └────┬─────┘                          │
                    │         │ onBecomeOfflineFromError()     │
                    │         ▼                                │
                    │    ┌──────────┐                          │
                    │    │ OFFLINE  │ (Initial State)          │
                    │    └────┬─────┘                          │
                    │         │                                │
                    │         │ onBecomeStandbyFromOffline()   │
                    │         ▼                                │
                    │    ┌──────────┐                          │
                    │    │ STANDBY  │ (Follower)               │
                    │    └────┬─────┘                          │
                    │         │                                │
                    │         │ onBecomeLeaderFromStandby()    │
                    │         ▼                                │
                    │    ┌──────────┐                          │
                    │    │  LEADER  │                          │
                    │    └────┬─────┘                          │
                    │         │                                │
                    │         │ onBecomeStandbyFromLeader()    │
                    │         ▼                                │
                    │    ┌──────────┐                          │
                    │    │ STANDBY  │                          │
                    │    └────┬─────┘                          │
                    │         │                                │
                    │         │ onBecomeOfflineFromStandby()   │
                    │         ▼                                │
                    │    ┌──────────┐                          │
                    │    │ OFFLINE  │                          │
                    │    └────┬─────┘                          │
                    │         │                                │
                    │         │ onBecomeDroppedFromOffline()   │
                    │         ▼                                │
                    │    ┌──────────┐                          │
                    │    │ DROPPED  │ (Terminal)               │
                    │    └──────────┘                          │
                    │                                          │
                    └──────────────────────────────────────────┘
```

## State Descriptions

| State | Description | Read Serving | Write Processing |
|-------|-------------|--------------|------------------|
| `OFFLINE` | Initial state, partition not active | No | No |
| `STANDBY` | Follower replica, consuming from VT | Yes | No (follows leader) |
| `LEADER` | Leader replica, handling writes | Yes | Yes |
| `DROPPED` | Partition removed, cleanup complete | No | No |
| `ERROR` | Error state, requires manual intervention | No | No |

## Transition Details

### OFFLINE -> STANDBY (onBecomeStandbyFromOffline)

This transition initializes a partition for consumption.

**Source:** `LeaderFollowerPartitionStateModel.java:97-138`

```java
@Transition(to = HelixState.STANDBY_STATE, from = HelixState.OFFLINE_STATE)
public void onBecomeStandbyFromOffline(Message message, NotificationContext context) {
    // 1. Reset offline timestamp
    lastOfflineTransitionTimestampMs = -1L;

    // 2. Determine if latch needed (current or ready future version)
    boolean isCurrentVersion = store.getCurrentVersion() == getVersionNumber();
    boolean isFutureVersionReady = Utils.isFutureVersionReady(resourceName, getStoreRepo());

    // 3. Create consumption latch if needed
    if (isCurrentVersion || isFutureVersionReady) {
        notifier.startConsumption(resourceName, getPartition());
    }

    // 4. Set up the new store partition (start ingestion)
    setupNewStorePartition();

    // 5. Update heartbeat monitor
    heartbeatMonitoringService.updateLagMonitor(
        resourceName, getPartition(),
        HeartbeatLagMonitorAction.SET_FOLLOWER_MONITOR
    );

    // 6. Wait for ingestion completion (if latched)
    if (isCurrentVersion || isFutureVersionReady) {
        waitConsumptionCompleted(resourceName, notifier);
    }
}
```

**Key Points:**
- For current/ready future versions, a latch ensures ingestion catches up before serving
- Prevents Helix "over-rebalancing" during cluster changes
- Heartbeat monitoring starts tracking follower lag

### STANDBY -> LEADER (onBecomeLeaderFromStandby)

This transition promotes a follower to leader.

**Source:** `LeaderFollowerPartitionStateModel.java:140-148`

```java
@Transition(to = HelixState.LEADER_STATE, from = HelixState.STANDBY_STATE)
public void onBecomeLeaderFromStandby(Message message, NotificationContext context) {
    // 1. Increment session ID for this transition
    LeaderSessionIdChecker checker = new LeaderSessionIdChecker(
        leaderSessionId.incrementAndGet(),
        leaderSessionId
    );

    // 2. Promote via ingestion service
    getIngestionBackend().getStoreIngestionService()
        .promoteToLeader(getStoreAndServerConfigs(), getPartition(), checker);
}
```

**What Changes:**
- Partition starts consuming from Real-Time Topic (RT)
- Leader produces processed messages to Version Topic (VT)
- Conflict resolution becomes active
- Write compute operations are handled

### LEADER -> STANDBY (onBecomeStandbyFromLeader)

This transition demotes a leader back to follower.

**Source:** `LeaderFollowerPartitionStateModel.java:150-158`

```java
@Transition(to = HelixState.STANDBY_STATE, from = HelixState.LEADER_STATE)
public void onBecomeStandbyFromLeader(Message message, NotificationContext context) {
    // 1. Increment session ID
    LeaderSessionIdChecker checker = new LeaderSessionIdChecker(
        leaderSessionId.incrementAndGet(),
        leaderSessionId
    );

    // 2. Demote via ingestion service
    getIngestionBackend().getStoreIngestionService()
        .demoteToStandby(getStoreAndServerConfigs(), getPartition(), checker);
}
```

**What Changes:**
- Stops consuming from RT
- Stops producing to VT
- Returns to VT-only consumption as follower

### STANDBY -> OFFLINE (onBecomeOfflineFromStandby)

This transition stops partition consumption.

**Source:** `LeaderFollowerPartitionStateModel.java:160-170`

```java
@Transition(to = HelixState.OFFLINE_STATE, from = HelixState.STANDBY_STATE)
public void onBecomeOfflineFromStandby(Message message, NotificationContext context) {
    // 1. Remove heartbeat monitor
    heartbeatMonitoringService.updateLagMonitor(
        resourceName, getPartition(),
        HeartbeatLagMonitorAction.REMOVE_MONITOR
    );

    // 2. Stop consumption
    stopConsumption(true);

    // 3. Record offline timestamp for graceful drop calculation
    lastOfflineTransitionTimestampMs = System.currentTimeMillis();
}
```

### OFFLINE -> DROPPED (onBecomeDroppedFromOffline)

This transition removes the partition completely.

**Source:** `LeaderFollowerPartitionStateModel.java:172-213`

```java
@Transition(to = HelixState.DROPPED_STATE, from = HelixState.OFFLINE_STATE)
public void onBecomeDroppedFromOffline(Message message, NotificationContext context) {
    // 1. Remove heartbeat monitor (safety)
    heartbeatMonitoringService.updateLagMonitor(
        resourceName, getPartition(),
        HeartbeatLagMonitorAction.REMOVE_MONITOR
    );

    // 2. For current version: execute graceful drop delay
    if (isCurrentVersion) {
        executeGracefulDropDelayForCurrentVersionReplica(replicaId);
    }

    // 3. Remove partition from store
    CompletableFuture<Void> dropPartitionFuture = removePartitionFromStoreGracefully();

    // 4. Wait for completion with timeout
    dropPartitionFuture.get(WAIT_DROP_PARTITION_TIME_OUT_MS, TimeUnit.MILLISECONDS);

    // 5. Reset offline timestamp
    lastOfflineTransitionTimestampMs = -1L;
}
```

**Graceful Drop Delay:**
```java
private void executeGracefulDropDelayForCurrentVersionReplica(String replicaId) {
    long gracefulDropDelayMs = TimeUnit.SECONDS.toMillis(
        getStoreAndServerConfigs().getPartitionGracefulDropDelaySeconds()
    );

    // Account for time already spent offline
    if (lastOfflineTransitionTimestampMs > 0) {
        long elapsedSinceOfflineMs = currentTimeMs - lastOfflineTransitionTimestampMs;
        remainingWaitMs = Math.max(0, gracefulDropDelayMs - elapsedSinceOfflineMs);
    }

    if (remainingWaitMs > 0) {
        Utils.sleep(remainingWaitMs);  // Allow in-flight requests to drain
    }
}
```

## Session ID Mechanism

Session IDs prevent stale state transition commands from being processed.

### Problem Addressed

In edge cases, Helix might rapidly flip between leader/follower states:
```
STANDBY -> LEADER -> STANDBY -> LEADER (rapid succession)
```

Without session IDs, old "become leader" commands could interfere with newer "become follower" commands.

### Implementation

```java
// State model tracks current session
private final AtomicLong leaderSessionId = new AtomicLong(0L);

// Each transition gets a new session ID
LeaderSessionIdChecker checker = new LeaderSessionIdChecker(
    leaderSessionId.incrementAndGet(),  // Assigned ID
    leaderSessionId                      // Reference to current
);

// Ingestion task validates before processing
public class LeaderSessionIdChecker {
    private final long assignedSessionId;
    private final AtomicLong latestSessionIdHandle;

    public boolean isSessionIdValid() {
        return assignedSessionId == latestSessionIdHandle.get();
    }
}
```

### Usage in Ingestion Task

```java
// In LeaderFollowerStoreIngestionTask
void processConsumerAction(ConsumerAction action) {
    LeaderSessionIdChecker checker = action.getSessionIdChecker();

    // Skip if session is stale
    if (!checker.isSessionIdValid()) {
        logger.info("Skipping stale action with session {}",
            action.getSessionId());
        return;
    }

    // Process the action
    executeAction(action);
}
```

## Consumption Latch Mechanism

The consumption latch ensures replicas catch up before serving traffic.

### When Latches Are Created

1. **Current version partitions** - Serving live traffic
2. **Ready future versions** - Deferred swap waiting to go live

### Latch Lifecycle

```java
// 1. Create latch (OFFLINE -> STANDBY start)
notifier.startConsumption(resourceName, partition);

// 2. Ingestion task signals completion
//    - When caught up to end offset
//    - When EOP received (for batch)
notifier.completed(resourceName, partition);

// 3. State transition waits
waitConsumptionCompleted(resourceName, notifier);
// Blocks until latch released or timeout
```

### Latch Release Conditions

| Condition | Description |
|-----------|-------------|
| Ingestion complete | Caught up to latest offset |
| EOP received | End of push for batch stores |
| Timeout | Configurable timeout exceeded |
| Error | Transition fails on error |

## State Transition Metrics

**Class:** `ParticipantStateTransitionStats`

| Metric | Description |
|--------|-------------|
| `offline_to_standby_latency` | Time for OFFLINE->STANDBY |
| `standby_to_leader_latency` | Time for STANDBY->LEADER |
| `leader_to_standby_latency` | Time for LEADER->STANDBY |
| `standby_to_offline_latency` | Time for STANDBY->OFFLINE |
| `offline_to_dropped_latency` | Time for OFFLINE->DROPPED |
| `thread_blocked_on_offline_to_dropped_count` | Threads in graceful drop wait |

## Error Handling

### ERROR State Recovery

```java
@Transition(to = HelixState.OFFLINE_STATE, from = HelixState.ERROR_STATE)
public void onBecomeOfflineFromError(Message message, NotificationContext context) {
    // Venice does not support automatic partition recovery
    // Manual intervention required
    logger.warn("unexpected state transition from ERROR to OFFLINE");
}
```

### DROPPED -> OFFLINE (Not Supported)

```java
@Transition(to = HelixState.OFFLINE_STATE, from = HelixState.DROPPED_STATE)
public void onBecomeOfflineFromDropped(Message message, NotificationContext context) {
    // Venice does not support automatic partition recovery
    logger.warn("unexpected state transition from DROPPED to OFFLINE");
}
```

## Heartbeat Monitor Integration

Each state transition updates the heartbeat monitoring service:

| Transition | Action |
|------------|--------|
| OFFLINE -> STANDBY | `SET_FOLLOWER_MONITOR` - Start tracking follower lag |
| STANDBY -> LEADER | (Handled by ingestion task) - Switch to leader tracking |
| STANDBY -> OFFLINE | `REMOVE_MONITOR` - Stop lag tracking |
| OFFLINE -> DROPPED | `REMOVE_MONITOR` - Ensure cleanup |

## Best Practices

### Graceful Drop Configuration

```properties
# Configure appropriate delay for in-flight request draining
# Default: 30 seconds
partition.graceful.drop.delay.seconds=30
```

### State Transition Timeout

```properties
# Timeout for waiting on partition drop
# Default: 5 minutes
state.transition.drop.timeout.ms=300000
```

### Monitoring State Transitions

Watch for:
- High `offline_to_standby_latency` - Slow ingestion catch-up
- High `thread_blocked_on_offline_to_dropped_count` - Too many concurrent drops
- Frequent ERROR state - Underlying issues
