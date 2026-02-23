# Configuring Narayana LRA Coordinator in HA Mode with WildFly

This guide covers how to configure the Narayana LRA Coordinator for High Availability (HA) when running on WildFly, using standalone Infinispan caches and JGroups for cluster communication.

## Architecture Overview

The LRA Coordinator HA mode uses a dual-layer persistence model:

- **Narayana ObjectStore** (local, file-based): Source of truth for durability. Survives crashes and restarts.
- **Infinispan Distributed Cache** (replicated, in-memory): Source of truth for availability. Enables any coordinator node to access any LRA.

The LRA coordinator embeds its own Infinispan `DefaultCacheManager` and JGroups transport stack, **separate from WildFly's built-in Infinispan subsystem and JGroups subsystem**. This means:

- LRA clustering operates independently of WildFly's own clustering.
- You configure LRA's JGroups and Infinispan via system properties, not via WildFly subsystem XML.
- WildFly's `standalone-ha.xml` provides the HA-capable server profile (with JGroups/Infinispan subsystems for WildFly's own use), but the LRA coordinator manages its own embedded cache manager.

### Cache Layout

| Cache | Contents | Max Entries |
|---|---|---|
| `lra-active` | LRAs in Active state | 10,000 |
| `lra-recovering` | LRAs in Closing/Cancelling/Closed/Cancelled state | 10,000 |
| `lra-failed` | LRAs in FailedToClose/FailedToCancel state | 1,000 |

All caches default to `REPL_SYNC` (synchronous replication to all nodes).

## Prerequisites

- WildFly 27+ with `standalone-ha.xml` configuration profile
- LRA Coordinator deployment (WAR) with embedded Infinispan and JGroups libraries
- Network connectivity between all coordinator nodes on the JGroups port (default: 7800)

## Step 1: Use the standalone-ha.xml Server Profile

Start WildFly with the HA profile:

```bash
./standalone.sh -c standalone-ha.xml
```

This enables JGroups and Infinispan subsystems in WildFly. While the LRA coordinator uses its own embedded Infinispan/JGroups, running with `standalone-ha.xml` is recommended so WildFly's own clustering is available for other features (web session replication, EJB clustering, etc.).

## Step 2: Configure System Properties

All LRA HA configuration is done via Java system properties. You can set these in several ways.

### Option A: In standalone-ha.xml (Recommended for Production)

Add system properties to the `<system-properties>` block in `standalone-ha.xml`:

```xml
<server xmlns="urn:jboss:domain:20.0">
    <system-properties>
        <!-- LRA HA Core Settings -->
        <property name="lra.coordinator.ha.enabled" value="true"/>
        <property name="lra.coordinator.cluster.name" value="lra-cluster"/>
        <property name="lra.coordinator.node.id" value="node1"/>

        <!-- JGroups Transport for LRA's embedded Infinispan -->
        <property name="lra.coordinator.jgroups.config" value="jgroups-lra-tcp.xml"/>
        <property name="lra.coordinator.jgroups.bind_addr" value="192.168.1.10"/>

        <!-- Infinispan Configuration -->
        <property name="lra.coordinator.infinispan.cache.mode" value="REPL_SYNC"/>
        <property name="lra.coordinator.infinispan.persistent.location"
                  value="${jboss.server.data.dir}/lra-infinispan"/>

        <!-- Narayana ObjectStore -->
        <property name="com.arjuna.ats.arjuna.objectstore.objectStoreDir"
                  value="${jboss.server.data.dir}/tx-object-store"/>
        <property name="com.arjuna.ats.arjuna.common.CoordinatorEnvironmentBean.nodeIdentifier"
                  value="1"/>

        <!-- Recovery -->
        <property name="com.arjuna.ats.arjuna.recovery.periodicRecoveryPeriod"
                  value="120000"/>
        <property name="com.arjuna.ats.arjuna.recovery.recoveryBackoffPeriod"
                  value="10000"/>
    </system-properties>
    <!-- ... rest of config ... -->
</server>
```

### Option B: Via CLI at Startup

```bash
./standalone.sh -c standalone-ha.xml \
    -Djboss.node.name=node1 \
    -Djboss.socket.binding.port-offset=0 \
    -Dlra.coordinator.ha.enabled=true \
    -Dlra.coordinator.node.id=node1 \
    -Dlra.coordinator.cluster.name=lra-cluster \
    -Dlra.coordinator.jgroups.config=jgroups-lra-tcp.xml \
    -Dlra.coordinator.jgroups.bind_addr=192.168.1.10 \
    -Dlra.coordinator.infinispan.persistent.location=/opt/wildfly/standalone/data/lra-infinispan \
    -Djava.net.preferIPv4Stack=true
```

