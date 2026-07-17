/*
 * Copyright (c) 2026 Deendayal Kumawat
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package com.wiredoctor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.metrics.buffering.BufferingApplicationStartup;
import org.springframework.boot.context.metrics.buffering.StartupTimeline;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.metrics.ApplicationStartup;
import org.springframework.core.metrics.StartupStep;
import org.springframework.util.ClassUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Files;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Core analysis engine for the WireDoctor diagnostic suite.
 * <p>
 * Listens for the {@link ApplicationReadyEvent} to extract and aggregate
 * application startup metrics, bean dependency graphs, cycle detections, and
 * proxy layer overheads. The collected metrics are then written to both JSON 
 * and HTML report formats, alongside a standard SLF4J console summary.
 *
 * @author Deendayal Kumawat
 * @since 0.1.0
 */
public class WireDoctorAnalyzer implements ApplicationListener<ApplicationReadyEvent> {

    private static final Logger log = LoggerFactory.getLogger(WireDoctorAnalyzer.class);

    private final WireDoctorProperties properties;

    /**
     * Creates the analyzer with its typed configuration.
     *
     * @param properties the bound {@code wiredoctor.*} configuration
     */
    public WireDoctorAnalyzer(WireDoctorProperties properties) {
        this.properties = properties;
    }

