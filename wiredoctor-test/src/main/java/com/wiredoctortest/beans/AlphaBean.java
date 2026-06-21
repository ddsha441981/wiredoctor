package com.wiredoctortest.beans;

import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import jakarta.annotation.PostConstruct;

@Component
public class AlphaBean {
    private BetaBean betaBean;

    @Autowired
    public void setBetaBean(BetaBean betaBean) {
        this.betaBean = betaBean;
    }
    
    @PostConstruct
    public void init() throws InterruptedException {
        // Deliberate slowdown to test ApplicationStartup timing
        Thread.sleep(50);
    }
}
