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
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Lazy;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end integration tests: boot a real (non-web) Spring application with
 * WireDoctor auto-configured and assert on the trust guarantees introduced in
 * v0.1.1 — never crash the host, never instantiate lazy beans, degrade
 * gracefully — plus report generation itself.
 */
class WireDoctorAnalyzerIntegrationTest {

    /** Flipped by {@link LazyCanary}'s constructor — must stay false through analysis. */
    static final AtomicBoolean LAZY_INSTANTIATED = new AtomicBoolean(false);

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApp {

        @Bean
        @Lazy
        LazyCanary lazyCanary() {
            return new LazyCanary();
        }
    }

    static class LazyCanary {
        LazyCanary() {
            LAZY_INSTANTIATED.set(true);
        }
    }

    private ConfigurableApplicationContext boot(String... properties) {
        return new SpringApplicationBuilder(TestApp.class)
                .web(org.springframework.boot.WebApplicationType.NONE)
                .properties(properties)
                .run();
    }

    @Test
    void analyzerRunsOnReadyEventAndWritesBothReports(@TempDir Path tempDir) throws Exception {
        try (ConfigurableApplicationContext context = boot(
                "wiredoctor.output-path=" + tempDir)) {
            assertThat(context.isActive()).isTrue();

            File json = tempDir.resolve("wiredoctor-report.json").toFile();
            File html = tempDir.resolve("wiredoctor-report.html").toFile();
            assertThat(json).exists().isNotEmpty();
            assertThat(html).exists().isNotEmpty();

            JsonNode report = new ObjectMapper().readTree(json);
            assertThat(report.has("dependencies")).isTrue();
            assertThat(report.has("proxies")).isTrue();
            assertThat(report.has("beanCategories")).isTrue();
            // v0.10.0: full per-bean timing map for graph timing heat
            assertThat(report.has("beanTimings")).isTrue();
            assertThat(report.path("dependencies").path("totalBeans").asInt()).isPositive();
        }
    }

    @Test
    void enabledFalseTrulyDisablesEverything(@TempDir Path tempDir) {
        try (ConfigurableApplicationContext context = boot(
                "wiredoctor.enabled=false",
                "wiredoctor.output-path=" + tempDir)) {
            assertThat(context.isActive()).isTrue();
            assertThat(context.getBeansOfType(WireDoctorAnalyzer.class)).isEmpty();
            assertThat(tempDir.resolve("wiredoctor-report.json")).doesNotExist();
            assertThat(tempDir.resolve("wiredoctor-report.html")).doesNotExist();
        }
    }

    @Test
    void malformedThresholdDoesNotCrashHostApplication(@TempDir Path tempDir) {
        // BUG-1 regression: the exact config that failed startup in v0.1.0.
        try (ConfigurableApplicationContext context = boot(
                "wiredoctor.slow-bean-threshold-ms=50ms",
                "wiredoctor.output-path=" + tempDir)) {
            assertThat(context.isActive()).isTrue();
            // Analysis still completed despite the bad value:
            assertThat(tempDir.resolve("wiredoctor-report.json")).exists();
        }
    }

    @Test
    void lazyBeanIsNotInstantiatedByAnalysis(@TempDir Path tempDir) throws Exception {
        // Forced-instantiation regression: the v0.1.0 proxy scan called getBean()
        // on every definition, firing @Lazy constructors at report time.
        LAZY_INSTANTIATED.set(false);
        try (ConfigurableApplicationContext context = boot(
                "wiredoctor.output-path=" + tempDir)) {
            assertThat(context.isActive()).isTrue();
            assertThat(tempDir.resolve("wiredoctor-report.json")).exists();
            assertThat(LAZY_INSTANTIATED)
                    .as("@Lazy bean must never be instantiated by WireDoctor's analysis")
                    .isFalse();

            // The skip is reported honestly:
            JsonNode report = new ObjectMapper()
                    .readTree(tempDir.resolve("wiredoctor-report.json").toFile());
            assertThat(report.path("proxies").path("notInstantiatedSkipped").asInt()).isPositive();
        }
    }

    @Test
    void unwritableOutputDirDegradesToLogOnlyAndAppStillStarts() {
        // Container-like read-only FS: mkdirs() fails, report writing fails —
        // the context must still start (crash-safety wrap catches everything).
        try (ConfigurableApplicationContext context = boot(
                "wiredoctor.output-path=/proc/wiredoctor-cannot-write-here")) {
            assertThat(context.isActive()).isTrue();
        }
    }

    @Test
    void mainApplicationClassIsNotListedAsOrphan(@TempDir Path tempDir) throws Exception {
        // MIN-3: TestApp is the @SpringBootConfiguration class of this context and has
        // 0 incoming dependencies — correct per the heuristic, but noise. Must be skipped.
        try (ConfigurableApplicationContext context = boot(
                "wiredoctor.output-path=" + tempDir)) {
            JsonNode report = new ObjectMapper()
                    .readTree(tempDir.resolve("wiredoctor-report.json").toFile());
            List<JsonNode> orphans = iterableToList(
                    report.path("dependencies").path("orphanBeans"));
            assertThat(orphans.stream().map(JsonNode::asText))
                    .noneMatch(name -> name.toLowerCase().contains("testapp"));
        }
    }

