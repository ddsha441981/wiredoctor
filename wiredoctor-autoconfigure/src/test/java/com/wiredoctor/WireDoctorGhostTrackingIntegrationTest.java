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
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;

import java.io.File;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end integration tests for opt-in ghost tracking (v0.6.0 Phase 2).
 * <p>
 * The single most important test here is the <b>passivity regression test</b>:
 * with the default configuration (no opt-in), WireDoctor must register ZERO
 * {@code BeanPostProcessor} — the trust story of every release since v0.1.1
 * depends on the default artifact never wrapping a user bean.
 */
class WireDoctorGhostTrackingIntegrationTest {

    interface Invokable {
        String invoke();
    }

    static class InvokedBean implements Invokable {
        @Override public String invoke() { return "invoked"; }
    }

    static class GhostBean implements Invokable {
        @Override public String invoke() { return "never called"; }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApp {

        @Bean
        InvokedBean invokedBean() {
            return new InvokedBean();
        }

        @Bean
        GhostBean ghostBean() {
            return new GhostBean();
        }
    }

    private ConfigurableApplicationContext boot(String... properties) {
        return new SpringApplicationBuilder(TestApp.class)
                .web(org.springframework.boot.WebApplicationType.NONE)
                .properties(properties)
                .run();
    }

    // ── THE passivity regression test ────────────────────────────────────────

    @Test
    void disabledByDefaultRegistersZeroBeanPostProcessor(@TempDir Path tempDir) {
        try (ConfigurableApplicationContext context = boot(
                "wiredoctor.output-path=" + tempDir)) {

            // No ghost-tracking beans at all in the default configuration.
            assertThat(context.getBeansOfType(WireDoctorGhostTrackingPostProcessor.class)).isEmpty();
            assertThat(context.getBeansOfType(WireDoctorGhostTracker.class)).isEmpty();
            assertThat(context.getBeansOfType(WireDoctorGhostReportWriter.class)).isEmpty();

            // Belt and braces: no WireDoctor-originated BPP in the factory's
            // live post-processor chain either.
            DefaultListableBeanFactory beanFactory =
                    (DefaultListableBeanFactory) context.getBeanFactory();
            for (BeanPostProcessor bpp : beanFactory.getBeanPostProcessors()) {
                assertThat(bpp.getClass().getPackageName())
                        .as("no WireDoctor BeanPostProcessor may be registered by default")
                        .isNotEqualTo("com.wiredoctor");
            }

            // And user beans are untouched raw instances — no proxies.
            assertThat(AopUtils.isAopProxy(context.getBean(InvokedBean.class))).isFalse();
            assertThat(AopUtils.isAopProxy(context.getBean(GhostBean.class))).isFalse();
        }
    }

    // ── Enabled: touched vs ghost ────────────────────────────────────────────

    @Test
    void enabledTracksTouchedVersusGhostAndWritesShutdownReport(@TempDir Path tempDir)
            throws Exception {
        ConfigurableApplicationContext context = boot(
                "wiredoctor.output-path=" + tempDir,
                "wiredoctor.ghost-tracking.enabled=true");

        // Both beans are wrapped; invoke only one.
        Invokable invoked = context.getBean("invokedBean", Invokable.class);
        assertThat(invoked.invoke()).isEqualTo("invoked");

        WireDoctorGhostTracker tracker = context.getBean(WireDoctorGhostTracker.class);
        assertThat(tracker.trackedCount()).isGreaterThanOrEqualTo(2);

        // Shutdown writes the ghost report.
        context.close();

        File ghostReport = tempDir.resolve("wiredoctor-ghost-report.json").toFile();
        assertThat(ghostReport).exists().isNotEmpty();

        JsonNode report = new ObjectMapper().readTree(ghostReport);
        assertThat(jsonList(report.path("touched"))).contains("invokedBean");
        assertThat(jsonList(report.path("untouched"))).contains("ghostBean");
        assertThat(report.path("disclaimer").asText()).contains("NOT that the bean is unused");
    }

    @Test
    void excludedBeanIsReportedUntrackable(@TempDir Path tempDir) {
        try (ConfigurableApplicationContext context = boot(
                "wiredoctor.output-path=" + tempDir,
                "wiredoctor.ghost-tracking.enabled=true",
                "wiredoctor.ghost-tracking.exclude=ghostBean")) {

            // Excluded bean is the raw instance, and the exclusion is reported.
            assertThat(AopUtils.isAopProxy(context.getBean("ghostBean"))).isFalse();

            WireDoctorGhostTracker tracker = context.getBean(WireDoctorGhostTracker.class);
            assertThat(tracker.toReportMap().get("untrackable").toString())
                    .contains("ghostBean");
        }
    }

    @Test
    void mainReportStillWrittenWithTrackingEnabled(@TempDir Path tempDir) {
        // Ghost tracking must not disturb the core analysis pipeline.
        try (ConfigurableApplicationContext context = boot(
                "wiredoctor.output-path=" + tempDir,
                "wiredoctor.ghost-tracking.enabled=true")) {
            assertThat(context.isActive()).isTrue();
            assertThat(tempDir.resolve("wiredoctor-report.json").toFile()).exists();
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static java.util.List<String> jsonList(JsonNode arrayNode) {
        java.util.List<String> values = new java.util.ArrayList<>();
        arrayNode.forEach(n -> values.add(n.asText()));
        return values;
    }
}
