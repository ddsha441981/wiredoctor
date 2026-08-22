/*
 * Copyright (c) 2026 Deendayal Kumawat
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package com.wiredoctor;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.metrics.buffering.BufferingApplicationStartup;
import org.springframework.core.env.Environment;
import org.springframework.core.metrics.ApplicationStartup;
import org.springframework.core.metrics.StartupStep;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link WireDoctorEnvironmentPostProcessor}: verifies the
 * politeness contract across all {@link ApplicationStartup} variants.
 */
class WireDoctorEnvironmentPostProcessorTest {

    private final WireDoctorEnvironmentPostProcessor processor = new WireDoctorEnvironmentPostProcessor();

    private void process(SpringApplication app, ApplicationStartup startup) {
        app.setApplicationStartup(startup);
        MockEnvironment env = new MockEnvironment();
        app.setEnvironment(env);
        processor.postProcessEnvironment(env, app);
    }

    // ── ApplicationStartup.DEFAULT (install our own) ──────────────────────────

    @Test
    void replacesDefaultWithWireDoctorBuffering() {
        SpringApplication app = new SpringApplication();
        assertThat(app.getApplicationStartup()).isSameAs(ApplicationStartup.DEFAULT);
        process(app, ApplicationStartup.DEFAULT);
        assertThat(app.getApplicationStartup())
                .isInstanceOf(WireDoctorBufferingApplicationStartup.class);
    }

    // ── Plain BufferingApplicationStartup (transparent upgrade) ────────────────

    @Test
    void plainBufferingStartupIsUpgradedForThreadTagging() {
        BufferingApplicationStartup original = new BufferingApplicationStartup(2048);
        SpringApplication app = new SpringApplication();
        process(app, original);
        assertThat(app.getApplicationStartup())
                .isInstanceOf(WireDoctorBufferingApplicationStartup.class);
        assertThat(app.getApplicationStartup()).isNotSameAs(original);
    }

    // ── WireDoctorBufferingApplicationStartup (idempotent) ─────────────────────

    @Test
    void doesNotDoubleWrapWireDoctorBuffering() {
        WireDoctorBufferingApplicationStartup wdBas =
                new WireDoctorBufferingApplicationStartup(4096);
        SpringApplication app = new SpringApplication();
        process(app, wdBas);
        assertThat(app.getApplicationStartup()).isSameAs(wdBas);
    }

    // ── Foreign BufferingApplicationStartup subclass (not overwritten) ─────────

    @Test
    void foreignBufferingSubclassIsNotOverwritten() {
        BufferingApplicationStartup foreign = new BufferingApplicationStartup(5000) {};
        SpringApplication app = new SpringApplication();
        process(app, foreign);
        assertThat(app.getApplicationStartup()).isSameAs(foreign);
    }

    // ── Foreign non-buffering ApplicationStartup (not overwritten) ──────────────

    @Test
    void foreignNonBufferingStartupIsNotOverwritten() {
        ApplicationStartup foreign = new ApplicationStartup() {
            @Override
            public StartupStep start(String name) {
                return ApplicationStartup.DEFAULT.start(name);
            }
        };
        SpringApplication app = new SpringApplication();
        process(app, foreign);
        assertThat(app.getApplicationStartup()).isSameAs(foreign);
    }
}
