/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.narayana.lra.benchmark;

import io.narayana.lra.coordinator.domain.model.LongRunningAction;
import io.narayana.lra.coordinator.domain.service.LRAService;
import io.narayana.lra.coordinator.internal.Implementations;
import java.net.URI;
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
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 2)
@Fork(2)
public class LRAServiceBenchmark {

    private static final String BASE_URL = "http://localhost:8080/lra-coordinator";

    @Param({ "10", "100" })
    private int preloadCount;

    private LRAService lraService;
    private URI[] existingLraIds;

    @Setup(Level.Trial)
    public void setup() throws Exception {
        Implementations.install();

        lraService = new LRAService();

        existingLraIds = new URI[preloadCount];
        for (int i = 0; i < preloadCount; i++) {
            LongRunningAction lra = lraService.startLRA(BASE_URL, null, "preload-" + i, 0L);
            existingLraIds[i] = lra.getId();
        }
    }

    @Benchmark
    public void startLRA(Blackhole bh) {
        bh.consume(lraService.startLRA(BASE_URL, null, "bench", 0L));
    }

    @Benchmark
    public void getTransaction(Blackhole bh) {
        URI uri = existingLraIds[ThreadLocalRandom.current().nextInt(existingLraIds.length)];
        bh.consume(lraService.getTransaction(uri));
    }
}
