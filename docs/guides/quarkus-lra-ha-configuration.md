# Configuring Narayana LRA Coordinator in HA Mode with Quarkus

This guide covers how to configure the Narayana LRA Coordinator for High Availability (HA) when using Quarkus as the application runtime.

## Architecture Overview

The LRA Coordinator HA mode uses a dual-layer persistence model:

- **Narayana ObjectStore** (local, file-based): Source of truth for durability. Survives crashes and restarts.
- **Infinispan Distributed Cache** (replicated, in-memory): Source of truth for availability. Enables any coordinator node to access any LRA.

The embedded Infinispan instance uses JGroups for cluster transport. Three replicated caches hold LRA state:

| Cache | Contents | Max Entries |
|---|---|---|
| `lra-active` | LRAs in Active state | 10,000 |
| `lra-recovering` | LRAs in Closing/Cancelling/Closed/Cancelled state | 10,000 |
| `lra-failed` | LRAs in FailedToClose/FailedToCancel state | 1,000 |

## Prerequisites

- Quarkus 3.x application with the `quarkus-narayana-lra` extension
- Infinispan libraries on the classpath (embedded mode)
- JGroups library on the classpath

## Step 1: Add Maven Dependencies

Add the Infinispan embedded and JGroups dependencies to your `pom.xml`:

```xml
<dependency>
    <groupId>org.infinispan</groupId>
    <artifactId>infinispan-core</artifactId>
</dependency>
<dependency>
    <groupId>org.infinispan</groupId>
    <artifactId>infinispan-clustered-lock</artifactId>
</dependency>
<dependency>
    <groupId>org.jgroups</groupId>
    <artifactId>jgroups</artifactId>
</dependency>
```

## Step 2: Configure application.properties

Add the following to your Quarkus `application.properties`:

```properties
# =============================================================
# LRA Coordinator HA Configuration
# =============================================================

# Enable HA mode (required)
lra.coordinator.ha.enabled=true

# Cluster name - must be identical on all nodes
lra.coordinator.cluster.name=lra-cluster

# Unique node identifier (each node must have a different value)
# If not set, falls back to HOSTNAME env var, then auto-generated
lra.coordinator.node.id=lra-coord-1

# =============================================================
# JGroups Transport
# =============================================================

# Path to a custom JGroups XML config (classpath or filesystem).
# If omitted, JGroups default UDP multicast stack is used.
lra.coordinator.jgroups.config=jgroups-tcp.xml

# Bind address for JGroups (default: 127.0.0.1)
lra.coordinator.jgroups.bind_addr=127.0.0.1

# =============================================================
# Infinispan
# =============================================================

# Cache replication mode (default: REPL_SYNC)
# Options: REPL_SYNC, REPL_ASYNC, DIST_SYNC, DIST_ASYNC, LOCAL
lra.coordinator.infinispan.cache.mode=REPL_SYNC

# Persistent location for Infinispan global state.
# Each node MUST use a separate directory.
lra.coordinator.infinispan.persistent.location=/var/lib/lra/infinispan/node1

# =============================================================
# Narayana ObjectStore
# =============================================================

# ObjectStore directory (each node needs its own directory)
com.arjuna.ats.arjuna.objectstore.objectStoreDir=/var/lib/lra/tx-object-store/node1

# Narayana node identifier (must be unique per node)
com.arjuna.ats.arjuna.common.CoordinatorEnvironmentBean.nodeIdentifier=1

# =============================================================
# Recovery Tuning
# =============================================================

# Recovery scan interval in milliseconds (default: 120000 = 2 minutes)
com.arjuna.ats.arjuna.recovery.periodicRecoveryPeriod=120000

# Backoff period between recovery phases in milliseconds
com.arjuna.ats.arjuna.recovery.recoveryBackoffPeriod=10000

# =============================================================
# Quarkus LRA Coordinator URL
# =============================================================

# The URL this coordinator instance advertises to participants.
# Typically fronted by a load balancer in production.
quarkus.lra.coordinator-url=http://lra-lb:8080/lra-coordinator/lra-coordinator
```

## Step 3: Provide a JGroups Configuration File

### Option A: TCP with Static Discovery (recommended for known hosts)

Place this as `src/main/resources/jgroups-tcp.xml`:

