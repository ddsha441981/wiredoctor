/*
 * Copyright (c) 2026 Deendayal Kumawat
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package com.wiredoctor;

import org.springframework.boot.context.event.ApplicationStartingEvent;
import org.springframework.boot.context.metrics.buffering.BufferingApplicationStartup;
import org.springframework.context.ApplicationListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
/**
 * Early-stage {@link ApplicationListener} that intercepts the context lifecycle
 * to inject a {@link BufferingApplicationStartup}.
 * <p>
 * This allows WireDoctor to collect high-fidelity, microsecond-accurate metrics 
 * for bean instantiation and post-processing steps before the core application 
 * logic begins execution.
 *
 * @author Deendayal Kumawat
 * @since 0.1.0
 */
public class WireDoctorStartupListener implements ApplicationListener<ApplicationStartingEvent> {
    private static final Logger log = LoggerFactory.getLogger(WireDoctorStartupListener.class);

    /**
     * Handles the initial {@link ApplicationStartingEvent} to override the default 
     * startup metric collector with a buffering implementation.
     *
     * @param event the starting event containing the Spring application instance
     */
    @Override
    public void onApplicationEvent(ApplicationStartingEvent event) {
        log.info(WireDoctorMessages.INTERCEPTING_STARTUP);
        event.getSpringApplication().setApplicationStartup(new BufferingApplicationStartup(10000));
    }
}
