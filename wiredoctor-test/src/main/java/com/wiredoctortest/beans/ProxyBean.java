package com.wiredoctortest.beans;

import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Async;

@Component
public class ProxyBean {
    @Async
    public void doSomethingAsync() {
    }
}
