package com.wiredoctor;

import org.springframework.boot.context.event.ApplicationStartingEvent;
import org.springframework.boot.context.metrics.buffering.BufferingApplicationStartup;
import org.springframework.context.ApplicationListener;

public class WireDoctorStartupListener implements ApplicationListener<ApplicationStartingEvent> {
    @Override
    public void onApplicationEvent(ApplicationStartingEvent event) {
        System.out.println("[WireDoctor] Intercepting startup to register BufferingApplicationStartup...");
        event.getSpringApplication().setApplicationStartup(new BufferingApplicationStartup(10000));
    }
}
