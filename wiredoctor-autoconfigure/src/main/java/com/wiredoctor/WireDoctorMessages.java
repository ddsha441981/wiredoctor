package com.wiredoctor;

public final class WireDoctorMessages {

    public static final String BANNER_TOP = "\n==================================================";
    public static final String BANNER_TEXT = " WIREDOCTOR ANALYSIS STARTING";
    public static final String BANNER_BOTTOM = "==================================================";
    
    public static final String INTERCEPTING_STARTUP = "[WireDoctor] Intercepting startup to register BufferingApplicationStartup...";
    public static final String STARTUP_NOT_BUFFERING_WARNING = "[WireDoctor] WARNING: ApplicationStartup is not BufferingApplicationStartup! It is: {}";
    
    public static final String SAVED_JSON_REPORT = "[WireDoctor] Saved detailed report to: {}";
    public static final String SAVED_HTML_REPORT = "[WireDoctor] Saved interactive HTML visualizer to: {}";
    
    public static final String FAILED_WRITE_JSON = "[WireDoctor] Failed to write reports: {}";
    public static final String FAILED_WRITE_HTML = "[WireDoctor] Failed to write HTML report: {}";
    
    public static final String SLOWEST_STEPS_HEADER = "[WireDoctor] Slowest Startup Steps:";
    public static final String SLOWEST_STEP_ITEM = "  - {} ({}ms)";
    
    public static final String CYCLES_HEADER = "\n[WireDoctor] Bean Dependency Cycles: {}";
    public static final String CYCLE_ITEM = "  - Cycle detected: {} -> {}";
    public static final String CYCLE_NOTE = "    (Note: Spring resolved this via proxy/setter, but structurally it is a cycle)";
    
    public static final String PROXY_HEADER = "\n[WireDoctor] Proxy Overhead:";
    public static final String PROXY_CGLIB_ITEM = "  - CGLIB Proxies: {}";
    public static final String PROXY_JDK_ITEM = "  - JDK Proxies: {}";
    
    public static final String BANNER_END = "==================================================\n";

    private WireDoctorMessages() {
        // Prevent instantiation
    }
}