```xml
<config xmlns="urn:org:jgroups"
        xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xsi:schemaLocation="urn:org:jgroups http://www.jgroups.org/schema/jgroups.xsd">

    <TCP bind_addr="${lra.coordinator.jgroups.bind_addr:127.0.0.1}"
         bind_port="7800"
         port_range="50"
         recv_buf_size="20m"
         send_buf_size="640k"
         thread_pool.enabled="true"
         thread_pool.min_threads="0"
         thread_pool.max_threads="25"
         thread_pool.keep_alive_time="5000" />

    <!-- List all coordinator nodes here -->
    <TCPPING async_discovery="true"
             initial_hosts="host1[7800],host2[7800],host3[7800]"
             port_range="5" />

    <MERGE3 min_interval="10000" max_interval="30000" />
    <FD_SOCK />
    <FD_ALL timeout="10000" interval="3000" />
    <VERIFY_SUSPECT timeout="1500" />

    <pbcast.NAKACK2 use_mcast_xmit="false"
                    discard_delivered_msgs="true" />

    <UNICAST3 conn_close_timeout="5000" />

    <pbcast.STABLE stability_delay="500"
                   desired_avg_gossip="5000"
                   max_bytes="1M" />

    <pbcast.GMS print_local_addr="false"
                join_timeout="3000"
                leave_timeout="1000" />

    <MFC max_credits="2m" min_threshold="0.40" />
    <FRAG3 frag_size="8000" />
</config>
```

### Option B: UDP Multicast (simpler for same-subnet nodes)

Omit the `lra.coordinator.jgroups.config` property entirely. JGroups will use its default UDP multicast stack. This works when all coordinator nodes are on the same network subnet and multicast is enabled.

### Option C: Kubernetes with KUBE_PING

Place this as `src/main/resources/jgroups-kubernetes.xml`:

```xml
<config xmlns="urn:org:jgroups"
        xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xsi:schemaLocation="urn:org:jgroups http://www.jgroups.org/schema/jgroups.xsd">

    <TCP bind_addr="match-interface:eth0"
         bind_port="7800"
         port_range="0" />

    <org.jgroups.protocols.kubernetes.KUBE_PING
         namespace="${KUBERNETES_NAMESPACE:default}"
         labels="${KUBERNETES_LABELS:app=lra-coordinator}" />

    <MERGE3 min_interval="10000" max_interval="30000" />
    <FD_SOCK />
    <FD_ALL timeout="10000" interval="3000" />
    <VERIFY_SUSPECT timeout="1500" />

    <pbcast.NAKACK2 use_mcast_xmit="false"
                    discard_delivered_msgs="true" />
    <UNICAST3 conn_close_timeout="5000" />
    <pbcast.STABLE stability_delay="500"
                   desired_avg_gossip="5000"
                   max_bytes="1M" />
    <pbcast.GMS print_local_addr="false"
                join_timeout="3000" />
    <MFC max_credits="2m" min_threshold="0.40" />
    <FRAG3 frag_size="8000" />
</config>
```

And add the Kubernetes discovery dependency:

```xml
<dependency>
    <groupId>org.jgroups.kubernetes</groupId>
    <artifactId>jgroups-kubernetes</artifactId>
</dependency>
```

Set in `application.properties`:

```properties
lra.coordinator.jgroups.config=jgroups-kubernetes.xml
lra.coordinator.jgroups.bind_addr=match-interface:eth0
```

## Step 4: Multi-Node Deployment

### Running Multiple Nodes Locally (Development / Testing)

Start each node with distinct ports and node IDs:

```bash
# Node 1
java -Dquarkus.http.port=8080 \
     -Dlra.coordinator.ha.enabled=true \
     -Dlra.coordinator.node.id=node1 \
     -Dlra.coordinator.cluster.name=lra-cluster \
     -Dlra.coordinator.jgroups.config=jgroups-tcp.xml \
     -Dlra.coordinator.jgroups.bind_addr=127.0.0.1 \
     -Dlra.coordinator.infinispan.persistent.location=/tmp/lra-ispn-node1 \
     -Djava.net.preferIPv4Stack=true \
     -jar target/quarkus-app/quarkus-run.jar

# Node 2
java -Dquarkus.http.port=8180 \
     -Dlra.coordinator.ha.enabled=true \
     -Dlra.coordinator.node.id=node2 \
     -Dlra.coordinator.cluster.name=lra-cluster \
     -Dlra.coordinator.jgroups.config=jgroups-tcp.xml \
     -Dlra.coordinator.jgroups.bind_addr=127.0.0.1 \
     -Dlra.coordinator.infinispan.persistent.location=/tmp/lra-ispn-node2 \
     -Djava.net.preferIPv4Stack=true \
     -jar target/quarkus-app/quarkus-run.jar
```

