/*
 * Copyright (c) 2026 Deendayal Kumawat
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package com.wiredoctor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextClosedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Files;
import java.util.Map;

/**
 * Writes the ghost report at context shutdown (v0.6.0 Phase 2).
 * <p>
 * Ghosts are only knowable at the <em>end</em> of a run: a bean untouched at
 * minute one may be invoked at minute thirty. This listener captures the final
 * tracking state on {@link ContextClosedEvent} and writes it to
 * {@code wiredoctor-ghost-report.json} in the configured output directory.
 * <p>
 * Only registered when ghost tracking is enabled. Fully defensive: any failure
 * is logged and swallowed — shutdown of the host application is never
 * disturbed by report writing.
 *
 * @author Deendayal Kumawat
 * @since 0.6.0
 */
public class WireDoctorGhostReportWriter implements ApplicationListener<ContextClosedEvent> {

    private static final Logger log = LoggerFactory.getLogger(WireDoctorGhostReportWriter.class);

    /** File name of the shutdown ghost report inside {@code wiredoctor.output-path}. */
    static final String REPORT_FILE_NAME = "wiredoctor-ghost-report.json";

    private final WireDoctorGhostTracker tracker;
    private final WireDoctorProperties properties;

    /**
     * @param tracker    the tracking state to snapshot at shutdown
     * @param properties supplies the output directory
     */
    public WireDoctorGhostReportWriter(WireDoctorGhostTracker tracker,
                                       WireDoctorProperties properties) {
        this.tracker = tracker;
        this.properties = properties;
    }

    @Override
    public void onApplicationEvent(ContextClosedEvent event) {
        try {
            Map<String, Object> ghostReport = tracker.toReportMap();

            ObjectMapper mapper = new ObjectMapper();
            mapper.enable(SerializationFeature.INDENT_OUTPUT);
            File outputDir = new File(properties.getOutputPath());
            if (!outputDir.exists()) outputDir.mkdirs();
            File reportFile = new File(outputDir, REPORT_FILE_NAME);
            Files.writeString(reportFile.toPath(), mapper.writeValueAsString(ghostReport));

            log.info(WireDoctorMessages.GHOST_REPORT_SAVED, reportFile.getAbsolutePath());
            log.info(WireDoctorMessages.GHOST_REPORT_SUMMARY,
                     ghostReport.get("touchedCount"),
                     ghostReport.get("untouchedCount"),
                     ghostReport.get("untrackableCount"));
        } catch (Throwable t) {
            // Shutdown must never be disturbed by a diagnostic write.
            log.warn(WireDoctorMessages.GHOST_REPORT_WRITE_FAILED, t.getMessage());
        }
    }
}
