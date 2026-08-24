---
title: Report tour
nav_order: 3
---

# 📸 WireDoctor Report Tour

A guided walkthrough of the WireDoctor HTML console, tab by tab. All screenshots are from a **real run against start.spring.io** (Spring Initializr, Spring Boot 4.0.x, 390 beans, 432 wiring edges) with WireDoctor **0.7.1** and performance gates armed (`wiredoctor.fail-on=startup-time,slow-bean`) — not a staged demo. The full sample reports are in [`sample/`](https://github.com/ddsha441981/wiredoctor/tree/main/sample).

The report is a single self-contained `wiredoctor-report.html` — the graph library is inlined at generation time, so it renders completely offline. Just open it in a browser.

The screenshots below were captured on 0.7.1. Everything they show is still there; the charts added in v1.1.3 (coupling quadrant, Pareto curve, trend verdict bands) are described in the Smells and Timing sections but are not in these images yet.

---

## Overview tab

![Overview tab](images/overview.png)

The landing tab — everything at a glance:

- **Header chips (top-right)**: active profile, bean/edge counts, and the health verdict chip. Here it shows **`GATE FAIL: slow-bean`** in red — the armed `slow-bean` performance gate tripped on this run (a bean crossed the 100ms threshold that wasn't slow in the baseline). The chip follows a strict precedence: `GATE FAIL` → `N CYCLES` → `GATES PASS` → `HEALTHY`, so the worst news always wins.
- **Stat cards**: total beans (390), wiring edges (432), dependency cycles (0), CGLIB/JDK proxies (3), ghost candidates (19), and the startup critical path cost (913ms).
- **Bean composition**: user-defined vs framework beans (55 vs 335 here — a typical real app is mostly framework), plus the heuristic orphan count.
- **Startup critical path**: the single most expensive dependency chain to readiness — here `InitializrProperties → initializrMetadataProvider → bomRangesInfoContributor` accounts for 16.3% of startup. This is where a `@Lazy` or a lighter bean pays off most.
- **Top coupling hotspots** and a **ghost candidates** preview round out the page.

The sidebar footer states the trust posture: *generated at startup, zero-intrusion snapshot — reads metadata, never state*.

---

## Graph tab

![Cycle detection in the graph](images/cycle-detection.png)

The full resolved dependency graph, rendered interactively. Filter chips toggle **User beans / Framework / Cycles only / Ghosts only**, and the search box focuses any bean by name.

Two more chips bring startup timing into the graph (v0.10.0):

- **Timing heat** — recolors nodes green→red (log scale) and scales their size by per-bean instantiation time, so expensive beans jump out at a glance. Off by default; cycle/ghost/proxy/orphan colors keep priority.
- **Critical path** — traces the startup critical path in gold and dims everything else, matching the chain shown in the Timing tab. Hidden when no timing data was captured.

The screenshot above (from the demo app) shows the killer feature: **circular dependencies are highlighted in red** — `alphaBean ⇄ betaBean` jump out instantly even in a busy graph. Spring may resolve such cycles at runtime via proxies or setter injection, but they remain structural design smells, and WireDoctor's Tarjan SCC detector finds them regardless of how Spring papered over them.

![Focused bean with dependency details](images/graph-focus.png)

Clicking a node opens the **inspector panel**: health status, instantiation time (when captured), an `on critical path` tag, fan-in (dependents), fan-out (dependencies), and the exact list of beans it depends on. Here `viewControllerHandlerMapping` is focused — its 3 outgoing edges are highlighted in blue while the rest of the graph dims. This is how you answer "what actually depends on this bean?" without grepping.

---

## Ghosts tab

![Ghosts tab](images/ghosts.png)

Beans that cost startup time and memory but show no sign of use. Two independent signals, honestly separated:

- **Phase 1 — Passive candidates (always on)**: beans that are eagerly instantiated ∧ have zero incoming dependencies ∧ expose no detectable entry point (`@Controller`, `@Scheduled`, `CommandLineRunner`, listeners, …). Deliberately labeled **`CONFIDENCE LOW`** — it's a static signal. On start.spring.io it flags 19 candidates, mostly version-customizer beans that are genuinely wired through non-graph mechanisms — exactly why the confidence label exists.
- **Phase 2 — First-touch tracking (opt-in)**: the right card explains how to enable `wiredoctor.ghost-tracking.enabled=true` (dev/staging only), which wraps beans in a thin counting proxy and reports touched/untouched at shutdown, or live via `/actuator/wiredoctor/ghosts`.

WireDoctor never claims "unused" — only "never invoked during this run". The distinction is printed right in the UI.

---

## Smells tab

![Smells tab](images/smells.png)

Architecture smells computed on the **live resolved graph** — what Spring actually wired, not what the source suggests. Framework beans are filtered out of the rankings so every row is something you can actually refactor:

- **High fan-in · coupling hotspots**: beans the most others depend on. A change here ripples widest — here `AzureTokenCredentialAutoConfiguration` (8 dependents) and `initializrMetadataProvider` (6) top the list.
- **High fan-out · shotgun surgery risk**: beans that depend on the most others — they break when any of their many dependencies change.
- **Who is on the other end (v1.1.3)**: every row in both tables expands to the actual beans it is coupled to, so "fan-in 6" no longer means tracing six edges in the graph by hand.

Above the tables sits the **coupling quadrant** (v1.1.3) — a fan-out vs fan-in scatter of every bean, so you see the shape the two top-10 lists hide:

- **Up the left edge**: god beans — high fan-in, low fan-out. Many beans depend on them, so a change ripples widest.
- **Along the bottom**: shotgun-surgery risks — high fan-out, low fan-in.
- The dashed **I = 0.8** line is the same instability threshold the *Unstable beans* table uses; everything below it is unstable.
- Dot size is how many beans share that exact position, framework beans are dimmed (with a **Hide framework beans** toggle), and clicking a dot that holds a single bean opens it in the Graph tab.
- Axes are square-root scaled so a 400-bean tail stays readable — but every tick is a real count, not a bucket.

---

## Timing tab

![Timing tab](images/timing.png)

Real measured startup numbers from `BufferingApplicationStartup` — no reflection heuristics:

- **How few beans you would have to fix (v1.1.3)**: a Pareto curve of cumulative bean-instantiation time with the 80% knee marked — on a start.spring.io run, **37 of 283 beans** carry 80% of it. The caption is explicit that a bean's time includes the beans its constructor triggers, so the total counts nested instantiation more than once: it is a ranking of where to look, not a wall-clock budget.
- **Slowest startup steps**: the Boot lifecycle phases, with `spring.context.refresh` (4,619ms) at the top and individual `spring.beans.instantiate` steps below. Since v1.1.3 the bean each `instantiate` step created is its own column, so a step no longer names a phase without naming what it built.
- **Slow bean instantiation**: every bean over the `slow-bean-threshold-ms` (default 100ms), ranked. On this run, `bomRangesInfoContributor` (315ms) and `initializrMetadataProvider` (313ms) lead.

The **startup time trend** chart closes the tab: startup time plus a dashed bean-count line, with a coloured band on every interval that crosses the `startup-time` gate's thresholds — red when a slowdown is *unexplained by bean count*, amber when the app also grew, green when it got faster. It only has data from the second `baseline-write=true` run onward; see the [Startup Time Trend guide](startup-time-trend.html) for the verdict rules and the caveats they carry.

Since v0.7.1, this tab also hosts the **Performance Gates card**: each gate (startup-time, slow-bean, new-cycle, condition-changed) with its threshold, actual value, and a PASS/FAIL/NOT RUN verdict chip — plus `not armed` tags and a CI hint when gates aren't configured. This is the UI counterpart of `wiredoctor.fail-on` CI gating (see the [Performance Gates guide](performance-gates.html)).

---

## Conditions tab

![Conditions tab](images/conditions.png)

Spring Boot's **condition evaluation report**, snapshotted into the report — 358 autoconfiguration classes here, each tagged `matched` / `notMatched` / `unconditional`, with count chips and a class-name filter.

Why snapshot something Boot already keeps? Because a snapshot can be **diffed**. Commit it in your baseline and a Boot upgrade that silently flips an autoconfiguration from `matched → notMatched` is caught in CI with the exact condition message — before you debug the mystery of the vanished bean. See the [Upgrade Guard guide](upgrade-guard.html).

---

## Try it yourself

Open the pre-generated reports in [`sample/`](https://github.com/ddsha441981/wiredoctor/tree/main/sample) in any browser, or add the dependency to your own app — the report appears in your working directory on next startup. See [Quick start](https://github.com/ddsha441981/wiredoctor#-quick-start).
