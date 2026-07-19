/*
 * Copyright (c) 2026 Deendayal Kumawat
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package com.wiredoctor;

import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.context.support.StaticApplicationContext;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Controller;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link WireDoctorGhostCandidates} (v0.6.0 Phase 1).
 * <p>
 * The entry-point matrix is the trust-critical part: a missed entry point
 * wrongly accuses a working bean, so every detection channel gets a test.
 */
class WireDoctorGhostCandidatesTest {

    // ── Entry-point fixtures ─────────────────────────────────────────────────

    static class PlainBean {
        void doWork() {}
    }

    @Controller
    static class ControllerBean {}

    /**
     * Local stand-in for {@code @RestController}: a stereotype meta-annotated
     * with {@code @Controller}. spring-web is deliberately NOT on this module's
     * classpath (zero optional deps), so the meta-annotation mechanics are
     * tested with an equivalent fixture.
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    @Controller
    @interface MetaController {}

    @MetaController
    static class RestControllerBean {}

    @Configuration
    static class ConfigBean {}

    static class ScheduledBean {
        @Scheduled(fixedDelay = 1000)
        void tick() {}
    }

    static class PrivateScheduledBean {
        @Scheduled(fixedDelay = 1000)
        private void tick() {}
    }

    static class EventListenerBean {
        @EventListener
        void onEvent(Object event) {}
    }

    static class RunnerBean implements CommandLineRunner {
        @Override public void run(String... args) {}
    }

    static class AppRunnerBean implements ApplicationRunner {
        @Override public void run(ApplicationArguments args) {}
    }

    /** Entry-point interface inherited via a superclass, not declared directly. */
    static class InheritedRunnerBean extends RunnerBean {}

    static class ScheduledSubclassBean extends ScheduledBean {}

    // ── isEntryPoint matrix ──────────────────────────────────────────────────

    @Test
    void plainBeanIsNotEntryPoint() {
        assertThat(WireDoctorGhostCandidates.isEntryPoint(PlainBean.class)).isFalse();
    }

    @Test
    void controllerIsEntryPoint() {
        assertThat(WireDoctorGhostCandidates.isEntryPoint(ControllerBean.class)).isTrue();
    }

    @Test
    void restControllerIsEntryPoint() {
        // @RestController-style stereotypes are meta-annotated with @Controller — must match.
        assertThat(WireDoctorGhostCandidates.isEntryPoint(RestControllerBean.class)).isTrue();
    }

    @Test
    void configurationIsEntryPoint() {
        // Config classes do their work at definition time — never accuse them.
        assertThat(WireDoctorGhostCandidates.isEntryPoint(ConfigBean.class)).isTrue();
    }

    @Test
    void scheduledMethodHolderIsEntryPoint() {
        assertThat(WireDoctorGhostCandidates.isEntryPoint(ScheduledBean.class)).isTrue();
    }

    @Test
    void privateScheduledMethodIsDetected() {
        // @Scheduled works on private methods; detection must walk declared methods.
        assertThat(WireDoctorGhostCandidates.isEntryPoint(PrivateScheduledBean.class)).isTrue();
    }

    @Test
    void scheduledInSuperclassIsDetected() {
        assertThat(WireDoctorGhostCandidates.isEntryPoint(ScheduledSubclassBean.class)).isTrue();
    }

    @Test
    void eventListenerHolderIsEntryPoint() {
        assertThat(WireDoctorGhostCandidates.isEntryPoint(EventListenerBean.class)).isTrue();
    }

    @Test
    void commandLineRunnerIsEntryPoint() {
        assertThat(WireDoctorGhostCandidates.isEntryPoint(RunnerBean.class)).isTrue();
    }

    @Test
    void applicationRunnerIsEntryPoint() {
        assertThat(WireDoctorGhostCandidates.isEntryPoint(AppRunnerBean.class)).isTrue();
    }

    @Test
    void inheritedRunnerInterfaceIsDetected() {
        // Interface implemented by the superclass, not the bean class itself.
        assertThat(WireDoctorGhostCandidates.isEntryPoint(InheritedRunnerBean.class)).isTrue();
    }

    // ── detect(): the three-signal cross ─────────────────────────────────────

    @Test
    void instantiatedPlainOrphanIsCandidate() {
        StaticApplicationContext context = contextWith("plain", PlainBean.class);
        WireDoctorGhostCandidates.Result result =
                WireDoctorGhostCandidates.detect(context.getBeanFactory(), List.of("plain"));

        assertThat(result.candidates).containsExactly("plain");
        assertThat(result.entryPointsExcluded).isZero();
        assertThat(result.notInstantiatedExcluded).isZero();
    }

