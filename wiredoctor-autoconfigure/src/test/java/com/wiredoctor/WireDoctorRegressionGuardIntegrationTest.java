/*
 * Copyright (c) 2026 Deendayal Kumawat
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package com.wiredoctor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end tests for the Architectural Regression Guard: baseline write
 * mode, diff generation, graceful degradation, and the opt-in new-cycle gate
 * failing the application.
 */
class WireDoctorRegressionGuardIntegrationTest {

    /**
     * App WITHOUT a cycle — used to write baselines. Carries a
     * {@code @ConditionalOnProperty} bean whose condition outcome we flip
     * between the baseline and diff runs by toggling {@code wd.feature.on}.
     * This is Boot-version-independent (unlike excluding a specific Boot
     * autoconfig, whose package moves across major versions) — the compat
     * matrix builds this module against Boot 2.7 → 4.x.
     */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class CleanApp {
        @Bean
        String standalone() {
            return "no deps";
        }

        @Bean
        @ConditionalOnProperty(name = "wd.feature.on", havingValue = "true", matchIfMissing = true)
        String conditionalFeature() {
            return "feature";
        }
    }

    /** App WITH a setter cycle (cycleA ↔ cycleB) — simulates the offending PR. */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class CyclicApp {

        @Component("cycleA")
        static class CycleA {
            @Autowired
            void setPartner(CycleB b) { }
        }

        @Component("cycleB")
        static class CycleB {
            @Autowired
            void setPartner(CycleA a) { }
        }

    }

    /**
     * Single app for the combined-gate test: one class used for BOTH the
     * baseline and the diff run, so condition source keys line up (they carry
     * the declaring class — a CleanApp→CyclicApp swap would read as
     * added/removed, not changed). Both the cycle and a condition flip are
     * toggled by properties:
     * <ul>
     *   <li>{@code wd.cycle.on=true} → {@code combB} exists → combA↔combB cycle
     *       (a NEW cycle vs a baseline written without it);</li>
     *   <li>{@code wd.feature.on=false} → {@code conditionalFeature} flips
     *       matched → notMatched (a condition change).</li>
     * </ul>
     */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class CombinedApp {
        @Component("combA")
        static class CombA {
            @Autowired(required = false)
            void setPartner(CombB b) { }
        }

        @Component("combB")
        @ConditionalOnProperty(name = "wd.cycle.on", havingValue = "true")
        static class CombB {
            @Autowired
            void setPartner(CombA a) { }
        }

        @Bean
        @ConditionalOnProperty(name = "wd.feature.on", havingValue = "true", matchIfMissing = true)
        String conditionalFeature() {
            return "feature";
        }
    }

    private ConfigurableApplicationContext boot(Class<?> app, String... properties) {
        return new SpringApplicationBuilder(app)
                .web(WebApplicationType.NONE)
                .properties(properties)
                .properties("spring.main.allow-circular-references=true")
                .run();
    }

    @Test
    void baselineWriteModeCreatesBaselineFile(@TempDir Path tempDir) throws Exception {
        Path baseline = tempDir.resolve("wiredoctor-baseline.json");
        try (var context = boot(CleanApp.class,
                "wiredoctor.output-path=" + tempDir,
                "wiredoctor.baseline=" + baseline,
                "wiredoctor.baseline-write=true")) {
            assertThat(context.isActive()).isTrue();
        }
        assertThat(baseline).exists();
        JsonNode parsed = new ObjectMapper().readTree(baseline.toFile());
        assertThat(parsed.path("dependencies").path("graph").isObject()).isTrue();
    }

    @Test
    void unchangedGraphDiffsCleanAndNeverGates(@TempDir Path tempDir) throws Exception {
        Path baseline = tempDir.resolve("wiredoctor-baseline.json");
        try (var ignored = boot(CleanApp.class,
                "wiredoctor.output-path=" + tempDir,
                "wiredoctor.baseline=" + baseline,
                "wiredoctor.baseline-write=true")) { }

        // Same app again, now diffing with the gate armed — must NOT trip.
        try (var context = boot(CleanApp.class,
                "wiredoctor.output-path=" + tempDir,
                "wiredoctor.baseline=" + baseline,
                "wiredoctor.fail-on=new-cycle")) {
            assertThat(context.isActive()).isTrue();
        }
        JsonNode diff = new ObjectMapper()
                .readTree(tempDir.resolve("wiredoctor-diff.json").toFile());
        assertThat(diff.path("newCyclesCount").asInt()).isZero();
    }