### Option C: Via WildFly Management CLI (jboss-cli)

```
/system-property=lra.coordinator.ha.enabled:add(value=true)
/system-property=lra.coordinator.cluster.name:add(value=lra-cluster)
/system-property=lra.coordinator.node.id:add(value=node1)
/system-property=lra.coordinator.jgroups.config:add(value=jgroups-lra-tcp.xml)
/system-property=lra.coordinator.jgroups.bind_addr:add(value=192.168.1.10)
/system-property=lra.coordinator.infinispan.cache.mode:add(value=REPL_SYNC)
/system-property=lra.coordinator.infinispan.persistent.location:add(value=${jboss.server.data.dir}/lra-infinispan)
```

## Step 3: JGroups Configuration for LRA

The LRA coordinator's embedded Infinispan needs its own JGroups configuration file, separate from WildFly's JGroups subsystem. Place this file in the WildFly `standalone/configuration/` directory or on the deployment classpath.

### Option A: TCP with Static Discovery (TCPPING)

Create `standalone/configuration/jgroups-lra-tcp.xml`:

```xml
<config xmlns="urn:org:jgroups"
        xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xsi:schemaLocation="urn:org:jgroups http://www.jgroups.org/schema/jgroups.xsd">

    <!--
      TCP transport for the LRA coordinator's embedded Infinispan.
      Uses port 7800 by default. Make sure this does NOT conflict with
      WildFly's own JGroups port (typically 7600).
    -->
    <TCP bind_addr="${lra.coordinator.jgroups.bind_addr:127.0.0.1}"
         bind_port="7800"
         port_range="50"
         recv_buf_size="20m"
         send_buf_size="640k"
         thread_pool.enabled="true"
         thread_pool.min_threads="0"
         thread_pool.max_threads="25"
         thread_pool.keep_alive_time="5000" />

    <!--
      List ALL coordinator node addresses here.
      Format: host[port],host[port],...
    -->
    <TCPPING async_discovery="true"
             initial_hosts="192.168.1.10[7800],192.168.1.11[7800],192.168.1.12[7800]"
             port_range="5" />

    <MERGE3 min_interval="10000" max_interval="30000" />
    <FD_SOCK />
    <FD_ALL timeout="10000" interval="3000" />
    <VERIFY_SUSPECT timeout="1500" />

    <pbcast.NAKACK2 use_mcast_xmit="false"
                    discard_delivered_msgs="true"
                    log_discard_msgs="false"
                    log_not_found_msgs="false" />

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

### Option B: UDP Multicast

Create `standalone/configuration/jgroups-lra-udp.xml`:

```xml
<config xmlns="urn:org:jgroups"
        xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xsi:schemaLocation="urn:org:jgroups http://www.jgroups.org/schema/jgroups.xsd">

    <UDP mcast_addr="230.0.0.100"
         mcast_port="46655"
         bind_addr="${lra.coordinator.jgroups.bind_addr:127.0.0.1}"
         tos="8"
         ucast_recv_buf_size="20m"
         ucast_send_buf_size="640k"
         mcast_recv_buf_size="25m"
         mcast_send_buf_size="640k"
         thread_pool.enabled="true"
         thread_pool.min_threads="0"
         thread_pool.max_threads="25"
         thread_pool.keep_alive_time="5000" />

    <PING />

    <MERGE3 min_interval="10000" max_interval="30000" />
    <FD_SOCK />
    <FD_ALL timeout="10000" interval="3000" />
    <VERIFY_SUSPECT timeout="1500" />

    <pbcast.NAKACK2 use_mcast_xmit="true"
                    discard_delivered_msgs="true" />
    <UNICAST3 conn_close_timeout="5000" />
    <pbcast.STABLE stability_delay="500"
                   desired_avg_gossip="5000"
                   max_bytes="1M" />
    <pbcast.GMS print_local_addr="true"
                join_timeout="3000" />
    <UFC max_credits="2m" min_threshold="0.40" />
    <MFC max_credits="2m" min_threshold="0.40" />
    <FRAG3 frag_size="8000" />
