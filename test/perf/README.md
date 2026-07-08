# LRA Coordinator Performance Benchmarks

JMH micro-benchmarks for measuring the performance of LRA Coordinator internal
components. The framework covers five layers of the coordinator stack, from raw
ObjectStore I/O through service-level operations.

## Building

The module is activated on-demand via the `-Pperf` Maven profile:

```bash
mvn clean package -Pperf -pl test/perf -am -DskipTests
```

This produces `test/perf/target/lra-benchmarks.jar` (a shaded uber-jar with
JMH's `Main` class).

## Running benchmarks

### All benchmarks (full suite)

```bash
java -jar test/perf/target/lra-benchmarks.jar
```

A full run with default parameters takes approximately 30-40 minutes.

### Specific benchmark class

```bash
java -jar test/perf/target/lra-benchmarks.jar "ObjectStoreBenchmark"
java -jar test/perf/target/lra-benchmarks.jar "SerializationBenchmark"
java -jar test/perf/target/lra-benchmarks.jar "LockingBenchmark"
java -jar test/perf/target/lra-benchmarks.jar "LRAServiceBenchmark"
java -jar test/perf/target/lra-benchmarks.jar "RecoveryScanBenchmark"
```

### Single benchmark method

```bash
java -jar test/perf/target/lra-benchmarks.jar "ObjectStoreBenchmark.readCommitted"
```

### Quick sanity check (fast run)

```bash
java -jar test/perf/target/lra-benchmarks.jar -wi 1 -i 1 -f 1 -p storeSize=100
```

### Custom parameters

```bash
java -jar test/perf/target/lra-benchmarks.jar "ObjectStoreBenchmark" \
  -p storeSize=1000 \
  -wi 3 -i 5 -f 2
```

### JSON output for trend analysis

```bash
java -jar test/perf/target/lra-benchmarks.jar \
  -rf json -rff target/jmh-results.json
```

### List available benchmarks

```bash
java -jar test/perf/target/lra-benchmarks.jar -l
```

## Benchmark classes

### ObjectStoreBenchmark

Raw `InfinispanObjectStore` CRUD operations on an embedded LOCAL Infinispan cache.

| Method            | What it measures                                  |
|-------------------|---------------------------------------------------|
| `writeCommitted`  | `cache.put()` — single entry write                |
| `readCommitted`   | `cache.get()` — random read from pre-loaded store |
| `removeAndReinsert` | `cache.remove()` + `cache.put()` cycle          |
| `allObjUids`      | Full key-set snapshot + type prefix filter         |

**Parameters**: `storeSize` = {100, 1000, 10000}

### SerializationBenchmark

LRA state serialization/deserialization via Narayana's `OutputObjectState`/`InputObjectState`.

| Method         | What it measures                              |
|----------------|-----------------------------------------------|
| `saveState`    | `LongRunningAction.save_state()` — serialize  |
| `restoreState` | `LongRunningAction.restore_state()` — deserialize |

**Parameters**: `participantCount` = {1, 5, 10, 50}

### LockingBenchmark

JVM-local and distributed lock performance under varying contention levels.

| Method                      | What it measures                              |
|-----------------------------|-----------------------------------------------|
| `jvmLocalLock_1thread`      | `LRAService.lockTransaction()` — single thread |
| `jvmLocalLock_4threads`     | Same with 4 concurrent threads                |
| `jvmLocalLock_8threads`     | Same with 8 concurrent threads                |
| `jvmLocalLock_16threads`    | Same with 16 concurrent threads               |
| `distributedLockCycle`      | `InfinispanObjectStore.tryLock()/releaseLock()` |

**Parameters**: `distinctLraCount` = {1, 10, 100}

The `distinctLraCount=1` case creates maximum contention (all threads compete for
the same LRA lock). Higher values spread contention across multiple LRA IDs, but
the `synchronized` keyword on `lockTransaction()` still serializes all operations
through a single monitor.

### LRAServiceBenchmark

Service-level LRA lifecycle operations using the default filesystem ObjectStore.

| Method           | What it measures                            |
|------------------|---------------------------------------------|
| `startLRA`       | `LRAService.startLRA()` — synchronized + ObjectStore write |
| `getTransaction` | `LRAService.getTransaction()` — in-memory map lookup |

**Parameters**: `preloadCount` = {10, 100}

### RecoveryScanBenchmark

Recovery scan key enumeration at scale. Uses `SingleShotTime` mode since recovery
is a latency-sensitive, infrequent operation.

| Method                | What it measures                            |
|-----------------------|---------------------------------------------|
| `fullRecoveryScan`    | `store.allObjUids()` — full key-set snapshot |
| `allTypesEnumeration` | `store.allTypes()` — type prefix enumeration |

**Parameters**: `entryCount` = {100, 1000, 10000}

## Expected output

A typical sanity run (`-wi 1 -i 1 -f 1`) produces output like:

```
Benchmark                              (storeSize)   Mode  Cnt        Score   Units
ObjectStoreBenchmark.readCommitted             100  thrpt       7418747.548  ops/s
ObjectStoreBenchmark.writeCommitted            100  thrpt        580322.102  ops/s

Benchmark                         (participantCount)   Mode  Cnt   Score    Units
SerializationBenchmark.saveState                   5  thrpt        0.100   ops/us
SerializationBenchmark.restoreState                5  thrpt        0.041   ops/us

Benchmark                              (distinctLraCount)   Mode  Cnt  Score    Units
LockingBenchmark.distributedLockCycle                  10  thrpt       1.591   ops/us
LockingBenchmark.jvmLocalLock_1thread                  10  thrpt      11.234   ops/us
LockingBenchmark.jvmLocalLock_16threads                10  thrpt       3.456   ops/us

Benchmark                           (preloadCount)   Mode  Cnt         Score   Units
LRAServiceBenchmark.getTransaction              10  thrpt       36805436.987  ops/s
LRAServiceBenchmark.startLRA                    10  thrpt           1654.493  ops/s

Benchmark                                  (entryCount)  Mode  Cnt  Score   Units
RecoveryScanBenchmark.fullRecoveryScan              100    ss       0.716  ms/op
RecoveryScanBenchmark.allTypesEnumeration            100    ss       0.599  ms/op
```

Exact numbers depend on hardware. The relative ordering and order-of-magnitude
should be consistent.

## JMH tuning options

| Flag | Description | Default |
|------|-------------|---------|
| `-wi N` | Warmup iterations | 5 (3 for RecoveryScan) |
| `-i N` | Measurement iterations | 10 (5 for RecoveryScan) |
| `-f N` | Forks (separate JVM processes) | 2 (1 for RecoveryScan) |
| `-t N` | Threads (overrides `@Threads`) | — |
| `-p key=val` | Override `@Param` values | — |
| `-rf json` | Result format (json, csv, text) | text |
| `-rff path` | Result file path | stdout |
| `-prof gc` | GC profiler | — |
| `-prof stack` | Stack profiler | — |

## TODO / Future work

- **Clustered Infinispan benchmarks**: Add `REPL_SYNC` cache mode with embedded
  JGroups transport to measure replication overhead. The `ObjectStoreBenchmark`
  is designed to accept a `cacheMode` parameter for this.

- **HTTP-level load tests**: Add a custom Java harness using JAX-RS client to
  benchmark the full REST API stack (start/close/cancel/join) against a running
  coordinator on WildFly or embedded Undertow.

- **Participant callback latency**: Measure the impact of slow/failing participant
  callbacks on coordinator throughput during close/cancel operations.

- **Recovery scan with REPL_SYNC**: Benchmark `allObjUids()` with replicated cache
  to measure the cost of `keySet()` snapshot across cluster nodes.

- **NFR validation**: Add specific benchmarks targeting the HA spec NFRs:
  - NFR-01: Cross-node LRA lookup p99 latency <= 200ms (3-node cluster)
  - NFR-02: Recovery leader election <= 10s
  - NFR-04: Zero startup overhead when `lra.coordinator.ha.enabled=false`

- **CI integration**: Add a lightweight benchmark profile to CI that runs a smoke
  subset (1 warmup, 1 measurement, 1 fork) to detect gross regressions without
  adding significant build time.

- **JMH Visualizer integration**: Set up automated JSON result collection for
  trend analysis across builds.

- **Filesystem vs Infinispan comparison**: The `LRAServiceBenchmark` currently
  uses only the filesystem ObjectStore. Add an Infinispan variant to compare
  `startLRA` and `getTransaction` performance between store backends.

- **Memory profiling**: Use JMH's `-prof gc` to measure allocation pressure,
  especially for `allObjUids()` (full key-set copy) and serialization (byte
  array allocation per LRA state).
