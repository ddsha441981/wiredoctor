/*
 * Copyright (c) 2026 Deendayal Kumawat
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package com.wiredoctor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link WireDoctorHtmlReporter} (v0.6.1 tabbed console UI):
 * the template/injection contract that the analyzer and the shutdown ghost
 * re-render both rely on.
 */
class WireDoctorHtmlReporterTest {

    private static final String MINIMAL_REPORT = """
            {"activeProfiles":[],"dependencies":{"totalBeans":1,"totalEdges":0,
            "cyclesCount":0,"cycles":[],"orphanBeansCount":0,"orphanBeans":[],"graph":{"a":[]}},
            "proxies":{"cglibCount":0,"jdkCount":0,"cglibBeans":[],"jdkBeans":[]},
            "beanCategories":{"totalBeans":1,"userDefined":1,"frameworkOwned":0}}""";

    @Test
    void writesSelfContainedHtmlWithAllInjectionsResolved(@TempDir Path tempDir) throws Exception {
        WireDoctorHtmlReporter.generateHtmlReport(MINIMAL_REPORT, tempDir.toFile());

        File html = tempDir.resolve("wiredoctor-report.html").toFile();
        assertThat(html).exists();
        String content = Files.readString(html.toPath());

        // Every injection point must be resolved — a leftover marker means a
        // broken page (raw JS comment where data should be).
        assertThat(content)
                .doesNotContain("DATA_INJECTION_POINT")
                .doesNotContain("GHOST_INJECTION_POINT")
                .doesNotContain("VERSION_INJECTION_POINT")
                .doesNotContain("VIS_NETWORK_INJECTION_POINT");

        // Offline promise: the bundled vis-network is inlined, not CDN-linked.
        assertThat(content).contains("Almende");
        assertThat(content).doesNotContain("src=\"https://unpkg.com");

        // The report data made it in.
        assertThat(content).contains("\"totalBeans\"");
    }

    @Test
    void startupRenderInjectsNullGhostReport(@TempDir Path tempDir) throws Exception {
        // At ApplicationReadyEvent ghost data does not exist yet — the template
        // must receive the literal null so the Ghosts tab renders its
        // "results pending" state instead of crashing.
        WireDoctorHtmlReporter.generateHtmlReport(MINIMAL_REPORT, tempDir.toFile());

        String content = Files.readString(tempDir.resolve("wiredoctor-report.html"));
        assertThat(content).contains("const ghostReport = null;");
    }

    @Test
    void shutdownRenderInjectsGhostReport(@TempDir Path tempDir) throws Exception {
        String ghostJson = "{\"trackedCount\":2,\"touchedCount\":1,\"untouchedCount\":1,"
                + "\"untouched\":[\"ghostBean\"],\"touched\":[\"usedBean\"],"
                + "\"untrackableCount\":0,\"untrackable\":{},\"frameworkSkipped\":5}";
        WireDoctorHtmlReporter.generateHtmlReport(MINIMAL_REPORT, ghostJson, tempDir.toFile());

        String content = Files.readString(tempDir.resolve("wiredoctor-report.html"));
        assertThat(content).contains("const ghostReport = {\"trackedCount\":2");
        assertThat(content).doesNotContain("const ghostReport = null;");
    }

    @Test
    void tabbedConsoleStructureIsPresent(@TempDir Path tempDir) throws Exception {
        WireDoctorHtmlReporter.generateHtmlReport(MINIMAL_REPORT, tempDir.toFile());
        String content = Files.readString(tempDir.resolve("wiredoctor-report.html"));

        // The v0.6.1 UI contract: every report section has a tab.
        assertThat(content)
                .contains("id:'overview'").contains("id:'graph'").contains("id:'ghosts'")
                .contains("id:'smells'").contains("id:'timing'").contains("id:'conditions'");
        // Honest-wording strings survive templating.
        assertThat(content).contains("neither ever claims");
    }
}
