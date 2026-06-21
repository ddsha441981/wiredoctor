package com.wiredoctor;

import org.springframework.boot.context.metrics.buffering.BufferingApplicationStartup;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;

public class WireDoctorContextInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {
    @Override
    public void initialize(ConfigurableApplicationContext context) {
        System.out.println("[WireDoctor] Forcing BufferingApplicationStartup via ContextInitializer...");
        // Override any previous ApplicationStartup with our buffer right before beans start initializing
        context.setApplicationStartup(new BufferingApplicationStartup(10000));
    }
}
