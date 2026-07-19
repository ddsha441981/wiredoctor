/*
 * Copyright (c) 2026 Deendayal Kumawat
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package com.wiredoctor;

import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link WireDoctorGhostTrackingPostProcessor} (v0.6.0 Phase 2):
 * the eligibility filter matrix and the first-touch flip mechanics.
 * <p>
 * Every skip path must return the ORIGINAL bean instance and record the
 * reason — a diagnostic tool must never turn a working bean into a broken one.
 */
class WireDoctorGhostTrackingPostProcessorTest {

    // ── Fixtures ─────────────────────────────────────────────────────────────

    interface Greeter {
        String greet();
    }

    static class GreeterImpl implements Greeter {
        @Override public String greet() { return "hi"; }
    }

    static class PlainBean {
        String work() { return "done"; }
    }

    static final class FinalBean {
        String work() { return "done"; }
    }

    static final class FinalWithInterface implements Greeter {
        @Override public String greet() { return "hi"; }
    }

    static class TestFactoryBean implements FactoryBean<PlainBean> {
        @Override public PlainBean getObject() { return new PlainBean(); }
        @Override public Class<?> getObjectType() { return PlainBean.class; }
    }

    private static WireDoctorGhostTrackingPostProcessor processor(WireDoctorGhostTracker tracker,
                                                                  Set<String> excluded) {
        return new WireDoctorGhostTrackingPostProcessor(
                tracker, new DefaultListableBeanFactory(), excluded);
    }

    private static WireDoctorGhostTrackingPostProcessor processor(WireDoctorGhostTracker tracker) {
        return processor(tracker, Set.of());
    }

    // ── Wrapping + first-touch mechanics ─────────────────────────────────────

    @Test
    void eligibleInterfaceBeanIsWrappedAndFlipsOnFirstTouch() {
        WireDoctorGhostTracker tracker = new WireDoctorGhostTracker();
        Object wrapped = processor(tracker)
                .postProcessAfterInitialization(new GreeterImpl(), "greeter");

        // Always a CGLIB subclass proxy — a JDK interface proxy would break
        // injection points typed to the concrete class.
        assertThat(AopUtils.isCglibProxy(wrapped)).isTrue();
        assertThat(wrapped).isInstanceOf(GreeterImpl.class);
        assertThat(tracker.trackedCount()).isEqualTo(1);

        Map<String, Object> before = tracker.toReportMap();
        assertThat(before.get("untouched")).isEqualTo(java.util.List.of("greeter"));

        // First invocation flips the flag; the call itself works normally.
        assertThat(((Greeter) wrapped).greet()).isEqualTo("hi");

        Map<String, Object> after = tracker.toReportMap();
        assertThat(after.get("touched")).isEqualTo(java.util.List.of("greeter"));
        assertThat(after.get("untouched")).isEqualTo(java.util.List.of());
    }

    @Test
    void interfaceLessBeanIsCglibWrappedAndStillWorks() {
        WireDoctorGhostTracker tracker = new WireDoctorGhostTracker();
        Object wrapped = processor(tracker)
                .postProcessAfterInitialization(new PlainBean(), "plain");

        assertThat(AopUtils.isCglibProxy(wrapped)).isTrue();
        assertThat(((PlainBean) wrapped).work()).isEqualTo("done");
        assertThat(tracker.toReportMap().get("touched"))
                .isEqualTo(java.util.List.of("plain"));
    }

    @Test
    void untouchedBeanStaysUntouchedInReport() {
        WireDoctorGhostTracker tracker = new WireDoctorGhostTracker();
        processor(tracker).postProcessAfterInitialization(new GreeterImpl(), "ghost");

        Map<String, Object> report = tracker.toReportMap();
        assertThat(report.get("untouchedCount")).isEqualTo(1);
        assertThat(report.get("touchedCount")).isEqualTo(0);
    }

    // ── Eligibility matrix: every skip returns the ORIGINAL instance ─────────

    @Test
    void alreadyProxiedBeanIsSkippedNotDoubleWrapped() {
        // Simulates a @Transactional/@Async bean arriving pre-proxied.
        ProxyFactory existing = new ProxyFactory(new GreeterImpl());
        Object preProxied = existing.getProxy();

        WireDoctorGhostTracker tracker = new WireDoctorGhostTracker();
        Object result = processor(tracker)
                .postProcessAfterInitialization(preProxied, "transactional");

        assertThat(result).isSameAs(preProxied);
        assertThat(untrackableReasons(tracker))
                .containsEntry("transactional", WireDoctorGhostTrackingPostProcessor.REASON_ALREADY_PROXIED);
    }

    @Test
    void finalClassWithoutInterfaceIsSkipped() {
        WireDoctorGhostTracker tracker = new WireDoctorGhostTracker();
        FinalBean bean = new FinalBean();
        Object result = processor(tracker).postProcessAfterInitialization(bean, "finalBean");

        assertThat(result).isSameAs(bean);
        assertThat(untrackableReasons(tracker))
                .containsEntry("finalBean", WireDoctorGhostTrackingPostProcessor.REASON_FINAL_CLASS);
    }

