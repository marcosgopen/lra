# LRA HA Clustering Tests

Integration tests for the LRA coordinator High Availability support.

In HA mode, the ObjectStore backend is swapped from the default filesystem to an Infinispan-backed `InfinispanSlots` implementation (via Narayana's `BackingSlots` / `SlotStore` abstraction). All LRA state is replicated across the cluster, and JGroups coordinator election ensures only one node performs recovery.

## Test suites

### Component tests (no WildFly required)

```bash
# Run HAClusteringTest (default, fast)
mvn test
```

Tests node ID embedding in LRA URIs, HA mode detection, coordinator initialization.

### WildFly multi-node tests (requires `JBOSS_HOME`)

These tests deploy the LRA coordinator WAR to a managed WildFly HA cluster
(3 nodes using `standalone-ha.xml`) via Arquillian. The `configure-lra-caches.cli`
script configures the Infinispan cache container.

```bash
# Run multi-node cluster tests
mvn verify -Parq -Pha-cluster-tests

# Run failure/failover scenario tests
mvn verify -Parq -Pha-failure-tests

# Run data consistency tests
mvn verify -Parq -Pha-consistency-tests

# Run concurrent consistency tests
mvn verify -Parq -Pha-concurrent-tests

# Run performance tests
mvn verify -Parq -Pha-performance-tests

# Run recovery coordinator tests
mvn verify -Parq -Pha-recovery-tests

# Run everything
mvn verify -Parq -Pha-full-suite
```

### Quarkus multi-node tests (requires Docker)

These tests build a Quarkus uber-jar containing the LRA coordinator with HA
support, then spin up multiple Docker containers using
[Testcontainers](https://www.testcontainers.org/). Each container runs an
independent coordinator JVM, and the containers form a JGroups cluster using
TCPPING over a shared Docker network.

**Prerequisites:**
- Docker daemon running
- No `JBOSS_HOME` required

**How to run:**

```bash
cd test/ha
mvn -Pquarkus-multinode verify
```

**What gets tested:**
- Cluster formation -- both nodes respond to `GET /lra-coordinator`
- Replication -- LRA created on node 1 is visible from node 2 and vice versa
- Cross-node lifecycle -- start an LRA on node 1, close it from node 2
- Multiple concurrent LRAs across both nodes
- Failover -- LRA survives a node crash
- New LRAs after node failure
- Node rejoin -- restarted node receives replicated state

## Test classes

| Class | Type | Profile | Description |
|-------|------|---------|-------------|
| `HAClusteringTest` | Unit | default | Node ID embedding, HA mode detection |
| `MultiNodeClusterIT` | Integration | `ha-cluster-tests` | 3-node cluster LRA lifecycle |
| `FailureScenarioIT` | Integration | `ha-failure-tests` | Node crashes, coordinator failover, partition handling |
| `DataConsistencyIT` | Integration | `ha-consistency-tests` | State integrity under concurrent access |
| `ConcurrentConsistencyIT` | Integration | `ha-concurrent-tests` | No lost updates, concurrent close/cancel races |
| `RecoveryCoordinatorHAIT` | Integration | `ha-recovery-tests` | Recovery operations across nodes |
| `PerformanceIT` | Integration | `ha-performance-tests` | Throughput and latency benchmarks |

## Module layout

```
test/ha/
  src/test/java/.../arquillian/ha/               WildFly-based HA tests
  src/test/java/.../quarkus/ha/                  Quarkus Testcontainers tests
  src/test/resources/
    arquillian-wildfly.xml                       Arquillian container definitions
    configure-lra-caches.cli                     WildFly CLI for Infinispan cache
```
