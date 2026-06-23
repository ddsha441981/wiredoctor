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
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.metrics.buffering.BufferingApplicationStartup;
import org.springframework.boot.context.metrics.buffering.StartupTimeline;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.metrics.ApplicationStartup;
import org.springframework.core.metrics.StartupStep;
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

    /**
     * Executes the comprehensive context analysis upon successful application startup.
     *
     * @param event the event indicating that the application context is fully ready
     */
    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        log.info(WireDoctorMessages.BANNER_TOP);
        log.info(WireDoctorMessages.BANNER_TEXT);
        log.info(WireDoctorMessages.BANNER_BOTTOM);

        ConfigurableApplicationContext context = event.getApplicationContext();
        ConfigurableListableBeanFactory beanFactory = context.getBeanFactory();

        // ── Feature 1: Resolve output directory ──────────────────────────────
        String outputPathProp = context.getEnvironment()
                .getProperty("wiredoctor.output-path", System.getProperty("user.dir"));
        File outputDir = new File(outputPathProp);
        if (!outputDir.exists()) outputDir.mkdirs();
        log.info(WireDoctorMessages.OUTPUT_PATH_INFO, outputDir.getAbsolutePath());

        // ── Feature 3: Slow bean threshold ───────────────────────────────────
        long slowBeanThresholdMs = Long.parseLong(
                context.getEnvironment()
                       .getProperty("wiredoctor.slow-bean-threshold-ms", "100")
        );

        Map<String, Object> report = new LinkedHashMap<>();

        // ── Section 1: Startup timing + Slow bean instantiation ──────────────
        ApplicationStartup applicationStartup = context.getApplicationStartup();
        List<Map<String, Object>> slowSteps = new ArrayList<>();
        List<Map<String, Object>> slowBeans = new ArrayList<>();

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
                if (durationMs < slowBeanThresholdMs) continue;

                String beanName = "unknown";
                for (StartupStep.Tag tag : e.getStartupStep().getTags()) {
                    if ("beanName".equals(tag.getKey())) {
                        beanName = tag.getValue();
                        break;
                    }
                }
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
        int totalDependencies = 0;

        for (String beanName : beanNames) {
            try {
                Object bean = beanFactory.getBean(beanName);
                if (AopUtils.isCglibProxy(bean))       cglibProxies.add(beanName);
                else if (AopUtils.isJdkDynamicProxy(bean)) jdkProxies.add(beanName);
            } catch (Exception ignored) {}

            String[] dependencies = beanFactory.getDependenciesForBean(beanName);
            graph.put(beanName, dependencies);
            totalDependencies += dependencies.length;
        }

        List<List<String>> cycles = CycleDetector.detectCycles(graph);

        Set<String> allDependencies = new HashSet<>();
        graph.values().forEach(deps -> allDependencies.addAll(Arrays.asList(deps)));

        String scanPackages = context.getEnvironment().getProperty("wiredoctor.scan-packages");
        List<String> orphanBeans = new ArrayList<>();

        for (String beanName : beanNames) {
            if (allDependencies.contains(beanName)) continue;
            if (beanName.toLowerCase().startsWith("wiredoctor")) continue;
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

        log.info(WireDoctorMessages.PROXY_HEADER);
        log.info(WireDoctorMessages.PROXY_CGLIB_ITEM, cglibProxies.size());
        log.info(WireDoctorMessages.PROXY_JDK_ITEM,   jdkProxies.size());

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

        log.info(WireDoctorMessages.BANNER_END);
    }
}
