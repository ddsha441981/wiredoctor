package com.wiredoctor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.file.Files;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.metrics.buffering.BufferingApplicationStartup;
import org.springframework.boot.context.metrics.buffering.StartupTimeline;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.metrics.ApplicationStartup;

import java.io.File;
import java.util.*;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
public class WireDoctorAnalyzer implements ApplicationListener<ApplicationReadyEvent> {
    private static final Logger log = LoggerFactory.getLogger(WireDoctorAnalyzer.class);

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        log.info(WireDoctorMessages.BANNER_TOP);
        log.info(WireDoctorMessages.BANNER_TEXT);
        log.info(WireDoctorMessages.BANNER_BOTTOM);

        ConfigurableApplicationContext context = event.getApplicationContext();
        ConfigurableListableBeanFactory beanFactory = context.getBeanFactory();
        
        Map<String, Object> report = new LinkedHashMap<>();

        // 1. Startup timing check
        ApplicationStartup applicationStartup = context.getApplicationStartup();
        List<Map<String, Object>> slowSteps = new ArrayList<>();
        if (applicationStartup instanceof BufferingApplicationStartup) {
            BufferingApplicationStartup bufferingStartup = (BufferingApplicationStartup) applicationStartup;
            StartupTimeline timeline = bufferingStartup.getBufferedTimeline();
            
            List<StartupTimeline.TimelineEvent> events = timeline.getEvents().stream()
                .sorted((e1, e2) -> e2.getDuration().compareTo(e1.getDuration()))
                .limit(20) // top 20
                .collect(Collectors.toList());
            
            for (StartupTimeline.TimelineEvent e : events) {
                Map<String, Object> stepInfo = new LinkedHashMap<>();
                stepInfo.put("name", e.getStartupStep().getName());
                stepInfo.put("durationMs", e.getDuration().toMillis());
                
                // Add tag details if useful
                Map<String, String> tags = new LinkedHashMap<>();
                e.getStartupStep().getTags().forEach(tag -> tags.put(tag.getKey(), tag.getValue()));
                stepInfo.put("tags", tags);
                
                slowSteps.add(stepInfo);
            }
        } else {
            log.warn(WireDoctorMessages.STARTUP_NOT_BUFFERING_WARNING, applicationStartup.getClass().getName());
        }
        report.put("startupSlowestSteps", slowSteps);

        // 2. Dependency Graph, Proxies, and Cycles
        String[] beanNames = beanFactory.getBeanDefinitionNames();
        Map<String, String[]> graph = new HashMap<>();
        List<String> cglibProxies = new ArrayList<>();
        List<String> jdkProxies = new ArrayList<>();
        int totalDependencies = 0;

        for (String beanName : beanNames) {
            try {
                Object bean = beanFactory.getBean(beanName);
                if (AopUtils.isCglibProxy(bean)) {
                    cglibProxies.add(beanName);
                } else if (AopUtils.isJdkDynamicProxy(bean)) {
                    jdkProxies.add(beanName);
                }
            } catch (Exception e) {
                // Ignore beans that cannot be eagerly initialized
            }
            
            String[] dependencies = beanFactory.getDependenciesForBean(beanName);
            graph.put(beanName, dependencies);
            totalDependencies += dependencies.length;
        }

        List<List<String>> cycles = CycleDetector.detectCycles(graph);

        // 3. Orphan Bean Heuristic (Lazy Candidates)
        Set<String> allDependencies = new HashSet<>();
        for (String[] deps : graph.values()) {
            allDependencies.addAll(Arrays.asList(deps));
        }
        
        String scanPackages = context.getEnvironment().getProperty("wiredoctor.scan-packages");
        List<String> orphanBeans = new ArrayList<>();
        
        for (String beanName : beanNames) {
            if (!allDependencies.contains(beanName)) {
                boolean include = true;
                if (scanPackages != null && !scanPackages.isEmpty()) {
                    try {
                        Class<?> beanType = beanFactory.getType(beanName);
                        if (beanType != null && beanType.getPackage() != null) {
                            String pkgName = beanType.getPackage().getName();
                            include = Arrays.stream(scanPackages.split(","))
                                    .map(String::trim)
                                    .anyMatch(pkgName::startsWith);
                        } else {
                            include = false;
                        }
                    } catch (Exception e) {
                        include = false;
                    }
                } else {
                    // Option A: Skip Spring internal packages by default
                    try {
                        Class<?> beanType = beanFactory.getType(beanName);
                        if (beanType != null && beanType.getPackage() != null) {
                            String pkgName = beanType.getPackage().getName();
                            include = !pkgName.startsWith("org.springframework")
                                   && !pkgName.startsWith("org.apache")
                                   && !pkgName.startsWith("com.sun")
                                   && !pkgName.startsWith("java.")
                                   && !pkgName.startsWith("javax.")
                                   && !pkgName.startsWith("jakarta.");
                        } else {
                            include = true;
                        }
                    } catch (Exception e) {
                        include = true;
                    }
                }
                
                if (include) {
                    orphanBeans.add(beanName);
                }
            }
        }

        Map<String, Object> proxyInfo = new LinkedHashMap<>();
        proxyInfo.put("cglibCount", cglibProxies.size());
        proxyInfo.put("jdkCount", jdkProxies.size());
        proxyInfo.put("cglibBeans", cglibProxies);
        proxyInfo.put("jdkBeans", jdkProxies);
        report.put("proxies", proxyInfo);

        Map<String, Object> dependencyInfo = new LinkedHashMap<>();
        dependencyInfo.put("totalBeans", beanNames.length);
        dependencyInfo.put("totalEdges", totalDependencies);
        dependencyInfo.put("cyclesCount", cycles.size());
        dependencyInfo.put("cycles", cycles);
        dependencyInfo.put("orphanBeansCount", orphanBeans.size());
        dependencyInfo.put("orphanBeans", orphanBeans); // Heuristic only
        dependencyInfo.put("graph", graph); // Export raw graph for visualizer
        report.put("dependencies", dependencyInfo);

        // Write to JSON
        try {
            ObjectMapper mapper = new ObjectMapper();
            mapper.enable(SerializationFeature.INDENT_OUTPUT);
            
            String jsonString = mapper.writeValueAsString(report);
            
            File reportFile = new File("wiredoctor-report.json");
            Files.writeString(reportFile.toPath(), jsonString);
            
            log.info(WireDoctorMessages.SAVED_JSON_REPORT, reportFile.getAbsolutePath());
            
            // Generate HTML Visualizer
            WireDoctorHtmlReporter.generateHtmlReport(jsonString);
            
        } catch (Exception e) {
            log.error(WireDoctorMessages.FAILED_WRITE_JSON, e.getMessage());
        }

        // Console Summary
        log.info(WireDoctorMessages.SLOWEST_STEPS_HEADER);
        slowSteps.stream().limit(5).forEach(step -> {
            log.info(WireDoctorMessages.SLOWEST_STEP_ITEM, step.get("name"), step.get("durationMs"));
        });
        
        log.info(WireDoctorMessages.CYCLES_HEADER, cycles.size());
        for (List<String> cycle : cycles) {
            log.info(WireDoctorMessages.CYCLE_ITEM, String.join(" -> ", cycle), cycle.get(0));
            log.info(WireDoctorMessages.CYCLE_NOTE);
        }
        
        log.info(WireDoctorMessages.PROXY_HEADER);
        log.info(WireDoctorMessages.PROXY_CGLIB_ITEM, cglibProxies.size());
        log.info(WireDoctorMessages.PROXY_JDK_ITEM, jdkProxies.size());
        
        log.info(WireDoctorMessages.BANNER_END);
    }
}
