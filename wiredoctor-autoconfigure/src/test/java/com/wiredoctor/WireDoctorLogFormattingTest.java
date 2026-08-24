/*
 * Copyright (c) 2026 Deendayal Kumawat
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package com.wiredoctor;

import org.junit.jupiter.api.Test;
import org.slf4j.helpers.MessageFormatter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the number formatting used in console output and in the machine-readable
 * gate status file.
 * <p>
 * v1.1.0 shipped three log templates containing Python-style specifiers
 * ({@code {:+d}}, {@code {:.1f}}). SLF4J only interpolates {@code {}}, so those were
 * printed literally and every following argument shifted one slot, producing
 * {@code "Startup Time: 6687ms -> ms ({:+d}ms, {:.1f}% 7178)"}. These tests fail if
 * that defect class returns.
 */
class WireDoctorLogFormattingTest {

    @Test
    void signedRendersAnExplicitSign() {
        assertThat(WireDoctorAnalyzer.signed(491)).isEqualTo("+491");
        assertThat(WireDoctorAnalyzer.signed(-491)).isEqualTo("-491");
        assertThat(WireDoctorAnalyzer.signed(0)).isEqualTo("+0");
    }

    @Test
    void oneDecimalKeepsADotSeparatorUnderAnyDefaultLocale() {
        Locale original = Locale.getDefault();
        try {
            // GERMANY formats decimals with a comma; wiredoctor-gate.status is grepped
            // by CI, so the separator must not follow the machine's locale.
            Locale.setDefault(Locale.GERMANY);
            assertThat(WireDoctorAnalyzer.oneDecimal(7.34260505458352)).isEqualTo("7.3");
            assertThat(WireDoctorAnalyzer.oneDecimal(31.1)).isEqualTo("31.1");
        }
        finally {
            Locale.setDefault(original);
        }
    }

    @Test
    void startupTimeSummaryRendersEveryValue() {
        WireDoctorBaselineDiff.StartupTimeRegression regression =
                new WireDoctorBaselineDiff.StartupTimeRegression(6687, 7178);

        String rendered = MessageFormatter.arrayFormat(
                "⏱ Startup Time: {}ms -> {}ms ({}ms, {}% {})",
                new Object[] {
                        regression.baselineMs(),
                        regression.currentMs(),
                        WireDoctorAnalyzer.signed(regression.deltaMs()),
                        WireDoctorAnalyzer.oneDecimal(Math.abs(regression.percentChange() * 100)),
                        regression.deltaMs() >= 0 ? "slower" : "faster" })
                .getMessage();

        assertThat(rendered).isEqualTo("⏱ Startup Time: 6687ms -> 7178ms (+491ms, 7.3% slower)");
    }

    @Test
    void noLogTemplateInTheModuleUsesANonSlf4jSpecifier() throws IOException {
        Path sources = Path.of("src", "main", "java");
        assertThat(sources).as("module source root — test must not silently pass").isDirectory();

        // log.info("... {:+d} ...") and friends: a '{' followed by anything but '}'.
        Pattern badTemplate = Pattern.compile("log\\.(?:trace|debug|info|warn|error)\\(\\s*\"[^\"]*\\{[^}]");
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(sources)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                Matcher m = badTemplate.matcher(Files.readString(file));
                while (m.find()) {
                    offenders.add(file.getFileName() + ": " + m.group());
                }
            }
        }
        assertThat(offenders)
                .as("SLF4J only interpolates {} — a specifier inside the braces is printed "
                        + "literally and shifts every later argument")
                .isEmpty();
    }

    // ── FactoryBean prefix in human-facing output (1.1.2) ────────────────────

    @Test
    void factoryBeanPrefixIsSpelledOutForHumans() {
        assertThat(WireDoctorMessages.displayBean("&entityManagerFactory"))
                .isEqualTo("entityManagerFactory (FactoryBean)");
    }

    @Test
    void ordinaryBeanNamesPassThroughUntouched() {
        assertThat(WireDoctorMessages.displayBean("entityManagerFactory"))
                .isEqualTo("entityManagerFactory");
        assertThat(WireDoctorMessages.displayBean("jpaSharedEM_entityManagerFactory"))
                .isEqualTo("jpaSharedEM_entityManagerFactory");
    }
}
