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

    /** App WITHOUT a cycle — used to write baselines. */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class CleanApp {
        @Bean
        String standalone() {
            return "no deps";
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
}