    @Test
    void newCycleWithGateArmedFailsTheApplication(@TempDir Path tempDir) throws Exception {
        // 1. Baseline from the clean app.
        Path baseline = tempDir.resolve("wiredoctor-baseline.json");
        try (var ignored = boot(CleanApp.class,
                "wiredoctor.output-path=" + tempDir,
                "wiredoctor.baseline=" + baseline,
                "wiredoctor.baseline-write=true")) { }

        // 2. "PR" introduces a bean cycle; gate armed → startup must fail.
        assertThatThrownBy(() -> boot(CyclicApp.class,
                "wiredoctor.output-path=" + tempDir,
                "wiredoctor.baseline=" + baseline,
                "wiredoctor.fail-on=new-cycle"))
                .isInstanceOf(WireDoctorRegressionException.class)
                .hasMessageContaining("new-cycle")
                .hasMessageContaining("cycleA");

        // The diff was still written before the gate fired (CI can inspect it).
        JsonNode diff = new ObjectMapper()
                .readTree(tempDir.resolve("wiredoctor-diff.json").toFile());
        assertThat(diff.path("newCyclesCount").asInt()).isEqualTo(1);
    }

    @Test
    void newCycleWithoutGateOnlyReports(@TempDir Path tempDir) throws Exception {
        Path baseline = tempDir.resolve("wiredoctor-baseline.json");
        try (var ignored = boot(CleanApp.class,
                "wiredoctor.output-path=" + tempDir,
                "wiredoctor.baseline=" + baseline,
                "wiredoctor.baseline-write=true")) { }

        // No fail-on configured: cycle is reported in the diff but app boots fine.
        try (var context = boot(CyclicApp.class,
                "wiredoctor.output-path=" + tempDir,
                "wiredoctor.baseline=" + baseline)) {
            assertThat(context.isActive()).isTrue();
        }
        JsonNode diff = new ObjectMapper()
                .readTree(tempDir.resolve("wiredoctor-diff.json").toFile());
        assertThat(diff.path("newCyclesCount").asInt()).isEqualTo(1);
    }

    @Test
    void missingBaselineDegradesGracefully(@TempDir Path tempDir) {
        // Gate armed but baseline doesn't exist: info log, no gate, no diff file, app boots.
        try (var context = boot(CyclicApp.class,
                "wiredoctor.output-path=" + tempDir,
                "wiredoctor.baseline=" + tempDir.resolve("does-not-exist.json"),
                "wiredoctor.fail-on=new-cycle")) {
            assertThat(context.isActive()).isTrue();
        }
        assertThat(tempDir.resolve("wiredoctor-diff.json")).doesNotExist();
    }

    @Test
    void corruptBaselineDegradesGracefully(@TempDir Path tempDir) throws Exception {
        Path baseline = tempDir.resolve("wiredoctor-baseline.json");
        Files.writeString(baseline, "this is not json {{{");
        try (var context = boot(CyclicApp.class,
                "wiredoctor.output-path=" + tempDir,
                "wiredoctor.baseline=" + baseline,
                "wiredoctor.fail-on=new-cycle")) {
            assertThat(context.isActive()).isTrue();
        }
    }

    @Test
    void failOnParsingHandlesListsAndUnknownGates() {
        WireDoctorProperties p = new WireDoctorProperties();
        p.setFailOn("new-cycle");
        assertThat(p.isFailOnNewCycle()).isTrue();
        p.setFailOn(" new-cycle , startup-regression ");
        assertThat(p.isFailOnNewCycle()).isTrue();
        p.setFailOn("startup-regression");
        assertThat(p.isFailOnNewCycle()).isFalse();
        p.setFailOn("");
        assertThat(p.isFailOnNewCycle()).isFalse();
        p.setFailOn(null);
        assertThat(p.isFailOnNewCycle()).isFalse();
    }

