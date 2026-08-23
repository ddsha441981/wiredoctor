---
title: Ghost Detector
nav_order: 8
---

# 👻 Ghost Bean Detector (v0.6.0)

> *"Which beans are wasting resources?" — answered honestly, in two phases.*

Every Spring Boot context carries beans that were instantiated at startup — costing memory
and boot time — but are never actually used. WireDoctor v0.6.0 finds them in two phases with
very different trust postures:

| | Phase 1: Ghost Candidates | Phase 2: First-Touch Tracking |
|---|---|---|
| Intrusion | **None** (pure metadata) | Wraps beans in a thin counting proxy |
| Default | Always on | **OFF** — explicit opt-in |
| Signal | "no known entry point" | "not invoked during this run" |
| Confidence | LOW (heuristic) | Scales with run duration |
| Where | `ghostCandidates` in the startup report | `wiredoctor-ghost-report.json` at shutdown + live actuator view |

Neither phase ever claims a bean is **"unused"**. A bean idle during a five-minute dev run
may be the month-end batch job. The wording is deliberate and embedded in every payload:
*no known entry point was found* (Phase 1), *never invoked during THIS run* (Phase 2).

---

## Phase 1 — Ghost Candidates (passive, always on)

The existing orphan list ("0 incoming dependencies") is a weak signal: controllers,
scheduled jobs and runners legitimately have no dependents. Phase 1 crosses three signals —
all available without instantiating or wrapping anything:

1. The bean was **eagerly instantiated** at startup (it's in the singleton cache — it cost you something).
2. It has **0 incoming edges** in the resolved dependency graph.
3. **No entry point is detectable from its metadata**:
   - `@Controller` / `@RestController` (and any `@Controller`-meta-annotated stereotype)
   - `@Scheduled` / `@EventListener` holders — including private and inherited methods
   - messaging listeners (`@KafkaListener`, `@RabbitListener`, `@JmsListener`, ...)
   - `CommandLineRunner`, `ApplicationRunner`, `Lifecycle`, servlet/filter types,
     `HealthIndicator`, and other framework-invoked interfaces
   - `@Configuration` classes and `@Aspect`s (they work at definition time / via weaving)

Detection errs **broad** on purpose: a false entry-point match merely shrinks the candidate
list, while a missed one would wrongly accuse a working bean. Classification failures
exclude the bean conservatively.

The report section (`wiredoctor-report.json`):

```json
"ghostCandidates": {
  "confidence": "LOW",
  "disclaimer": "Heuristic: ... NOT proof of dead code ...",
  "count": 2,
  "beans": ["legacyPdfExporter", "unusedMetricsAdapter"],
  "entryPointsExcluded": 5,
  "notInstantiatedExcluded": 1
}
```

The exclusion counts are part of the honesty contract: you can see *why* the candidate list
is shorter than the orphan list, instead of beans silently disappearing. The orphan list
itself is unchanged — it stays the raw graph fact; `ghostCandidates` is the refined advice
sitting beside it.

**What Phase 1 cannot see:** reflective access, programmatic `context.getBean()` lookups,
and beans collected by the framework into lists/maps. That's why it's `confidence: LOW` —
and why Phase 2 exists.

---

## Phase 2 — First-Touch Tracking (opt-in, dev/staging only)

> ⚠️ **This is the only intrusive feature in WireDoctor.** When enabled, it wraps your
> eligible beans in a thin counting proxy. Use it in dev/staging — not production.

```properties
# application-dev.properties
wiredoctor.ghost-tracking.enabled=true

# optional: beans to never wrap (reported as untrackable:excluded)
wiredoctor.ghost-tracking.exclude=legacySoapClient,nativeBridge
```

With the default configuration (`enabled=false`, which is implicit), **zero**
`BeanPostProcessor` is registered — a regression test asserts exactly that on every build.
The zero-intrusion promise of the default artifact is untouched.

### What the proxy does

Exactly one thing: flip an `AtomicBoolean` the first time any method of the bean is invoked.

- No timing. No argument capture. No logging in the hot path.
- Measured overhead: **~180 ns per call** after first touch on a warmed JVM (dominated by
  the proxy dispatch itself, not the flag). For comparison, a `@Transactional` proxy does
  far more work per call.

### What gets wrapped — and what never does

Eligibility guards skip (and **report**, never silently hide):

| Skipped | Reason in report | Why |
|---|---|---|
| Framework beans (Spring, Jackson, ...) | counted in `frameworkSkipped` | out of scope — you can't delete them |
| Already-proxied beans (`@Transactional`, `@Async`) | `already-proxied` | never double-wrap; the tracker runs at lowest precedence so existing proxies stay intact (integration-tested) |
| `FactoryBean`s, `BeanPostProcessor`s, AOP infrastructure | `factory-bean` | wrapping infrastructure breaks contexts |
| Interface-less `final` classes | `final-class` | CGLIB cannot subclass them |
| Non-singleton beans | `non-singleton` | one flag per name would lie about instances |
| Your `exclude` list | `excluded` | your call |

**Failure posture:** if wrapping a bean throws for any reason, WireDoctor logs a warning,
returns the bean **unwrapped**, and counts it untrackable. A diagnostic tool must never
turn a working bean into a broken one.

### Reading the results

**At shutdown** — ghosts are only knowable at the *end* of a run — a `ContextClosedEvent`
listener writes `wiredoctor-ghost-report.json`:

```json
{
  "disclaimer": "'untouched' means no proxied method was invoked during THIS run — NOT that the bean is unused. ...",
  "trackedCount": 41,
  "touchedCount": 38,
  "untouchedCount": 3,
  "untouched": ["legacyPdfExporter", "unusedMetricsAdapter", "xmlFallbackParser"],
  "touched": ["..."],
  "untrackableCount": 6,
  "untrackable": { "auditingHandler": "already-proxied", "..." : "..." },
  "frameworkSkipped": 214
}
```

**Live** — for long-running staging environments where waiting for shutdown is impractical,
add the `wiredoctor-actuator` module and query:

```
GET /actuator/wiredoctor/ghosts
```

This serves the same state as a live snapshot ("invocations up to this moment"). Reading it
triggers nothing. Without the opt-in it answers `{"status": "DISABLED"}` with the enable
instructions.

---

## Interpreting a ghost honestly

A bean in `untouched` after a run means exactly one thing: **no proxied method of it was
called during that run.** Before deleting anything, ask:

1. **How long and how realistic was the run?** A 30-second smoke boot proves nothing. A week
   of staging traffic is a real signal.
2. **Is it periodic?** Month-end jobs, cleanup tasks, failover paths — idle by design most
   of the time.
3. **Is it reached without method calls?** Field access, reflection against the raw class,
   or beans held as type markers won't flip the flag.
4. **Cross-check with Phase 1:** a bean that is *both* a ghost candidate (no entry point)
   *and* untouched across long realistic runs is the strongest deletion signal WireDoctor
   can give you — and it's still your judgment call.

---

## Design notes

- The tracker state is concurrent (`ConcurrentHashMap` + `AtomicBoolean`); first-touch
  flips happen on arbitrary application threads.
- Wrapping is implemented with Spring's own `ProxyFactory` (JDK proxy when the bean has
  interfaces, CGLIB subclass otherwise) — no new dependencies.
- The shutdown writer is fully defensive: a failed report write logs a warning and never
  disturbs host shutdown.
- Security: the ghost report reveals bean names — same information class as the main
  report; see the [security posture guide](security-posture.html).
