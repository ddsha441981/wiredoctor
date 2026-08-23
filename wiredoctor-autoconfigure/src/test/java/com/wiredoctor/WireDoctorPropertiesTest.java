/*
 * Copyright (c) 2026 Deendayal Kumawat
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package com.wiredoctor;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link WireDoctorProperties}: defaults, binding from the
 * {@code wiredoctor.*} namespace, and lenient threshold parsing (invalid values
 * must degrade to the default, never throw — the v0.1.1 trust guarantee).
 */
class WireDoctorPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(WireDoctorAutoConfiguration.class));

    // ── Defaults ──────────────────────────────────────────────────────────────

    @Test
    void defaultsAreAppliedWhenNothingConfigured() {
        WireDoctorProperties p = new WireDoctorProperties();
        assertThat(p.isEnabled()).isTrue();
        assertThat(p.getOutputPath()).isEqualTo(System.getProperty("user.dir"));
        assertThat(p.getScanPackages()).isNull();
        assertThat(p.resolveSlowBeanThresholdMs())
                .isEqualTo(WireDoctorProperties.DEFAULT_SLOW_BEAN_THRESHOLD_MS);
        assertThat(p.isSlowBeanThresholdValid()).isTrue();
    }

    // ── Threshold parsing (lenient) ──────────────────────────────────────────

    @Test
    void validThresholdIsParsed() {
        WireDoctorProperties p = new WireDoctorProperties();
        p.setSlowBeanThresholdMs("250");
        assertThat(p.resolveSlowBeanThresholdMs()).isEqualTo(250L);
        assertThat(p.isSlowBeanThresholdValid()).isTrue();
    }

    @Test
    void thresholdWithSurroundingWhitespaceIsParsed() {
        WireDoctorProperties p = new WireDoctorProperties();
        p.setSlowBeanThresholdMs("  75  ");
        assertThat(p.resolveSlowBeanThresholdMs()).isEqualTo(75L);
    }

    @Test
    void thresholdWithUnitSuffixFallsBackToDefault() {
        // The exact bad value that crashed host apps in v0.1.0 (BUG-1).
        WireDoctorProperties p = new WireDoctorProperties();
        p.setSlowBeanThresholdMs("50ms");
        assertThat(p.resolveSlowBeanThresholdMs())
                .isEqualTo(WireDoctorProperties.DEFAULT_SLOW_BEAN_THRESHOLD_MS);
        assertThat(p.isSlowBeanThresholdValid()).isFalse();
    }

    @Test
    void emptyThresholdFallsBackToDefault() {
        WireDoctorProperties p = new WireDoctorProperties();
        p.setSlowBeanThresholdMs("");
        assertThat(p.resolveSlowBeanThresholdMs())
                .isEqualTo(WireDoctorProperties.DEFAULT_SLOW_BEAN_THRESHOLD_MS);
        assertThat(p.isSlowBeanThresholdValid()).isFalse();
    }

    @Test
    void nullThresholdFallsBackToDefaultAndIsNotFlaggedInvalid() {
        WireDoctorProperties p = new WireDoctorProperties();
        p.setSlowBeanThresholdMs(null);
        assertThat(p.resolveSlowBeanThresholdMs())
                .isEqualTo(WireDoctorProperties.DEFAULT_SLOW_BEAN_THRESHOLD_MS);
        assertThat(p.isSlowBeanThresholdValid()).isTrue();
    }

    @Test
    void negativeThresholdIsParsedAsIs() {
        // Negative means "flag every bean" — harmless, so we accept it rather than guess.
        WireDoctorProperties p = new WireDoctorProperties();
        p.setSlowBeanThresholdMs("-1");
        assertThat(p.resolveSlowBeanThresholdMs()).isEqualTo(-1L);
    }

    // ── max-graph-nodes parsing (lenient, v0.3.0) ────────────────────────────

    @Test
    void maxGraphNodesDefaultsTo2000() {
        WireDoctorProperties p = new WireDoctorProperties();
        assertThat(p.resolveMaxGraphNodes())
                .isEqualTo(WireDoctorProperties.DEFAULT_MAX_GRAPH_NODES);
    }

    @Test
    void maxGraphNodesZeroMeansUnlimited() {
        WireDoctorProperties p = new WireDoctorProperties();
        p.setMaxGraphNodes("0");
        assertThat(p.resolveMaxGraphNodes()).isZero();
    }

    @Test
    void malformedMaxGraphNodesFallsBackToDefault() {
        WireDoctorProperties p = new WireDoctorProperties();
        p.setMaxGraphNodes("lots");
        assertThat(p.resolveMaxGraphNodes())
                .isEqualTo(WireDoctorProperties.DEFAULT_MAX_GRAPH_NODES);
    }

    @Test
    void negativeMaxGraphNodesFallsBackToDefault() {
        // Unlike the slow-bean threshold, a negative cap has no sane meaning.
        WireDoctorProperties p = new WireDoctorProperties();
        p.setMaxGraphNodes("-5");
        assertThat(p.resolveMaxGraphNodes())
                .isEqualTo(WireDoctorProperties.DEFAULT_MAX_GRAPH_NODES);
    }

    @Test
    void nullMaxGraphNodesFallsBackToDefault() {
        WireDoctorProperties p = new WireDoctorProperties();
        p.setMaxGraphNodes(null);
        assertThat(p.resolveMaxGraphNodes())
                .isEqualTo(WireDoctorProperties.DEFAULT_MAX_GRAPH_NODES);
    }

    // ── trend-history-size parsing (lenient, v1.1.0) ──────────────────────────

    @Test
    void trendHistorySizeDefaultsTo30() {
        WireDoctorProperties p = new WireDoctorProperties();
        assertThat(p.resolveTrendHistorySize()).isEqualTo(30);
    }

    @Test
    void trendHistorySizeZeroMeansUnlimited() {
        WireDoctorProperties p = new WireDoctorProperties();
        p.setTrendHistorySize("0");
        assertThat(p.resolveTrendHistorySize()).isZero();
    }

    @Test
    void malformedTrendHistorySizeFallsBackToDefault() {
        WireDoctorProperties p = new WireDoctorProperties();
        p.setTrendHistorySize("lots");
        assertThat(p.resolveTrendHistorySize()).isEqualTo(30);
    }

    @Test
    void negativeTrendHistorySizeFallsBackToDefault() {
        WireDoctorProperties p = new WireDoctorProperties();
        p.setTrendHistorySize("-5");
        assertThat(p.resolveTrendHistorySize()).isEqualTo(30);
    }

    // ── Context binding ──────────────────────────────────────────────────────

    @Test
    void propertiesBindFromWiredoctorNamespace() {
        runner.withPropertyValues(
                "wiredoctor.output-path=/tmp/wd-reports",
                "wiredoctor.scan-packages=com.example.app,io.example.svc",
                "wiredoctor.slow-bean-threshold-ms=42"
        ).run(context -> {
            WireDoctorProperties p = context.getBean(WireDoctorProperties.class);
            assertThat(p.getOutputPath()).isEqualTo("/tmp/wd-reports");
            assertThat(p.getScanPackages()).isEqualTo("com.example.app,io.example.svc");
            assertThat(p.resolveSlowBeanThresholdMs()).isEqualTo(42L);
        });
    }

    @Test
    void malformedThresholdDoesNotFailContextStartup() {
        // Bind-time regression test for BUG-1: "50ms" must not break the context.
        runner.withPropertyValues("wiredoctor.slow-bean-threshold-ms=50ms")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    WireDoctorProperties p = context.getBean(WireDoctorProperties.class);
                    assertThat(p.resolveSlowBeanThresholdMs())
                            .isEqualTo(WireDoctorProperties.DEFAULT_SLOW_BEAN_THRESHOLD_MS);
                });
    }

    @Test
    void enabledFalseBacksOffTheWholeAutoConfiguration() {
        runner.withPropertyValues("wiredoctor.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(WireDoctorAnalyzer.class);
                    assertThat(context).doesNotHaveBean(WireDoctorProperties.class);
                });
    }

    @Test
    void analyzerBeanIsRegisteredByDefault() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(WireDoctorAnalyzer.class);
            assertThat(context).hasSingleBean(WireDoctorProperties.class);
        });
    }
}
