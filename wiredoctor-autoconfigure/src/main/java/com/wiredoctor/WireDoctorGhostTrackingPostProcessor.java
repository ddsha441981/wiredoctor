/*
 * Copyright (c) 2026 Deendayal Kumawat
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package com.wiredoctor;

import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.aop.framework.AopInfrastructureBean;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.config.SmartInstantiationAwareBeanPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.util.ClassUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Opt-in first-touch tracking (v0.6.0 Phase 2): wraps eligible user singletons
 * in a thin counting proxy so the ghost report can distinguish beans that were
 * actually invoked from beans that only sat in memory.
 * <p>
 * <b>This class is never registered unless the user explicitly opts in</b> via
 * {@code wiredoctor.ghost-tracking.enabled=true} — the {@code @ConditionalOnProperty}
 * guard in {@link WireDoctorAutoConfiguration} keeps the default artifact
 * behavior 100% passive (the doctor's rule: no medicine without consent). When
 * enabled, the docs say plainly: this wraps beans — dev/staging, not prod.
 * <p>
 * The proxy does exactly ONE thing: flip an {@link AtomicBoolean} on first
 * invocation. No timing, no argument capture, no logging in the hot path —
 * after first touch, the overhead is a single volatile read per call.
 * <p>
 * <b>Eligibility guards (skip, never break)</b> — every skip is counted and
 * reported as untrackable, never silently hidden:
 * <ul>
 *   <li>framework-owned beans (out of scope — counted, not listed)</li>
 *   <li>already-proxied beans ({@code @Transactional}/{@code @Async} — never
 *       double-wrap; this processor runs at lowest precedence so existing
 *       proxies are visible)</li>
 *   <li>{@link FactoryBean}s and AOP infrastructure</li>
 *   <li>interface-less final classes (CGLIB cannot subclass them)</li>
 *   <li>non-singleton beans (a shared flag per name would be misleading)</li>
 *   <li>beans listed in {@code wiredoctor.ghost-tracking.exclude}</li>
 * </ul>
 * <b>Failure posture:</b> any wrapping error logs a warning and returns the
 * bean unwrapped, counted untrackable. A diagnostic tool must never turn a
 * working bean into a broken one.
 *
 * @author Deendayal Kumawat
 * @since 0.6.0
 */
