/*
 * Copyright (c) 2026 Deendayal Kumawat
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package com.wiredoctor;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Pure resolver for profile-keyed baseline paths (v0.4.0).
 * <p>
 * The bean graph differs between active profiles (a {@code prod} profile wires
 * beans a {@code dev} profile does not), so diffing a single shared baseline
 * across profiles produces spurious added/removed churn. This resolver lets the
 * {@code wiredoctor.baseline} path carry a {@code {profiles}} token that is
 * replaced with a stable key derived from the currently active profiles, so
 * each profile combination diffs against its own like-with-like baseline.
 * <p>
 * The token is opt-in: a path without {@code {profiles}} is returned unchanged,
 * preserving the pre-v0.4.0 single-baseline behavior. A missing per-profile
 * baseline degrades gracefully through the analyzer's existing
 * "baseline missing → info log → skip" path.
 *
 * @author Deendayal Kumawat
 * @since 0.4.0
 */
public final class WireDoctorBaselineResolver {

    /** Placeholder replaced with the active-profile key in a baseline path. */
    static final String PROFILES_TOKEN = "{profiles}";

    /** Key used when no profiles are active — the Spring "default" profile. */
    static final String DEFAULT_PROFILE_KEY = "default";

    private WireDoctorBaselineResolver() {
        // Static utility
    }

    /**
     * Derives a stable, filesystem-friendly key from the active profiles.
     * <p>
     * Profiles are trimmed, blanks dropped, de-duplicated, sorted, and joined
     * with {@code -} so the key is independent of activation order (a
     * {@code dev,db} run and a {@code db,dev} run share one baseline). Returns
     * {@value #DEFAULT_PROFILE_KEY} when no profiles are active.
     *
     * @param activeProfiles the environment's active profiles (may be {@code null} or empty)
     * @return the profile key, never {@code null}
     */
    public static String profileKey(String[] activeProfiles) {
        if (activeProfiles == null || activeProfiles.length == 0) {
            return DEFAULT_PROFILE_KEY;
        }
        String key = Arrays.stream(activeProfiles)
                .filter(p -> p != null && !p.isBlank())
                .map(String::trim)
                .distinct()
                .sorted()
                .collect(Collectors.joining("-"));
        return key.isEmpty() ? DEFAULT_PROFILE_KEY : key;
    }

    /**
     * Resolves a configured baseline path, substituting the {@value #PROFILES_TOKEN}
     * token (if present) with the active-profile key.
     *
     * @param rawPath        the configured {@code wiredoctor.baseline} value
     * @param activeProfiles the environment's active profiles
     * @return the resolved path; {@code rawPath} unchanged when it is
     *         {@code null} or contains no {@value #PROFILES_TOKEN} token
     */
    public static String resolve(String rawPath, String[] activeProfiles) {
        if (rawPath == null || !rawPath.contains(PROFILES_TOKEN)) {
            return rawPath;
        }
        return rawPath.replace(PROFILES_TOKEN, profileKey(activeProfiles));
    }
}