### Kubernetes StatefulSet Example

```yaml
apiVersion: apps/v1
kind: StatefulSet
metadata:
  name: lra-coordinator
spec:
  serviceName: lra-coordinator-headless
  replicas: 3
  selector:
    matchLabels:
      app: lra-coordinator
  template:
    metadata:
      labels:
        app: lra-coordinator
    spec:
      containers:
      - name: lra-coordinator
        image: my-registry/lra-coordinator:latest
        ports:
        - containerPort: 8080
          name: http
        - containerPort: 7800
          name: jgroups
        env:
        - name: KUBERNETES_NAMESPACE
          valueFrom:
            fieldRef:
              fieldPath: metadata.namespace
        - name: LRA_CLUSTER_NAME
          value: "lra-cluster"
        - name: JAVA_OPTS
          value: >-
            -Dlra.coordinator.ha.enabled=true
            -Dlra.coordinator.jgroups.config=jgroups-kubernetes.xml
            -Dlra.coordinator.infinispan.cache.mode=REPL_SYNC
        volumeMounts:
        - name: lra-data
          mountPath: /var/lib/lra
  volumeClaimTemplates:
  - metadata:
      name: lra-data
    spec:
      accessModes: ["ReadWriteOnce"]
      resources:
        requests:
          storage: 1Gi
---
apiVersion: v1
kind: Service
metadata:
  name: lra-coordinator-headless
spec:
  clusterIP: None
  selector:
    app: lra-coordinator
  ports:
  - port: 7800
    name: jgroups
---
apiVersion: v1
kind: Service
metadata:
  name: lra-coordinator
spec:
  selector:
    app: lra-coordinator
  ports:
  - port: 8080
    name: http
```

## Cache Mode Selection

| Mode | Behavior | When to Use |
|---|---|---|
| `REPL_SYNC` | Synchronous replication to all nodes | **Default.** Strong consistency. Best for clusters up to 5 nodes. |
| `REPL_ASYNC` | Asynchronous replication | Higher throughput when eventual consistency is acceptable. |
| `DIST_SYNC` | Distributed (sharded) with synchronous writes | Clusters larger than 5 nodes to reduce network overhead. |
| `DIST_ASYNC` | Distributed with asynchronous writes | Large clusters, eventual consistency acceptable. |
| `LOCAL` | No replication | Single-node mode (no HA). |

## Partition Handling

The coordinator uses `DENY_READ_WRITES` partition handling:

- **Majority partition** (>50% of nodes): Continues normal operation.
- **Minority partition**: All read/write operations are denied (HTTP 503). This prevents split-brain inconsistencies.
- **On merge**: The majority partition's data wins (`PREFERRED_ALWAYS` merge policy).

## Recovery Behavior

- Only the **cluster coordinator** (first node in the JGroups view) runs recovery scans.
- If the coordinator node goes down, a new coordinator is elected automatically.
- Recovery scans both Infinispan caches and the ObjectStore.
- Recovery interval is configurable via `com.arjuna.ats.arjuna.recovery.periodicRecoveryPeriod`.

## Verification

After starting multiple nodes, verify clustering:

1. Check logs for `Infinispan cluster members: [node1, node2, ...]`
2. Create an LRA on node 1: `curl -X POST http://localhost:8080/lra-coordinator/lra-coordinator/start`
3. List LRAs on node 2: `curl http://localhost:8180/lra-coordinator/lra-coordinator`
4. The LRA created on node 1 should be visible on node 2.

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| Nodes don't discover each other | JGroups misconfiguration or firewall | Check `initial_hosts` in TCP config; ensure port 7800 is open between nodes |
| `DEGRADED_MODE` in logs | Node is in minority partition | Check network connectivity between nodes |
| `HA mode enabled but Infinispan caches not available` | Missing Infinispan dependencies | Add `infinispan-core` and `infinispan-clustered-lock` to classpath |
| LRA not visible on other node | Cache mode is LOCAL | Set `lra.coordinator.infinispan.cache.mode=REPL_SYNC` |
| File lock errors on Infinispan state | Multiple nodes sharing same persistent location | Each node must have a unique `lra.coordinator.infinispan.persistent.location` |
