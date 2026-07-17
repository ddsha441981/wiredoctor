package com.wiredoctortest.beans;

import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * A lazy bean that must NEVER be instantiated by WireDoctor's analysis.
 * If WireDoctor force-instantiates beans (the v0.1.0 intrusion bug), the
 * loud constructor message below will appear at report time.
 */
@Lazy
@Component
public class LazyBean {

    public LazyBean() {
        System.err.println("!!! LazyBean INSTANTIATED — if this prints during WireDoctor analysis, the zero-intrusion promise is broken !!!");
    }

    public String doWork() {
        return "lazy work done";
    }
}
