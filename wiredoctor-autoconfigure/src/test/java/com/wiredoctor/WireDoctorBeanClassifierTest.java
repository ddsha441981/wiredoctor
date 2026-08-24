/*
 * Copyright (c) 2026 Deendayal Kumawat
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package com.wiredoctor;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link WireDoctorBeanClassifier}.
 */
class WireDoctorBeanClassifierTest {

    // ── isFrameworkPackage ────────────────────────────────────────────────────

    @Test
    void springPackageIsFramework() {
        assertThat(WireDoctorBeanClassifier.isFrameworkPackage("org.springframework.context"))
                .isTrue();
    }

    @Test
    void javaLangIsFramework() {
        assertThat(WireDoctorBeanClassifier.isFrameworkPackage("java.lang")).isTrue();
    }

    @Test
    void jakartaIsFramework() {
        assertThat(WireDoctorBeanClassifier.isFrameworkPackage("jakarta.persistence")).isTrue();
    }

    @Test
    void userPackageIsNotFramework() {
        assertThat(WireDoctorBeanClassifier.isFrameworkPackage("com.myapp.service"))
                .isFalse();
    }

    @Test
    void nullPackageIsNotFramework() {
        assertThat(WireDoctorBeanClassifier.isFrameworkPackage(null)).isFalse();
    }

    @Test
    void thirdPartyVendorPackageIsNotFrameworkByDefault() {
        // com.azure.spring is not in FRAMEWORK_PACKAGES; the scan-packages
        // allowlist approach is used for vendor filtering instead.
        assertThat(WireDoctorBeanClassifier.isFrameworkPackage("com.azure.spring.cloud"))
                .isFalse();
    }

    // ── isWellKnownFrameworkBean ─────────────────────────────────────────────

    @Test
    void environmentIsWellKnownFrameworkBean() {
        assertThat(WireDoctorBeanClassifier.isWellKnownFrameworkBean("environment"))
                .isTrue();
    }

    @Test
    void systemPropertiesIsWellKnownFrameworkBean() {
        assertThat(WireDoctorBeanClassifier.isWellKnownFrameworkBean("systemProperties"))
                .isTrue();
    }

    @Test
    void systemEnvironmentIsWellKnownFrameworkBean() {
        assertThat(WireDoctorBeanClassifier.isWellKnownFrameworkBean("systemEnvironment"))
                .isTrue();
    }

    @Test
    void applicationStartupIsWellKnownFrameworkBean() {
        assertThat(WireDoctorBeanClassifier.isWellKnownFrameworkBean("applicationStartup"))
                .isTrue();
    }

    @Test
    void messageSourceIsWellKnownFrameworkBean() {
        assertThat(WireDoctorBeanClassifier.isWellKnownFrameworkBean("messageSource"))
                .isTrue();
    }

    @Test
    void userBeanNameIsNotWellKnown() {
        assertThat(WireDoctorBeanClassifier.isWellKnownFrameworkBean("myService"))
                .isFalse();
    }

    @Test
    void unknownInfraNameIsNotWellKnown() {
        // Errs toward showing unknown beans rather than silently dropping them.
        assertThat(WireDoctorBeanClassifier.isWellKnownFrameworkBean("someObscureName"))
                .isFalse();
    }

    // ── WireDoctor's own beans (1.1.2) ───────────────────────────────────────

    @Test
    void wireDoctorOwnBeansAreRecognised() {
        assertThat(WireDoctorBeanClassifier.isWireDoctorBean("wireDoctorAnalyzer")).isTrue();
        assertThat(WireDoctorBeanClassifier.isWireDoctorBean("wireDoctorEndpoint")).isTrue();
        assertThat(WireDoctorBeanClassifier.isWireDoctorBean(
                "wiredoctor-com.wiredoctor.WireDoctorProperties")).isTrue();
        assertThat(WireDoctorBeanClassifier.isWireDoctorBean(
                "com.wiredoctor.WireDoctorAutoConfiguration")).isTrue();
    }

    @Test
    void userBeansAreNotMistakenForWireDoctorBeans() {
        assertThat(WireDoctorBeanClassifier.isWireDoctorBean("ownerService")).isFalse();
        assertThat(WireDoctorBeanClassifier.isWireDoctorBean("petTypeFormatter")).isFalse();
        // The tool's own test fixtures live in com.wiredoctor but are named plainly —
        // matching on the type package instead would classify all of them framework.
        assertThat(WireDoctorBeanClassifier.isWireDoctorBean("plainBean")).isFalse();
    }
}
