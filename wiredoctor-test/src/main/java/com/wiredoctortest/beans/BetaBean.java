package com.wiredoctortest.beans;

import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

@Component
public class BetaBean {
    private final AlphaBean alphaBean;

    @Autowired
    public BetaBean(AlphaBean alphaBean) {
        this.alphaBean = alphaBean;
    }
}
