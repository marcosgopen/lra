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

@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3)
@Measurement(iterations = 5)
@Fork(1)
public class RecoveryScanBenchmark {

    private static final String TYPE = "/StateManager/BasicAction/LongRunningAction";

    @Param({ "100", "1000", "10000" })
    private int entryCount;

    private InfinispanStoreFixture fixture;
    private InfinispanObjectStore store;

    @Setup(Level.Trial)
    public void setup() throws Exception {
        fixture = new InfinispanStoreFixture();
        fixture.start();
        store = fixture.getStore();

        for (int i = 0; i < entryCount; i++) {
            Uid uid = new Uid();
            OutputObjectState os = new OutputObjectState(uid, TYPE);
            os.packString("http://localhost:8080/lra-coordinator/" + uid);
            os.packString("client-" + i);
            os.packBoolean(true);
            os.packLong(System.currentTimeMillis());
            os.packString("Active");
            store.write_committed(uid, TYPE, os);
        }
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        fixture.stop();
    }

    @Benchmark
    public void fullRecoveryScan(Blackhole bh) throws Exception {
        InputObjectState found = new InputObjectState();
        bh.consume(store.allObjUids(TYPE, found));
    }

    @Benchmark
    public void allTypesEnumeration(Blackhole bh) throws Exception {
        InputObjectState foundTypes = new InputObjectState();
        bh.consume(store.allTypes(foundTypes));
    }
}
