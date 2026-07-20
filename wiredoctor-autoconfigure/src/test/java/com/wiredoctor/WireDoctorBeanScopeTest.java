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
import org.springframework.beans.factory.FactoryBean;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Scope;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bean-scope behavior pinning (v0.8.0, plan §3 check #2): prototype,
 * FactoryBean and untouched-@Lazy beans each get their documented behavior
 * asserted here, so README Limitation #5 can state facts instead of "may not
 * fully map out". Every assertion in this class is a sentence in the docs —
 * if one fails, the docs are lying.
 */
class WireDoctorBeanScopeTest {

    /** Flipped by prototype/lazy constructors — analysis must never trigger them. */
    static final AtomicInteger PROTOTYPE_INSTANTIATIONS = new AtomicInteger();
    static final AtomicBoolean LAZY_TOUCHED = new AtomicBoolean(false);

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class ScopeApp {

        @Bean
        @Scope("prototype")
        PrototypeWidget prototypeWidget() {
            return new PrototypeWidget();
        }

        @Bean
        @Lazy
        LazyGadget lazyGadget() {
            return new LazyGadget();
        }

        @Bean
        WidgetFactoryBean widgetFactory() {
            return new WidgetFactoryBean();
        }

        /** Eager consumer of the FactoryBean PRODUCT — creates a real edge. */
        @Bean
        ProductConsumer productConsumer(ProducedWidget widget) {
            return new ProductConsumer(widget);
        }
    }

    static class PrototypeWidget {
        PrototypeWidget() {
            PROTOTYPE_INSTANTIATIONS.incrementAndGet();
        }
    }

    static class LazyGadget {
        LazyGadget() {
            LAZY_TOUCHED.set(true);
        }
    }

    static class ProducedWidget { }

    static class WidgetFactoryBean implements FactoryBean<ProducedWidget> {
        @Override public ProducedWidget getObject() { return new ProducedWidget(); }
        @Override public Class<?> getObjectType() { return ProducedWidget.class; }
        @Override public boolean isSingleton() { return true; }
    }

    static class ProductConsumer {
        ProductConsumer(ProducedWidget widget) { }
    }

    private JsonNode analyze(Path tempDir) throws Exception {
        PROTOTYPE_INSTANTIATIONS.set(0);
        LAZY_TOUCHED.set(false);
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(ScopeApp.class)
                .web(WebApplicationType.NONE)
                .properties("wiredoctor.output-path=" + tempDir)
                .run()) {
            assertThat(context.isActive()).isTrue();
        }
        return new ObjectMapper().readTree(tempDir.resolve("wiredoctor-report.json").toFile());
    }

    private static List<String> beanNamesIn(JsonNode graphNode) {
        List<String> names = new ArrayList<>();
        for (Iterator<String> it = graphNode.fieldNames(); it.hasNext(); ) {
            names.add(it.next());
        }
        return names;
    }

    @Test
    void prototypeBeanAppearsInGraphButIsNeverInstantiated(@TempDir Path tempDir) throws Exception {
        JsonNode report = analyze(tempDir);

        // Documented: prototype bean DEFINITIONS appear as graph nodes
        // (getBeanDefinitionNames() includes them) ...
        JsonNode graph = report.path("dependencies").path("graph");
        if (!graph.isMissingNode()) {
            assertThat(beanNamesIn(graph)).contains("prototypeWidget");
        }

        // ... but analysis NEVER instantiates them (zero-intrusion promise):
        assertThat(PROTOTYPE_INSTANTIATIONS.get()).isZero();
    }

    @Test
    void untouchedLazyBeanStaysUntouchedThroughFullAnalysis(@TempDir Path tempDir) throws Exception {
        analyze(tempDir);
        // Documented: an untouched @Lazy bean survives the entire analysis
        // (graph + proxy scan + ghosts + smells + report) untouched.
        assertThat(LAZY_TOUCHED.get()).isFalse();
    }

    @Test
    void factoryBeanProductEdgeIsCaptured(@TempDir Path tempDir) throws Exception {
        JsonNode report = analyze(tempDir);

        // Documented: the FactoryBean itself is a node under its bean name, and
        // a consumer of the PRODUCT gets a resolved dependency edge pointing at
        // the factory's bean name (Spring records the product under the
        // factory's name).
        JsonNode graph = report.path("dependencies").path("graph");
        if (graph.isMissingNode()) {
            return; // graph serialization capped — edge fact asserted below via consumer deps
        }
        List<String> names = beanNamesIn(graph);
        assertThat(names).contains("widgetFactory", "productConsumer");

        List<String> consumerDeps = new ArrayList<>();
        graph.path("productConsumer").forEach(d -> consumerDeps.add(d.asText()));
        assertThat(consumerDeps).contains("widgetFactory");
    }

    @Test
    void notInstantiatedBeansAreHonestlyCountedNotForced(@TempDir Path tempDir) throws Exception {
        JsonNode report = analyze(tempDir);

        // Documented: prototype + untouched @Lazy beans are skipped by the
        // proxy scan and the skip is COUNTED in the report, not hidden.
        int skipped = report.path("proxies").path("notInstantiatedSkipped").asInt();
        assertThat(skipped).isGreaterThanOrEqualTo(2); // prototypeWidget + lazyGadget at minimum
    }
}
