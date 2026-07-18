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
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.context.annotation.Import;
import org.springframework.core.type.AnnotationMetadata;

import java.io.File;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Large-context hardening benchmark (v0.3.0): boots a context with 5,000
 * programmatically registered beans and asserts that
 * <ul>
 *   <li>analysis completes without OOM or pathological slowdown,</li>
 *   <li>the serialized graph is truncated to {@code max-graph-nodes} with
 *       honest metadata, and</li>
 *   <li>full-graph sections (totals, smells) still reflect all beans.</li>
 * </ul>
 * Beans are registered via a {@link ImportBeanDefinitionRegistrar} (no
 * classpath scanning — deterministic and fast). Each bean depends on a hub
 * bean chosen by modulo, giving hubs high fan-in so the top-N-by-fan-in cut
 * is observable.
 */
class WireDoctorLargeContextTest {

    static final int BEAN_COUNT = 5_000;
    static final int HUB_COUNT = 20;

    /** Marker type for the synthetic beans. */
    public static class SyntheticBean {
    }

    static class FiveThousandBeansRegistrar implements ImportBeanDefinitionRegistrar {
        @Override
        public void registerBeanDefinitions(AnnotationMetadata metadata,
                                            BeanDefinitionRegistry registry) {
            for (int i = 0; i < HUB_COUNT; i++) {
                GenericBeanDefinition def = new GenericBeanDefinition();
                def.setBeanClass(SyntheticBean.class);
                registry.registerBeanDefinition("synHub" + i, def);
            }
            for (int i = 0; i < BEAN_COUNT - HUB_COUNT; i++) {
                GenericBeanDefinition def = new GenericBeanDefinition();
                def.setBeanClass(SyntheticBean.class);
                // dependsOn produces edges in getDependenciesForBean — the same
                // channel WireDoctor reads — without constructor wiring cost.
                def.setDependsOn("synHub" + (i % HUB_COUNT));
                registry.registerBeanDefinition("synBean" + i, def);
            }
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(FiveThousandBeansRegistrar.class)
    static class LargeApp {
    }

    @Test
    void fiveThousandBeansAnalyzeFastAndTruncateHonestly(@TempDir Path tempDir) throws Exception {
        long start = System.nanoTime();
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(LargeApp.class)
                .web(WebApplicationType.NONE)
                .properties(
                        "wiredoctor.output-path=" + tempDir,
                        "wiredoctor.max-graph-nodes=500")
                .run()) {
            long bootAndAnalyzeMs = (System.nanoTime() - start) / 1_000_000;
            assertThat(context.isActive()).isTrue();
            // Plan budget: analysis < 10s. Boot + analysis together stays
            // comfortably under 30s even on slow CI runners; a hang or
            // quadratic blowup would trip this.
            assertThat(bootAndAnalyzeMs)
                    .as("boot + analysis wall time for %d beans", BEAN_COUNT)
                    .isLessThan(30_000);

            File json = tempDir.resolve("wiredoctor-report.json").toFile();
            assertThat(json).exists();
            JsonNode report = new ObjectMapper().readTree(json);
            JsonNode deps = report.path("dependencies");

            // Full-graph sections are NOT capped:
            assertThat(deps.path("totalBeans").asInt()).isGreaterThanOrEqualTo(BEAN_COUNT);

            // Serialized graph IS capped, with honest metadata:
            assertThat(deps.path("graphTruncated").asBoolean()).isTrue();
            assertThat(deps.path("graphNodesKept").asInt()).isEqualTo(500);
            assertThat(deps.path("graphNodesTotal").asInt()).isGreaterThanOrEqualTo(BEAN_COUNT);
            assertThat(deps.path("graph").size()).isEqualTo(500);

            // The high-fan-in hubs survived the cut (that's the point of top-N):
            assertThat(deps.path("graph").has("synHub0")).isTrue();

            // Smells were computed on the full graph: each hub has ~249 dependents.
            JsonNode highFanIn = report.path("smells").path("highFanIn");
            assertThat(highFanIn.size()).isPositive();
            assertThat(highFanIn.get(0).path("inDegree").asInt()).isGreaterThan(200);

            // JSON stays reviewable: capped graph keeps the file well under
            // what 5k nodes with edges would produce.
            assertThat(json.length()).isLessThan(5_000_000);

            // HTML carries the truncation banner logic and renders the capped graph.
            String html = java.nio.file.Files.readString(tempDir.resolve("wiredoctor-report.html"));
            assertThat(html).contains("graphTruncated");
        }
    }

    @Test
    void unlimitedModeStillSerializesEverything(@TempDir Path tempDir) throws Exception {
        // max-graph-nodes=0 restores pre-v0.3.0 behavior — the escape hatch
        // must keep working for users who want the full graph regardless.
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(LargeApp.class)
                .web(WebApplicationType.NONE)
                .properties(
                        "wiredoctor.output-path=" + tempDir,
                        "wiredoctor.max-graph-nodes=0")
                .run()) {
            JsonNode deps = new ObjectMapper()
                    .readTree(tempDir.resolve("wiredoctor-report.json").toFile())
                    .path("dependencies");
            assertThat(deps.path("graphTruncated").asBoolean()).isFalse();
            assertThat(deps.path("graph").size()).isGreaterThanOrEqualTo(BEAN_COUNT);
        }
    }
}