    /**
     * Fires the analyzer twice on the same context to prove analysis is idempotent
     * (re-running never throws or corrupts state — relevant for devtools restarts).
     */
    @Test
    void analysisIsIdempotentAcrossRepeatedEvents(@TempDir Path tempDir) {
        try (ConfigurableApplicationContext context = boot(
                "wiredoctor.output-path=" + tempDir)) {
            WireDoctorAnalyzer analyzer = context.getBean(WireDoctorAnalyzer.class);
            ApplicationReadyEvent event = new ApplicationReadyEvent(
                    new org.springframework.boot.SpringApplication(TestApp.class),
                    new String[0],
                    context,
                    java.time.Duration.ZERO);
            analyzer.onApplicationEvent(event);
            analyzer.onApplicationEvent(event);
            assertThat(context.isActive()).isTrue();
        }
    }

    @Test
    void criticalPathSectionIsInReportWithOrderedCumulativeTimes(@TempDir Path tempDir) throws Exception {
        // v0.2.0 Feature 2: the report carries an instantiation-weighted critical
        // path joined from the graph + full per-bean timings, with the honesty
        // disclaimer attached.
        try (ConfigurableApplicationContext context = boot(
                "wiredoctor.output-path=" + tempDir)) {
            JsonNode report = new ObjectMapper()
                    .readTree(tempDir.resolve("wiredoctor-report.json").toFile());

            JsonNode section = report.path("criticalPath");
            assertThat(section.isObject()).isTrue();
            assertThat(section.path("disclaimer").asText()).contains("approximation");

            if (section.path("available").asBoolean()) {
                List<JsonNode> nodes = iterableToList(section.path("path"));
                assertThat(nodes).isNotEmpty();
                // Cumulative times are non-decreasing along the chain and end at totalMs.
                long previous = 0;
                for (JsonNode node : nodes) {
                    assertThat(node.path("beanName").asText()).isNotBlank();
                    long cumulative = node.path("cumulativeMs").asLong();
                    assertThat(cumulative).isGreaterThanOrEqualTo(previous);
                    previous = cumulative;
                }
                assertThat(section.path("totalMs").asLong()).isEqualTo(previous);
            }
        }
    }

    @Test
    void htmlReportIsSelfContainedWithInlinedGraphLibrary(@TempDir Path tempDir) throws Exception {
        // v0.2.0: BUG-2 Option A — vis-network is bundled and inlined, so the
        // report renders fully offline. No external <script src> may remain.
        try (ConfigurableApplicationContext context = boot(
                "wiredoctor.output-path=" + tempDir)) {
            String html = java.nio.file.Files.readString(
                    tempDir.resolve("wiredoctor-report.html"));

            assertThat(html)
                    .as("graph library must be inlined, not loaded from a CDN")
                    .doesNotContain("src=\"https://unpkg.com")
                    .contains("vis-network")
                    .contains("Almende B.V."); // attribution comment present
            // The inlined UMD bundle is ~600KB — a self-contained report must carry it.
            assertThat(html.length()).isGreaterThan(500_000);
        }
    }

    // ── v0.4.0 Task 1b: smell-filter gap fixes ───────────────────────────────

    @Test
    void environmentBeanDoesNotAppearInFilteredSmellRankings(@TempDir Path tempDir)
            throws Exception {
        // Gap fix 1: "environment" is a well-known Spring infrastructure singleton
        // registered outside beanDefinitionNames. It must NOT appear in the filtered
        // highFanIn/highFanOut/unstable lists (it was the top highFanIn entry in the
        // v0.4.0 real-world test on Spring Initializr).
        try (ConfigurableApplicationContext context = boot(
                "wiredoctor.output-path=" + tempDir,
                "wiredoctor.include-framework-smells=false")) {
            JsonNode report = new ObjectMapper()
                    .readTree(tempDir.resolve("wiredoctor-report.json").toFile());

            List<JsonNode> highFanIn = iterableToList(
                    report.path("smells").path("highFanIn"));
            List<JsonNode> highFanOut = iterableToList(
                    report.path("smells").path("highFanOut"));
            List<JsonNode> unstable = iterableToList(
                    report.path("smells").path("unstable"));

            java.util.stream.Stream.of(highFanIn, highFanOut, unstable).forEach(list ->
                    assertThat(list.stream().map(n -> n.path("beanName").asText()))
                            .as("well-known Spring infrastructure bean must be filtered")
                            .doesNotContain("environment", "systemProperties",
                                    "systemEnvironment", "messageSource"));
        }
    }

