/*
 * Copyright (c) 2026 Deendayal Kumawat
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package com.wiredoctor;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Micro-benchmark for the ghost-tracking proxy hot path (v0.6.0).
 * <p>
 * Not JMH — a deliberately simple warmed-up loop that produces the
 * order-of-magnitude number documented in the README ("one volatile read per
 * call after first touch"). The assertion is a generous sanity bound, not a
 * performance gate: CI machines vary wildly, and this test must never be the
 * flaky one. Run manually for the documented figure; the printed ns/call is
 * the artifact.
 */
class WireDoctorGhostTrackingBenchmarkTest {

    interface Work {
        int compute(int x);
    }

    static class WorkImpl implements Work {
        @Override public int compute(int x) { return x * 31 + 7; }
    }

    private static final int WARMUP_ITERATIONS = 200_000;
    private static final int MEASURED_ITERATIONS = 2_000_000;

    @Test
    void hotPathOverheadIsBounded() {
        WireDoctorGhostTracker tracker = new WireDoctorGhostTracker();
        WireDoctorGhostTrackingPostProcessor processor = new WireDoctorGhostTrackingPostProcessor(
                tracker, new DefaultListableBeanFactory(), Set.of());

        Work raw = new WorkImpl();
        Work proxied = (Work) processor.postProcessAfterInitialization(new WorkImpl(), "bench");

        // Warm up both paths (JIT).
        int sink = 0;
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            sink += raw.compute(i);
            sink += proxied.compute(i);
        }

        long rawNanos = measure(raw);
        long proxiedNanos = measure(proxied);

        double rawPerCall = (double) rawNanos / MEASURED_ITERATIONS;
        double proxiedPerCall = (double) proxiedNanos / MEASURED_ITERATIONS;
        // The README artifact: post-first-touch per-call overhead.
        System.out.printf(
                "[WireDoctor benchmark] raw=%.1f ns/call, proxied=%.1f ns/call, overhead=%.1f ns/call%n",
                rawPerCall, proxiedPerCall, proxiedPerCall - rawPerCall);

        // Generous sanity bound (~microseconds, not milliseconds): catches an
        // accidental hot-path regression (logging, allocation, reflection per
        // call) without being CI-flaky. A JDK proxy dispatch costs tens to a
        // few hundred ns; anything above 5µs/call means something broke.
        assertThat(proxiedPerCall - rawPerCall).isLessThan(5_000.0);
        assertThat(sink).isNotZero(); // keep the JIT from eliding the loops
    }

    private static long measure(Work work) {
        int sink = 0;
        long start = System.nanoTime();
        for (int i = 0; i < MEASURED_ITERATIONS; i++) {
            sink += work.compute(i);
        }
        long elapsed = System.nanoTime() - start;
        if (sink == 42) System.out.print(""); // consume the sink
        return elapsed;
    }
}
