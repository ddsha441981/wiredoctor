/*
 * Copyright (c) 2026 Deendayal Kumawat
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package com.wiredoctor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerResponse;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.web.reactive.function.server.RequestPredicates.GET;
import static org.springframework.web.reactive.function.server.RouterFunctions.route;

/**
 * WebFlux/reactive validation (v0.8.0, plan §3 check #4): boots a REACTIVE
 * (Netty, no servlet) application with WireDoctor auto-configured and asserts
 * the same trust guarantees the WebMVC integration tests pin — reports are
 * written, timings populated, graph non-empty, and the app is never crashed.
 * Also pins the reactive ghost posture: a {@code RouterFunction} bean is an
 * entry point, never a ghost candidate.
 */
class WireDoctorWebFluxIntegrationTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class ReactiveApp {

        @Bean
        RouterFunction<ServerResponse> pingRoute() {
            return route(GET("/ping"),
                    request -> ServerResponse.ok()
                            .contentType(MediaType.TEXT_PLAIN)
                            .bodyValue("pong"));
        }
    }

    private ConfigurableApplicationContext boot(String... properties) {
        List<String> props = new ArrayList<>(List.of("server.port=0"));
        props.addAll(List.of(properties));
        return new SpringApplicationBuilder(ReactiveApp.class)
                .web(WebApplicationType.REACTIVE)
                .properties(props.toArray(String[]::new))
                .run();
    }

    @Test
    void reactiveContextBootsAndWritesBothReports(@TempDir Path tempDir) throws Exception {
        try (ConfigurableApplicationContext context = boot(
                "wiredoctor.output-path=" + tempDir)) {
            assertThat(context.isActive()).isTrue();

            File json = tempDir.resolve("wiredoctor-report.json").toFile();
            File html = tempDir.resolve("wiredoctor-report.html").toFile();
            assertThat(json).exists().isNotEmpty();
            assertThat(html).exists().isNotEmpty();

            JsonNode report = new ObjectMapper().readTree(json);
            assertThat(report.path("dependencies").path("totalBeans").asInt()).isPositive();
            assertThat(report.has("proxies")).isTrue();
            assertThat(report.has("ghostCandidates")).isTrue();
        }
    }

    @Test
    void startupTimingsArePopulatedInReactiveMode(@TempDir Path tempDir) throws Exception {
        try (ConfigurableApplicationContext context = boot(
                "wiredoctor.output-path=" + tempDir)) {
            assertThat(context.isActive()).isTrue();

            JsonNode report = new ObjectMapper()
                    .readTree(tempDir.resolve("wiredoctor-report.json").toFile());
            // BufferingApplicationStartup must capture reactive context refresh
            // the same way it does for servlet apps.
            assertThat(report.path("startupSlowestSteps").isArray()).isTrue();
            assertThat(report.path("startupSlowestSteps").size()).isPositive();
        }
    }

    @Test
    void routerFunctionBeanIsNeverAGhostCandidate(@TempDir Path tempDir) throws Exception {
        try (ConfigurableApplicationContext context = boot(
                "wiredoctor.output-path=" + tempDir)) {
            assertThat(context.isActive()).isTrue();

            JsonNode report = new ObjectMapper()
                    .readTree(tempDir.resolve("wiredoctor-report.json").toFile());
            JsonNode candidates = report.path("ghostCandidates").path("beans");
            List<String> names = new ArrayList<>();
            candidates.forEach(node -> names.add(node.asText()));
            // A RouterFunction has zero incoming dependency edges by design —
            // without entry-point detection it would be flagged as a ghost.
            assertThat(names).doesNotContain("pingRoute");
        }
    }
}