    // ── v0.4.0: CI marker-file contract (wiredoctor-gate.status) ─────────────

    @Test
    void cleanDiffWritesPassMarker(@TempDir Path tempDir) throws Exception {
        Path baseline = tempDir.resolve("wiredoctor-baseline.json");
        try (var ignored = boot(CleanApp.class,
                "wiredoctor.output-path=" + tempDir,
                "wiredoctor.baseline=" + baseline,
                "wiredoctor.baseline-write=true")) { }

        try (var ignored = boot(CleanApp.class,
                "wiredoctor.output-path=" + tempDir,
                "wiredoctor.baseline=" + baseline)) { }

        Path status = tempDir.resolve("wiredoctor-gate.status");
        assertThat(status).exists();
        var lines = Files.readAllLines(status);
        assertThat(lines.get(0)).isEqualTo("PASS");
        assertThat(lines).anyMatch(l -> l.equals("newCycles=0"));
        assertThat(lines).anyMatch(l -> l.equals("gateArmed=false"));
    }

    @Test
    void newCycleWritesFailMarkerEvenWithoutArmedGate(@TempDir Path tempDir) throws Exception {
        // Soft-gating: teams can check the marker in CI without opting into
        // fail-on (the app must still boot successfully here).
        Path baseline = tempDir.resolve("wiredoctor-baseline.json");
        try (var ignored = boot(CleanApp.class,
                "wiredoctor.output-path=" + tempDir,
                "wiredoctor.baseline=" + baseline,
                "wiredoctor.baseline-write=true")) { }

        try (var context = boot(CyclicApp.class,
                "wiredoctor.output-path=" + tempDir,
                "wiredoctor.baseline=" + baseline)) {
            assertThat(context.isActive()).isTrue();
        }

        var lines = Files.readAllLines(tempDir.resolve("wiredoctor-gate.status"));
        assertThat(lines.get(0)).isEqualTo("FAIL:new-cycle");
        assertThat(lines).anyMatch(l -> l.equals("newCycles=1"));
        assertThat(lines).anyMatch(l -> l.equals("gateArmed=false"));
    }

    @Test
    void trippedGateWritesFailMarkerBeforeThrowing(@TempDir Path tempDir) throws Exception {
        // The marker must exist even when the hard gate kills the app — CI
        // reads the file after the JVM is gone.
        Path baseline = tempDir.resolve("wiredoctor-baseline.json");
        try (var ignored = boot(CleanApp.class,
                "wiredoctor.output-path=" + tempDir,
                "wiredoctor.baseline=" + baseline,
                "wiredoctor.baseline-write=true")) { }

        assertThatThrownBy(() -> boot(CyclicApp.class,
                "wiredoctor.output-path=" + tempDir,
                "wiredoctor.baseline=" + baseline,
                "wiredoctor.fail-on=new-cycle"))
                .isInstanceOf(WireDoctorRegressionException.class);

        var lines = Files.readAllLines(tempDir.resolve("wiredoctor-gate.status"));
        assertThat(lines.get(0)).isEqualTo("FAIL:new-cycle");
        assertThat(lines).anyMatch(l -> l.equals("gateArmed=true"));
    }

    @Test
    void staleMarkerIsRemovedWhenDiffCannotRun(@TempDir Path tempDir) throws Exception {
        // A FAIL from a previous run must never survive into a run where the
        // diff is skipped (missing baseline) — absence means "no verdict".
        Path status = tempDir.resolve("wiredoctor-gate.status");
        Files.writeString(status, "FAIL:new-cycle\nstale=true\n");

        try (var context = boot(CleanApp.class,
                "wiredoctor.output-path=" + tempDir,
                "wiredoctor.baseline=" + tempDir.resolve("does-not-exist.json"))) {
            assertThat(context.isActive()).isTrue();
        }
        assertThat(status).doesNotExist();
    }

    @Test
    void baselineWriteModeDoesNotWriteMarker(@TempDir Path tempDir) throws Exception {
        // Write mode never diffs, so there is no verdict to report.
        Path baseline = tempDir.resolve("wiredoctor-baseline.json");
        try (var ignored = boot(CleanApp.class,
                "wiredoctor.output-path=" + tempDir,
                "wiredoctor.baseline=" + baseline,
                "wiredoctor.baseline-write=true")) { }
        assertThat(tempDir.resolve("wiredoctor-gate.status")).doesNotExist();
    }

