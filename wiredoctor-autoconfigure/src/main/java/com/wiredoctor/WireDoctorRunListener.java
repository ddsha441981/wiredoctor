package com.wiredoctor;

import org.springframework.boot.ConfigurableBootstrapContext;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringApplicationRunListener;
import org.springframework.boot.context.metrics.buffering.BufferingApplicationStartup;
import org.springframework.core.env.ConfigurableEnvironment;

import java.time.Duration;

public class WireDoctorRunListener implements SpringApplicationRunListener {

    private final SpringApplication application;
    private final String[] args;

    public WireDoctorRunListener(SpringApplication application, String[] args) {
        this.application = application;
        this.args = args;
    }

    @Override
    public void starting(ConfigurableBootstrapContext bootstrapContext) {
        System.out.println("[WireDoctor] Intercepting startup to register BufferingApplicationStartup...");
        // Capacity of 10000 should be enough for most applications
        BufferingApplicationStartup startup = new BufferingApplicationStartup(10000);
        this.application.setApplicationStartup(startup);
    }
}
