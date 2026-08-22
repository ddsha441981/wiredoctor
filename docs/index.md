# WireDoctor

**Runtime diagnostic and architectural analysis for Spring Boot.**

Add one dependency — WireDoctor hooks into the real, resolved `ApplicationContext` at startup and turns it into an interactive HTML report, honest advice, and CI gates.

**Zero-intrusion. Zero dashboard server. Pure insights.**

---

## Why WireDoctor?

| Pain | WireDoctor answer |
|------|-------------------|
| "Something changed between Boot 3.x and 4.x — our app is slower, what happened?" | **Upgrade Guardian** diffs autoconfiguration conditions across upgrades |
| "We have a bean cycle in CI — which PR introduced it?" | **Regression Guard** fails the PR the moment a new cycle appears |
| "Startup took 40 s in staging — which bean is the culprit?" | **Critical path** + **timing heat** in the Graph tab |
| "We suspect dead beans eating memory — how do we find them?" | **Ghost Detector** — passive candidates + opt-in first-touch tracking |
| "Our K8s pods are expensive because of slow cold-start — what do we cut?" | **Cost Guardian** gates on startup-time and slow-bean regressions |

---

## Quick Start

```xml
<dependency>
  <groupId>io.github.ddsha441981</groupId>
  <artifactId>wiredoctor-autoconfigure</artifactId>
  <version>1.1.0</version>
</dependency>
```

Run your app once — `wiredoctor-report.json` and `wiredoctor-report.html` appear in the project root. Open the HTML file in any browser — no server needed.

---

## What you get

### See — the report

- **Interactive HTML console** — self-contained `wiredoctor-report.html` with tabs: Overview, Graph, Ghosts, Smells, Timing, Conditions. Opens offline in any browser.
- **Real startup timings** — per-bean instantiation times from `BufferingApplicationStartup`, no reflection heuristics.
- **The resolved graph** — read directly from `getDependenciesForBean()`: what Spring actually wired, not what the source suggests.
- **Condition snapshot** — Boot's autoconfiguration decisions, tabbed and filterable.
- **JSON export** — `wiredoctor-report.json` as the single source of truth for tooling; live views via `/actuator/wiredoctor/*`.

### Diagnose — the analysis

- **Cycle detection with fix advice** — Tarjan SCC finds silently-resolved cycles; `lazySuggestions` ranks which `@Lazy` breaks the most cycles with the smallest blast radius.
- **Startup critical path** — the instantiation-weighted dependency chain your readiness time actually sits on.
- **Architecture smells** — fan-in coupling hotspots, fan-out shotgun-surgery risk, and instability metrics on the live graph; framework beans filtered so every ranked bean is refactorable.
- **Ghost beans** — passive candidates (always on, labeled `confidence: LOW`) plus opt-in first-touch tracking for dev/staging.
- **Proxy overhead** — CGLIB/JDK proxy count exposing hidden indirection layers.

### Guard — the CI gates

- **Architectural regression guard** — commit `wiredoctor-baseline.json`, fail the PR that adds a new cycle (`fail-on=new-cycle`).
- **Upgrade Guard** — condition diff across Boot upgrades; gate on `condition-changed`.
- **Performance gates** — fail on startup-time regressions (dual-threshold, noise-tolerant) and new slow beans (jitter-margin protected).
- **CI-friendly output** — gates write `wiredoctor-gate.status` (`PASS`/`FAIL`) and `wiredoctor-diff.json`; the report is written even when a gate fails the build.

---

## Guides

| Guide | What it covers |
|-------|----------------|
| [Report tour](report-tour.md) | Every tab of the HTML console, explained with real screenshots |
| [Configuration reference](configuration.md) | Every property, grouped by feature, with defaults |
| [CI gating](ci-gating.md) | Fail your PR on a new bean cycle — the full workflow |
| [Performance gates](performance-gates.md) | Startup-time and slow-bean gates, thresholds, noise tolerance |
| [Upgrade Guard](upgrade-guard.md) | Catching silent autoconfiguration changes across Boot upgrades |
| [Ghost Detector](ghost-detector.md) | Passive candidates + opt-in first-touch tracking, and their trust postures |
| [Thread Distribution](thread-distribution.md) | Per-thread bean map with donut chart (v1.1.0) |
| [Startup Time Trend](startup-time-trend.md) | trendHistory in baseline + sparkline (v1.1.0) |
| [Security posture](security-posture.md) | What the reports expose, offline-only network behavior |
| [Known Limitations](known-limitations.md) | Honest heuristics and what the tool cannot guarantee |

---

## Supported Versions

The full test suite runs against this matrix in CI ([compat.yml](https://github.com/ddsha441981/wiredoctor/blob/main/.github/workflows/compat.yml)); the table below reflects what is actually green:

| Spring Boot | Java 17 | Java 21 | Java 25 |
|-------------|:-------:|:-------:|:-------:|
| 2.7.x       | ✅      | ✅      | ✅      |
| 3.3.x       | ✅      | ✅      | ✅      |
| 3.5.x       | ✅      | ✅      | ✅      |
| 4.0.x       | ✅      | ✅      | ✅      |

Notes:
- **Floor is Boot 2.4**: startup timings need `BufferingApplicationStartup`, introduced in Boot 2.4. Lines older than 2.7 are not CI-verified.
- Boot lines between the tested ones (3.0–3.2, 3.4) are expected to work since WireDoctor only uses stable APIs, but only the listed lines carry a CI guarantee.
- WireDoctor itself is compiled for **Java 17** bytecode.
- **WebFlux (reactive, Netty)**: verified since v0.8.0 — `RouterFunction`, `WebHandler`, `WebSocketHandler` and `WebExceptionHandler` beans are recognized as entry points (never flagged as ghosts).