    @Test
    void noBaselineConfiguredWritesNoMarker(@TempDir Path tempDir) {
        try (var ignored = boot(CleanApp.class,
                "wiredoctor.output-path=" + tempDir)) { }
        assertThat(tempDir.resolve("wiredoctor-gate.status")).doesNotExist();
    }

    // ── v0.5.0: condition diff + condition-changed gate ──────────────────────
    //
    // We flip a condition outcome by toggling the CleanApp `conditionalFeature`
    // bean's `@ConditionalOnProperty("wd.feature.on")` — matched by default
    // (baseline), notMatched when set to false (diff run). This is
    // Boot-version-independent, so it holds across the 2.7 → 4.x compat matrix
    // (excluding a specific Boot autoconfig would break, since those classes
    // move packages between major versions).

    @Test
    void baselineCapturesConditionsSection(@TempDir Path tempDir) throws Exception {
        Path baseline = tempDir.resolve("wiredoctor-baseline.json");
        try (var ignored = boot(CleanApp.class,
                "wiredoctor.output-path=" + tempDir,
                "wiredoctor.baseline=" + baseline,
                "wiredoctor.baseline-write=true")) { }

        JsonNode parsed = new ObjectMapper().readTree(baseline.toFile());
        assertThat(parsed.path("conditions").isObject()).isTrue();
        assertThat(parsed.path("conditions").size()).isPositive();
    }

    @Test
    void flippedConditionWithGateArmedFailsTheApplication(@TempDir Path tempDir) throws Exception {
        // 1. Baseline with the conditional feature ON (matched).
        Path baseline = tempDir.resolve("wiredoctor-baseline.json");
        try (var ignored = boot(CleanApp.class,
                "wiredoctor.output-path=" + tempDir,
                "wiredoctor.baseline=" + baseline,
                "wiredoctor.baseline-write=true")) { }

        // 2. Same app, feature now OFF → condition flips matched → notMatched.
        assertThatThrownBy(() -> boot(CleanApp.class,
                "wiredoctor.output-path=" + tempDir,
                "wiredoctor.baseline=" + baseline,
                "wiredoctor.fail-on=condition-changed",
                "wd.feature.on=false"))
                .isInstanceOf(WireDoctorRegressionException.class)
                .hasMessageContaining("condition-changed");

        // Diff written before the gate fired — condition change is recorded.
        JsonNode diff = new ObjectMapper()
                .readTree(tempDir.resolve("wiredoctor-diff.json").toFile());
        assertThat(diff.path("conditionDiff").path("available").asBoolean()).isTrue();
        assertThat(diff.path("conditionDiff").path("changedCount").asInt()).isPositive();
    }

    @Test
    void flippedConditionWithoutGateOnlyReports(@TempDir Path tempDir) throws Exception {
        Path baseline = tempDir.resolve("wiredoctor-baseline.json");
        try (var ignored = boot(CleanApp.class,
                "wiredoctor.output-path=" + tempDir,
                "wiredoctor.baseline=" + baseline,
                "wiredoctor.baseline-write=true")) { }

        // No fail-on: condition change reported, app boots fine.
        try (var context = boot(CleanApp.class,
                "wiredoctor.output-path=" + tempDir,
                "wiredoctor.baseline=" + baseline,
                "wd.feature.on=false")) {
            assertThat(context.isActive()).isTrue();
        }
        JsonNode diff = new ObjectMapper()
                .readTree(tempDir.resolve("wiredoctor-diff.json").toFile());
        assertThat(diff.path("conditionDiff").path("changedCount").asInt()).isPositive();
    }

