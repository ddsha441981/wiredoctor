/*
 * Copyright (c) 2026 Deendayal Kumawat
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package com.wiredoctor;

import com.fasterxml.jackson.databind.JsonNode;
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
     * The most recently generated report, retained in memory after startup
     * analysis completes. {@code null} until the {@link ApplicationReadyEvent}
     * has been processed (or if analysis failed before the report was built).
     * <p>
     * Exposed via {@link #getLastReport()} so an out-of-core integration (the
     * optional {@code wiredoctor-actuator} module) can serve the report without
     * re-reading it from disk. Written once at the end of analysis and only
     * read afterwards; {@code volatile} guarantees visibility across the
     * startup thread and any later reader thread (e.g. an HTTP worker).
     */
    private volatile Map<String, Object> lastReport;

    /**
     * Creates the analyzer with its typed configuration.
     *
     * @param properties the bound {@code wiredoctor.*} configuration
     */
    public WireDoctorAnalyzer(WireDoctorProperties properties) {
        this.properties = properties;
    }

    /**
     * Returns the most recently generated diagnostic report, or {@code null}
     * before startup analysis has completed.
     * <p>
     * The returned map is the live report instance; callers should treat it as
     * read-only. Provided for the optional {@code wiredoctor-actuator} module —
     * the core itself never depends on Spring Boot Actuator.
     *
     * @return the last report map, or {@code null} if none has been produced yet
     */
    public Map<String, Object> getLastReport() {
        return lastReport;
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

        // ── Feature (v0.7.0): Total startup time from ApplicationReadyEvent ──
        // Available since Boot 2.6+; null/absent for Boot 2.5 and earlier.
        Long totalStartupMs = null;
        try {
            java.time.Duration timeTaken = event.getTimeTaken();
            if (timeTaken != null) {
                totalStartupMs = timeTaken.toMillis();
                log.debug("[WireDoctor] Captured total startup time: {}ms", totalStartupMs);
            } else {
                log.debug("[WireDoctor] ApplicationReadyEvent.getTimeTaken() returned null");
            }
        } catch (Exception e) {
            // Boot 2.5 or exotic context: timeTaken() doesn't exist or throws.
            // Gracefully absent; timing diff will skip with an info log.
            log.debug("[WireDoctor] Failed to get startup time: {}", e.getMessage());
        }

        // Active profiles: recorded in the report for traceability and used to
        // resolve a profile-keyed baseline path (v0.4.0) — the bean graph
        // differs per profile, so each profile diffs against its own baseline.
        String[] activeProfiles;
        try {
            activeProfiles = context.getEnvironment().getActiveProfiles();
        } catch (Exception e) {
            activeProfiles = new String[0];
        }

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
        report.put("activeProfiles", Arrays.asList(activeProfiles));

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
        // v0.10.0: full per-bean timing map (not just >threshold) for graph timing heat
        report.put("beanTimings", beanInstantiationMs);
        // v0.7.0: record threshold in baseline so diff knows what "slow" meant then
        report.put("slowBeanThreshold", slowBeanThresholdMs);

        // v0.7.0: record total startup time (when available) for timing regression gate
        if (totalStartupMs != null) {
            report.put("totalStartupMs", totalStartupMs);
        }

        // ── Section 2: Feature 2 — Bean category summary ─────────────────────
        String[] beanNames = beanFactory.getBeanDefinitionNames();
        int roleApplication  = 0;
        int roleSupport      = 0;
        int roleInfra        = 0;
        int userDefined      = 0;
        int frameworkOwned   = 0;
        // Beans classified framework-owned by resolved type — reused below to
        // filter the smell rankings down to beans a user can actually refactor.
        Set<String> frameworkBeanNames = new HashSet<>();

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

            // User-defined vs framework (package heuristic + well-known names)
            try {
                // Well-known Spring infrastructure singletons registered outside
                // beanDefinitionNames (e.g. "environment") are not reachable via
                // getType() in the normal class. Pre-seed them here so the smell
                // filter does not surface them as user beans.
                if (WireDoctorBeanClassifier.isWellKnownFrameworkBean(beanName)) {
                    frameworkOwned++;
                    frameworkBeanNames.add(beanName);
                } else {
                    Class<?> beanType = beanFactory.getType(beanName);
                    if (beanType != null && beanType.getPackage() != null) {
                        String pkg = beanType.getPackage().getName();
                        boolean isFramework = WireDoctorBeanClassifier.isFrameworkPackage(pkg);
                        if (isFramework) {
                            frameworkOwned++;
                            frameworkBeanNames.add(beanName);
                        } else {
                            userDefined++;
                        }
                    }
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
                        include = !WireDoctorBeanClassifier.isFrameworkPackage(pkg);
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

        // ── Feature (v0.6.0): Ghost candidates (Phase 1 — passive) ───────────
        // Refines the orphan list with two extra signals: the bean was eagerly
        // instantiated (it cost startup time and memory) AND no entry point is
        // detectable from its metadata (@Controller, @Scheduled holder,
        // CommandLineRunner, ...). Sits BESIDE the orphan list — orphans stay
        // the raw graph fact, this is the refined advice. 100% passive:
        // read-only singleton-cache checks, nothing is instantiated or wrapped.
        WireDoctorGhostCandidates.Result ghostCandidates =
                WireDoctorGhostCandidates.detect(beanFactory, orphanBeans);
        report.put("ghostCandidates", WireDoctorGhostCandidates.toReportMap(ghostCandidates));

        // ── Feature (v0.3.0): Architecture Smell Metrics ─────────────────────
        // Fan-in/fan-out hotspots + Martin's instability metric, computed on
        // the live resolved graph (what Spring actually wired — proxies,
        // conditionals, profiles included). Pure graph functions, no heuristics.
        // Computed BEFORE graph serialization: truncation below reuses fan-in.
        // By default the ranked lists (highFanIn/highFanOut/unstable) are
        // narrowed to user beans: framework beans genuinely dominate the
        // coupling hotspots of any Boot app, but users cannot refactor them,
        // so ranking them first buries the actionable signal. A bean is
        // "framework" if it was classified so by resolved type above, or if its
        // name is itself a framework-package FQN (the synthetic
        // ...ApplicationContext@hash fan-in entries that carry no bean type).
        boolean includeFramework = properties.isIncludeFrameworkSmells();
        // When scan-packages is configured, it defines what "user code" means for
        // this app.  Restrict the smell rankings to that allowlist (consistent with
        // the orphan-bean logic above) so that third-party autoconfig packages that
        // are not in FRAMEWORK_PACKAGES don't surface as smells.
        // Without scan-packages: fall back to the package-prefix heuristic + the
        // well-known-name set pre-seeded in frameworkBeanNames above.
        java.util.function.Predicate<String> userBean;
        if (scanPackages != null && !scanPackages.isEmpty()) {
            String[] pkgPrefixes = Arrays.stream(scanPackages.split(","))
                    .map(String::trim).toArray(String[]::new);
            userBean = name -> {
                try {
                    Class<?> t = beanFactory.getType(name);
                    if (t != null && t.getPackage() != null) {
                        String pkg = t.getPackage().getName();
                        for (String prefix : pkgPrefixes) {
                            if (pkg.startsWith(prefix)) return true;
                        }
                        return false;
                    }
                } catch (Exception ignored) {}
                return false; // unknown type: not in user's declared packages
            };
        } else {
            userBean = name ->
                    !frameworkBeanNames.contains(name)
                            && !WireDoctorBeanClassifier.isFrameworkPackage(name)
                            && !WireDoctorBeanClassifier.isWellKnownFrameworkBean(name);
        }
        Map<String, Object> smells = includeFramework
                ? WireDoctorSmellDetector.toReportMap(graph)
                : WireDoctorSmellDetector.toReportMap(graph, userBean, true);
        report.put("smells", smells);

        // ── Feature (v0.3.0): Large-Context Hardening ────────────────────────
        // Above the cap, serialize only the top-N nodes by fan-in (plus all
        // cycle participants) so the JSON stays reviewable and the HTML
        // visualizer does not freeze. Analysis above ran on the FULL graph —
        // cycles, smells, critical path and diff are never capped.
        @SuppressWarnings("unchecked")
        Map<String, Integer> fanInCounts = (Map<String, Integer>) smells.get("fanIn");
        WireDoctorGraphTruncator.Result truncation = WireDoctorGraphTruncator.truncate(
                graph, cycles, fanInCounts, properties.resolveMaxGraphNodes());
        dependencyInfo.put("graph",          truncation.graph);
        dependencyInfo.put("graphTruncated", truncation.truncated);
        if (truncation.truncated) {
            dependencyInfo.put("graphNodesTotal", truncation.originalNodeCount);
            dependencyInfo.put("graphNodesKept",  truncation.keptNodeCount);
            log.info(WireDoctorMessages.GRAPH_TRUNCATED,
                     truncation.keptNodeCount, truncation.originalNodeCount);
        }
        report.put("dependencies", dependencyInfo);

        // ── Feature (v0.3.0): Counterfactual @Lazy Simulator ─────────────────
        // For each detected cycle, rank which beans would break it if marked
        // @Lazy — most cycles broken first, smallest blast radius (fan-in) next.
        // Pure computation over the graph we already built; empty when no cycles.
        List<WireDoctorLazySimulator.LazySuggestion> lazySuggestions =
                WireDoctorLazySimulator.suggestLazyPlacements(graph, cycles);
        report.put("lazySuggestions", WireDoctorLazySimulator.toReportList(lazySuggestions));

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

        // ── Feature (v0.5.0): Autoconfig condition snapshot ──────────────────
        // Read-only capture of the ConditionEvaluationReport Boot already keeps
        // in memory (the --debug report's data source). Recorded in the report/
        // baseline so the regression guard can diff condition outcomes across
        // builds and Boot upgrades — "the cause" where the bean diff is "the
        // effect". Absent report (exotic contexts) → null, section skipped.
        Map<String, WireDoctorConditionSnapshot.Outcome> conditionOutcomes = null;
        try {
            org.springframework.boot.autoconfigure.condition.ConditionEvaluationReport
                    conditionReport = org.springframework.boot.autoconfigure.condition
                    .ConditionEvaluationReport.get(beanFactory);
            conditionOutcomes = WireDoctorConditionSnapshot.extract(conditionReport);
            report.put("conditions", WireDoctorConditionSnapshot.toReportMap(conditionOutcomes));
        } catch (Throwable t) {
            // Additive section — never let it disturb the analysis.
            log.warn(WireDoctorMessages.CONDITIONS_UNAVAILABLE, t.getMessage());
        }

        // ── Feature (v0.7.1): Gates section — verdicts surfaced in the report ──
        // Base config is recorded here; runRegressionGuard() below enriches it
        // with the actual mode/verdicts BEFORE the JSON/HTML write, so the gate
        // results land in the report files and the actuator view.
        Map<String, Object> gatesMap = new LinkedHashMap<>();
        gatesMap.put("baselineConfigured",
                properties.getBaseline() != null && !properties.getBaseline().isBlank());
        gatesMap.put("mode", "off"); // guard overwrites: write | diff | baseline-missing | baseline-unreadable
        gatesMap.put("armed", parseArmedGates());
        Map<String, Object> gatesConfig = new LinkedHashMap<>();
        gatesConfig.put("startupTimeAbsoluteThresholdMs", properties.getStartupTimeAbsoluteThreshold());
        gatesConfig.put("startupTimeRelativePercent", properties.getStartupTimeRelativeThreshold() * 100);
        gatesConfig.put("slowBeanThresholdMs", slowBeanThresholdMs);
        gatesConfig.put("slowBeanMarginMs", properties.getSlowBeanMarginMs());
        gatesMap.put("config", gatesConfig);
        report.put("gates", gatesMap);

        // ── Feature (v0.2.0): Architectural Regression Guard ─────────────────
        // v0.7.1: runs BEFORE the JSON/HTML write so the gate verdicts reach
        // the report. CI contract intact: the gate exception is RETURNED (not
        // thrown), so every file below is still written before CI failure.
        WireDoctorRegressionException trippedGate =
                runRegressionGuard(graph, cycles, conditionOutcomes, totalStartupMs,
                                   slowBeanThresholdMs, slowBeans, report, gatesMap,
                                   outputDir, activeProfiles);

        // Retain the completed report in memory for out-of-core consumers (the
        // optional wiredoctor-actuator endpoint). Set BEFORE the disk write so a
        // failed write never leaves the endpoint without a report to serve.
        this.lastReport = report;

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

        // Feature (v0.6.0): ghost candidates console output (top 10)
        if (!ghostCandidates.candidates.isEmpty()) {
            log.info(WireDoctorMessages.GHOST_CANDIDATES_HEADER);
            ghostCandidates.candidates.stream().limit(10).forEach(b ->
                    log.info(WireDoctorMessages.GHOST_CANDIDATE_ITEM, b));
            log.info(WireDoctorMessages.GHOST_CANDIDATES_NOTE);
        }

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
     * Parses {@code wiredoctor.fail-on} into the list of recognized armed
     * gates (v0.7.1) — recorded in the report's {@code gates.armed} so the
     * HTML report can show which verdicts would actually fail CI. Unknown
     * tokens are silently ignored (lenient, consistent with {@code hasGate}).
     */
    private List<String> parseArmedGates() {
        List<String> armed = new ArrayList<>();
        if (properties.isFailOnNewCycle())         armed.add("new-cycle");
        if (properties.isFailOnConditionChanged()) armed.add("condition-changed");
        if (properties.isFailOnStartupTime())      armed.add("startup-time");
        if (properties.isFailOnSlowBean())         armed.add("slow-bean");
        return armed;
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
     * @param conditionOutcomes autoconfig condition outcomes (v0.5.0), or
     *                          {@code null} when the condition report was unavailable
     * @param graph              bean name → dependency names
     * @param cycles             detected cycles
     * @param conditionOutcomes  autoconfig condition outcomes (v0.5.0), or null
     * @param totalStartupMs     total startup time (v0.7.0), or null when unavailable
     * @param currentThreshold   current slow-bean threshold (v0.7.0)
     * @param currentSlowBeans   current slow beans list (v0.7.0)
     * @param report             the full current report (persisted as baseline in write mode)
     * @param gatesMap           the report's {@code gates} section (v0.7.1) — enriched
     *                           in place with mode and verdicts before the report is written
     * @param outputDir          directory for {@code wiredoctor-diff.json}
     * @param activeProfiles     the environment's active profiles, used to resolve a
     *                           {@code {profiles}} token in the baseline path (v0.4.0)
     * @return the gate exception to throw, or {@code null} when no gate tripped
     */
    private WireDoctorRegressionException runRegressionGuard(Map<String, String[]> graph,
                                                             List<List<String>> cycles,
                                                             Map<String, WireDoctorConditionSnapshot.Outcome> conditionOutcomes,
                                                             Long totalStartupMs,
                                                             long currentThreshold,
                                                             List<Map<String, Object>> currentSlowBeans,
                                                             Map<String, Object> report,
                                                             Map<String, Object> gatesMap,
                                                             File outputDir,
                                                             String[] activeProfiles) {
        String baselinePath = properties.getBaseline();
        if (baselinePath == null || baselinePath.isBlank()) {
            return null; // feature not enabled — no marker file either
        }
        // v0.4.0: resolve a {profiles} token to a per-profile baseline so dev/prod
        // graphs diff like-with-like. No token → path unchanged (pre-v0.4.0 behavior).
        baselinePath = WireDoctorBaselineResolver.resolve(baselinePath, activeProfiles);
        File baselineFile = new File(baselinePath);
        ObjectMapper mapper = new ObjectMapper();

        // v0.4.0 CI contract: remove any stale marker BEFORE computing, so a
        // crash mid-analysis can never leave a previous run's verdict behind
        // for CI to misread. The absence of the file itself means "no verdict".
        File gateStatusFile = new File(outputDir, "wiredoctor-gate.status");
        try {
            Files.deleteIfExists(gateStatusFile.toPath());
        } catch (Exception ignored) {}

        if (properties.isBaselineWrite()) {
            gatesMap.put("mode", "write"); // v0.7.1
            try {
                mapper.enable(SerializationFeature.INDENT_OUTPUT);
                File parent = baselineFile.getAbsoluteFile().getParentFile();
                if (parent != null && !parent.exists()) parent.mkdirs();
                // The baseline must always carry the FULL graph — future diffs
                // compare against it, and a truncated baseline would report
                // spurious bean/edge changes. Swap the (possibly truncated)
                // serialized graph out for the write, then restore it.
                // v0.7.1: the gates section is a per-run verdict, not part of
                // the architecture snapshot — strip it from the baseline the
                // same way, so a future diff never reads a stale verdict.
                @SuppressWarnings("unchecked")
                Map<String, Object> deps = (Map<String, Object>) report.get("dependencies");
                Object serializedGraph = deps.get("graph");
                deps.put("graph", graph);
                Object gatesSection = report.remove("gates");
                try {
                    Files.writeString(baselineFile.toPath(), mapper.writeValueAsString(report));
                } finally {
                    deps.put("graph", serializedGraph);
                    report.put("gates", gatesSection);
                }
                log.info(WireDoctorMessages.BASELINE_WRITTEN, baselineFile.getAbsolutePath());
            } catch (Exception e) {
                log.error(WireDoctorMessages.BASELINE_WRITE_FAILED,
                          baselineFile.getAbsolutePath(), e.getMessage());
            }
            return null; // write mode never diffs or gates
        }

        if (!baselineFile.isFile()) {
            gatesMap.put("mode", "baseline-missing"); // v0.7.1
            log.info(WireDoctorMessages.BASELINE_MISSING, baselineFile.getAbsolutePath());
            return null;
        }

        WireDoctorBaselineDiff.Snapshot baseline;
        JsonNode baselineJsonRoot;
        try {
            baselineJsonRoot = mapper.readTree(baselineFile);
            baseline = WireDoctorBaselineDiff.Snapshot.fromJson(baselineJsonRoot);
        } catch (Exception e) {
            gatesMap.put("mode", "baseline-unreadable"); // v0.7.1
            log.warn(WireDoctorMessages.BASELINE_UNREADABLE,
                     baselineFile.getAbsolutePath(), e.getMessage());
            return null;
        }

        WireDoctorBaselineDiff.Snapshot current =
                WireDoctorBaselineDiff.Snapshot.fromAnalysis(graph, cycles, conditionOutcomes,
                                                              totalStartupMs, currentThreshold);
        WireDoctorBaselineDiff.DiffResult diff = WireDoctorBaselineDiff.diff(baseline, current);

        // v0.7.0: compute new slow beans by comparing current vs baseline slow bean lists
        List<WireDoctorBaselineDiff.NewSlowBean> newSlowBeans = List.of();
        if (baseline.slowBeanThreshold() != null && !currentSlowBeans.isEmpty()) {
            // Extract baseline slow bean names from the baseline JSON
            Set<String> baselineSlowBeanNames = new java.util.HashSet<>();
            JsonNode baselineSlowBeansNode = baselineJsonRoot.path("slowBeans");
            if (baselineSlowBeansNode.isArray()) {
                baselineSlowBeansNode.forEach(bean -> {
                    JsonNode nameNode = bean.path("beanName");
                    if (!nameNode.isMissingNode()) {
                        baselineSlowBeanNames.add(nameNode.asText());
                    }
                });
            }
            newSlowBeans = WireDoctorBaselineDiff.computeNewSlowBeans(
                    baselineSlowBeanNames,
                    baseline.slowBeanThreshold(),
                    currentSlowBeans,
                    currentThreshold,
                    properties.getSlowBeanMarginMs());
            // Rebuild the diff with the computed slow beans
            diff = new WireDoctorBaselineDiff.DiffResult(
                    diff.addedBeans(), diff.removedBeans(),
                    diff.addedEdges(), diff.removedEdges(),
                    diff.newCycles(), diff.resolvedCycles(),
                    diff.conditionDiffAvailable(),
                    diff.conditionsChanged(), diff.conditionsAdded(), diff.conditionsRemoved(),
                    diff.startupTimeRegression(),
                    newSlowBeans);
        }

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
        // v0.5.0: condition diff summary — only when both sides carried data.
        if (diff.conditionDiffAvailable()) {
            log.info(WireDoctorMessages.CONDITION_DIFF_SUMMARY,
                    diff.conditionsChanged().size(),
                    diff.conditionsAdded().size(), diff.conditionsRemoved().size());
            diff.conditionsChanged().stream().limit(10).forEach(c ->
                    log.info(WireDoctorMessages.CONDITION_DIFF_CHANGED_ITEM,
                            c.className(), c.oldOutcome(), c.newOutcome()));
        } else if (baseline.conditions() == null && conditionOutcomes != null) {
            log.info(WireDoctorMessages.CONDITION_DIFF_BASELINE_PREDATES);
        }

        // v0.7.0: timing diff summary — only when both sides carried timing data
        if (diff.hasStartupTimeRegression()) {
            WireDoctorBaselineDiff.StartupTimeRegression regression = diff.startupTimeRegression();
            log.info("⏱ Startup Time: {}ms -> ms ({:+d}ms, {:.1f}% {})",
                    regression.baselineMs(),
                    regression.currentMs(),
                    regression.deltaMs(),
                    Math.abs(regression.percentChange() * 100),
                    regression.deltaMs() >= 0 ? "slower" : "faster");
        } else if (baseline.timing() != null && totalStartupMs != null) {
            // Both sides have timing, but no regression (faster or within noise)
            long baselineMs = baseline.timing().totalStartupMs();
            long delta = totalStartupMs - baselineMs;
            log.info("⏱ Startup Time: {}ms -> {}ms ({:+d}ms)", baselineMs, totalStartupMs, delta);
        } else if (baseline.timing() == null && totalStartupMs != null) {
            log.info("⏱ Startup Time: {}ms (baseline predates timing tracking, diff skipped)", totalStartupMs);
        }

        // v0.7.0: slow-bean diff summary
        if (diff.hasNewSlowBeans()) {
            log.info("🐌 New Slow Beans: {} bean(s) crossed the slow threshold ({}ms) vs baseline",
                    diff.newSlowBeans().size(), currentThreshold);
            diff.newSlowBeans().stream().limit(5).forEach(b ->
                    log.info("   • {} ({}ms)", b.beanName(), b.instantiationMs()));
        }

        try {
            mapper.enable(SerializationFeature.INDENT_OUTPUT);
            File diffFile = new File(outputDir, "wiredoctor-diff.json");
            Files.writeString(diffFile.toPath(), mapper.writeValueAsString(diff.toReportMap()));
            log.info(WireDoctorMessages.DIFF_SAVED, diffFile.getAbsolutePath());
        } catch (Exception e) {
            log.error(WireDoctorMessages.FAILED_WRITE_JSON, e.getMessage());
        }

        // ── Feature (v0.7.1): enrich the report's gates section with verdicts ──
        // Mirrors the gate.status semantics: `failed` lists every gate whose
        // SIGNAL fired, armed or not (`armed` tells which would fail CI).
        List<String> failedGates = computeFailedGates(diff);
        gatesMap.put("mode", "diff");
        gatesMap.put("baseline", baselineFile.getName());
        gatesMap.put("failed", failedGates);
        if (baseline.timing() != null && totalStartupMs != null) {
            // Always record the delta when both sides carry timing — the HTML
            // shows it even when no regression tripped.
            long baselineMs = baseline.timing().totalStartupMs();
            long deltaMs = totalStartupMs - baselineMs;
            Map<String, Object> startupTime = new LinkedHashMap<>();
            startupTime.put("baselineMs", baselineMs);
            startupTime.put("currentMs", totalStartupMs);
            startupTime.put("deltaMs", deltaMs);
            startupTime.put("percentChange",
                    baselineMs > 0 ? (double) deltaMs / baselineMs * 100 : 0.0);
            gatesMap.put("startupTime", startupTime);
        }
        if (diff.hasNewSlowBeans()) {
            List<Map<String, Object>> slowBeanEntries = new ArrayList<>();
            for (WireDoctorBaselineDiff.NewSlowBean b : diff.newSlowBeans()) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("beanName", b.beanName());
                entry.put("instantiationMs", b.instantiationMs());
                slowBeanEntries.add(entry);
            }
            gatesMap.put("newSlowBeans", slowBeanEntries);
        }

        // ── Feature (v0.4.0): CI marker-file contract ────────────────────────
        // A machine-readable verdict so Maven/Gradle steps can gate without
        // parsing logs or forcing a JVM exit code. Written on EVERY completed
        // diff — armed or not — so teams can gate softly (check the file)
        // without opting into fail-on. Format, line 1: "PASS" or
        // "FAIL:<gate>[,<gate>...]"; subsequent lines are informational.
        writeGateStatus(gateStatusFile, diff, baselineFile.getName(),
                        WireDoctorBaselineResolver.profileKey(activeProfiles));

        if (properties.isFailOnNewCycle() && diff.hasNewCycles()) {
            log.error(WireDoctorMessages.GATE_TRIPPED,
                      properties.getFailOn(), diff.newCycles().size());
            return new WireDoctorRegressionException(
                    "WireDoctor regression gate 'new-cycle' tripped: "
                    + diff.newCycles().size() + " new bean dependency cycle(s) vs baseline "
                    + baselineFile.getName() + ": " + diff.newCycles());
        }
        // v0.5.0: condition-changed gate — trips only when the condition diff
        // actually ran (an old baseline must not trip a false FAIL).
        if (properties.isFailOnConditionChanged()
                && diff.conditionDiffAvailable() && diff.hasConditionChanges()) {
            log.error(WireDoctorMessages.CONDITION_GATE_TRIPPED,
                      properties.getFailOn(), diff.conditionsChanged().size());
            return new WireDoctorRegressionException(
                    "WireDoctor regression gate 'condition-changed' tripped: "
                    + diff.conditionsChanged().size()
                    + " autoconfiguration condition outcome(s) changed vs baseline "
                    + baselineFile.getName() + ": "
                    + diff.conditionsChanged().stream()
                            .map(c -> c.className() + " (" + c.oldOutcome()
                                    + " -> " + c.newOutcome() + ")")
                            .collect(Collectors.toList()));
        }

        // v0.7.0: startup-time gate — trips only when BOTH dual thresholds exceeded
        log.debug("[WireDoctor] Checking startup-time gate: enabled={}, hasRegression={}",
                properties.isFailOnStartupTime(), diff.hasStartupTimeRegression());
        if (properties.isFailOnStartupTime() && diff.hasStartupTimeRegression()) {
            WireDoctorBaselineDiff.StartupTimeRegression regression = diff.startupTimeRegression();
            double relativeThreshold = properties.getStartupTimeRelativeThreshold();
            long absoluteThreshold = properties.getStartupTimeAbsoluteThreshold();
            // Dual-threshold check: BOTH must trip to avoid false alarms on noise
            boolean tripsDual = regression.deltaMs() >= absoluteThreshold
                                && regression.percentChange() >= relativeThreshold;
            if (tripsDual) {
                log.error("WireDoctor regression gate 'startup-time' tripped: startup time "
                        + "increased by {}ms ({:.1f}%) vs baseline {} ({}ms -> {}ms). "
                        + "Thresholds: >={}ms AND >={}%",
                        regression.deltaMs(),
                        regression.percentChange() * 100,
                        baselineFile.getName(),
                        regression.baselineMs(),
                        regression.currentMs(),
                        absoluteThreshold,
                        (int)(relativeThreshold * 100));
                return new WireDoctorRegressionException(
                        String.format("WireDoctor regression gate 'startup-time' tripped: "
                                + "startup time increased by %dms (%.1f%%) vs baseline %s "
                                + "(%dms -> %dms)",
                                regression.deltaMs(),
                                regression.percentChange() * 100,
                                baselineFile.getName(),
                                regression.baselineMs(),
                                regression.currentMs()));
            }
        }

        // v0.7.0: slow-bean gate — trips when any bean became slow that wasn't before
        if (properties.isFailOnSlowBean() && diff.hasNewSlowBeans()) {
            List<WireDoctorBaselineDiff.NewSlowBean> slowBeanRegressions = diff.newSlowBeans();
            log.error("WireDoctor regression gate 'slow-bean' tripped: {} bean(s) crossed "
                    + "the slow threshold ({}ms) that were NOT slow in baseline {}",
                    slowBeanRegressions.size(), currentThreshold, baselineFile.getName());
            return new WireDoctorRegressionException(
                    String.format("WireDoctor regression gate 'slow-bean' tripped: "
                            + "%d bean(s) crossed the slow threshold (%dms) vs baseline %s: %s",
                            slowBeanRegressions.size(),
                            currentThreshold,
                            baselineFile.getName(),
                            slowBeanRegressions.stream()
                                    .map(b -> b.beanName() + " (" + b.instantiationMs() + "ms)")
                                    .collect(Collectors.toList())));
        }

        return null;
    }

    /**
     * Computes which gate SIGNALS fired in the diff (v0.7.1 — extracted from
     * {@link #writeGateStatus}): {@code new-cycle}, {@code condition-changed},
     * {@code startup-time} (dual-threshold), {@code slow-bean}. Armed-or-not —
     * this is the signal list shared by the gate.status marker file and the
     * report's {@code gates.failed} section.
     */
    private List<String> computeFailedGates(WireDoctorBaselineDiff.DiffResult diff) {
        List<String> failedGates = new ArrayList<>();
        if (diff.hasNewCycles()) {
            failedGates.add("new-cycle");
        }
        if (diff.conditionDiffAvailable() && diff.hasConditionChanges()) {
            failedGates.add("condition-changed");
        }
        // v0.7.0: startup-time gate signal (trips only if dual-threshold check passes)
        if (diff.hasStartupTimeRegression()) {
            WireDoctorBaselineDiff.StartupTimeRegression regression = diff.startupTimeRegression();
            double relativeThreshold = properties.getStartupTimeRelativeThreshold();
            long absoluteThreshold = properties.getStartupTimeAbsoluteThreshold();
            boolean tripsDual = regression.deltaMs() >= absoluteThreshold
                                && regression.percentChange() >= relativeThreshold;
            if (tripsDual) {
                failedGates.add("startup-time");
            }
        }
        // v0.7.0: slow-bean gate signal
        if (diff.hasNewSlowBeans()) {
            failedGates.add("slow-bean");
        }
        return failedGates;
    }

    /**
     * CI marker-file contract (v0.4.0): writes {@code wiredoctor-gate.status}
     * with a machine-readable verdict.
     * <p>
     * Line 1 is the contract: {@code PASS} when no gate condition is present
     * in the diff, or {@code FAIL:<gate>} (comma-separated list) when one is —
     * independent of whether {@code wiredoctor.fail-on} armed the hard gate.
     * This lets build steps gate with a one-liner
     * ({@code grep -q '^PASS' wiredoctor-gate.status}) instead of parsing
     * logs or relying on JVM exit codes. Remaining lines are informational
     * key=value pairs. Write failures are logged and swallowed — the marker
     * is an ergonomic extra, never a crash risk.
     *
     * @param gateStatusFile target marker file (stale copy already deleted)
     * @param diff           the completed baseline diff
     * @param baselineName   baseline file name, echoed for traceability
     * @param profileKey     active-profile key the baseline was resolved for
     */
    private void writeGateStatus(File gateStatusFile,
                                 WireDoctorBaselineDiff.DiffResult diff,
                                 String baselineName,
                                 String profileKey) {
        StringBuilder status = new StringBuilder();
        // Line 1 contract: "PASS" or "FAIL:<gate>[,<gate>...]" — a gate appears
        // in the FAIL list when its SIGNAL fired, armed or not (gateArmed tells
        // the consumer whether the JVM was configured to die over it).
        List<String> failedGates = computeFailedGates(diff);
        if (failedGates.isEmpty()) {
            status.append("PASS");
        } else {
            status.append("FAIL:").append(String.join(",", failedGates));
        }
        status.append('\n');
        status.append("baseline=").append(baselineName).append('\n');
        status.append("profiles=").append(profileKey).append('\n');
        status.append("newCycles=").append(diff.newCycles().size()).append('\n');
        status.append("resolvedCycles=").append(diff.resolvedCycles().size()).append('\n');
        status.append("addedBeans=").append(diff.addedBeans().size()).append('\n');
        status.append("removedBeans=").append(diff.removedBeans().size()).append('\n');
        // v0.5.0: condition info. conditionDiff=skipped distinguishes "no
        // changes" from "baseline predates condition tracking".
        if (diff.conditionDiffAvailable()) {
            status.append("conditionsChanged=").append(diff.conditionsChanged().size()).append('\n');
        } else {
            status.append("conditionDiff=skipped").append('\n');
        }
        // v0.7.0: timing info
        if (diff.hasStartupTimeRegression()) {
            WireDoctorBaselineDiff.StartupTimeRegression regression = diff.startupTimeRegression();
            status.append("startupTimeDeltaMs=").append(regression.deltaMs()).append('\n');
            status.append("startupTimePercentChange=")
                  .append(String.format("%.1f", regression.percentChange() * 100)).append('\n');
        }
        if (diff.hasNewSlowBeans()) {
            status.append("newSlowBeansCount=").append(diff.newSlowBeans().size()).append('\n');
        }
        status.append("gateArmed=")
              .append(properties.isFailOnNewCycle()
                      || properties.isFailOnConditionChanged()
                      || properties.isFailOnStartupTime()
                      || properties.isFailOnSlowBean())
              .append('\n');
        try {
            Files.writeString(gateStatusFile.toPath(), status.toString());
            log.info(WireDoctorMessages.GATE_STATUS_WRITTEN, gateStatusFile.getAbsolutePath());
        } catch (Exception e) {
            log.error(WireDoctorMessages.GATE_STATUS_WRITE_FAILED,
                      gateStatusFile.getAbsolutePath(), e.getMessage());
        }
    }
}
