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
 * ApplicationStartup coexistence (v0.8.0, v1.1.0): the app (or another tool) may install
 * its own {@link ApplicationStartup} before WireDoctor's processor runs. Pins the
 * politeness contract:
 * <ul>
 *   <li>Plain {@link BufferingApplicationStartup} → transparently upgraded to
 *       {@link WireDoctorBufferingApplicationStartup} (adds thread tagging, same type)</li>
 *   <li>Foreign non-buffering {@link ApplicationStartup} → never overwritten,
 *       timing analysis degrades gracefully</li>
 *   <li>Host app never crashes regardless of what's set</li>
 * </ul>
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
    void plainBufferingStartupIsUpgradedForThreadTagging(@TempDir Path tempDir) throws Exception {
        // v1.1.0: plain BufferingApplicationStartup is transparently replaced with
        // WireDoctorBufferingApplicationStartup to capture threadName tags.
        // The user's instance is NOT kept — it's upgraded to the same type with
        // additional capability (thread tagging on step creation).
        BufferingApplicationStartup usersOwn = new BufferingApplicationStartup(5000);
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(PlainApp.class)
                .web(WebApplicationType.NONE)
                .applicationStartup(usersOwn)
                .properties("wiredoctor.output-path=" + tempDir)
                .run()) {
            assertThat(context.isActive()).isTrue();
            // Upgraded to WireDoctorBufferingApplicationStartup (same BufferingApplicationStartup type)
            assertThat(context.getApplicationStartup())
                    .isInstanceOf(WireDoctorBufferingApplicationStartup.class);

            // Timings still work — it's still buffering, just with thread tagging
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
