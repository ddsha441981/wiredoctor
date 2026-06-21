package com.wiredoctor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
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

public class WireDoctorAnalyzer implements ApplicationListener<ApplicationReadyEvent> {

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        System.out.println("\n==================================================");
        System.out.println(" WIREDOCTOR ANALYSIS STARTING");
        System.out.println("==================================================");

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
            System.err.println("[WireDoctor] WARNING: ApplicationStartup is not BufferingApplicationStartup! It is: " + applicationStartup.getClass().getName());
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
        List<String> orphanBeans = new ArrayList<>();
        for (String beanName : beanNames) {
            if (!allDependencies.contains(beanName)) {
                orphanBeans.add(beanName);
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
        report.put("dependencies", dependencyInfo);

        // Write to JSON
        try {
            ObjectMapper mapper = new ObjectMapper();
            mapper.enable(SerializationFeature.INDENT_OUTPUT);
            File reportFile = new File("wiredoctor-report.json");
            mapper.writeValue(reportFile, report);
            
            System.out.println("[WireDoctor] Saved detailed report to: " + reportFile.getAbsolutePath());
        } catch (Exception e) {
            System.err.println("[WireDoctor] Failed to write JSON report: " + e.getMessage());
        }

        // Console Summary
        System.out.println("[WireDoctor] Slowest Startup Steps:");
        slowSteps.stream().limit(5).forEach(step -> {
            System.out.println("  - " + step.get("name") + " (" + step.get("durationMs") + "ms)");
        });
        
        System.out.println("\n[WireDoctor] Bean Dependency Cycles: " + cycles.size());
        for (List<String> cycle : cycles) {
            System.out.println("  - Cycle detected: " + String.join(" -> ", cycle) + " -> " + cycle.get(0));
            System.out.println("    (Note: Spring resolved this via proxy/setter, but structurally it is a cycle)");
        }
        
        System.out.println("\n[WireDoctor] Proxy Overhead:");
        System.out.println("  - CGLIB Proxies: " + cglibProxies.size());
        System.out.println("  - JDK Proxies: " + jdkProxies.size());
        
        System.out.println("==================================================\n");
    }
}