    @Test
    void flippedConditionWritesFailMarkerWithConditionGate(@TempDir Path tempDir) throws Exception {
        Path baseline = tempDir.resolve("wiredoctor-baseline.json");
        try (var ignored = boot(CleanApp.class,
                "wiredoctor.output-path=" + tempDir,
                "wiredoctor.baseline=" + baseline,
                "wiredoctor.baseline-write=true")) { }

        try (var context = boot(CleanApp.class,
                "wiredoctor.output-path=" + tempDir,
                "wiredoctor.baseline=" + baseline,
                "wd.feature.on=false")) {
            assertThat(context.isActive()).isTrue();
        }

        var lines = Files.readAllLines(tempDir.resolve("wiredoctor-gate.status"));
        assertThat(lines.get(0)).isEqualTo("FAIL:condition-changed");
        assertThat(lines).anyMatch(l -> l.startsWith("conditionsChanged="))
                         .noneMatch(l -> l.equals("conditionsChanged=0"));
    }

    @Test
    void combinedGatesReportBothInMarker(@TempDir Path tempDir) throws Exception {
        // new-cycle AND condition-changed both fire → FAIL lists both, order stable.
        // ONE app class for both runs so condition keys align: baseline has no
        // cycle + feature on; diff run turns the cycle ON and the feature OFF.
        Path baseline = tempDir.resolve("wiredoctor-baseline.json");
        try (var ignored = boot(CombinedApp.class,
                "wiredoctor.output-path=" + tempDir,
                "wiredoctor.baseline=" + baseline,
                "wiredoctor.baseline-write=true")) { }

        try (var context = boot(CombinedApp.class,
                "wiredoctor.output-path=" + tempDir,
                "wiredoctor.baseline=" + baseline,
                "wd.cycle.on=true",
                "wd.feature.on=false")) {
            assertThat(context.isActive()).isTrue();
        }
        var lines = Files.readAllLines(tempDir.resolve("wiredoctor-gate.status"));
        assertThat(lines.get(0)).isEqualTo("FAIL:new-cycle,condition-changed");
    }

    @Test
    void oldBaselineWithoutConditionsSkipsConditionDiffGracefully(@TempDir Path tempDir)
            throws Exception {
        // BACKWARD COMPAT: a v0.2.0-era baseline (no conditions section) must
        // NOT trip the condition gate and must mark conditionDiff=skipped.
        Path baseline = tempDir.resolve("wiredoctor-baseline.json");
        // Simulate an old baseline: dependencies only, no conditions.
        Files.writeString(baseline,
                "{\"dependencies\": {\"graph\": {\"standalone\": []}, \"cycles\": []}}");

        try (var context = boot(CleanApp.class,
                "wiredoctor.output-path=" + tempDir,
                "wiredoctor.baseline=" + baseline,
                "wiredoctor.fail-on=condition-changed")) {
            assertThat(context.isActive()).isTrue(); // gate must NOT trip
        }
        var lines = Files.readAllLines(tempDir.resolve("wiredoctor-gate.status"));
        assertThat(lines).anyMatch(l -> l.equals("conditionDiff=skipped"));
    }

    @Test
    void conditionGateParsingHandlesListsAndCombos() {
        WireDoctorProperties p = new WireDoctorProperties();
        p.setFailOn("condition-changed");
        assertThat(p.isFailOnConditionChanged()).isTrue();
        assertThat(p.isFailOnNewCycle()).isFalse();
        p.setFailOn(" new-cycle , condition-changed ");
        assertThat(p.isFailOnNewCycle()).isTrue();
        assertThat(p.isFailOnConditionChanged()).isTrue();
        p.setFailOn("new-cycle");
        assertThat(p.isFailOnConditionChanged()).isFalse();
        p.setFailOn(null);
        assertThat(p.isFailOnConditionChanged()).isFalse();
    }

    // ── v0.7.0: timing regression gates ──────────────────────────────────────

    /** App with an intentionally slow bean for timing gate tests */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class SlowBeanApp {
        @Bean
        String normalBean() {
            return "fast";
        }

        @Bean
        @ConditionalOnProperty(name = "wd.slow.enabled", havingValue = "true")
        String slowBean() throws InterruptedException {
            // Simulate a slow bean initialization (1 second)
            Thread.sleep(1000);
            return "slow";
        }
    }

