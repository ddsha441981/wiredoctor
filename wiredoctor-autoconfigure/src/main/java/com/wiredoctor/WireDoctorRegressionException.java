/*
 * Copyright (c) 2026 Deendayal Kumawat
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package com.wiredoctor;

/**
 * Thrown when a configured regression gate ({@code wiredoctor.fail-on}) is
 * tripped — e.g. the current bean graph contains a cycle that is absent from
 * the committed baseline.
 * <p>
 * This is the ONLY exception WireDoctor ever lets escape into the host
 * application, and only when the user explicitly opted in via
 * {@code wiredoctor.fail-on}. It propagates out of
 * {@code SpringApplication.run()} so a CI step running the app fails with a
 * non-zero exit code.
 *
 * @author Deendayal Kumawat
 * @since 0.2.0
 */
public class WireDoctorRegressionException extends RuntimeException {

    /**
     * @param message description of the tripped gate(s)
     */
    public WireDoctorRegressionException(String message) {
        super(message);
    }
}