    @Test
    void entryPointOrphanIsExcludedAndCounted() {
        StaticApplicationContext context = contextWith("runner", RunnerBean.class);
        WireDoctorGhostCandidates.Result result =
                WireDoctorGhostCandidates.detect(context.getBeanFactory(), List.of("runner"));

        assertThat(result.candidates).isEmpty();
        assertThat(result.entryPointsExcluded).isEqualTo(1);
    }

    @Test
    void notInstantiatedOrphanIsExcludedAndCounted() {
        // Registered but never instantiated (no refresh/getBean) — a lazy bean
        // that was never touched costs nothing and must not be called a ghost.
        StaticApplicationContext context = new StaticApplicationContext();
        context.registerBeanDefinition("lazyOrphan",
                new org.springframework.beans.factory.support.RootBeanDefinition(PlainBean.class));

        WireDoctorGhostCandidates.Result result =
                WireDoctorGhostCandidates.detect(context.getBeanFactory(), List.of("lazyOrphan"));

        assertThat(result.candidates).isEmpty();
        assertThat(result.notInstantiatedExcluded).isEqualTo(1);
    }

    @Test
    void unresolvableTypeIsExcludedConservatively() {
        // A singleton whose type cannot be resolved from the factory: registered
        // directly without a bean definition of a concrete class — getType on a
        // factory-object indirection can return null. Simulate with a singleton
        // whose registered type resolution fails by using a name with no definition
        // and no singleton: the classification failure path must exclude, not add.
        StaticApplicationContext context = contextWith("plain", PlainBean.class);
        WireDoctorGhostCandidates.Result result = WireDoctorGhostCandidates.detect(
                context.getBeanFactory(), List.of("plain", "doesNotExist"));

        // "doesNotExist" is not in the singleton cache → counted not-instantiated,
        // never a candidate and never an exception.
        assertThat(result.candidates).containsExactly("plain");
        assertThat(result.notInstantiatedExcluded).isEqualTo(1);
    }

    @Test
    void mixedOrphansSplitCorrectly() {
        StaticApplicationContext context = new StaticApplicationContext();
        context.registerSingleton("ghost", PlainBean.class);
        context.registerSingleton("controller", ControllerBean.class);
        context.registerSingleton("scheduler", ScheduledBean.class);
        context.refresh();

        WireDoctorGhostCandidates.Result result = WireDoctorGhostCandidates.detect(
                context.getBeanFactory(), List.of("ghost", "controller", "scheduler"));

        assertThat(result.candidates).containsExactly("ghost");
        assertThat(result.entryPointsExcluded).isEqualTo(2);
    }

    @Test
    void emptyOrphanListYieldsEmptyResult() {
        StaticApplicationContext context = new StaticApplicationContext();
        context.refresh();

        WireDoctorGhostCandidates.Result result =
                WireDoctorGhostCandidates.detect(context.getBeanFactory(), List.of());

        assertThat(result.candidates).isEmpty();
        assertThat(result.entryPointsExcluded).isZero();
        assertThat(result.notInstantiatedExcluded).isZero();
    }

    // ── toReportMap ──────────────────────────────────────────────────────────

    @Test
    void reportMapCarriesHonestWording() {
        WireDoctorGhostCandidates.Result result = new WireDoctorGhostCandidates.Result(
                List.of("a", "b"), 3, 1);
        Map<String, Object> map = WireDoctorGhostCandidates.toReportMap(result);

        assertThat(map.get("confidence")).isEqualTo("LOW");
        assertThat((String) map.get("disclaimer")).contains("NOT proof of dead code");
        assertThat(map.get("count")).isEqualTo(2);
        assertThat(map.get("beans")).isEqualTo(List.of("a", "b"));
        assertThat(map.get("entryPointsExcluded")).isEqualTo(3);
        assertThat(map.get("notInstantiatedExcluded")).isEqualTo(1);
    }

    @Test
    void disclaimerNeverClaimsUnused() {
        // The feature contract: never say "unused" — a bean idle during one run
        // may be the month-end batch job.
        assertThat(WireDoctorGhostCandidates.DISCLAIMER.toLowerCase()).doesNotContain("unused");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static StaticApplicationContext contextWith(String name, Class<?> type) {
        StaticApplicationContext context = new StaticApplicationContext();
        context.registerSingleton(name, type);
        context.refresh();
        return context;
    }
}
