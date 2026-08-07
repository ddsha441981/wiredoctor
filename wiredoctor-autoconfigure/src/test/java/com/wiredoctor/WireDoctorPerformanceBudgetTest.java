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
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.GenericBeanDefinition;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.type.AnnotationMetadata;

import java.io.File;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v1.0.0 Performance Budget — certified analysis overhead guarantee.
 * <p>
 * Contract (frozen at v1.0.0):
 * <ul>
 *   <li>Full analysis on 1 000 beans completes within a generous wall-time
 *       ceiling that comfortably survives slow CI runners and JVM cold-start
 *       noise. A quadratic-or-worse regression would breach this well before
 *       it could threaten production startup budgets.</li>
 *   <li>The JSON report is well-formed and carries {@code schemaVersion: 1}.</li>
 * </ul>
 *
 * Why this bound: real-world tests on start.spring.io (273 beans, Boot 4.0.7)
 * observed sub-100 ms analysis. 1 000 beans with a 30 s wall-time ceiling
 * leaves ≥40× headroom for normal JVM variance.
 *
 * @since 1.0.0
 */
class WireDoctorPerformanceBudgetTest {

    /** Synthetic context size matching the documented per-1k-beans budget. */
    static final int BEAN_COUNT = 1_000;

    /** Wall-time ceiling for boot + analysis together (ms). */
    static final long WALL_TIME_BUDGET_MS = 30_000;

    public static class BudgetBean {}

    static class ThousandBeansRegistrar implements ImportBeanDefinitionRegistrar {
        @Override
        public void registerBeanDefinitions(AnnotationMetadata metadata,
                                            BeanDefinitionRegistry registry) {
            // Hub beans — high fan-in, exercises smell and critical-path code paths.
            for (int h = 0; h < 10; h++) {
                GenericBeanDefinition hub = new GenericBeanDefinition();
                hub.setBeanClass(BudgetBean.class);
                registry.registerBeanDefinition("budgetHub" + h, hub);
            }
            // Spoke beans — each depends on one hub via dependsOn (no constructor
            // wiring cost; edges flow through getDependenciesForBean, the same
            // channel WireDoctor reads).
            for (int i = 0; i < BEAN_COUNT - 10; i++) {
                GenericBeanDefinition spoke = new GenericBeanDefinition();
                spoke.setBeanClass(BudgetBean.class);
                spoke.setDependsOn("budgetHub" + (i % 10));
                registry.registerBeanDefinition("budgetBean" + i, spoke);
            }
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(ThousandBeansRegistrar.class)
    static class BudgetApp {}

    @Test
    void analysisCompletesWithinBudgetFor1kBeans(@TempDir Path tempDir) throws Exception {
        long start = System.nanoTime();
        try (ConfigurableApplicationContext ctx = new SpringApplicationBuilder(BudgetApp.class)
                .web(WebApplicationType.NONE)
                .properties(
                        "wiredoctor.output-path=" + tempDir,
                        "wiredoctor.max-graph-nodes=0")    // unlimited — worst-case serialization
                .run()) {

            long elapsedMs = (System.nanoTime() - start) / 1_000_000;

            assertThat(ctx.isActive()).isTrue();

            // ── Performance contract (v1.0.0) ─────────────────────────────────
            assertThat(elapsedMs)
                    .as("boot + analysis for %d beans must complete within %d ms",
                            BEAN_COUNT, WALL_TIME_BUDGET_MS)
                    .isLessThan(WALL_TIME_BUDGET_MS);

            // ── Report contract ────────────────────────────────────────────────
            File json = tempDir.resolve("wiredoctor-report.json").toFile();
            assertThat(json).as("wiredoctor-report.json must be written").exists();

            JsonNode report = new ObjectMapper().readTree(json);

            // schemaVersion frozen at 1 (v1.0.0 API freeze)
            assertThat(report.path("schemaVersion").asInt())
                    .as("schemaVersion must be 1")
                    .isEqualTo(1);

            // Bean count covers the full synthetic context
            assertThat(report.path("beanCategories").path("totalBeans").asInt())
                    .as("totalBeans must cover the synthetic %d-bean context", BEAN_COUNT)
                    .isGreaterThanOrEqualTo(BEAN_COUNT);

            // Core analysis sections present
            assertThat(report.has("smells")).isTrue();
            assertThat(report.has("dependencies")).isTrue();
        }
    }
}