    @Test
    void startupTimeGateTripsWhenBothThresholdsExceeded(@TempDir Path tempDir) throws Exception {
        // Step 1: Write baseline with fast startup (~300ms typical)
        try (var ctx = new SpringApplicationBuilder(CleanApp.class)
                .web(WebApplicationType.NONE)
                .properties(
                        "wiredoctor.enabled=true",
                        "wiredoctor.baseline=" + tempDir.resolve("wiredoctor-baseline.json"),
                        "wiredoctor.baseline-write=true",
                        "wiredoctor.output-path=" + tempDir)
                .run()) {
            assertThat(ctx.isRunning()).isTrue();
        }

        // Step 2: Run diff with slow bean enabled (adds ~1 second)
        // Default thresholds: 20% relative AND 500ms absolute
        assertThatThrownBy(() -> {
            new SpringApplicationBuilder(SlowBeanApp.class)
                    .web(WebApplicationType.NONE)
                    .properties(
                            "wiredoctor.enabled=true",
                            "wiredoctor.baseline=" + tempDir.resolve("wiredoctor-baseline.json"),
                            "wiredoctor.output-path=" + tempDir,
                            "wiredoctor.fail-on=startup-time",
                            "wiredoctor.startup-time-relative-threshold=0.10",
                            "wiredoctor.startup-time-absolute-threshold=200",
                            "wd.slow.enabled=true")
                    .run();
        }).isInstanceOf(WireDoctorRegressionException.class)
                .hasMessageContaining("startup-time");

        // Verify gate status file
        var lines = Files.readAllLines(tempDir.resolve("wiredoctor-gate.status"));
        assertThat(lines.get(0)).startsWith("FAIL:").contains("startup-time");
        assertThat(lines).anyMatch(l -> l.startsWith("startupTimeDeltaMs="));
    }

    @Test
    void startupTimeGateSkippedWhenBaselineMissingTiming(@TempDir Path tempDir) throws Exception {
        // Step 1: Write a pre-v0.7.0 style baseline (manually craft JSON without timing)
        Path baselineFile = tempDir.resolve("wiredoctor-baseline.json");
        String oldBaselineJson = """
                {
                  "dependencies": {
                    "graph": {"standalone": []},
                    "cycles": []
                  }
                }
                """;
        Files.writeString(baselineFile, oldBaselineJson);

        // Step 2: Run with slow bean — gate should gracefully skip (no timing in baseline)
        try (var ctx = new SpringApplicationBuilder(SlowBeanApp.class)
                .web(WebApplicationType.NONE)
                .properties(
                        "wiredoctor.enabled=true",
                        "wiredoctor.baseline=" + baselineFile,
                        "wiredoctor.output-path=" + tempDir,
                        "wiredoctor.fail-on=startup-time",
                        "wd.slow.enabled=true")
                .run()) {
            assertThat(ctx.isRunning()).isTrue();
        }

        var lines = Files.readAllLines(tempDir.resolve("wiredoctor-gate.status"));
        // No startup time regression signal when baseline lacks timing
        assertThat(lines.get(0)).isEqualTo("PASS");
        assertThat(lines).noneMatch(l -> l.startsWith("startupTimeDeltaMs="));
    }

    @Test
    void startupTimeGatePassesWhenOnlyRelativeThresholdExceeded(@TempDir Path tempDir) throws Exception {
        // Write baseline
        try (var ctx = new SpringApplicationBuilder(CleanApp.class)
                .web(WebApplicationType.NONE)
                .properties(
                        "wiredoctor.enabled=true",
                        "wiredoctor.baseline=" + tempDir.resolve("wiredoctor-baseline.json"),
                        "wiredoctor.baseline-write=true",
                        "wiredoctor.output-path=" + tempDir)
                .run()) {
            assertThat(ctx.isRunning()).isTrue();
        }

        // Run with tight absolute threshold (10000ms) — only relative trips
        // This should PASS because both thresholds must trip
        try (var ctx = new SpringApplicationBuilder(SlowBeanApp.class)
                .web(WebApplicationType.NONE)
                .properties(
                        "wiredoctor.enabled=true",
                        "wiredoctor.baseline=" + tempDir.resolve("wiredoctor-baseline.json"),
                        "wiredoctor.output-path=" + tempDir,
                        "wiredoctor.fail-on=startup-time",
                        "wiredoctor.startup-time-absolute-threshold=10000", // 10s (way too high)
                        "wd.slow.enabled=true")
                .run()) {
            assertThat(ctx.isRunning()).isTrue();
        }

        var lines = Files.readAllLines(tempDir.resolve("wiredoctor-gate.status"));
        // The startup-time SIGNAL must not fire (absolute threshold not met — dual-threshold
        // check). Other signals (e.g. slow-bean, unarmed) may still appear on line 1;
        // what matters: the armed startup-time gate did not trip and the app stayed up.
        assertThat(lines.get(0)).doesNotContain("startup-time");
    }

