/*
 * Copyright (c) 2026 Deendayal Kumawat
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package com.wiredoctor;

import org.springframework.boot.context.metrics.buffering.BufferingApplicationStartup;
import org.springframework.core.metrics.StartupStep;

/**
 * A specialized subclass of {@link BufferingApplicationStartup} that captures the
 * instantiating thread for each bean natively.
 * <p>
 * By extending the exact class expected by Spring Boot Actuator, this implementation
 * remains fully compliant with the `/actuator/startup` endpoint's type checks,
 * avoiding the zero-intrusion violations that occur when using a generic interface
 * decorator.
 *
 * @author Deendayal Kumawat
 * @since 1.1.0
 */
public class WireDoctorBufferingApplicationStartup extends BufferingApplicationStartup {

    public static final int DEFAULT_CAPACITY = 4096;
    private static final String BEAN_INSTANTIATE_STEP = "spring.beans.instantiate";

    public WireDoctorBufferingApplicationStartup(int capacity) {
        super(capacity);
    }

    @Override
    public StartupStep start(String name) {
        // Calling super.start() persists the step into the timeline buffer immediately.
        StartupStep step = super.start(name);
        
        // We only add thread-tagging overhead for actual bean instantiations.
        // Spring Boot already tags this step with 'beanName', so this combines perfectly.
        if (BEAN_INSTANTIATE_STEP.equals(name)) {
            step.tag("threadName", Thread.currentThread().getName());
        }
        
        return step;
    }
}
