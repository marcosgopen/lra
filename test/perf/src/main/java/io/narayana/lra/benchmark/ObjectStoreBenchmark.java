/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.narayana.lra.benchmark;

import com.arjuna.ats.arjuna.common.Uid;
import com.arjuna.ats.arjuna.state.InputObjectState;
import com.arjuna.ats.arjuna.state.OutputObjectState;
import io.narayana.lra.benchmark.support.InfinispanStoreFixture;
import io.narayana.lra.coordinator.infinispan.InfinispanObjectStore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
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
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 2)
@Fork(2)
public class ObjectStoreBenchmark {

    private static final String TYPE = "/StateManager/BasicAction/LongRunningAction";

    private InfinispanStoreFixture fixture;
    private InfinispanObjectStore store;
    private Uid[] preloadedUids;

    @Param({ "100", "1000", "10000" })
    private int storeSize;

    @Setup(Level.Trial)
    public void setup() throws Exception {
        fixture = new InfinispanStoreFixture();
        fixture.start();
        store = fixture.getStore();

        preloadedUids = new Uid[storeSize];
        for (int i = 0; i < storeSize; i++) {
            preloadedUids[i] = new Uid();
            OutputObjectState os = new OutputObjectState(preloadedUids[i], TYPE);
            os.packString("benchmark-data-" + i);
            store.write_committed(preloadedUids[i], TYPE, os);
        }
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        fixture.stop();
    }

    @Benchmark
    public void writeCommitted(Blackhole bh) throws Exception {
        Uid uid = new Uid();
        OutputObjectState os = new OutputObjectState(uid, TYPE);
        os.packString("bench");
        bh.consume(store.write_committed(uid, TYPE, os));
    }

    @Benchmark
    public void readCommitted(Blackhole bh) throws Exception {
        Uid uid = preloadedUids[ThreadLocalRandom.current().nextInt(preloadedUids.length)];
        bh.consume(store.read_committed(uid, TYPE));
    }

    @Benchmark
    public void removeAndReinsert(Blackhole bh) throws Exception {
        int idx = ThreadLocalRandom.current().nextInt(preloadedUids.length);
        Uid uid = preloadedUids[idx];
        boolean removed = store.remove_committed(uid, TYPE);
        if (removed) {
            OutputObjectState os = new OutputObjectState(uid, TYPE);
            os.packString("re-inserted");
            store.write_committed(uid, TYPE, os);
        }
        bh.consume(removed);
    }

    @Benchmark
    public void allObjUids(Blackhole bh) throws Exception {
        InputObjectState found = new InputObjectState();
        bh.consume(store.allObjUids(TYPE, found));
    }
}
