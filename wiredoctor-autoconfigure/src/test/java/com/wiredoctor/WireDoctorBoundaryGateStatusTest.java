/*
 * Copyright (c) 2026 Deendayal Kumawat
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package com.wiredoctor;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WD-707 (v1.2.1) — boundary verdict folded into the gate.status line-1 contract
 * ({@code PASS} / {@code FAIL:<gate>[,...]}), so the baseline-free boundary gate
 * gets the same grep-able CI fallback the diff gates have.
 */
class WireDoctorBoundaryGateStatusTest {

    @Test
    void passBecomesBoundaryFailWhenViolated() {
        assertThat(WireDoctorAnalyzer.mergeBoundaryIntoVerdict("PASS", true))
                .isEqualTo("FAIL:boundary-violation");
    }

    @Test
    void appendsToAnExistingDiffFailList() {
        assertThat(WireDoctorAnalyzer.mergeBoundaryIntoVerdict("FAIL:new-cycle,startup-time", true))
                .isEqualTo("FAIL:new-cycle,startup-time,boundary-violation");
    }

    @Test
    void isIdempotentWhenAlreadyPresent() {
        assertThat(WireDoctorAnalyzer.mergeBoundaryIntoVerdict("FAIL:boundary-violation", true))
                .isEqualTo("FAIL:boundary-violation");
    }

    @Test
    void leavesVerdictUntouchedWhenNoViolation() {
        assertThat(WireDoctorAnalyzer.mergeBoundaryIntoVerdict("PASS", false)).isEqualTo("PASS");
        assertThat(WireDoctorAnalyzer.mergeBoundaryIntoVerdict("FAIL:new-cycle", false))
                .isEqualTo("FAIL:new-cycle");
    }
}
