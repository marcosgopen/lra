/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.narayana.lra.benchmark;

import com.arjuna.ats.arjuna.ObjectType;
import com.arjuna.ats.arjuna.state.InputObjectState;
import com.arjuna.ats.arjuna.state.OutputObjectState;
import io.narayana.lra.coordinator.domain.model.LongRunningAction;
import io.narayana.lra.coordinator.domain.service.LRAService;
import io.narayana.lra.coordinator.internal.Implementations;
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
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

@BenchmarkMode({ Mode.Throughput, Mode.AverageTime })
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 2)
@Fork(2)
public class SerializationBenchmark {

    private static final String BASE_URL = "http://localhost:8080/lra-coordinator";
    private static final String RECOVERY_URL_BASE = BASE_URL + "/lra-coordinator/recovery";

    @Param({ "1", "5", "10", "50" })
    private int participantCount;

    private LRAService lraService;
    private LongRunningAction lra;
    private byte[] serializedState;

    @Setup(Level.Trial)
    public void setup() throws Exception {
        Implementations.install();

        lraService = new LRAService();

        lra = new LongRunningAction(lraService, BASE_URL, null, "bench-client");
        lra.begin(0L);

        for (int i = 0; i < participantCount; i++) {
            String participantUrl = "<http://example.com/p" + i + "/compensate>;rel=\"compensate\","
                    + "<http://example.com/p" + i + "/complete>;rel=\"complete\"";
            lra.enlistParticipant(lra.getId(), participantUrl, RECOVERY_URL_BASE, 0L, "data-" + i, null);
        }

        OutputObjectState saveBuffer = new OutputObjectState();
        lra.save_state(saveBuffer, ObjectType.ANDPERSISTENT);
        serializedState = saveBuffer.buffer();
    }

    @Benchmark
    public void saveState(Blackhole bh) {
        OutputObjectState os = new OutputObjectState();
        bh.consume(lra.save_state(os, ObjectType.ANDPERSISTENT));
    }

    @Benchmark
    public void restoreState(Blackhole bh) {
        LongRunningAction fresh = new LongRunningAction(lraService, lra.get_uid());
        InputObjectState is = new InputObjectState(lra.get_uid(), lra.type(), serializedState);
        bh.consume(fresh.restore_state(is, ObjectType.ANDPERSISTENT));
    }
}