    /**
     * Returns whether the bean is the application's own {@code @SpringBootApplication}
     * (or plain {@code @SpringBootConfiguration}) main class.
     *
     * @param beanFactory the bean factory to resolve the bean type from
     * @param beanName    the bean to check
     * @return {@code true} when the bean's user class carries {@code @SpringBootConfiguration}
     */
    private static boolean isSpringBootApplicationClass(ConfigurableListableBeanFactory beanFactory,
                                                        String beanName) {
        try {
            Class<?> beanType = beanFactory.getType(beanName);
            if (beanType == null) return false;
            Class<?> userClass = ClassUtils.getUserClass(beanType); // unwrap CGLIB enhancement
            // @SpringBootApplication is meta-annotated with @SpringBootConfiguration.
            return AnnotatedElementUtils.hasAnnotation(userClass, SpringBootConfiguration.class);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Executes the comprehensive context analysis upon successful application startup.
     * <p>
     * The entire analysis is defensively wrapped: no failure inside WireDoctor
     * (bad configuration, unexpected Spring state, reflection errors) is ever
     * allowed to propagate and affect the host application.
     * <p>
     * The single deliberate exception: when the user opted into a regression
     * gate via {@code wiredoctor.fail-on} and it trips, a
     * {@link WireDoctorRegressionException} is thrown AFTER analysis completes
     * so a CI run fails with a non-zero exit code.
     *
     * @param event the event indicating that the application context is fully ready
     */
    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        WireDoctorRegressionException trippedGate = null;
        try {
            trippedGate = analyze(event);
        } catch (Throwable t) {
            // Deliberately catch Throwable: a diagnostic tool must NEVER take down the host app.
            log.error(WireDoctorMessages.ANALYSIS_FAILED, t);
        }
        if (trippedGate != null) {
            // Opt-in CI gate: the ONLY path where WireDoctor fails the host — by explicit request.
            throw trippedGate;
        }
    }

    private WireDoctorRegressionException analyze(ApplicationReadyEvent event) {
        log.info(WireDoctorMessages.BANNER_TOP);
        log.info(WireDoctorMessages.BANNER_TEXT);
        log.info(WireDoctorMessages.BANNER_BOTTOM);

        ConfigurableApplicationContext context = event.getApplicationContext();
        ConfigurableListableBeanFactory beanFactory = context.getBeanFactory();

        // ── Feature 1: Resolve output directory ──────────────────────────────
        File outputDir = new File(properties.getOutputPath());
        if (!outputDir.exists()) outputDir.mkdirs();
        log.info(WireDoctorMessages.OUTPUT_PATH_INFO, outputDir.getAbsolutePath());

        // ── Feature 3: Slow bean threshold (lenient — never crash on bad config) ──
        long slowBeanThresholdMs = properties.resolveSlowBeanThresholdMs();
        if (!properties.isSlowBeanThresholdValid()) {
            log.warn(WireDoctorMessages.BAD_THRESHOLD,
                     properties.getSlowBeanThresholdMs(),
                     WireDoctorProperties.DEFAULT_SLOW_BEAN_THRESHOLD_MS);
        }

        Map<String, Object> report = new LinkedHashMap<>();

        // ── Section 1: Startup timing + Slow bean instantiation ──────────────
        ApplicationStartup applicationStartup = context.getApplicationStartup();
        List<Map<String, Object>> slowSteps = new ArrayList<>();
        List<Map<String, Object>> slowBeans = new ArrayList<>();
        // Feature (v0.2.0): full per-bean instantiation times for the critical path —
        // ALL beans, not just those above the slow threshold.
        Map<String, Long> beanInstantiationMs = new HashMap<>();

        if (applicationStartup instanceof BufferingApplicationStartup bufferingStartup) {
            StartupTimeline timeline = bufferingStartup.getBufferedTimeline();

            // Top 20 slowest steps (existing)
            slowSteps = timeline.getEvents().stream()
                    .sorted((e1, e2) -> e2.getDuration().compareTo(e1.getDuration()))
                    .limit(20)
                    .map(e -> {
                        Map<String, Object> stepInfo = new LinkedHashMap<>();
                        stepInfo.put("name", e.getStartupStep().getName());
                        stepInfo.put("durationMs", e.getDuration().toMillis());
                        Map<String, String> tags = new LinkedHashMap<>();
                        e.getStartupStep().getTags()
                         .forEach(t -> tags.put(t.getKey(), t.getValue()));
                        stepInfo.put("tags", tags);
                        return stepInfo;
                    })
                    .collect(Collectors.toList());

            // Feature 3: Slow bean instantiation (spring.beans.instantiate steps)
            for (StartupTimeline.TimelineEvent e : timeline.getEvents()) {
                if (!"spring.beans.instantiate".equals(e.getStartupStep().getName())) continue;
                long durationMs = e.getDuration().toMillis();

                String beanName = "unknown";
                for (StartupStep.Tag tag : e.getStartupStep().getTags()) {
                    if ("beanName".equals(tag.getKey())) {
                        beanName = tag.getValue();
                        break;
                    }
                }
                // Instantiate steps nest (a bean's constructor triggers its deps),
                // so keep the max per bean name rather than overwriting.
                beanInstantiationMs.merge(beanName, durationMs, Math::max);

                if (durationMs < slowBeanThresholdMs) continue;
                Map<String, Object> beanInfo = new LinkedHashMap<>();
                beanInfo.put("beanName", beanName);
                beanInfo.put("durationMs", durationMs);
                slowBeans.add(beanInfo);
            }
            // Sort slowest first
            slowBeans.sort((a, b) ->
                    Long.compare((Long) b.get("durationMs"), (Long) a.get("durationMs")));

        } else {
            log.warn(WireDoctorMessages.STARTUP_NOT_BUFFERING_WARNING,
                     applicationStartup.getClass().getName());
        }

        report.put("startupSlowestSteps", slowSteps);
        report.put("slowBeans", slowBeans);

        // ── Section 2: Feature 2 — Bean category summary ─────────────────────
        String[] beanNames = beanFactory.getBeanDefinitionNames();
        int roleApplication  = 0;
        int roleSupport      = 0;
        int roleInfra        = 0;
        int userDefined      = 0;
        int frameworkOwned   = 0;

        // Package prefixes that indicate "framework-owned" beans
        List<String> frameworkPkgs = List.of(
            "org.springframework", "org.apache", "com.sun",
            "java.", "javax.", "jakarta.", "io.netty",
            "com.fasterxml", "io.micrometer"
        );

        for (String beanName : beanNames) {
            // Role classification
            try {
                BeanDefinition bd = beanFactory.getBeanDefinition(beanName);
                switch (bd.getRole()) {
                    case BeanDefinition.ROLE_APPLICATION  -> roleApplication++;
                    case BeanDefinition.ROLE_SUPPORT      -> roleSupport++;
                    case BeanDefinition.ROLE_INFRASTRUCTURE -> roleInfra++;
                }
            } catch (Exception ignored) {}

            // User-defined vs framework (package heuristic)
            try {
                Class<?> beanType = beanFactory.getType(beanName);
                if (beanType != null && beanType.getPackage() != null) {
                    String pkg = beanType.getPackage().getName();
                    boolean isFramework = frameworkPkgs.stream().anyMatch(pkg::startsWith);
                    if (isFramework) frameworkOwned++;
                    else userDefined++;
                }
            } catch (Exception ignored) {}
        }

        Map<String, Object> beanCategories = new LinkedHashMap<>();
        beanCategories.put("totalBeans",       beanNames.length);
        beanCategories.put("roleApplication",  roleApplication);
        beanCategories.put("roleSupport",       roleSupport);
        beanCategories.put("roleInfrastructure", roleInfra);
        beanCategories.put("userDefined",       userDefined);
        beanCategories.put("frameworkOwned",    frameworkOwned);
        report.put("beanCategories", beanCategories);

        // ── Section 3: Dependency graph, proxies, cycles, orphans ────────────
        Map<String, String[]> graph = new HashMap<>();
        List<String> cglibProxies = new ArrayList<>();
        List<String> jdkProxies   = new ArrayList<>();
        int proxyScanSkipped = 0;
        int totalDependencies = 0;

        for (String beanName : beanNames) {
            // Zero-intrusion proxy scan: only inspect beans that are ALREADY instantiated.
            // Calling getBean() here would force-instantiate @Lazy singletons, prototypes
            // and FactoryBean products — mutating application state at report time.
            try {
                if (beanFactory.containsSingleton(beanName)) {
                    Object bean = beanFactory.getSingleton(beanName);
                    if (AopUtils.isCglibProxy(bean))           cglibProxies.add(beanName);
                    else if (AopUtils.isJdkDynamicProxy(bean)) jdkProxies.add(beanName);
                } else {
                    proxyScanSkipped++;
                }
            } catch (Exception ignored) {}

            String[] dependencies = beanFactory.getDependenciesForBean(beanName);
            graph.put(beanName, dependencies);
            totalDependencies += dependencies.length;
        }

        List<List<String>> cycles = CycleDetector.detectCycles(graph);

        Set<String> allDependencies = new HashSet<>();
        graph.values().forEach(deps -> allDependencies.addAll(Arrays.asList(deps)));

        String scanPackages = properties.getScanPackages();
        List<String> orphanBeans = new ArrayList<>();

        for (String beanName : beanNames) {
            if (allDependencies.contains(beanName)) continue;
            if (beanName.toLowerCase().startsWith("wiredoctor")) continue;
            // The @SpringBootApplication main class legitimately has 0 incoming
            // dependencies — listing it as an "orphan" is correct per the heuristic
            // but pure noise, so skip it.
            if (isSpringBootApplicationClass(beanFactory, beanName)) continue;
            boolean include;
            try {
                Class<?> beanType = beanFactory.getType(beanName);
                if (beanType != null && beanType.getPackage() != null) {
                    String pkg = beanType.getPackage().getName();
                    if (scanPackages != null && !scanPackages.isEmpty()) {
                        include = Arrays.stream(scanPackages.split(","))
                                .map(String::trim)
                                .anyMatch(pkg::startsWith);
                    } else {
                        include = frameworkPkgs.stream().noneMatch(pkg::startsWith);
                    }
                } else {
                    include = scanPackages == null; // unknown package: include only in default mode
                }
            } catch (Exception ignored) {
                include = false;
            }
            if (include) orphanBeans.add(beanName);
        }

        Map<String, Object> proxyInfo = new LinkedHashMap<>();
        proxyInfo.put("cglibCount", cglibProxies.size());
        proxyInfo.put("jdkCount",   jdkProxies.size());
        proxyInfo.put("cglibBeans", cglibProxies);
        proxyInfo.put("jdkBeans",   jdkProxies);
        // Beans not yet instantiated (@Lazy, prototype, unresolved FactoryBean products)
        // are deliberately NOT instantiated for this scan — honest count instead.
        proxyInfo.put("notInstantiatedSkipped", proxyScanSkipped);
        report.put("proxies", proxyInfo);

        Map<String, Object> dependencyInfo = new LinkedHashMap<>();
        dependencyInfo.put("totalBeans",      beanNames.length);
        dependencyInfo.put("totalEdges",      totalDependencies);
        dependencyInfo.put("cyclesCount",     cycles.size());
        dependencyInfo.put("cycles",          cycles);
        dependencyInfo.put("orphanBeansCount", orphanBeans.size());
        dependencyInfo.put("orphanBeans",     orphanBeans);
        dependencyInfo.put("graph",           graph);
        report.put("dependencies", dependencyInfo);

        // ── Feature (v0.3.0): Counterfactual @Lazy Simulator ─────────────────
        // For each detected cycle, rank which beans would break it if marked
        // @Lazy — most cycles broken first, smallest blast radius (fan-in) next.
        // Pure computation over the graph we already built; empty when no cycles.
        List<WireDoctorLazySimulator.LazySuggestion> lazySuggestions =
                WireDoctorLazySimulator.suggestLazyPlacements(graph, cycles);
        report.put("lazySuggestions", WireDoctorLazySimulator.toReportList(lazySuggestions));

        // ── Feature (v0.3.0): Architecture Smell Metrics ─────────────────────
        // Fan-in/fan-out hotspots + Martin's instability metric, computed on
        // the live resolved graph (what Spring actually wired — proxies,
        // conditionals, profiles included). Pure graph functions, no heuristics.
        Map<String, Object> smells = WireDoctorSmellDetector.toReportMap(graph);
        report.put("smells", smells);

        // ── Feature (v0.2.0): Startup Critical Path ──────────────────────────
        // Longest instantiation-weighted dependency chain — what actually gated
        // readiness, not a flat sorted list. Pure computation over data we
        // already have; empty when timings are unavailable (non-buffering startup).
        List<WireDoctorCriticalPath.PathNode> criticalPath =
                WireDoctorCriticalPath.compute(graph, beanInstantiationMs);
        long readinessMs = 0;
        try {
            if (event.getTimeTaken() != null) {
                readinessMs = event.getTimeTaken().toMillis();
            }
        } catch (Exception ignored) {}
        report.put("criticalPath", WireDoctorCriticalPath.toReportMap(criticalPath, readinessMs));

        // ── Write JSON ────────────────────────────────────────────────────────
        try {
            ObjectMapper mapper = new ObjectMapper();
            mapper.enable(SerializationFeature.INDENT_OUTPUT);
            String jsonString = mapper.writeValueAsString(report);

            File reportFile = new File(outputDir, "wiredoctor-report.json");
            Files.writeString(reportFile.toPath(), jsonString);
            log.info(WireDoctorMessages.SAVED_JSON_REPORT, reportFile.getAbsolutePath());

            // Pass outputDir to HTML reporter
            WireDoctorHtmlReporter.generateHtmlReport(jsonString, outputDir);

        } catch (Exception e) {
            log.error(WireDoctorMessages.FAILED_WRITE_JSON, e.getMessage());
        }

        // ── Feature (v0.2.0): Architectural Regression Guard ─────────────────
        WireDoctorRegressionException trippedGate =
                runRegressionGuard(graph, cycles, report, outputDir);

        // ── Console summary ───────────────────────────────────────────────────
        log.info(WireDoctorMessages.SLOWEST_STEPS_HEADER);
        slowSteps.stream().limit(5).forEach(s ->
                log.info(WireDoctorMessages.SLOWEST_STEP_ITEM, s.get("name"), s.get("durationMs")));

        log.info(WireDoctorMessages.CYCLES_HEADER, cycles.size());
        for (List<String> cycle : cycles) {
            log.info(WireDoctorMessages.CYCLE_ITEM, String.join(" -> ", cycle), cycle.get(0));
            log.info(WireDoctorMessages.CYCLE_NOTE);
        }

        // Feature (v0.3.0): @Lazy suggestions console output (top 5)
        if (!lazySuggestions.isEmpty()) {
            log.info(WireDoctorMessages.LAZY_SUGGESTIONS_HEADER);
            int rank = 1;
            for (WireDoctorLazySimulator.LazySuggestion s
                    : lazySuggestions.subList(0, Math.min(5, lazySuggestions.size()))) {
                log.info(WireDoctorMessages.LAZY_SUGGESTION_ITEM,
                         rank++, s.beanName, s.breaksCycles.size(), s.downstreamImpact);
            }
        }

        // Feature (v0.3.0): architecture smell console output (top 3 per direction)
        logSmellSummary(smells);

        log.info(WireDoctorMessages.PROXY_HEADER);
        log.info(WireDoctorMessages.PROXY_CGLIB_ITEM, cglibProxies.size());
        log.info(WireDoctorMessages.PROXY_JDK_ITEM,   jdkProxies.size());
        if (proxyScanSkipped > 0) {
            log.info(WireDoctorMessages.PROXY_SKIPPED_ITEM, proxyScanSkipped);
        }

        // Feature 2: Bean category summary console output
        log.info(WireDoctorMessages.BEAN_CATEGORIES_HEADER);
        log.info(WireDoctorMessages.BEAN_USER_DEFINED, userDefined);
        log.info(WireDoctorMessages.BEAN_FRAMEWORK,    frameworkOwned);
        log.info(WireDoctorMessages.BEAN_ROLE_APP,     roleApplication);
        log.info(WireDoctorMessages.BEAN_ROLE_INFRA,   roleInfra);

        // Feature 3: Slow bean console output
        log.info(WireDoctorMessages.SLOW_BEANS_HEADER, slowBeanThresholdMs);
        if (slowBeans.isEmpty()) {
            log.info(WireDoctorMessages.SLOW_BEANS_NONE);
        } else {
            slowBeans.stream().limit(10).forEach(b ->
                    log.info(WireDoctorMessages.SLOW_BEAN_ITEM,
                             b.get("beanName"), b.get("durationMs")));
        }

        // Feature (v0.2.0): Critical path console output
        if (!criticalPath.isEmpty()) {
            long pathMs = criticalPath.get(criticalPath.size() - 1).cumulativeMs();
            if (readinessMs > 0) {
                log.info(WireDoctorMessages.CRITICAL_PATH_HEADER_PCT,
                         Math.round(pathMs * 1000.0 / readinessMs) / 10.0, pathMs);
            } else {
                log.info(WireDoctorMessages.CRITICAL_PATH_HEADER, pathMs);
            }
            log.info(WireDoctorMessages.CRITICAL_PATH_CHAIN,
                     WireDoctorCriticalPath.render(criticalPath));
            log.info(WireDoctorMessages.CRITICAL_PATH_NOTE);
        }

        log.info(WireDoctorMessages.BANNER_END);
        return trippedGate;
    }

    /**
     * Console summary for the {@code smells} report section (v0.3.0):
     * top 3 fan-in hotspots, top 3 fan-out hotspots, and any beans over the
     * instability threshold. Sections with no entries are skipped entirely.
     */
    @SuppressWarnings("unchecked")
    private void logSmellSummary(Map<String, Object> smells) {
        List<Map<String, Object>> highFanIn  = (List<Map<String, Object>>) smells.get("highFanIn");
        List<Map<String, Object>> highFanOut = (List<Map<String, Object>>) smells.get("highFanOut");
        List<Map<String, Object>> unstable   = (List<Map<String, Object>>) smells.get("unstable");

        if (highFanIn.isEmpty() && highFanOut.isEmpty() && unstable.isEmpty()) {
            return;
        }
        log.info(WireDoctorMessages.SMELLS_HEADER);
        highFanIn.stream().limit(3).forEach(e ->
                log.info(WireDoctorMessages.SMELL_FAN_IN_ITEM, e.get("beanName"), e.get("inDegree")));
        highFanOut.stream().limit(3).forEach(e ->
                log.info(WireDoctorMessages.SMELL_FAN_OUT_ITEM, e.get("beanName"), e.get("outDegree")));
        unstable.stream().limit(3).forEach(e ->
                log.info(WireDoctorMessages.SMELL_UNSTABLE_ITEM,
                         e.get("beanName"), e.get("instability"), e.get("fanIn"), e.get("fanOut")));
    }

    /**
     * Architectural Regression Guard (v0.2.0).
     * <p>
     * In {@code baseline-write} mode, saves the current report as the new
     * baseline. Otherwise, when a baseline path is configured, diffs the
     * current graph against it, writes {@code wiredoctor-diff.json}, logs a
     * summary, and — only if the user opted in via
     * {@code wiredoctor.fail-on=new-cycle} — returns the gate exception for
     * the caller to throw after analysis completes.
     * <p>
     * Every failure path here degrades gracefully: missing baseline → info
     * log, unreadable baseline → warning, write failure → error log. The gate
     * exception is the only intentional signal that leaves this method.
     *
     * @param graph     the current dependency graph
     * @param cycles    the current detected cycles
     * @param report    the full current report (persisted as baseline in write mode)
     * @param outputDir directory for {@code wiredoctor-diff.json}
     * @return the gate exception to throw, or {@code null} when no gate tripped
     */
    private WireDoctorRegressionException runRegressionGuard(Map<String, String[]> graph,
                                                             List<List<String>> cycles,
                                                             Map<String, Object> report,
                                                             File outputDir) {
        String baselinePath = properties.getBaseline();
        if (baselinePath == null || baselinePath.isBlank()) {
            return null; // feature not enabled
        }
        File baselineFile = new File(baselinePath);
        ObjectMapper mapper = new ObjectMapper();

        if (properties.isBaselineWrite()) {
            try {
                mapper.enable(SerializationFeature.INDENT_OUTPUT);
                File parent = baselineFile.getAbsoluteFile().getParentFile();
                if (parent != null && !parent.exists()) parent.mkdirs();
                Files.writeString(baselineFile.toPath(), mapper.writeValueAsString(report));
                log.info(WireDoctorMessages.BASELINE_WRITTEN, baselineFile.getAbsolutePath());
            } catch (Exception e) {
                log.error(WireDoctorMessages.BASELINE_WRITE_FAILED,
                          baselineFile.getAbsolutePath(), e.getMessage());
            }
            return null; // write mode never diffs or gates
        }

        if (!baselineFile.isFile()) {
            log.info(WireDoctorMessages.BASELINE_MISSING, baselineFile.getAbsolutePath());
            return null;
        }

        WireDoctorBaselineDiff.Snapshot baseline;
        try {
            baseline = WireDoctorBaselineDiff.Snapshot.fromJson(mapper.readTree(baselineFile));
        } catch (Exception e) {
            log.warn(WireDoctorMessages.BASELINE_UNREADABLE,
                     baselineFile.getAbsolutePath(), e.getMessage());
            return null;
        }

        WireDoctorBaselineDiff.Snapshot current =
                WireDoctorBaselineDiff.Snapshot.fromAnalysis(graph, cycles);
        WireDoctorBaselineDiff.DiffResult diff = WireDoctorBaselineDiff.diff(baseline, current);

        log.info(WireDoctorMessages.DIFF_HEADER, baselineFile.getName());
        if (diff.isEmpty()) {
            log.info(WireDoctorMessages.DIFF_NO_CHANGES);
        } else {
            log.info(WireDoctorMessages.DIFF_SUMMARY,
                    diff.addedBeans().size(), diff.removedBeans().size(),
                    diff.addedEdges().size(), diff.removedEdges().size(),
                    diff.newCycles().size(), diff.resolvedCycles().size());
            diff.newCycles().forEach(c ->
                    log.info(WireDoctorMessages.DIFF_NEW_CYCLE_ITEM, String.join(" -> ", c)));
        }

        try {
            mapper.enable(SerializationFeature.INDENT_OUTPUT);
            File diffFile = new File(outputDir, "wiredoctor-diff.json");
            Files.writeString(diffFile.toPath(), mapper.writeValueAsString(diff.toReportMap()));
            log.info(WireDoctorMessages.DIFF_SAVED, diffFile.getAbsolutePath());
        } catch (Exception e) {
            log.error(WireDoctorMessages.FAILED_WRITE_JSON, e.getMessage());
        }

        if (properties.isFailOnNewCycle() && diff.hasNewCycles()) {
            log.error(WireDoctorMessages.GATE_TRIPPED,
                      properties.getFailOn(), diff.newCycles().size());
            return new WireDoctorRegressionException(
                    "WireDoctor regression gate 'new-cycle' tripped: "
                    + diff.newCycles().size() + " new bean dependency cycle(s) vs baseline "
                    + baselineFile.getName() + ": " + diff.newCycles());
        }
        return null;
    }
}
