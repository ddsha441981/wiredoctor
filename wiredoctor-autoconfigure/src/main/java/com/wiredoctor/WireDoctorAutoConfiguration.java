package com.wiredoctor;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
public class WireDoctorAutoConfiguration {

    @Bean
    public WireDoctorAnalyzer wireDoctorAnalyzer() {
        return new WireDoctorAnalyzer();
    }
}
