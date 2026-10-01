/*
 * Copyright (c) 2026 Deendayal Kumawat
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package com.wiredoctor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Multi-module boundary detector (WD-702, v1.2.0).
 * <p>
 * Given the resolved bean dependency graph and a package-prefix → module map,
 * flags every edge that crosses from one module into another module's
 * <em>internal</em> code — i.e. the target package is not part of that
 * module's declared public API ({@code api-packages}). This is hidden coupling
 * that compiles and runs fine but erodes modularity silently.
 * <p>
 * Pure and stateless: no Spring types, no IO. The caller resolves each bean to
 * its package (framework beans already filtered out) and passes the config.
 * With no modules configured the detector returns an empty list immediately —
 * zero overhead when the feature is off.
 */
public final class WireDoctorBoundaryDetector {

    private WireDoctorBoundaryDetector() {
    }

    /**
     * One hidden-coupling edge: {@code sourceBean} (in {@code sourceModule})
     * depends on {@code targetBean}, which lives in {@code targetModule}'s
     * internal package {@code targetPackage} rather than its public API.
     */
    public record Violation(String sourceBean, String targetBean,
                            String sourceModule, String targetModule,
                            String targetPackage) {
    }

    /**
     * @param graph        bean name → dependency bean names (the resolved graph)
     * @param beanPackages bean name → package (user beans only; framework beans
     *                     should already be excluded by the caller)
     * @param modules      package-prefix → module name; empty = feature off
     * @param apiPackages  glob patterns for public-API target packages
     * @return every cross-module edge into non-API code; never null
     */
    public static List<Violation> detect(Map<String, String[]> graph,
                                         Map<String, String> beanPackages,
                                         Map<String, String> modules,
                                         List<String> apiPackages) {
        if (modules == null || modules.isEmpty() || graph == null || graph.isEmpty()) {
            return List.of();
        }
        List<Pattern> apiPatterns = compileApiPatterns(apiPackages);
        List<Violation> violations = new ArrayList<>();

        for (Map.Entry<String, String[]> edge : graph.entrySet()) {
            String sourceBean = edge.getKey();
            String sourcePkg = beanPackages.get(sourceBean);
            if (sourcePkg == null) continue;                       // unmapped/framework source
            String sourceModule = moduleOf(sourcePkg, modules);
            if (sourceModule == null) continue;                    // source not in any module

            String[] targets = edge.getValue();
            if (targets == null) continue;
            for (String targetBean : targets) {
                String targetPkg = beanPackages.get(targetBean);
                if (targetPkg == null) continue;
                String targetModule = moduleOf(targetPkg, modules);
                if (targetModule == null || targetModule.equals(sourceModule)) {
                    continue;                                      // same module or unmapped
                }
                if (matchesAny(targetPkg, apiPatterns)) continue;  // allowed public API

                violations.add(new Violation(sourceBean, targetBean,
                                             sourceModule, targetModule, targetPkg));
            }
        }
        return violations;
    }

    /**
     * Module owning {@code pkg}: the module whose configured prefix is the
     * longest one that {@code pkg} sits under, or {@code null} if none. Longest
     * wins so nested modules resolve correctly (e.g. {@code com.acme} vs
     * {@code com.acme.orders}).
     */
    static String moduleOf(String pkg, Map<String, String> modules) {
        String best = null;
        int bestLen = -1;
        for (Map.Entry<String, String> e : modules.entrySet()) {
            String prefix = e.getKey();
            if (prefix == null || prefix.isEmpty()) continue;
            if (isUnderPrefix(pkg, prefix) && prefix.length() > bestLen) {
                best = e.getValue();
                bestLen = prefix.length();
            }
        }
        return best;
    }

    /** {@code pkg} is the prefix itself or a sub-package — not {@code com.foobar} under {@code com.foo}. */
    private static boolean isUnderPrefix(String pkg, String prefix) {
        return pkg.equals(prefix) || pkg.startsWith(prefix + ".");
    }

    private static List<Pattern> compileApiPatterns(List<String> apiPackages) {
        if (apiPackages == null || apiPackages.isEmpty()) return List.of();
        List<Pattern> patterns = new ArrayList<>();
        for (String glob : apiPackages) {
            if (glob == null || glob.isBlank()) continue;
            // glob → regex: literal segments quoted, '*' → '.*', trailing
            // "(\..*)?" so a matched package's sub-packages count as API too.
            String regex = Arrays.stream(glob.trim().split("\\*", -1))
                    .map(Pattern::quote)
                    .collect(Collectors.joining(".*"));
            patterns.add(Pattern.compile("^" + regex + "(\\..*)?$"));
        }
        return patterns;
    }

    private static boolean matchesAny(String pkg, List<Pattern> patterns) {
        for (Pattern p : patterns) {
            if (p.matcher(pkg).matches()) return true;
        }
        return false;
    }

    /**
     * Serializes violations into the JSON report shape (WD-703, v1.2.0).
     * Additive — {@code schemaVersion} stays 1; ordered keys mirror the
     * {@link Violation} record.
     *
     * @param violations detected cross-module boundary violations
     * @return list of ordered maps ready for Jackson serialization; never null
     */
    public static List<Map<String, Object>> toReportList(List<Violation> violations) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Violation v : violations) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("sourceBean",    v.sourceBean());
            entry.put("targetBean",    v.targetBean());
            entry.put("sourceModule",  v.sourceModule());
            entry.put("targetModule",  v.targetModule());
            entry.put("targetPackage", v.targetPackage());
            out.add(entry);
        }
        return out;
    }
}