</config>
```

Use a multicast address and port that do NOT conflict with WildFly's own JGroups UDP stack (which defaults to `230.0.0.4:45688`).

### Port Conflict Avoidance

WildFly's built-in JGroups subsystem and the LRA coordinator's embedded JGroups are **independent stacks**. Ensure they bind to different ports:

| Component | Default Port | Protocol |
|---|---|---|
| WildFly JGroups (TCP) | 7600 | TCP |
| WildFly JGroups (UDP mcast) | 45688 | UDP |
| LRA embedded JGroups (TCP) | 7800 | TCP |
| LRA embedded JGroups (UDP mcast) | 46655 | UDP (if using UDP) |

If you use `jboss.socket.binding.port-offset`, note that the LRA JGroups port is **not** affected by that offset (it is configured directly in the JGroups XML, not via WildFly socket bindings).

## Step 4: Multi-Node Deployment

### Two-Node Local Setup (Development / Testing)

```bash
# Node 1
./standalone.sh -c standalone-ha.xml \
    -Djboss.node.name=node1 \
    -Djboss.socket.binding.port-offset=0 \
    -Dlra.coordinator.ha.enabled=true \
    -Dlra.coordinator.node.id=node1 \
    -Dlra.coordinator.cluster.name=lra-cluster \
    -Dlra.coordinator.jgroups.bind_addr=127.0.0.1 \
    -Dlra.coordinator.infinispan.persistent.location=/tmp/lra-ispn-node1 \
    -Djava.net.preferIPv4Stack=true

# Node 2 (in a separate terminal)
./standalone.sh -c standalone-ha.xml \
    -Djboss.node.name=node2 \
    -Djboss.socket.binding.port-offset=100 \
    -Dlra.coordinator.ha.enabled=true \
    -Dlra.coordinator.node.id=node2 \
    -Dlra.coordinator.cluster.name=lra-cluster \
    -Dlra.coordinator.jgroups.bind_addr=127.0.0.1 \
    -Dlra.coordinator.infinispan.persistent.location=/tmp/lra-ispn-node2 \
    -Djava.net.preferIPv4Stack=true
```

Note: With `port-offset=100`, node 2's HTTP port becomes 8180 and management port becomes 10090.

### Production Multi-Host Setup

On each host, set the bind address and node ID appropriately:

**Host 1 (192.168.1.10):**
```bash
./standalone.sh -c standalone-ha.xml \
    -Djboss.node.name=node1 \
    -Djboss.bind.address=192.168.1.10 \
    -Dlra.coordinator.ha.enabled=true \
    -Dlra.coordinator.node.id=node1 \
    -Dlra.coordinator.cluster.name=lra-cluster \
    -Dlra.coordinator.jgroups.config=jgroups-lra-tcp.xml \
    -Dlra.coordinator.jgroups.bind_addr=192.168.1.10 \
    -Dlra.coordinator.infinispan.persistent.location=/opt/lra/infinispan
```

**Host 2 (192.168.1.11):**
```bash
./standalone.sh -c standalone-ha.xml \
    -Djboss.node.name=node2 \
    -Djboss.bind.address=192.168.1.11 \
    -Dlra.coordinator.ha.enabled=true \
    -Dlra.coordinator.node.id=node2 \
    -Dlra.coordinator.cluster.name=lra-cluster \
    -Dlra.coordinator.jgroups.config=jgroups-lra-tcp.xml \
    -Dlra.coordinator.jgroups.bind_addr=192.168.1.11 \
    -Dlra.coordinator.infinispan.persistent.location=/opt/lra/infinispan
```

## Step 5: Configure WildFly's standalone-ha.xml Subsystems (Optional)

While the LRA coordinator uses its own embedded Infinispan, you may want to tune WildFly's own subsystems. Here are the relevant sections in `standalone-ha.xml`:

### WildFly JGroups Subsystem (for WildFly's own clustering)

```xml
<subsystem xmlns="urn:jboss:domain:jgroups:9.0">
    <channels default="ee">
        <channel name="ee" stack="tcp"/>
    </channels>
    <stacks>
        <stack name="tcp">
            <transport type="TCP"
                       socket-binding="jgroups-tcp"/>
            <protocol type="TCPPING">
                <property name="initial_hosts">
                    192.168.1.10[7600],192.168.1.11[7600]
                </property>
            </protocol>
            <protocol type="MERGE3"/>
            <protocol type="FD_SOCK"/>
            <protocol type="FD_ALL"/>
            <protocol type="VERIFY_SUSPECT"/>
            <protocol type="pbcast.NAKACK2"/>
            <protocol type="UNICAST3"/>
            <protocol type="pbcast.STABLE"/>
            <protocol type="pbcast.GMS"/>
            <protocol type="MFC"/>
            <protocol type="FRAG3"/>
        </stack>
    </stacks>
