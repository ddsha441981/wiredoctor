package com.wiredoctor;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@ConditionalOnProperty(
    name = "wiredoctor.enabled",
    havingValue = "true",
    matchIfMissing = true
)
public class WireDoctorAutoConfiguration {

    @Bean
    public WireDoctorAnalyzer wireDoctorAnalyzer() {
        return new WireDoctorAnalyzer();
    }
}