    @Test
    void slowBeanGateTripsWhenNewBeanCrossesThreshold(@TempDir Path tempDir) throws Exception {
        // Step 1: Write baseline WITHOUT slow bean
        try (var ctx = new SpringApplicationBuilder(SlowBeanApp.class)
                .web(WebApplicationType.NONE)
                .properties(
                        "wiredoctor.enabled=true",
                        "wiredoctor.baseline=" + tempDir.resolve("wiredoctor-baseline.json"),
                        "wiredoctor.baseline-write=true",
                        "wiredoctor.output-path=" + tempDir,
                        "wiredoctor.slow-bean-threshold-ms=800")
                .run()) {
            assertThat(ctx.isRunning()).isTrue();
        }

        // Step 2: Enable slow bean (1000ms) — should trip gate
        assertThatThrownBy(() -> {
            new SpringApplicationBuilder(SlowBeanApp.class)
                    .web(WebApplicationType.NONE)
                    .properties(
                            "wiredoctor.enabled=true",
                            "wiredoctor.baseline=" + tempDir.resolve("wiredoctor-baseline.json"),
                            "wiredoctor.output-path=" + tempDir,
                            "wiredoctor.fail-on=slow-bean",
                            "wiredoctor.slow-bean-threshold-ms=800",
                            "wd.slow.enabled=true")
                    .run();
        }).isInstanceOf(WireDoctorRegressionException.class)
                .hasMessageContaining("slow-bean");

        var lines = Files.readAllLines(tempDir.resolve("wiredoctor-gate.status"));
        // Line 1 lists every fired signal (the slow bean also slows startup, so
        // startup-time may appear too) — slow-bean must be among them.
        assertThat(lines.get(0)).startsWith("FAIL:").contains("slow-bean");
        assertThat(lines).anyMatch(l -> l.startsWith("newSlowBeansCount="));
    }

    @Test
    void slowBeanGatePassesWhenSlowBeanAlreadyInBaseline(@TempDir Path tempDir) throws Exception {
        // Step 1: Write baseline WITH slow bean already present
        try (var ctx = new SpringApplicationBuilder(SlowBeanApp.class)
                .web(WebApplicationType.NONE)
                .properties(
                        "wiredoctor.enabled=true",
                        "wiredoctor.baseline=" + tempDir.resolve("wiredoctor-baseline.json"),
                        "wiredoctor.baseline-write=true",
                        "wiredoctor.output-path=" + tempDir,
                        "wiredoctor.slow-bean-threshold-ms=800",
                        "wd.slow.enabled=true")
                .run()) {
            assertThat(ctx.isRunning()).isTrue();
        }

        // Step 2: Run again with same slow bean — should PASS
        try (var ctx = new SpringApplicationBuilder(SlowBeanApp.class)
                .web(WebApplicationType.NONE)
                .properties(
                        "wiredoctor.enabled=true",
                        "wiredoctor.baseline=" + tempDir.resolve("wiredoctor-baseline.json"),
                        "wiredoctor.output-path=" + tempDir,
                        "wiredoctor.fail-on=slow-bean",
                        "wiredoctor.slow-bean-threshold-ms=800",
                        "wd.slow.enabled=true")
                .run()) {
            assertThat(ctx.isRunning()).isTrue();
        }

        var lines = Files.readAllLines(tempDir.resolve("wiredoctor-gate.status"));
        // No new slow beans, should PASS
        assertThat(lines.get(0)).isEqualTo("PASS");
        assertThat(lines).noneMatch(l -> l.startsWith("newSlowBeansCount="));
    }

