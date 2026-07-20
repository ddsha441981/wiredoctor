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
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.metrics.buffering.BufferingApplicationStartup;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.metrics.ApplicationStartup;
import org.springframework.core.metrics.StartupStep;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ApplicationStartup coexistence (v0.8.0): the app (or another tool) may install
 * its own {@link ApplicationStartup} before WireDoctor's listener runs. Pins the
 * politeness contract: WireDoctor never overwrites foreign instrumentation, the
 * host never crashes, and the report degrades gracefully (timing section empty,
 * everything else intact).
 */
class WireDoctorStartupCoexistenceTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class PlainApp { }

    /** Minimal foreign (non-buffering) ApplicationStartup — stands in for any third-party tracer. */
    static class ForeignStartup implements ApplicationStartup {
        boolean used = false;

        @Override
        public StartupStep start(String name) {
            used = true;
            return ApplicationStartup.DEFAULT.start(name);
        }
    }

    @Test
    void userSetBufferingStartupIsKeptAndTimingsStillWork(@TempDir Path tempDir) throws Exception {
        BufferingApplicationStartup usersOwn = new BufferingApplicationStartup(5000);
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(PlainApp.class)
                .web(WebApplicationType.NONE)
                .applicationStartup(usersOwn)
                .properties("wiredoctor.output-path=" + tempDir)
                .run()) {
            assertThat(context.isActive()).isTrue();
            // WireDoctor must keep the USER'S instance, not swap in its own.
            assertThat(context.getApplicationStartup()).isSameAs(usersOwn);

            // And timings still work — it's buffering, so the analyzer reads it.
            JsonNode report = new ObjectMapper()
                    .readTree(tempDir.resolve("wiredoctor-report.json").toFile());
            assertThat(report.path("startupSlowestSteps").size()).isPositive();
        }
    }

    @Test
    void foreignStartupIsNotOverwrittenAndReportDegradesGracefully(@TempDir Path tempDir) throws Exception {
        ForeignStartup foreign = new ForeignStartup();
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(PlainApp.class)
                .web(WebApplicationType.NONE)
                .applicationStartup(foreign)
                .properties("wiredoctor.output-path=" + tempDir)
                .run()) {
            // Never crash the host; never replace foreign instrumentation.
            assertThat(context.isActive()).isTrue();
            assertThat(context.getApplicationStartup()).isSameAs(foreign);
            assertThat(foreign.used).isTrue(); // the foreign tracer kept receiving steps

            // Report still written; timing section empty, the rest intact.
            JsonNode report = new ObjectMapper()
                    .readTree(tempDir.resolve("wiredoctor-report.json").toFile());
            assertThat(report.path("startupSlowestSteps").size()).isZero();
            assertThat(report.path("slowBeans").size()).isZero();
            assertThat(report.path("dependencies").path("totalBeans").asInt()).isPositive();
            assertThat(report.has("proxies")).isTrue();
            assertThat(report.has("ghostCandidates")).isTrue();
        }
    }
}
