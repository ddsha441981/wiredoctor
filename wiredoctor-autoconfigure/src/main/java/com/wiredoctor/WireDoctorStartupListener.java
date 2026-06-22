package com.wiredoctor;

import org.springframework.boot.context.event.ApplicationStartingEvent;
import org.springframework.boot.context.metrics.buffering.BufferingApplicationStartup;
import org.springframework.context.ApplicationListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
public class WireDoctorStartupListener implements ApplicationListener<ApplicationStartingEvent> {
    private static final Logger log = LoggerFactory.getLogger(WireDoctorStartupListener.class);

    @Override
    public void onApplicationEvent(ApplicationStartingEvent event) {
        log.info(WireDoctorMessages.INTERCEPTING_STARTUP);
        event.getSpringApplication().setApplicationStartup(new BufferingApplicationStartup(10000));
    }
}