public class WireDoctorGhostTrackingPostProcessor
        implements SmartInstantiationAwareBeanPostProcessor, Ordered {

    private static final Logger log =
            LoggerFactory.getLogger(WireDoctorGhostTrackingPostProcessor.class);

    /** Untrackable reasons — machine-stable strings surfaced in the report. */
    static final String REASON_ALREADY_PROXIED = "already-proxied";
    static final String REASON_FACTORY_BEAN    = "factory-bean";
    static final String REASON_FINAL_CLASS     = "final-class";
    static final String REASON_NON_SINGLETON   = "non-singleton";
    static final String REASON_EXCLUDED        = "excluded";
    static final String REASON_PROXY_FAILED    = "proxy-failed";

    private final WireDoctorGhostTracker tracker;
    private final ConfigurableListableBeanFactory beanFactory;
    private final Set<String> excludedBeans;

    /**
     * Beans already wrapped via {@link #getEarlyBeanReference} (circular
     * reference participants). Same contract as Spring's own
     * {@code AbstractAutoProxyCreator#earlyBeanReferences}: when the raw bean
     * later arrives at {@link #postProcessAfterInitialization}, it must be
     * returned unchanged so the container substitutes the early proxy —
     * wrapping twice would make the early-injected reference and the singleton
     * diverge and fail context refresh.
     */
    private final Map<String, Object> earlyBeanReferences = new ConcurrentHashMap<>();

    /**
     * @param tracker       shared tracking state the proxies flip into
     * @param beanFactory   used read-only for singleton-scope checks
     * @param excludedBeans bean names from {@code wiredoctor.ghost-tracking.exclude}
     */
    public WireDoctorGhostTrackingPostProcessor(WireDoctorGhostTracker tracker,
                                                ConfigurableListableBeanFactory beanFactory,
                                                Set<String> excludedBeans) {
        this.tracker = tracker;
        this.beanFactory = beanFactory;
        this.excludedBeans = excludedBeans;
        // Loud, honest banner: the user opted into the ONE intrusive feature.
        log.info(WireDoctorMessages.GHOST_TRACKING_ENABLED);
    }

    /**
     * Lowest precedence: run AFTER the AOP infrastructure so beans that other
     * frameworks proxied ({@code @Transactional}, {@code @Async}) arrive here
     * already wrapped — and are skipped instead of double-wrapped.
     */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    /**
     * Circular-reference support: when a bean under creation is needed early
     * by another bean in the cycle, wrap it HERE so the early-injected
     * reference and the finished singleton are the same proxy instance —
     * exactly how Spring's own auto-proxy creator handles it. Without this,
     * a resolvable cycle (allow-circular-references=true) would fail with
     * "Bean named 'x' has been injected in its raw version".
     */
    @Override
    public Object getEarlyBeanReference(Object bean, String beanName) {
        try {
            Object wrapped = wrapIfEligible(bean, beanName);
            if (wrapped != bean) {
                earlyBeanReferences.put(beanName, bean);
            }
            return wrapped;
        } catch (Throwable t) {
            log.warn(WireDoctorMessages.GHOST_TRACKING_WRAP_FAILED, beanName, t.getMessage());
            tracker.markUntrackable(beanName, REASON_PROXY_FAILED + ": " + t.getMessage());
            return bean;
        }
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        // Already wrapped as an early reference (cycle participant): return the
        // raw bean so the container swaps in the early proxy itself.
        if (bean != null && earlyBeanReferences.remove(beanName) == bean) {
            return bean;
        }
        try {
            return wrapIfEligible(bean, beanName);
        } catch (Throwable t) {
            // Failure posture: warn, count, return the ORIGINAL bean untouched.
            log.warn(WireDoctorMessages.GHOST_TRACKING_WRAP_FAILED, beanName, t.getMessage());
            tracker.markUntrackable(beanName, REASON_PROXY_FAILED + ": " + t.getMessage());
            return bean;
        }
    }

    private Object wrapIfEligible(Object bean, String beanName) {
        if (bean == null) {
            return null;
        }
        Class<?> userClass = ClassUtils.getUserClass(bean.getClass());
        String packageName = userClass.getPackage() != null
                ? userClass.getPackage().getName() : null;

        // WireDoctor's own beans: tracking the tracker is noise, skip silently.
        // Covers both name forms: property-named beans ("wireDoctorAnalyzer")
        // and FQN-named configuration beans ("com.wiredoctor.WireDoctor...").
        // The @Configuration check below catches the rest of our config surface.
        if (beanName.toLowerCase().startsWith("wiredoctor")
                || beanName.startsWith("com.wiredoctor.WireDoctor")) {
            return bean;
        }

        // Framework beans are out of scope — hundreds per Boot app, counted only.
        if (WireDoctorBeanClassifier.isFrameworkPackage(packageName)
                || WireDoctorBeanClassifier.isWellKnownFrameworkBean(beanName)
                || WireDoctorBeanClassifier.isWireDoctorBean(beanName)) {
            tracker.countFrameworkSkipped();
            return bean;
        }
        // @Configuration classes (incl. the @SpringBootApplication class) do
        // their work at definition time — "untouched" would be pure noise.
        if (org.springframework.core.annotation.AnnotatedElementUtils
                .hasAnnotation(userClass, org.springframework.context.annotation.Configuration.class)) {
            return bean;
        }
        // Config-class beans are registered under their own FQN as the bean
        // name. Catch those the annotation check misses (observed on Boot 4:
        // Azure @AutoConfiguration classes slipped through and showed up as
        // "untouched" noise on start.spring.io).
        if (beanName.equals(userClass.getName())) {
            return bean;
        }
        if (excludedBeans.contains(beanName)) {
            tracker.markUntrackable(beanName, REASON_EXCLUDED);
            return bean;
        }
        if (bean instanceof FactoryBean || bean instanceof AopInfrastructureBean
                || bean instanceof BeanPostProcessor) {
            tracker.markUntrackable(beanName, REASON_FACTORY_BEAN);
            return bean;
        }
        if (AopUtils.isAopProxy(bean)) {
            tracker.markUntrackable(beanName, REASON_ALREADY_PROXIED);
            return bean;
        }
        if (!isSingleton(beanName)) {
            tracker.markUntrackable(beanName, REASON_NON_SINGLETON);
            return bean;
        }
        // ALWAYS a CGLIB subclass proxy, never a JDK interface proxy: a JDK
        // proxy is not assignable to the bean's concrete class, so any
        // injection point typed to the class (rather than an interface) fails
        // context refresh with "could not be injected because it is a JDK
        // dynamic proxy". Found live on start.spring.io
        // (springCloudAzureGlobalProperties). A subclass proxy is type-
        // compatible with every injection point. Consequence: final classes
        // are untrackable regardless of interfaces — counted, never risked.
        if (Modifier.isFinal(bean.getClass().getModifiers())) {
            tracker.markUntrackable(beanName, REASON_FINAL_CLASS);
            return bean;
        }

        AtomicBoolean touched = tracker.track(beanName);
        ProxyFactory proxyFactory = new ProxyFactory(bean);
        proxyFactory.setProxyTargetClass(true);
        // The entire hot path: one volatile read, one conditional write on
        // first touch only. Nothing else — no timing, no args, no logging.
        proxyFactory.addAdvice((MethodInterceptor) invocation -> {
            if (!touched.get()) {
                touched.set(true);
            }
            return invocation.proceed();
        });
        return proxyFactory.getProxy(bean.getClass().getClassLoader());
    }

    /**
     * Read-only singleton check; unresolvable definitions (manually registered
     * singletons have none) default to {@code true} — they live in the
     * singleton cache by construction.
     */
    private boolean isSingleton(String beanName) {
        try {
            if (beanFactory.containsBeanDefinition(beanName)) {
                return beanFactory.getBeanDefinition(beanName).isSingleton();
            }
        } catch (Exception ignored) {}
        return true;
    }
}