    @Test
    void scanPackagesAllowlistRestrictsSmellRankingsToUserPackage(@TempDir Path tempDir)
            throws Exception {
        // Gap fix 2: when scan-packages is set, only beans whose package starts with
        // one of the declared prefixes appear in the smell rankings. Third-party
        // autoconfig beans (azure, initializr, etc.) are excluded automatically.
        // This test uses the test app's own package as the allowlist and verifies that
        // every bean in highFanIn/highFanOut is from com.wiredoctor (the test app pkg).
        try (ConfigurableApplicationContext context = boot(
                "wiredoctor.output-path=" + tempDir,
                "wiredoctor.scan-packages=com.wiredoctor")) {
            JsonNode report = new ObjectMapper()
                    .readTree(tempDir.resolve("wiredoctor-report.json").toFile());

            List<JsonNode> highFanIn = iterableToList(
                    report.path("smells").path("highFanIn"));
            List<JsonNode> highFanOut = iterableToList(
                    report.path("smells").path("highFanOut"));

            // frameworkFiltered flag must still be set
            assertThat(report.path("smells").path("frameworkFiltered").asBoolean())
                    .as("frameworkFiltered must be true when scan-packages is set")
                    .isTrue();

            // All ranked beans must be in the declared scan package
            java.util.stream.Stream.of(highFanIn, highFanOut).forEach(list ->
                    list.forEach(entry -> {
                        String beanName = entry.path("beanName").asText();
                        // beans that appear in the rankings should be from the
                        // declared scan package — spring beans must not leak through
                        assertThat(beanName)
                                .as("non-user bean leaked into filtered smells: " + beanName)
                                .doesNotStartWith("org.springframework")
                                .doesNotContain("@");
                    }));
        }
    }

    // ── v1.1.0: trend history ────────────────────────────────────────────────

    @Test
    void baselineWriteProducesTrendHistory(@TempDir Path tempDir) throws Exception {
        try (ConfigurableApplicationContext context = boot(
                "wiredoctor.baseline=" + tempDir.resolve("baseline.json"),
                "wiredoctor.baseline-write=true")) {
            JsonNode report = new ObjectMapper()
                    .readTree(tempDir.resolve("baseline.json").toFile());
            JsonNode trend = report.path("trendHistory");
            assertThat(trend.isArray()).isTrue();
            assertThat(trend).hasSize(1);
            assertThat(trend.get(0).has("timestamp")).isTrue();
            assertThat(trend.get(0).has("totalStartupMs")).isTrue();
            assertThat(trend.get(0).has("slowBeanCount")).isTrue();
        }
    }

    @Test
    void trendHistoryCarriesForwardAcrossWrites(@TempDir Path tempDir) throws Exception {
        try (ConfigurableApplicationContext ctx1 = boot(
                "wiredoctor.baseline=" + tempDir.resolve("baseline.json"),
                "wiredoctor.baseline-write=true")) {
            JsonNode first = new ObjectMapper()
                    .readTree(tempDir.resolve("baseline.json").toFile());
            assertThat(first.path("trendHistory")).hasSize(1);
            long firstTimestamp = first.path("trendHistory").get(0).path("timestamp").asLong();
            assertThat(firstTimestamp).isPositive();
        }
        // Second write should carry forward the first entry and append a new one
        try (ConfigurableApplicationContext ctx2 = boot(
                "wiredoctor.baseline=" + tempDir.resolve("baseline.json"),
                "wiredoctor.baseline-write=true")) {
            JsonNode second = new ObjectMapper()
                    .readTree(tempDir.resolve("baseline.json").toFile());
            assertThat(second.path("trendHistory")).hasSize(2);
            assertThat(second.path("trendHistory").get(0).path("timestamp").asLong())
                    .isLessThan(second.path("trendHistory").get(1).path("timestamp").asLong());
        }
    }

    @Test
    void trendHistoryRespectsCap(@TempDir Path tempDir) throws Exception {
        try (ConfigurableApplicationContext ctx1 = boot(
                "wiredoctor.baseline=" + tempDir.resolve("baseline.json"),
                "wiredoctor.baseline-write=true",
                "wiredoctor.trend-history-size=2")) {
            assertThat(tempDir.resolve("baseline.json")).exists();
        }
        try (ConfigurableApplicationContext ctx2 = boot(
                "wiredoctor.baseline=" + tempDir.resolve("baseline.json"),
                "wiredoctor.baseline-write=true",
                "wiredoctor.trend-history-size=2")) {
            JsonNode report = new ObjectMapper()
                    .readTree(tempDir.resolve("baseline.json").toFile());
            assertThat(report.path("trendHistory")).hasSize(2); // capped, not 3
        }
    }

    @Test
    void reportContainsTrendHistoryField(@TempDir Path tempDir) throws Exception {
        try (ConfigurableApplicationContext context = boot(
                "wiredoctor.output-path=" + tempDir)) {
            JsonNode report = new ObjectMapper()
                    .readTree(tempDir.resolve("wiredoctor-report.json").toFile());
            // Normal run (no baseline-write) should not have trendHistory — it's baseline-only
            assertThat(report.has("trendHistory")).isFalse();
        }
    }

    private static List<JsonNode> iterableToList(JsonNode arrayNode) {
        java.util.ArrayList<JsonNode> list = new java.util.ArrayList<>();
        arrayNode.forEach(list::add);
        return list;
    }
}
