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

    /** Marker for the pre-proxied fixture (stands in for @Transactional/@Async). */
    interface Advised {
        String call();
    }

    static class AdvisedBean implements Advised {
        @Override public String call() { return "raw"; }
    }

    /**
     * Simulates the AOP infrastructure (@Transactional/@Async proxying): a
     * HIGHEST_PRECEDENCE BPP that wraps {@link AdvisedBean} in an advice that
     * rewrites the return value. If ghost tracking double-wrapped or broke this
     * proxy, the advice would be lost and the assertion below would see "raw".
     */
    static class SimulatedTxPostProcessor implements BeanPostProcessor,
            org.springframework.core.Ordered {
        @Override
        public int getOrder() {
            return HIGHEST_PRECEDENCE;
        }

        @Override
        public Object postProcessAfterInitialization(Object bean, String beanName) {
            if (!(bean instanceof AdvisedBean)) {
                return bean;
            }
            org.springframework.aop.framework.ProxyFactory factory =
                    new org.springframework.aop.framework.ProxyFactory(bean);
            factory.addAdvice((org.aopalliance.intercept.MethodInterceptor) invocation ->
                    "advised:" + invocation.proceed());
            return factory.getProxy();
        }
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

        @Bean
        AdvisedBean advisedBean() {
            return new AdvisedBean();
        }

        @Bean
        static SimulatedTxPostProcessor simulatedTxPostProcessor() {
            return new SimulatedTxPostProcessor();
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
            // live post-processor chain either (the simulated TX fixture is a
            // test-only stand-in for the AOP infrastructure, not WireDoctor's).
            DefaultListableBeanFactory beanFactory =
                    (DefaultListableBeanFactory) context.getBeanFactory();
            for (BeanPostProcessor bpp : beanFactory.getBeanPostProcessors()) {
                assertThat(bpp.getClass().getSimpleName())
                        .as("no WireDoctor BeanPostProcessor may be registered by default")
                        .doesNotStartWith("WireDoctor");
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

    @Test
    void alreadyProxiedBeanKeepsItsAdviceAndIsReportedUntrackable(@TempDir Path tempDir) {
        // The @Transactional-equivalent regression test: a bean proxied by the
        // (simulated) AOP infrastructure must arrive at the ghost tracker
        // already wrapped, be skipped — and keep behaving as proxied.
        try (ConfigurableApplicationContext context = boot(
                "wiredoctor.output-path=" + tempDir,
                "wiredoctor.ghost-tracking.enabled=true")) {

            Advised advised = context.getBean("advisedBean", Advised.class);
            // The original advice still applies — proxy not broken or replaced.
            assertThat(advised.call()).isEqualTo("advised:raw");

            WireDoctorGhostTracker tracker = context.getBean(WireDoctorGhostTracker.class);
            @SuppressWarnings("unchecked")
            java.util.Map<String, String> untrackable = (java.util.Map<String, String>)
                    tracker.toReportMap().get("untrackable");
            assertThat(untrackable).containsEntry("advisedBean",
                    WireDoctorGhostTrackingPostProcessor.REASON_ALREADY_PROXIED);
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static java.util.List<String> jsonList(JsonNode arrayNode) {
        java.util.List<String> values = new java.util.ArrayList<>();
        arrayNode.forEach(n -> values.add(n.asText()));
        return values;
    }
}
