# Venice Clients Overview

This document provides an overview of Venice client types, helping you choose the right client for your use case.

## Table of Contents

- [Client Types](#client-types)
- [Client Comparison Matrix](#client-comparison-matrix)
- [Selection Decision Tree](#selection-decision-tree)
- [Data Flow Overview](#data-flow-overview)
- [Quick Links](#quick-links)

---

## Client Types

Venice offers several client types optimized for different use cases:

| Client | Purpose | Key Characteristic |
|--------|---------|-------------------|
| **Da-Vinci Client** | Embedded reads | Local RocksDB storage, ultra-low latency |
| **Fast Client** | Remote reads | Direct server access, optimized routing |
| **Thin Client** | Remote reads | Minimal dependencies, routes via Router |
| **Producer** | Real-time writes | Async writes to Kafka RT topic |
| **Push Job** | Batch writes | Hadoop-based full dataset replacement |
| **Changelog Consumer** | Change Data Capture | Stream change events from Venice stores |

---

## Client Comparison Matrix

| Feature | Da-Vinci | Fast Client | Thin Client | Producer | Push Job |
|---------|----------|-------------|-------------|----------|----------|
| **Operation** | Read | Read | Read | Write | Write |
| **Data Location** | Local (embedded) | Remote (server) | Remote (router→server) | N/A | N/A |
| **Latency** | Ultra-low (<1ms) | Low (P99 <10ms) | Low-Medium | N/A | N/A |
| **Dependencies** | Heavy (RocksDB) | Minimal | Minimal | Light | Hadoop |
| **Memory Usage** | High (full partition) | Low | Low | Low | N/A |
| **Disk Usage** | High | None | None | None | N/A |
| **Network** | Kafka ingestion | Direct to server | Via Router | To Kafka | To Kafka |
| **Subscription** | Required | No | No | N/A | N/A |
| **Batch Get** | Yes | Yes | Yes | N/A | N/A |
| **Streaming** | No | Yes | Yes | N/A | N/A |
| **Read Compute** | Yes | Yes | Yes | N/A | N/A |
| **Partial Updates** | N/A | N/A | N/A | Yes | No |

---

## Selection Decision Tree

Use this decision tree to select the appropriate Venice client:

```
                        ┌─────────────────────────────────────┐
                        │       What operation do you need?   │
                        └──────────────────┬──────────────────┘
                                           │
                    ┌──────────────────────┼──────────────────────┐
                    │                      │                      │
                   Read                  Write                  Both
                    │                      │                      │
                    v                      v                      v
        ┌───────────────────┐   ┌─────────────────────┐   ┌──────────────────┐
        │ Latency priority? │   │ Batch or Real-time? │   │ Use Read Client  │
        └─────────┬─────────┘   └──────────┬──────────┘   │ + Write Client   │
                  │                        │              └──────────────────┘
     ┌────────────┼────────────┐      ┌────┴────┐
     │            │            │      │         │
  Ultra-low    Low       Low-Med    Batch   Real-time
  (<1ms P99)  (<10ms)   (<50ms)      │         │
     │            │            │      │         │
     v            v            v      v         v
┌─────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐
│Da-Vinci │ │Fast      │ │Thin      │ │Push Job  │ │Producer  │
│Client   │ │Client    │ │Client    │ │          │ │          │
└─────────┘ └──────────┘ └──────────┘ └──────────┘ └──────────┘
     │            │            │
     └────────────┴────────────┘
                  │
                  v
        ┌─────────────────────┐
        │ Additional factors: │
        │ • Memory/disk OK?   │
        │   → Da-Vinci        │
        │ • Minimal deps?     │
        │   → Thin Client     │
        │ • Best perf?        │
        │   → Fast Client     │
        └─────────────────────┘
```

### Decision Summary

| Your Need | Recommended Client |
|-----------|-------------------|
| Lowest possible latency, memory/disk OK | **Da-Vinci Client** |
| Low latency, minimal resource usage | **Fast Client** |
| Simple integration, moderate latency OK | **Thin Client** |
| Incremental/partial updates | **Producer** |
| Full dataset replacement from Hadoop | **Push Job** |

---

## Data Flow Overview

This diagram shows how data flows through Venice for different client types:

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                              READ PATHS                                          │
├─────────────────────────────────────────────────────────────────────────────────┤
│                                                                                  │
│  Da-Vinci Client:                                                               │
│  ┌─────────┐      ┌─────────────────┐                                           │
│  │   App   │ ───► │ Local RocksDB   │ ───► Value                                │
│  └─────────┘      └─────────────────┘                                           │
│       │                    ▲                                                     │
│       │ subscribe()        │ ingestion                                          │
│       │                    │                                                     │
│       └────────────────────┼────────────────────┐                               │
│                            │                    │                                │
│                      ┌─────┴─────┐        ┌─────┴─────┐                         │
│                      │ Kafka VT  │        │ Kafka RT  │                         │
│                      └───────────┘        └───────────┘                         │
│                                                                                  │
│  Thin Client:                                                                   │
│  ┌─────────┐      ┌─────────┐      ┌─────────┐                                  │
│  │   App   │ ───► │ Router  │ ───► │ Server  │ ───► Value                       │
│  └─────────┘      └─────────┘      └─────────┘                                  │
│                                                                                  │
│  Fast Client:                                                                   │
│  ┌─────────┐                       ┌─────────┐                                  │
│  │   App   │ ─────────────────────►│ Server  │ ───► Value                       │
│  └─────────┘                       └─────────┘                                  │
│       │ metadata                                                                 │
│       └─────────────► Router                                                    │
│                                                                                  │
├─────────────────────────────────────────────────────────────────────────────────┤
│                              WRITE PATHS                                         │
├─────────────────────────────────────────────────────────────────────────────────┤
│                                                                                  │
│  Producer (Real-time):                                                          │
│  ┌─────────┐      ┌───────────┐      ┌─────────┐      ┌───────────┐            │
│  │   App   │ ───► │ Kafka RT  │ ───► │ Server  │ ───► │ RocksDB   │            │
│  └─────────┘      └───────────┘      │(ingest) │      └───────────┘            │
│                                       └─────────┘                               │
│                                                                                  │
│  Push Job (Batch):                                                              │
│  ┌─────────┐      ┌───────────┐      ┌─────────┐      ┌───────────┐            │
│  │ Hadoop  │ ───► │ Kafka VT  │ ───► │ Server  │ ───► │ RocksDB   │            │
│  └─────────┘      └───────────┘      │(ingest) │      └───────────┘            │
│                                       └─────────┘                               │
│                                                                                  │
├─────────────────────────────────────────────────────────────────────────────────┤
│                              CDC/STREAMING PATH                                  │
├─────────────────────────────────────────────────────────────────────────────────┤
│                                                                                  │
│  Changelog Consumer (CDC):                                                      │
│  ┌─────────┐      ┌───────────┐                                                 │
│  │   App   │ ◄─── │ Kafka VT  │ ◄─── Change events (version topic)              │
│  │  (CDC)  │      └───────────┘                                                 │
│  │         │      ┌───────────┐                                                 │
│  │         │ ◄─── │ Kafka RT  │ ◄─── Change events (real-time topic)            │
│  └─────────┘      └───────────┘                                                 │
│                                                                                  │
└─────────────────────────────────────────────────────────────────────────────────┘

Legend:
  VT = Version Topic (batch data, one per version)
  RT = Real-Time Topic (incremental updates, one per store)
```

### Key Concepts

| Concept | Description |
|---------|-------------|
| **VT (Version Topic)** | Kafka topic containing complete dataset for a store version |
| **RT (Real-Time Topic)** | Kafka topic for incremental updates to a store |
| **Hybrid Store** | Store supporting both batch (VT) and real-time (RT) data |
| **Subscription** | Da-Vinci client subscribing to partitions for local storage |

---

## Quick Links

### Read Clients

| Client | Architecture | Metrics |
|--------|-------------|---------|
| **Thin Client** | [thin_client_architecture.md](thin_client_architecture.md) | [thin_client_metrics.md](thin_client_metrics.md) |
| **Fast Client** | [fast_client_architecture.md](fast_client_architecture.md) | [fast_client_metrics.md](fast_client_metrics.md) |
| **Da-Vinci Client** | [da_vinci_architecture.md](da_vinci_architecture.md) | [da_vinci_metrics.md](da_vinci_metrics.md) |

### Write Clients

| Client | Architecture | Metrics |
|--------|-------------|---------|
| **Producer** | [producer_architecture.md](producer_architecture.md) | [producer_metrics.md](producer_metrics.md) |

### CDC/Streaming Clients

| Client | Architecture | Metrics |
|--------|-------------|---------|
| **Changelog Consumer** | [changelog_consumer_architecture.md](changelog_consumer_architecture.md) | [changelog_consumer_metrics.md](changelog_consumer_metrics.md) |

### Related Documentation

- [Router Architecture](../router_architecture.md)
- [Router Metrics](../router_metrics.md)
- [Server Architecture](../server/server_architecture.md)

---

## See Also

- [Key Classes Map](../../../.claude/rules/key-classes.md) - Overview of important Venice classes
- [Venice Documentation](https://venicedb.org/) - Main documentation site