</subsystem>
```

### WildFly Infinispan Subsystem (for WildFly's own caches)

This is WildFly's own cache container. The LRA coordinator does **not** use this; it creates its own `DefaultCacheManager`. No changes are needed here for LRA HA.

```xml
<subsystem xmlns="urn:jboss:domain:infinispan:14.0">
    <cache-container name="server" default-cache="default" modules="org.jboss.as.clustering.server">
        <transport lock-timeout="60000"/>
        <replicated-cache name="default"/>
    </cache-container>
    <cache-container name="web" default-cache="dist" modules="org.wildfly.clustering.web.infinispan">
        <transport lock-timeout="60000"/>
        <distributed-cache name="dist">
            <locking isolation="REPEATABLE_READ"/>
            <transaction mode="BATCH"/>
            <file-store/>
        </distributed-cache>
    </cache-container>
    <!-- LRA does NOT use these cache containers -->
</subsystem>
```

## System Property Reference

| Property | Default | Description |
|---|---|---|
| `lra.coordinator.ha.enabled` | `false` | Enable HA mode |
| `lra.coordinator.cluster.name` | `lra-cluster` | Cluster name (must match on all nodes) |
| `lra.coordinator.node.id` | `$HOSTNAME` | Unique node identifier |
| `lra.coordinator.jgroups.config` | _(UDP default)_ | Path to JGroups XML configuration |
| `lra.coordinator.jgroups.bind_addr` | `127.0.0.1` | JGroups bind address |
| `lra.coordinator.infinispan.cache.mode` | `REPL_SYNC` | Cache replication mode |
| `lra.coordinator.infinispan.persistent.location` | `$TMPDIR/lra-infinispan-$NODE` | Infinispan global state directory |

## Cache Mode Selection

| Mode | Behavior | When to Use |
|---|---|---|
| `REPL_SYNC` | Synchronous replication to all nodes | **Default.** Strong consistency. Best for clusters up to 5 nodes. |
| `REPL_ASYNC` | Asynchronous replication | Higher throughput when eventual consistency is acceptable. |
| `DIST_SYNC` | Distributed (sharded) with synchronous writes | Clusters larger than 5 nodes. |
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

## Verification

After starting multiple nodes, verify clustering:

1. Check server logs for:
   ```
   Infinispan initialized for cluster 'lra-cluster' with node name 'node1'
   Infinispan cluster members: [node1, node2]
   ```

2. Create an LRA on node 1:
   ```bash
   curl -X POST http://192.168.1.10:8080/lra-coordinator/lra-coordinator/start
   ```

3. List LRAs on node 2:
   ```bash
   curl http://192.168.1.11:8080/lra-coordinator/lra-coordinator
   ```

4. The LRA created on node 1 should be visible on node 2.

## Running the HA Integration Tests

The project includes Arquillian-based HA tests that spin up two WildFly instances automatically:

```bash
# Fast component tests (no WildFly needed)
cd test/ha
mvn test

# Multi-node cluster tests (starts 2 WildFly instances)
mvn verify -Pha-cluster-tests

# Failure scenario tests (node crash, failover)
mvn verify -Pha-failure-tests

# Data consistency tests (locking, replication)
mvn verify -Pha-consistency-tests

# Full test suite
mvn verify -Pha-full-suite
```

The test Arquillian configuration (`test/ha/src/test/resources/arquillian-wildfly.xml`) provides a reference for how two-node clusters are configured.

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| Nodes don't discover each other | JGroups misconfiguration or firewall | Check `initial_hosts` in TCP config; ensure port 7800 is open between nodes |
| `DEGRADED_MODE` in logs | Node is in minority partition | Check network connectivity between nodes |
| `HA mode enabled but Infinispan caches not available` | Missing Infinispan JARs in deployment | Ensure `infinispan-core` and `infinispan-clustered-lock` are in the WAR or as WildFly modules |
| LRA not visible on other node | Cache mode is LOCAL | Set `lra.coordinator.infinispan.cache.mode=REPL_SYNC` |
| Port conflict with WildFly JGroups | LRA and WildFly JGroups binding to same port | LRA uses port 7800 by default; WildFly uses 7600. Verify no overlap. |
| File lock errors on Infinispan state | Multiple nodes sharing same persistent location | Each node must have a unique `lra.coordinator.infinispan.persistent.location` |
| `Address already in use` on startup | Two JGroups stacks binding to same port | LRA JGroups port is NOT affected by `jboss.socket.binding.port-offset`. Change `bind_port` in the LRA JGroups XML if running multiple nodes on one host. |
