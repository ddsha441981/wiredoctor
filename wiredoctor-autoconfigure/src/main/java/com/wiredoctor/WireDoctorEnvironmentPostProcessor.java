/*
 * Copyright (c) 2026 Deendayal Kumawat
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package com.wiredoctor;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.boot.context.metrics.buffering.BufferingApplicationStartup;
import org.springframework.core.NativeDetector;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.metrics.ApplicationStartup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;

/**
 * Replaces the default {@link ApplicationStartup} with {@link WireDoctorBufferingApplicationStartup}.
 * <p>
 * This processor runs exactly before context preparation, ensuring that the SpringApplication
 * correctly binds the updated startup tracker.
 *
 * @author Deendayal Kumawat
 * @since 1.1.0
 */
public class WireDoctorEnvironmentPostProcessor implements EnvironmentPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(WireDoctorEnvironmentPostProcessor.class);

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (NativeDetector.inNativeImage()) {
            return;
        }

        ApplicationStartup current = application.getApplicationStartup();

        if (current instanceof WireDoctorBufferingApplicationStartup) {
            return;
        }

        if (current instanceof BufferingApplicationStartup bas) {
            if (bas.getClass() == BufferingApplicationStartup.class) {
                int capacity = extractCapacity(bas);
                application.setApplicationStartup(
                    new WireDoctorBufferingApplicationStartup(capacity)
                );
            } else {
                log.warn(WireDoctorMessages.STARTUP_FOREIGN_DETECTED, bas.getClass().getName());
                log.warn("WireDoctor thread-distribution disabled: custom ApplicationStartup subclass '{}' detected.", bas.getClass().getName());
            }
        } else {
            application.setApplicationStartup(
                new WireDoctorBufferingApplicationStartup(WireDoctorBufferingApplicationStartup.DEFAULT_CAPACITY)
            );
        }
    }

    private int extractCapacity(BufferingApplicationStartup bas) {
        try {
            Field capacityField = BufferingApplicationStartup.class.getDeclaredField("capacity");
            capacityField.setAccessible(true);
            return capacityField.getInt(bas);
        } catch (Exception ex) {
            log.debug("Failed to extract capacity from BufferingApplicationStartup. Using default.", ex);
            return WireDoctorBufferingApplicationStartup.DEFAULT_CAPACITY;
        }
    }
}
