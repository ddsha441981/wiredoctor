/*
 * Copyright (c) 2026 Deendayal Kumawat
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package com.wiredoctor;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility responsible for generating the single-file, interactive HTML diagnostic report.
 * <p>
 * Since v0.6.1 the report UI is a tabbed diagnostic console (Overview / Graph /
 * Ghosts / Smells / Timing / Conditions) loaded from the classpath template
 * {@code /wiredoctor/report-template.html} — every report section has a home in
 * the HTML, not just the graph. The serialized JSON context is injected into
 * the template, alongside the vis-network graph library (dual-licensed
 * Apache-2.0 / MIT, © visjs contributors), which is bundled on the classpath
 * and inlined so the generated file is fully self-contained and renders
 * offline. Only if the bundled resource is missing does the template fall back
 * to loading the library from a CDN.
 * <p>
 * The ghost-tracking section supports late injection: the HTML is normally
 * written at {@code ApplicationReadyEvent} (ghost data does not exist yet →
 * {@code null} injected, the tab shows how to read the shutdown/live results);
 * when opt-in tracking is enabled, {@link WireDoctorGhostReportWriter}
 * re-renders the report at shutdown with the final ghost data filled in.
 *
 * @author Deendayal Kumawat
 * @since 0.1.0
 */
public class WireDoctorHtmlReporter {
    private static final Logger log = LoggerFactory.getLogger(WireDoctorHtmlReporter.class);

    /** Classpath location of the bundled vis-network standalone UMD build. */
    static final String VIS_NETWORK_RESOURCE = "/wiredoctor/vis-network.min.js";

    /** Classpath location of the report UI template (v0.6.1 tabbed console). */
    static final String TEMPLATE_RESOURCE = "/wiredoctor/report-template.html";

    /**
     * Loads the bundled vis-network library as an inline {@code <script>} block.
     *
     * @return the inline script tag, or a CDN {@code <script src>} tag when the
     *         bundled resource cannot be read (never returns broken HTML)
     */
    private static String visNetworkScriptTag() {
        try (InputStream in = WireDoctorHtmlReporter.class.getResourceAsStream(VIS_NETWORK_RESOURCE)) {
            if (in != null) {
                String js = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                // vis-network is dual-licensed Apache-2.0 / MIT; the bundle keeps its
                // own copyright header. Attribution comment required for redistribution.
                return "<!-- vis-network (https://visjs.github.io/vis-network/), "
                        + "(c) 2011-2017 Almende B.V., (c) 2017+ vis.js contributors. "
                        + "Dual-licensed Apache-2.0 / MIT. Inlined for offline use. -->\n"
                        + "    <script type=\"text/javascript\">" + js + "</script>";
            }
        } catch (Exception e) {
            log.warn(WireDoctorMessages.VIS_BUNDLE_UNAVAILABLE, e.getMessage());
        }
        log.warn(WireDoctorMessages.VIS_BUNDLE_UNAVAILABLE, "resource not found on classpath");
        return "<script type=\"text/javascript\" src=\"https://unpkg.com/vis-network/standalone/umd/vis-network.min.js\"></script>";
    }

    /**
     * Loads the bundled report UI template.
     *
     * @return the template HTML, or {@code null} when the resource is missing
     *         (the caller logs and skips HTML generation — the JSON report is
     *         unaffected)
     */
    private static String loadTemplate() {
        try (InputStream in = WireDoctorHtmlReporter.class.getResourceAsStream(TEMPLATE_RESOURCE)) {
            if (in != null) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            log.error(WireDoctorMessages.FAILED_WRITE_HTML, e.getMessage());
        }
        return null;
    }

    /**
     * @return the artifact version for the header chip, or {@code dev} when
     *         unavailable (exploded classpath, tests)
     */
    private static String version() {
        String v = WireDoctorHtmlReporter.class.getPackage() != null
                ? WireDoctorHtmlReporter.class.getPackage().getImplementationVersion() : null;
        return v != null ? "v" + v : "dev";
    }

    /**
     * Generates and writes the HTML report to the specified output directory.
     * Ghost-tracking data is not yet available at this point (it exists only at
     * shutdown), so the Ghosts tab renders its Phase 2 card in the
     * "results pending" state.
     *
     * @param jsonReport the serialized JSON representation of the diagnostic data
     * @param outputDir  the target directory where the {@code wiredoctor-report.html} file will be written
     */
    public static void generateHtmlReport(String jsonReport, File outputDir) {
        generateHtmlReport(jsonReport, null, outputDir);
    }

    /**
     * Generates and writes the HTML report, optionally with ghost-tracking
     * results (used by {@link WireDoctorGhostReportWriter} to re-render the
     * report at shutdown once ghosts are knowable).
     *
     * @param jsonReport      the serialized JSON representation of the diagnostic data
     * @param ghostReportJson the serialized ghost report, or {@code null} when
     *                        tracking is disabled or results are not yet available
     * @param outputDir       the target directory for {@code wiredoctor-report.html}
     */
    public static void generateHtmlReport(String jsonReport, String ghostReportJson, File outputDir) {
        String template = loadTemplate();
        if (template == null) {
            log.error(WireDoctorMessages.FAILED_WRITE_HTML, "report template missing from classpath");
            return;
        }
        String html = template
                .replace("<!-- VIS_NETWORK_INJECTION_POINT -->", visNetworkScriptTag())
                .replace("<!-- VERSION_INJECTION_POINT -->", version())
                .replace("/* DATA_INJECTION_POINT */", jsonReport)
                .replace("/* GHOST_INJECTION_POINT */", ghostReportJson != null ? ghostReportJson : "null");

        try {
            File htmlFile = new File(outputDir, "wiredoctor-report.html");
            Files.writeString(htmlFile.toPath(), html);
            log.info(WireDoctorMessages.SAVED_HTML_REPORT, htmlFile.getAbsolutePath());
        } catch (Exception e) {
            log.error(WireDoctorMessages.FAILED_WRITE_HTML, e.getMessage());
        }
    }
}
