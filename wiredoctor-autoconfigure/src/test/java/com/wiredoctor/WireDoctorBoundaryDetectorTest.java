/*
 * Copyright (c) 2026 Deendayal Kumawat
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package com.wiredoctor;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WD-702 (v1.2.0) — multi-module boundary detector.
 * <p>
 * Two-module fixture (orders, billing): a cross-module edge into an internal
 * package is flagged, an edge into a declared API package is not, same-module
 * and framework/unmapped edges are ignored, and the feature is a no-op when no
 * modules are configured.
 */
class WireDoctorBoundaryDetectorTest {

    // pkg-prefix → module
    private static final Map<String, String> MODULES = Map.of(
            "com.acme.orders", "orders",
            "com.acme.billing", "billing");

    private static final List<String> API = List.of("*.api");

    // bean → package (framework beans are filtered out by the caller, so a
    // framework dependency simply never appears in this map).
    private static final Map<String, String> PKGS = Map.of(
            "orderService", "com.acme.orders",
            "orderRepo", "com.acme.orders.internal",
            "billingApi", "com.acme.billing.api",
            "billingApiDto", "com.acme.billing.api.dto",
            "billingInternal", "com.acme.billing.internal");

    private static List<WireDoctorBoundaryDetector.Violation> detect(Map<String, String[]> graph) {
        return WireDoctorBoundaryDetector.detect(graph, PKGS, MODULES, API);
    }

    @Test
    void crossModuleEdgeIntoInternalPackage_isFlagged() {
        var v = detect(Map.of("orderService", new String[]{"billingInternal"}));
        assertThat(v).hasSize(1);
        assertThat(v.get(0).sourceModule()).isEqualTo("orders");
        assertThat(v.get(0).targetModule()).isEqualTo("billing");
        assertThat(v.get(0).targetBean()).isEqualTo("billingInternal");
        assertThat(v.get(0).targetPackage()).isEqualTo("com.acme.billing.internal");
    }

    @Test
    void crossModuleEdgeIntoApiPackage_isNotFlagged() {
        assertThat(detect(Map.of("orderService", new String[]{"billingApi"}))).isEmpty();
    }

    @Test
    void apiSubPackageIsAlsoPublic() {
        assertThat(detect(Map.of("orderService", new String[]{"billingApiDto"}))).isEmpty();
    }

    @Test
    void sameModuleEdge_isNotFlagged() {
        // orders → orders.internal is the module's own business.
        assertThat(detect(Map.of("orderService", new String[]{"orderRepo"}))).isEmpty();
    }

    @Test
    void frameworkOrUnmappedTarget_isIgnored() {
        // A dependency not present in beanPackages (framework bean the caller
        // filtered, or a bean in no declared module) cannot be attributed.
        assertThat(detect(Map.of("orderService", new String[]{"dataSource"}))).isEmpty();
    }

    @Test
    void featureOff_noModules_returnsEmpty_evenForInternalEdge() {
        var v = WireDoctorBoundaryDetector.detect(
                Map.of("orderService", new String[]{"billingInternal"}),
                PKGS, Map.of(), API);
        assertThat(v).isEmpty();
    }

    @Test
    void longestPrefixWins_forNestedModules() {
        // com.acme → core, but com.acme.orders → orders (more specific).
        var modules = Map.of("com.acme", "core", "com.acme.orders", "orders");
        var pkgs = Map.of(
                "coreBean", "com.acme.core",
                "ordersInternal", "com.acme.orders.internal");
        var v = WireDoctorBoundaryDetector.detect(
                Map.of("coreBean", new String[]{"ordersInternal"}),
                pkgs, modules, List.of());
        assertThat(v).hasSize(1);
        assertThat(v.get(0).sourceModule()).isEqualTo("core");
        assertThat(v.get(0).targetModule()).isEqualTo("orders");
    }

    @Test
    void prefixBoundaryIsRespected_comFooDoesNotMatchComFoobar() {
        // com.acme.orders must not swallow com.acme.ordersx.
        assertThat(WireDoctorBoundaryDetector.moduleOf("com.acme.ordersx", MODULES)).isNull();
        assertThat(WireDoctorBoundaryDetector.moduleOf("com.acme.orders", MODULES)).isEqualTo("orders");
        assertThat(WireDoctorBoundaryDetector.moduleOf("com.acme.orders.sub", MODULES)).isEqualTo("orders");
    }
}