    @Test
    void finalClassWithInterfaceIsAlsoSkipped() {
        // Tracking is CGLIB-only (a JDK interface proxy is not assignable to
        // the concrete class and breaks class-typed injection points — found
        // live on start.spring.io). Final classes are untrackable, interfaces
        // or not.
        WireDoctorGhostTracker tracker = new WireDoctorGhostTracker();
        FinalWithInterface bean = new FinalWithInterface();
        Object result = processor(tracker)
                .postProcessAfterInitialization(bean, "finalGreeter");

        assertThat(result).isSameAs(bean);
        assertThat(untrackableReasons(tracker))
                .containsEntry("finalGreeter", WireDoctorGhostTrackingPostProcessor.REASON_FINAL_CLASS);
    }

    @Test
    void factoryBeanIsSkipped() {
        WireDoctorGhostTracker tracker = new WireDoctorGhostTracker();
        TestFactoryBean bean = new TestFactoryBean();
        Object result = processor(tracker).postProcessAfterInitialization(bean, "factory");

        assertThat(result).isSameAs(bean);
        assertThat(untrackableReasons(tracker))
                .containsEntry("factory", WireDoctorGhostTrackingPostProcessor.REASON_FACTORY_BEAN);
    }

    @Test
    void beanPostProcessorIsSkipped() {
        WireDoctorGhostTracker tracker = new WireDoctorGhostTracker();
        BeanPostProcessor bean = new BeanPostProcessor() {};
        Object result = processor(tracker).postProcessAfterInitialization(bean, "someBpp");

        assertThat(result).isSameAs(bean);
    }

    @Test
    void frameworkBeanIsSkippedAndCounted() {
        WireDoctorGhostTracker tracker = new WireDoctorGhostTracker();
        // java.lang.Object → "java." framework prefix.
        Object bean = new Object();
        Object result = processor(tracker).postProcessAfterInitialization(bean, "someFrameworkBean");

        assertThat(result).isSameAs(bean);
        assertThat(tracker.toReportMap().get("frameworkSkipped")).isEqualTo(1);
        assertThat(tracker.trackedCount()).isZero();
    }

    @Test
    void excludedBeanIsSkippedAndReported() {
        WireDoctorGhostTracker tracker = new WireDoctorGhostTracker();
        GreeterImpl bean = new GreeterImpl();
        Object result = processor(tracker, Set.of("noTrack"))
                .postProcessAfterInitialization(bean, "noTrack");

        assertThat(result).isSameAs(bean);
        assertThat(untrackableReasons(tracker))
                .containsEntry("noTrack", WireDoctorGhostTrackingPostProcessor.REASON_EXCLUDED);
    }

    @Test
    void nonSingletonBeanIsSkipped() {
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        RootBeanDefinition prototype = new RootBeanDefinition(GreeterImpl.class);
        prototype.setScope("prototype");
        beanFactory.registerBeanDefinition("proto", prototype);

        WireDoctorGhostTracker tracker = new WireDoctorGhostTracker();
        WireDoctorGhostTrackingPostProcessor processor =
                new WireDoctorGhostTrackingPostProcessor(tracker, beanFactory, Set.of());

        GreeterImpl bean = new GreeterImpl();
        Object result = processor.postProcessAfterInitialization(bean, "proto");

        assertThat(result).isSameAs(bean);
        assertThat(untrackableReasons(tracker))
                .containsEntry("proto", WireDoctorGhostTrackingPostProcessor.REASON_NON_SINGLETON);
    }

    @Test
    void wireDoctorOwnBeansAreSkippedSilently() {
        WireDoctorGhostTracker tracker = new WireDoctorGhostTracker();
        GreeterImpl bean = new GreeterImpl();
        Object result = processor(tracker)
                .postProcessAfterInitialization(bean, "wireDoctorAnalyzer");

        assertThat(result).isSameAs(bean);
        assertThat(tracker.trackedCount()).isZero();
        assertThat(untrackableReasons(tracker)).isEmpty();
    }

    @Test
    void nullBeanIsPassedThrough() {
        WireDoctorGhostTracker tracker = new WireDoctorGhostTracker();
        assertThat(processor(tracker).postProcessAfterInitialization(null, "whatever")).isNull();
    }

    // ── Report shape ─────────────────────────────────────────────────────────

    @Test
    void reportCarriesHonestWording() {
        WireDoctorGhostTracker tracker = new WireDoctorGhostTracker();
        Map<String, Object> report = tracker.toReportMap();

        assertThat((String) report.get("disclaimer")).contains("NOT that the bean is unused");
        assertThat(report).containsKeys(
                "trackedCount", "touchedCount", "untouchedCount",
                "touched", "untouched", "untrackable", "frameworkSkipped");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static Map<String, String> untrackableReasons(WireDoctorGhostTracker tracker) {
        return (Map<String, String>) tracker.toReportMap().get("untrackable");
    }
}