    // ── v0.7.1: gate verdicts surfaced in the report (JSON + HTML) ───────────

    @Test
    void reportJsonContainsGateVerdictWhenGateTrips(@TempDir Path tempDir) throws Exception {
        // Step 1: Baseline WITHOUT the slow bean.
        try (var ignored = boot(SlowBeanApp.class,
                "wiredoctor.output-path=" + tempDir,
                "wiredoctor.baseline=" + tempDir.resolve("wiredoctor-baseline.json"),
                "wiredoctor.baseline-write=true",
                "wiredoctor.slow-bean-threshold-ms=800")) { }

        // Step 2: Slow bean appears — signal fires (unarmed, app stays up) and
        // the verdict must land in the report files, which are written AFTER
        // the guard runs (v0.7.1 reorder).
        try (var context = boot(SlowBeanApp.class,
                "wiredoctor.output-path=" + tempDir,
                "wiredoctor.baseline=" + tempDir.resolve("wiredoctor-baseline.json"),
                "wiredoctor.slow-bean-threshold-ms=800",
                "wd.slow.enabled=true")) {
            assertThat(context.isActive()).isTrue();
        }

        JsonNode report = new ObjectMapper()
                .readTree(tempDir.resolve("wiredoctor-report.json").toFile());
        JsonNode gates = report.path("gates");
        assertThat(gates.path("mode").asText()).isEqualTo("diff");
        assertThat(gates.path("baselineConfigured").asBoolean()).isTrue();
        java.util.List<String> failed = new java.util.ArrayList<>();
        gates.path("failed").forEach(n -> failed.add(n.asText()));
        assertThat(failed).contains("slow-bean");
        assertThat(gates.path("newSlowBeans").isArray()).isTrue();
        assertThat(gates.path("newSlowBeans").size()).isPositive();

        // The HTML report embeds the same data (string-level check: the gates
        // section reached the injected JSON).
        String html = Files.readString(tempDir.resolve("wiredoctor-report.html"));
        assertThat(html).contains("\"gates\"").contains("\"slow-bean\"");
    }

    @Test
    void reportJsonContainsGatesConfigWhenBaselineOff(@TempDir Path tempDir) throws Exception {
        // No baseline configured: mode stays "off", but armed + config are
        // still recorded so the HTML can show the discovery hint + thresholds.
        try (var ignored = boot(CleanApp.class,
                "wiredoctor.output-path=" + tempDir)) { }

        JsonNode report = new ObjectMapper()
                .readTree(tempDir.resolve("wiredoctor-report.json").toFile());
        JsonNode gates = report.path("gates");
        assertThat(gates.path("mode").asText()).isEqualTo("off");
        assertThat(gates.path("baselineConfigured").asBoolean()).isFalse();
        assertThat(gates.path("armed").isArray()).isTrue();
        assertThat(gates.path("failed").isMissingNode()).isTrue(); // no diff → no verdict
        JsonNode config = gates.path("config");
        assertThat(config.path("startupTimeAbsoluteThresholdMs").asLong()).isEqualTo(500);
        assertThat(config.path("slowBeanThresholdMs").asLong()).isEqualTo(100);
    }

    @Test
    void baselineFileDoesNotContainGatesKey(@TempDir Path tempDir) throws Exception {
        // The gates section is a per-run verdict, not part of the architecture
        // snapshot — it must be stripped from the persisted baseline (a stale
        // verdict there would be misleading and pure churn on rewrite).
        Path baseline = tempDir.resolve("wiredoctor-baseline.json");
        try (var ignored = boot(CleanApp.class,
                "wiredoctor.output-path=" + tempDir,
                "wiredoctor.baseline=" + baseline,
                "wiredoctor.baseline-write=true")) { }

        JsonNode parsed = new ObjectMapper().readTree(baseline.toFile());
        assertThat(parsed.path("gates").isMissingNode()).isTrue();

        // ...while the report written in the same run still carries it.
        JsonNode report = new ObjectMapper()
                .readTree(tempDir.resolve("wiredoctor-report.json").toFile());
        assertThat(report.path("gates").path("mode").asText()).isEqualTo("write");
    }
}
