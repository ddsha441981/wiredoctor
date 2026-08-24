/*
 * Copyright (c) 2026 Deendayal Kumawat
 *
 * SPDX-License-Identifier: MIT OR Apache-2.0
 */
package com.wiredoctor;

import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Controller;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;

import java.lang.annotation.Annotation;
import java.net.URL;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Ghost candidate detection, Phase 1 of the v0.6.0 Ghost Bean Detector —
 * 100% passive, zero new intrusion.
 * <p>
 * A <em>ghost candidate</em> is a bean that crosses three signals WireDoctor
 * already has, without instantiating or wrapping anything:
 * <ol>
 *   <li>it was <b>eagerly instantiated</b> at startup (present in the
 *       singleton cache — it cost memory and startup time), AND</li>
 *   <li>it has <b>0 incoming edges</b> in the resolved dependency graph
 *       (the existing orphan heuristic), AND</li>
 *   <li>it is <b>not a known entry-point type</b> — nothing detectable from
 *       metadata suggests the framework will ever invoke it dynamically.</li>
 * </ol>
 * This refines the orphan list ("nobody depends on it") into a stronger
 * signal ("nobody depends on it AND nothing is known to call it") while
 * staying a heuristic: reflective access, programmatic {@code getBean()}
 * lookups and framework-collected usages are invisible here. The section is
 * therefore labeled confidence {@code LOW} and carries a disclaimer — it must
 * never claim "unused", only "no known entry point was found".
 * <p>
 * Entry-point detection deliberately errs <b>broad</b>: a false positive here
 * (treating a non-entry-point as one) merely shrinks the ghost list, while a
 * false negative wrongly accuses a working bean. Detection covers class-level
 * stereotypes ({@code @Controller} and meta-annotations such as
 * {@code @RestController}), invocation-style method annotations
 * ({@code @Scheduled}, {@code @EventListener}, messaging listeners), and
 * framework-invoked interfaces ({@code CommandLineRunner},
 * {@code ApplicationRunner}, {@code Lifecycle}, servlet types, ...). Types
 * from optional classpaths (actuator, kafka, web) are matched by simple name
 * so the core keeps zero optional dependencies. Since 1.1.2 it also covers
 * <em>any</em> interface declared in a different artifact than the bean —
 * whoever owns the contract can resolve the bean by type
 * ({@link #isCollectedByTypeOwner}).
 * <p>
 * The candidate list sits <em>beside</em> the orphan list, not instead of it:
 * the orphan list stays the raw graph fact, this section is the refined
 * advice built on top of it.
 *
 * @author Deendayal Kumawat
 * @since 0.6.0
 */
public final class WireDoctorGhostCandidates {

    /** Confidence label for the report section — Phase 1 is a heuristic, deliberately LOW. */
    static final String CONFIDENCE = "LOW";

    /**
     * The honest-wording contract for this feature: candidates are never
     * claimed "unused" — only that no known entry point was found.
     */
    static final String DISCLAIMER =
            "Heuristic: bean was eagerly instantiated, has no incoming dependencies, and no known "
            + "entry point was detected from its metadata. NOT proof of dead code — reflective "
            + "access, programmatic getBean() lookups and framework-collected usages are invisible "
            + "to this analysis.";

    /**
     * Interfaces (matched by simple name across the full type hierarchy) whose
     * implementors the framework invokes without a dependency edge. Simple-name
     * matching keeps optional classpaths (web, actuator, messaging) out of the
     * core; a user interface that happens to share a name only shrinks the
     * ghost list — the safe direction.
     */
    static final Set<String> ENTRY_POINT_INTERFACES = Set.of(
            "CommandLineRunner", "ApplicationRunner",
            "ApplicationListener", "Lifecycle", "SmartLifecycle",
            "Filter", "Servlet", "WebFilter", "HandlerInterceptor",
            "HealthIndicator", "InfoContributor", "MessageListener",
            "RouterFunction", "WebHandler", "WebSocketHandler",
            "WebExceptionHandler"
    );

    /**
     * Method-level annotations (by simple name) that mark a bean as invoked by
     * the framework or an external trigger. {@code @Scheduled} and
     * {@code @EventListener} are additionally checked via
     * {@link AnnotatedElementUtils} so meta-annotated forms (e.g.
     * {@code @TransactionalEventListener}) are found; the rest live on optional
     * classpaths and match by name only.
     */
    static final Set<String> ENTRY_POINT_METHOD_ANNOTATIONS = Set.of(
            "Scheduled", "Schedules", "EventListener", "TransactionalEventListener",
            "KafkaListener", "RabbitListener", "JmsListener", "SqsListener",
            "MessageMapping", "SubscribeMapping"
    );

    /**
     * Class-level annotations (by simple name) that mark the whole bean as an
     * entry point or as definition-time infrastructure. {@code @Configuration}
     * and {@code @Aspect} are here as classic false-positive sources: a config
     * class does its work at definition time and an aspect is invoked via
     * weaving — neither ever earns an incoming dependency edge.
     */
    static final Set<String> ENTRY_POINT_CLASS_ANNOTATIONS = Set.of(
            "Controller", "RestController",
            "Endpoint", "WebEndpoint", "RestControllerEndpoint", "ControllerEndpoint",
            "ServerEndpoint", "Configuration", "Aspect"
    );

    private WireDoctorGhostCandidates() {
        // static utility
    }

    /**
     * Outcome of a detection run: the candidates plus honest exclusion counts,
     * so the report can say <em>why</em> the candidate list is shorter than the
     * orphan list instead of silently hiding beans.
     */
    public static final class Result {
        /** Orphan beans that are instantiated and have no known entry point. */
        public final List<String> candidates;
        /** Orphans excluded because an entry point was detected on their type. */
        public final int entryPointsExcluded;
        /** Orphans excluded because they were never instantiated (cost nothing). */
        public final int notInstantiatedExcluded;

        Result(List<String> candidates, int entryPointsExcluded, int notInstantiatedExcluded) {
            this.candidates = candidates;
            this.entryPointsExcluded = entryPointsExcluded;
            this.notInstantiatedExcluded = notInstantiatedExcluded;
        }
    }

    /**
     * Classifies the given orphan beans into ghost candidates.
     * <p>
     * Only the (already filtered, user-scoped) orphan list is examined —
     * every input bean is known to have 0 incoming edges, so this method adds
     * the two remaining signals: eager instantiation and entry-point absence.
     * Any classification failure on a bean excludes it conservatively (an
     * uncertain accusation is worse than a missed one).
     *
     * @param beanFactory the bean factory, used read-only ({@code containsSingleton}
     *                    and {@code getType} — nothing is instantiated)
     * @param orphanBeans beans with 0 incoming dependency edges (the analyzer's
     *                    existing orphan list)
     * @return the ghost candidates plus exclusion counts
     */
    public static Result detect(ConfigurableListableBeanFactory beanFactory,
                                List<String> orphanBeans) {
        List<String> candidates = new ArrayList<>();
        int entryPointsExcluded = 0;
        int notInstantiated = 0;

        for (String beanName : orphanBeans) {
            try {
                // Signal 1: eagerly instantiated. A @Lazy/prototype orphan that
                // was never touched costs nothing — not a ghost, skip it.
                // Read-only singleton-cache check; never instantiates.
                if (!beanFactory.containsSingleton(beanName)) {
                    notInstantiated++;
                    continue;
                }
                // Signal 3: no known entry point (signal 2 — no incoming
                // edges — is the precondition of the orphan list itself).
                Class<?> beanType = beanFactory.getType(beanName, false);
                if (beanType == null || isEntryPoint(beanType)) {
                    // Unresolvable type: exclude conservatively rather than accuse.
                    entryPointsExcluded++;
                    continue;
                }
                candidates.add(beanName);
            } catch (Exception e) {
                // Classification failure → conservative exclusion, never a crash.
                entryPointsExcluded++;
            }
        }
        return new Result(candidates, entryPointsExcluded, notInstantiated);
    }

    /**
     * Returns whether the bean type is a known entry point — i.e. metadata
     * suggests the framework (or an external trigger) will invoke it even
     * though no bean depends on it.
     *
     * @param beanType the bean's resolved type (possibly a CGLIB subclass)
     * @return {@code true} when any entry-point signal matches
     */
    static boolean isEntryPoint(Class<?> beanType) {
        Class<?> userClass = ClassUtils.getUserClass(beanType); // unwrap CGLIB enhancement

        // Framework-invoked interfaces anywhere in the hierarchy, plus any
        // interface owned by another artifact (whoever owns it can look the
        // bean up by type — see isCollectedByTypeOwner).
        for (Class<?> iface : ClassUtils.getAllInterfacesForClassAsSet(userClass)) {
            if (ENTRY_POINT_INTERFACES.contains(iface.getSimpleName())
                    || isCollectedByTypeOwner(userClass, iface)) {
                return true;
            }
        }

        // Class-level stereotypes. @Controller via AnnotatedElementUtils so
        // meta-annotated forms (@RestController) match without spring-web on
        // the classpath; the rest by simple name (optional classpaths).
        if (AnnotatedElementUtils.hasAnnotation(userClass, Controller.class)) {
            return true;
        }
        for (Annotation annotation : userClass.getAnnotations()) {
            if (ENTRY_POINT_CLASS_ANNOTATIONS.contains(annotation.annotationType().getSimpleName())) {
                return true;
            }
        }

        // Method-level invocation annotations, walked up the hierarchy
        // (private @Scheduled methods included).
        AtomicBoolean found = new AtomicBoolean(false);
        ReflectionUtils.doWithMethods(userClass, method -> {
            if (found.get()) {
                return;
            }
            for (Annotation annotation : method.getAnnotations()) {
                if (ENTRY_POINT_METHOD_ANNOTATIONS.contains(
                        annotation.annotationType().getSimpleName())) {
                    found.set(true);
                    return;
                }
            }
            // Meta-annotated forms of the two core annotations.
            if (AnnotatedElementUtils.hasAnnotation(method, Scheduled.class)
                    || AnnotatedElementUtils.hasAnnotation(method, EventListener.class)) {
                found.set(true);
            }
        }, ReflectionUtils.USER_DECLARED_METHODS);
        return found.get();
    }

    /**
     * Returns whether {@code iface} is declared in a different artifact than the class
     * implementing it — the signal that somebody else owns the contract and can
     * therefore find the bean <em>by type</em> instead of depending on it by name.
     * <p>
     * This covers the largest class of ghost false positive. A bean consumed through
     * {@code ObjectProvider<X>} injection or a programmatic {@code getBeansOfType(X)}
     * lookup never earns an incoming dependency edge and carries no entry-point
     * annotation, so it satisfies every ghost signal while being fully in use —
     * {@code Formatter}, {@code ViewResolver}, {@code JCacheManagerCustomizer} and
     * {@code ITemplateResolver} implementations all reached the candidate list that
     * way on spring-petclinic. Note that {@code Collection<X>} and
     * {@code Map<String, X>} injection points do <em>not</em> need this: Spring
     * registers those dependencies, so such beans are never orphans to begin with.
     * <p>
     * Enumerating collectable interfaces by name is unbounded ({@link
     * #ENTRY_POINT_INTERFACES} is a best-effort list, not a closed set); "the
     * interface came from another jar" is the general form of the same idea.
     * <p>
     * JDK interfaces are excluded deliberately: {@code Serializable},
     * {@code Comparable} and friends are markers nobody collects, and treating them as
     * entry points would silence genuinely dead beans — the one thing this feature
     * exists to report.
     *
     * @param implementation the bean's user class (CGLIB already unwrapped)
     * @param iface          an interface from its hierarchy
     * @return {@code true} when the interface comes from a different code source
     */
    static boolean isCollectedByTypeOwner(Class<?> implementation, Class<?> iface) {
        if (iface.getPackageName().startsWith("java.")) {
            return false;
        }
        URL owner = codeSourceLocation(iface);
        // Unknown origin (bootstrap/platform loader, exotic classloader) -> no claim.
        return owner != null && !owner.equals(codeSourceLocation(implementation));
    }

    private static URL codeSourceLocation(Class<?> type) {
        ProtectionDomain domain = type.getProtectionDomain();
        CodeSource source = (domain == null) ? null : domain.getCodeSource();
        return (source == null) ? null : source.getLocation();
    }

    /**
     * Serializes a detection result into the {@code ghostCandidates} report
     * section. Confidence and disclaimer are part of the payload — any
     * consumer (JSON, HTML, actuator) carries the honest wording with the data.
     *
     * @param result the completed detection result
     * @return an ordered map ready for Jackson
     */
    public static Map<String, Object> toReportMap(Result result) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("confidence", CONFIDENCE);
        map.put("disclaimer", DISCLAIMER);
        map.put("count", result.candidates.size());
        map.put("beans", result.candidates);
        map.put("entryPointsExcluded", result.entryPointsExcluded);
        map.put("notInstantiatedExcluded", result.notInstantiatedExcluded);
        return map;
    }
}
