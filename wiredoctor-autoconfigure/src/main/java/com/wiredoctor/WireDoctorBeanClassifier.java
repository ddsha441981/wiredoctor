/*
 * Copyright (c) 2026 Deendayal Kumawat
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package com.wiredoctor;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Single source of truth for classifying a bean as framework-owned versus
 * user-defined, based on the package of its resolved type.
 * <p>
 * The heuristic is the well-known-framework-package prefix list below. It is
 * used both for the bean-category summary and — since v0.4.0 — to filter the
 * architecture-smell rankings down to beans a user can actually refactor
 * (framework beans genuinely dominate the coupling hotspots of any Boot app,
 * so ranking them first buries the actionable signal).
 * <p>
 * A bean whose type or package cannot be resolved is treated as
 * <em>not</em> framework (i.e. user-defined): the smell filter should err
 * toward showing a bean rather than silently dropping it, and the synthetic
 * {@code ...ApplicationContext@hash} entries that carry no package fall out of
 * the user-bean lists for free.
 *
 * @author Deendayal Kumawat
 * @since 0.4.0
 */
public final class WireDoctorBeanClassifier {

    /** Package prefixes that indicate a framework-owned (non-user) bean. */
    static final List<String> FRAMEWORK_PACKAGES = List.of(
            "org.springframework", "org.apache", "com.sun",
            "java.", "javax.", "jakarta.", "io.netty",
            "com.fasterxml", "io.micrometer"
    );

    /**
     * Well-known Spring infrastructure singletons registered outside
     * {@code beanDefinitionNames} (e.g. via {@code registerSingleton}). Their
     * names are plain words, not FQNs, so {@link #isFrameworkPackage} cannot
     * catch them.  The set is intentionally small; unknown beans default to
     * user-defined (show rather than silently drop).
     */
    static final Set<String> WELL_KNOWN_FRAMEWORK_NAMES = Set.of(
            "environment", "systemProperties", "systemEnvironment",
            "applicationStartup", "messageSource"
    );

    private WireDoctorBeanClassifier() {
        // Static utility
    }

    /**
     * @param packageName the package of a bean's resolved type, or {@code null}
     * @return {@code true} when the package matches a known framework prefix
     */
    public static boolean isFrameworkPackage(String packageName) {
        if (packageName == null) {
            return false;
        }
        return FRAMEWORK_PACKAGES.stream().anyMatch(packageName::startsWith);
    }

    /**
     * @param beanName a bean name as returned by the bean factory
     * @return {@code true} when the name is a well-known Spring infrastructure
     *         singleton that lives outside {@code beanDefinitionNames}
     */
    public static boolean isWellKnownFrameworkBean(String beanName) {
        return WELL_KNOWN_FRAMEWORK_NAMES.contains(beanName);
    }

    /**
     * WireDoctor's own beans ({@code wireDoctorAnalyzer},
     * {@code wiredoctor-com.wiredoctor.WireDoctorProperties},
     * {@code com.wiredoctor.WireDoctorAutoConfiguration}, the actuator endpoint).
     * <p>
     * They are infrastructure from the user's point of view: nobody can refactor
     * them in response to a coupling smell, so the tool ranking itself in its own
     * report is noise. Matched by bean name rather than by type package because
     * WireDoctor's own tests declare their fixtures in {@code com.wiredoctor} —
     * a package prefix on the type would classify every fixture as framework.
     *
     * @param beanName a bean name as returned by the bean factory
     * @return {@code true} when the bean was registered by WireDoctor itself
     * @since 1.1.2
     */
    public static boolean isWireDoctorBean(String beanName) {
        String lower = beanName.toLowerCase(Locale.ROOT);
        return lower.startsWith("wiredoctor") || lower.startsWith("com.wiredoctor.");
    }
}
