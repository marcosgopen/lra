# LRA HA Clustering Tests

Integration tests for the LRA coordinator High Availability support using Infinispan distributed caches.

## Test suites

### WildFly multi-node tests (requires `JBOSS_HOME`)

These tests deploy the LRA coordinator WAR to a managed WildFly HA cluster
(3 nodes using `standalone-ha.xml`) via Arquillian. Each WildFly node gets a
shared Infinispan cache container configured by `configure-lra-caches.cli`.

```bash
# Run multi-node cluster tests
mvn verify -Parq -Pha-cluster-tests

# Run failure/failover scenario tests
mvn verify -Parq -Pha-failure-tests

# Run data consistency tests
mvn verify -Parq -Pha-consistency-tests

# Run performance tests
mvn verify -Parq -Pha-performance-tests

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

This single command:
1. Compiles the Quarkus coordinator app (`src/main/java`) with the
   `quarkus-maven-plugin` into an uber-jar
2. Builds a Docker image from `eclipse-temurin:21-jre` + the uber-jar
3. Starts two coordinator containers on an isolated Docker network
4. Runs `QuarkusMultiNodeIT` which interacts with the coordinators via REST

**What gets tested:**
- Cluster formation -- both nodes respond to `GET /lra-coordinator`
- Replication -- LRA created on node 1 is visible from node 2 and vice versa
- Cross-node lifecycle -- start an LRA on node 1, close it from node 2
- Multiple concurrent LRAs across both nodes
- Failover -- LRA survives a node crash
- New LRAs after node failure
- Node rejoin -- restarted node receives replicated state

**Architecture:**

```
 Test JVM (JUnit + NarayanaLRAClient)
   |            |
   v            v
 +--------+  +--------+
 | node1  |  | node2  |     Docker containers
 | :8080  |  | :8080  |     (mapped to random host ports)
 +--------+  +--------+
      \          /
       \        /
    Docker network
    (TCPPING on port 7800)
```

Each container receives its configuration via `JAVA_OPTS`:
- `lra.coordinator.node.id` -- unique node identifier
- `lra.coordinator.ha.enabled=true` -- enables Infinispan HA
- `lra.coordinator.cluster.name` -- shared cluster name
- `lra.coordinator.jgroups.config` -- path to TCPPING stack XML

The JGroups TCPPING stack is used instead of UDP multicast (which does not
work in Docker). Both container aliases are listed in `initial_hosts` so
they can discover each other.

## Module layout

```
test/ha/
  src/main/java/.../QuarkusCoordinatorApp.java   CDI startup (replaces servlet AppContextListener)
  src/main/resources/application.properties      Quarkus defaults
  src/test/java/.../arquillian/ha/               WildFly-based HA tests
  src/test/java/.../quarkus/ha/                  Quarkus Testcontainers tests
  src/test/resources/
    arquillian-wildfly.xml                       Arquillian container definitions
    configure-lra-caches.cli                     WildFly CLI for Infinispan caches
```
