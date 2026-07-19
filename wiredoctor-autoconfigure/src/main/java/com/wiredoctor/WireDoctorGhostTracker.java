/*
 * Copyright (c) 2026 Deendayal Kumawat
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package com.wiredoctor;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Shared state of the opt-in ghost tracking feature (v0.6.0 Phase 2): which
 * beans are wrapped in a first-touch counting proxy, which have been invoked,
 * and which could not be tracked (and why).
 * <p>
 * Registered as a bean only when {@code wiredoctor.ghost-tracking.enabled=true}
 * — in the default configuration this class is never instantiated. The
 * {@link WireDoctorGhostTrackingPostProcessor} writes into it during startup
 * wrapping and (via the proxies) at first invocation; the shutdown report
 * writer and the optional actuator endpoint read from it. All state is
 * concurrent: proxy flips happen on arbitrary application threads.
 * <p>
 * The report deliberately says <em>touched / untouched / untrackable</em> —
 * never "unused". A bean idle during a five-minute dev run may be the
 * month-end batch job; confidence scales with how long and how realistically
 * the application ran.
 *
 * @author Deendayal Kumawat
 * @since 0.6.0
 */
public class WireDoctorGhostTracker {

    /** The honest-wording contract, embedded in every ghost report payload. */
    static final String DISCLAIMER =
            "'untouched' means no proxied method was invoked during THIS run — NOT that the bean "
            + "is unused. Confidence scales with run duration and traffic realism. Framework beans "
            + "are out of scope; untrackable beans could not be wrapped (reason given).";

    /** Bean name → first-touch flag, flipped by the counting proxy. */
    private final Map<String, AtomicBoolean> tracked = new ConcurrentHashMap<>();

    /** Bean name → reason it could not be wrapped (already-proxied, final class, ...). */
    private final Map<String, String> untrackable = new ConcurrentHashMap<>();

    /** Framework-owned beans skipped as out of scope (count only — hundreds in any Boot app). */
    private final java.util.concurrent.atomic.AtomicInteger frameworkSkipped =
            new java.util.concurrent.atomic.AtomicInteger();

    /**
     * Registers a bean for tracking and returns its first-touch flag — the
     * single {@code AtomicBoolean} the counting proxy flips.
     *
     * @param beanName the bean being wrapped
     * @return the flag to flip on first invocation
     */
    AtomicBoolean track(String beanName) {
        AtomicBoolean touched = new AtomicBoolean(false);
        tracked.put(beanName, touched);
        return touched;
    }

    /**
     * Records a bean that could not be wrapped. Every skip is counted and
     * reported — never silently hidden.
     *
     * @param beanName the skipped bean
     * @param reason   short machine-stable reason ({@code already-proxied},
     *                 {@code factory-bean}, {@code proxy-failed:...}, ...)
     */
    void markUntrackable(String beanName, String reason) {
        untrackable.put(beanName, reason);
    }

    /** Counts a framework-owned bean skipped as out of scope. */
    void countFrameworkSkipped() {
        frameworkSkipped.incrementAndGet();
    }

    /** @return number of beans currently wrapped in a counting proxy */
    public int trackedCount() {
        return tracked.size();
    }

    /**
     * Builds the {@code ghostReport} section from the current tracking state.
     * Pure read — safe to call at any time (shutdown listener, actuator
     * endpoint), reflecting invocations up to this moment.
     *
     * @return an ordered map ready for Jackson
     */
    public Map<String, Object> toReportMap() {
        // Sorted for deterministic output — reports may be committed/diffed.
        Map<String, Boolean> byName = new TreeMap<>();
        tracked.forEach((name, touched) -> byName.put(name, touched.get()));

        java.util.List<String> touchedBeans = new java.util.ArrayList<>();
        java.util.List<String> untouchedBeans = new java.util.ArrayList<>();
        byName.forEach((name, touched) -> {
            if (touched) touchedBeans.add(name);
            else untouchedBeans.add(name);
        });

        Map<String, Object> map = new LinkedHashMap<>();
        map.put("disclaimer", DISCLAIMER);
        map.put("trackedCount", tracked.size());
        map.put("touchedCount", touchedBeans.size());
        map.put("untouchedCount", untouchedBeans.size());
        map.put("untouched", untouchedBeans);
        map.put("touched", touchedBeans);
        map.put("untrackableCount", untrackable.size());
        map.put("untrackable", new TreeMap<>(untrackable));
        map.put("frameworkSkipped", frameworkSkipped.get());
        return map;
    }
}
