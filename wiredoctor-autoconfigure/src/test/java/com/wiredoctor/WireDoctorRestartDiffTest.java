/*
 * Copyright (c) 2026 Deendayal Kumawat
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package com.wiredoctor;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WD-701 (v1.2.0) — DevTools restart feedback.
 * <p>
 * Two pieces of new logic are guarded here: the devtools gate (no restart
 * work at all when {@code RestartClassLoader} is absent — the acceptance
 * "absent → no-op") and the one-line summary format. The reused diff engine
 * ({@link WireDoctorBaselineDiff}) has its own tests, so this covers only the
 * WD-701 additions plus the fromJson → diff → summary integration.
 */
class WireDoctorRestartDiffTest {

    private final WireDoctorAnalyzer analyzer = new WireDoctorAnalyzer(new WireDoctorProperties());

    @Test
    void devtoolsAbsent_isNoOp_evenWhenPreviousReportExists(@TempDir File outputDir) throws Exception {
        // A previous report is present on disk...
        Files.writeString(new File(outputDir, "wiredoctor-report.json").toPath(),
                "{\"dependencies\":{\"graph\":{\"a\":[]},\"cycles\":[]}}");

        // ...but DevTools' RestartClassLoader is not on the test classpath, so
        // the feature must not even read the file: silent no-op, zero overhead.
        assertThat(analyzer.readPreviousReportForRestart(outputDir, getClass().getClassLoader()))
                .isNull();
    }

    @Test
    void summary_flagsNewSlowBean_andStatesCyclesUnchanged() {
        var noCycleChange = new WireDoctorBaselineDiff.DiffResult(
                Set.of(), Set.of(),        // beans +/-
                Set.of(), Set.of(),        // edges +/-
                List.of(), List.of(),      // new / resolved cycles
                false, List.of(), Set.of(), Set.of(), // conditions (unused)
                null,                      // no startup regression
                List.of());                // newSlowBeans passed separately below

        var newSlow = List.of(new WireDoctorBaselineDiff.NewSlowBean("myService", 340L, 100L));

        assertThat(WireDoctorAnalyzer.buildRestartSummary(noCycleChange, newSlow))
                .isEqualTo("+1 slow bean (myService 340ms), cycles unchanged");
    }

    @Test
    void summary_fromTwoRealReports_flagsNewCycle() throws Exception {
        var mapper = new ObjectMapper();
        // Previous restart: a → b, no cycle.
        var previous = mapper.readTree(
                "{\"dependencies\":{\"graph\":{\"a\":[\"b\"],\"b\":[]},\"cycles\":[]}}");
        // This restart: the dev's change wired b → a, closing a cycle.
        var current = WireDoctorBaselineDiff.Snapshot.fromAnalysis(
                java.util.Map.of("a", new String[]{"b"}, "b", new String[]{"a"}),
                List.of(List.of("a", "b")));

        var diff = WireDoctorBaselineDiff.diff(
                WireDoctorBaselineDiff.Snapshot.fromJson(previous), current);

        assertThat(WireDoctorAnalyzer.buildRestartSummary(diff, List.of()))
                .contains("+1 cycle");
    }
}
