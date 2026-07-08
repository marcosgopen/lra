/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.narayana.lra.benchmark;

import com.arjuna.ats.arjuna.common.Uid;
import io.narayana.lra.benchmark.support.InfinispanStoreFixture;
import io.narayana.lra.coordinator.domain.service.LRAService;
import io.narayana.lra.coordinator.infinispan.InfinispanObjectStore;
import java.net.URI;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

@BenchmarkMode({ Mode.Throughput, Mode.AverageTime })
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 2)
@Fork(2)
public class LockingBenchmark {

    @Param({ "1", "10", "100" })
    private int distinctLraCount;

    private LRAService lraService;
    private URI[] lraUris;

    private InfinispanStoreFixture fixture;
    private InfinispanObjectStore store;
    private Uid[] lockUids;

    @Setup(Level.Trial)
    public void setup() throws Exception {
        lraService = new LRAService();
        lraUris = new URI[distinctLraCount];
        for (int i = 0; i < distinctLraCount; i++) {
            lraUris[i] = new URI("http://localhost:8080/lra-coordinator/" + new Uid());
        }

        fixture = new InfinispanStoreFixture();
        fixture.start();
        store = fixture.getStore();
        lockUids = new Uid[distinctLraCount];
        for (int i = 0; i < distinctLraCount; i++) {
            lockUids[i] = new Uid();
        }
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        fixture.stop();
    }

    @Benchmark
    @Threads(1)
    public void jvmLocalLock_1thread(Blackhole bh) {
        URI uri = lraUris[ThreadLocalRandom.current().nextInt(lraUris.length)];
        ReentrantLock lock = lraService.lockTransaction(uri);
        lock.unlock();
        bh.consume(lock);
    }

    @Benchmark
    @Threads(4)
    public void jvmLocalLock_4threads(Blackhole bh) {
        URI uri = lraUris[ThreadLocalRandom.current().nextInt(lraUris.length)];
        ReentrantLock lock = lraService.lockTransaction(uri);
        lock.unlock();
        bh.consume(lock);
    }

    @Benchmark
    @Threads(8)
    public void jvmLocalLock_8threads(Blackhole bh) {
        URI uri = lraUris[ThreadLocalRandom.current().nextInt(lraUris.length)];
        ReentrantLock lock = lraService.lockTransaction(uri);
        lock.unlock();
        bh.consume(lock);
    }

    @Benchmark
    @Threads(16)
    public void jvmLocalLock_16threads(Blackhole bh) {
        URI uri = lraUris[ThreadLocalRandom.current().nextInt(lraUris.length)];
        ReentrantLock lock = lraService.lockTransaction(uri);
        lock.unlock();
        bh.consume(lock);
    }

    @Benchmark
    public void distributedLockCycle(Blackhole bh) throws Exception {
        Uid uid = lockUids[ThreadLocalRandom.current().nextInt(lockUids.length)];
        boolean acquired = store.tryLock(uid);
        if (acquired) {
            store.releaseLock(uid);
        }
        bh.consume(acquired);
    }
}
