/*
 * Copyright (c) 2026 Deendayal Kumawat
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package com.wiredoctor;

import java.util.List;

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
}
