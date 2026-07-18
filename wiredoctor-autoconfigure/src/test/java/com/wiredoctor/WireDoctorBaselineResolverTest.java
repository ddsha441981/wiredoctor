/*
 * Copyright (c) 2026 Deendayal Kumawat
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package com.wiredoctor;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link WireDoctorBaselineResolver}: profile-key derivation
 * (default, sorting, de-duplication, blank handling) and {@code {profiles}}
 * token substitution including the no-token/null passthrough that preserves
 * pre-v0.4.0 single-baseline behavior.
 */
class WireDoctorBaselineResolverTest {

    @Test
    void profileKeyIsDefaultWhenNoProfilesActive() {
        assertThat(WireDoctorBaselineResolver.profileKey(null)).isEqualTo("default");
        assertThat(WireDoctorBaselineResolver.profileKey(new String[0])).isEqualTo("default");
    }

    @Test
    void profileKeyIsSingleProfileVerbatim() {
        assertThat(WireDoctorBaselineResolver.profileKey(new String[]{"prod"}))
                .isEqualTo("prod");
    }

    @Test
    void profileKeyIsOrderIndependentAndDashJoined() {
        // Activation order must not change the key — same baseline either way.
        assertThat(WireDoctorBaselineResolver.profileKey(new String[]{"dev", "db"}))
                .isEqualTo("db-dev");
        assertThat(WireDoctorBaselineResolver.profileKey(new String[]{"db", "dev"}))
                .isEqualTo("db-dev");
    }

    @Test
    void profileKeyTrimsDedupesAndDropsBlanks() {
        assertThat(WireDoctorBaselineResolver.profileKey(
                new String[]{" prod ", "prod", "", "  "}))
                .isEqualTo("prod");
    }

    @Test
    void profileKeyIsDefaultWhenOnlyBlankProfiles() {
        assertThat(WireDoctorBaselineResolver.profileKey(new String[]{"", "   "}))
                .isEqualTo("default");
    }

    @Test
    void resolveSubstitutesProfilesToken() {
        String resolved = WireDoctorBaselineResolver.resolve(
                "config/wiredoctor-baseline-{profiles}.json", new String[]{"prod"});
        assertThat(resolved).isEqualTo("config/wiredoctor-baseline-prod.json");
    }

    @Test
    void resolveUsesDefaultKeyForTokenWhenNoProfiles() {
        assertThat(WireDoctorBaselineResolver.resolve(
                "wiredoctor-baseline-{profiles}.json", new String[0]))
                .isEqualTo("wiredoctor-baseline-default.json");
    }

    @Test
    void resolveLeavesPathUnchangedWithoutToken() {
        // Backward compat: no token → single shared baseline as before v0.4.0.
        String path = "wiredoctor-baseline.json";
        assertThat(WireDoctorBaselineResolver.resolve(path, new String[]{"prod"}))
                .isEqualTo(path);
    }

    @Test
    void resolveHandlesNullPath() {
        assertThat(WireDoctorBaselineResolver.resolve(null, new String[]{"prod"}))
                .isNull();
    }
}
