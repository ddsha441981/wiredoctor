/*
 * Copyright (c) 2026 Deendayal Kumawat
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package com.wiredoctor;

import org.springframework.boot.context.event.ApplicationStartingEvent;
import org.springframework.boot.context.metrics.buffering.BufferingApplicationStartup;
import org.springframework.context.ApplicationListener;
import org.springframework.core.metrics.ApplicationStartup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
/**
 * Early-stage {@link ApplicationListener} that intercepts the context lifecycle
 * to inject a {@link BufferingApplicationStartup}.
 * <p>
 * This allows WireDoctor to collect high-fidelity, microsecond-accurate metrics
 * for bean instantiation and post-processing steps before the core application
 * logic begins execution.
 * <p>
 * Since v0.8.0 the listener is polite about it: if the application (or another
 * tool) has already installed its own {@link ApplicationStartup}, WireDoctor
 * <em>keeps it</em> instead of silently overwriting it. If the existing one is
 * a {@link BufferingApplicationStartup}, timings work as usual; otherwise the
 * timing section of the report degrades gracefully (the analyzer already
 * handles a non-buffering startup) and a WARN explains why.
 *
 * @author Deendayal Kumawat
 * @since 0.1.0
 */
public class WireDoctorStartupListener implements ApplicationListener<ApplicationStartingEvent> {
    private static final Logger log = LoggerFactory.getLogger(WireDoctorStartupListener.class);

    /**
     * Handles the initial {@link ApplicationStartingEvent} to install a buffering
     * startup metric collector — unless one was already set by the application.
     *
     * @param event the starting event containing the Spring application instance
     */
    @Override
    public void onApplicationEvent(ApplicationStartingEvent event) {
        ApplicationStartup existing = event.getSpringApplication().getApplicationStartup();
        if (existing != null && existing != ApplicationStartup.DEFAULT) {
            // The app (or another tool) installed its own ApplicationStartup —
            // never overwrite someone else's instrumentation.
            if (existing instanceof BufferingApplicationStartup) {
                log.info(WireDoctorMessages.STARTUP_ALREADY_BUFFERING);
            } else {
                log.warn(WireDoctorMessages.STARTUP_FOREIGN_DETECTED, existing.getClass().getName());
            }
            return;
        }
        log.info(WireDoctorMessages.INTERCEPTING_STARTUP);
        event.getSpringApplication().setApplicationStartup(new BufferingApplicationStartup(10000));
    }
}
