# 🩺 WireDoctor

> *"Your bean graph has a story. WireDoctor reads it."*

WireDoctor is a runtime diagnostic and architectural analysis tool for Spring Boot. Add one dependency — it hooks into the real, resolved `ApplicationContext` at startup and turns it into an interactive report, honest advice, and CI gates. **Zero-intrusion, zero-dashboard-server, pure insights.**

![WireDoctor report — Overview tab](docs/images/overview.png)

*Captured from a real run against start.spring.io (Boot 4.0.x, 390 beans) with performance gates armed — the red `GATE FAIL` chip is a genuinely tripped gate, not a mockup. [Full report tour →](docs/report-tour.md)*

**▶ [Open this exact report live in your browser](https://ddsha441981.github.io/wiredoctor/sample/test2/wiredoctor-report.html)** — no install needed; it's the self-contained HTML WireDoctor writes on every run.

---

## Why WireDoctor?

- **"Why does this service take 40 seconds to boot now?"** — Actuator gives you raw `StartupStep` JSON; nobody ships the analysis layer on top. WireDoctor ranks the slow beans, computes the critical path, and tells you which `@Lazy` would pay off most.
- **"We bumped Spring Boot and a feature quietly broke."** — An autoconfiguration stopped matching and nobody noticed until production. WireDoctor diffs Boot's condition report across builds and catches the `matched → notMatched` flip in CI, with the exact condition message.
- **"Someone added a bean cycle six months ago and now it's load-bearing."** — Commit a baseline like a lockfile for your architecture; the PR that introduces a new cycle fails its build the same day, not at refactoring time.
- **"Which of these beans actually do anything?"** — Ghost detection crosses three signals to find beans that cost startup time and memory but show no sign of use — honestly labeled, never overclaimed.

---

## ✨ What it does

*(Release history lives in the [CHANGELOG](CHANGELOG.md).)*

### 🔍 See — the report

- **Interactive HTML console** — a single self-contained `wiredoctor-report.html` (tabs: Overview / Graph / Ghosts / Smells / Timing / Conditions) with a health-verdict header and a searchable, click-to-inspect dependency graph. Renders fully offline. → [Report tour](docs/report-tour.md)
- **Real startup timings** — per-bean instantiation times from `BufferingApplicationStartup`, no reflection heuristics.
- **The resolved graph** — read directly from `getDependenciesForBean()`: what Spring actually wired, not what the source suggests.
- **Condition snapshot** — Boot's autoconfiguration decisions, tabbed and filterable.
- **JSON export** — `wiredoctor-report.json` as the single source of truth for tooling; live views via `/actuator/wiredoctor/*`.

### 🚨 Diagnose — the analysis

- **Cycle detection with fix advice** — Tarjan SCC finds silently-resolved cycles, and `lazySuggestions` ranks which `@Lazy` breaks the most cycles with the smallest blast radius.
- **Startup critical path** — the instantiation-weighted dependency chain your readiness time actually sits on.
- **Architecture smells** — fan-in coupling hotspots, fan-out shotgun-surgery risk, and instability metrics on the live graph; framework beans filtered so every ranked bean is refactorable.
- **Ghost beans** — passive candidates (always on, labeled `confidence: LOW`) plus opt-in first-touch tracking for dev/staging. → [Ghost Detector guide](docs/ghost-detector.md)
- **Proxy overhead** — CGLIB/JDK proxy count exposing hidden indirection layers.

### 🛡️ Guard — the CI gates

- **Architectural regression guard** — commit `wiredoctor-baseline.json`, fail the PR that adds a new cycle (`fail-on=new-cycle`). → [CI gating guide](docs/ci-gating.md)
- **Upgrade Guard** — condition diff across Boot upgrades; gate on `condition-changed`. → [Upgrade Guard guide](docs/upgrade-guard.md)
- **Performance gates** — fail on startup-time regressions (dual-threshold, noise-tolerant) and new slow beans (jitter-margin protected). → [Performance Gates guide](docs/performance-gates.md)
- **CI-friendly output** — gates write `wiredoctor-gate.status` (`PASS`/`FAIL`) and `wiredoctor-diff.json`; the report is written even when a gate fails the build.

---

## 🚀 Quick start

Add the dependency — that's it. WireDoctor runs at startup, writes `wiredoctor-report.html` + `wiredoctor-report.json`, and prints a diagnostic summary to your logs.

**Maven:**
```xml
<dependency>
    <groupId>io.github.ddsha441981</groupId>
    <artifactId>wiredoctor-autoconfigure</artifactId>
    <version>0.9.0</version>
</dependency>
```

**Gradle:**
```groovy
implementation 'io.github.ddsha441981:wiredoctor-autoconfigure:0.9.0'
```

Want CI gates? Capture a baseline once, commit it, arm the gates:

```bash
./mvnw spring-boot:run \
  -Dspring-boot.run.arguments="--wiredoctor.baseline=wiredoctor-baseline.json --wiredoctor.baseline-write=true"
git add wiredoctor-baseline.json && git commit -m "chore: WireDoctor baseline"
```

```properties
# application-ci.properties
wiredoctor.baseline=wiredoctor-baseline.json
wiredoctor.fail-on=new-cycle,startup-time,slow-bean
```

A tripped gate fails startup with a precise message:
```
WireDoctorRegressionException: WireDoctor regression gate 'startup-time' tripped:
  startup time increased by 734ms (18.2%) vs baseline (4025ms -> 4759ms)
```

Common knobs (`wiredoctor.scan-packages`, thresholds, output path, production kill-switch `wiredoctor.enabled=false`) are in the **[configuration reference](docs/configuration.md)**.

---

## ✅ Supported Versions

The full test suite runs against this matrix in CI ([compat.yml](.github/workflows/compat.yml)); the table below reflects what is actually green, not what we hope works:

| Spring Boot | Java 17 | Java 21 | Java 25 |
|-------------|:-------:|:-------:|:-------:|
| 2.7.x       | ✅      | ✅      | ✅      |
| 3.3.x       | ✅      | ✅      | ✅      |
| 3.5.x       | ✅      | ✅      | ✅      |
| 4.0.x       | ✅      | ✅      | ✅      |

Notes:
- **Floor is Boot 2.4**: startup timings need `BufferingApplicationStartup`, introduced in Boot 2.4. Lines older than 2.7 are not CI-verified — they may work, but you're on your own.
- Boot lines between the tested ones (3.0–3.2, 3.4) are expected to work since WireDoctor only uses stable `spring-context` / `spring-boot` APIs, but only the listed lines carry a CI guarantee.
- WireDoctor itself is compiled for **Java 17** bytecode, so Java 8/11 apps cannot load it even on Boot 2.7.
- **WebFlux (reactive, Netty)**: verified since v0.8.0 — an integration test boots a reactive (non-servlet) context in CI and asserts reports, startup timings, and ghost analysis all work; `RouterFunction`, `WebHandler`, `WebSocketHandler` and `WebExceptionHandler` beans are recognized as entry points (never flagged as ghosts).

---

## 📚 Documentation

| Guide | What it covers |
|-------|----------------|
| [Report tour](docs/report-tour.md) | Every tab of the HTML console, explained with real screenshots |
| [Configuration reference](docs/configuration.md) | Every property, grouped by feature, with defaults |
| [CI gating](docs/ci-gating.md) | Fail your PR on a new bean cycle — the full workflow |
| [Performance gates](docs/performance-gates.md) | Startup-time and slow-bean gates, thresholds, noise tolerance |
| [Upgrade Guard](docs/upgrade-guard.md) | Catching silent autoconfiguration changes across Boot upgrades |
| [Ghost Detector](docs/ghost-detector.md) | Passive candidates + opt-in first-touch tracking, and their trust postures |
| [Security posture](docs/security-posture.md) | What the reports expose, offline-only network behavior |

Pre-generated sample reports (from real apps, including start.spring.io) are in [`sample/`](sample/) — or **[view the start.spring.io report live](https://ddsha441981.github.io/wiredoctor/sample/test2/wiredoctor-report.html)** without cloning anything.

---

## 🔬 Epistemic Honesty & Known Limitations

Like any static/runtime analysis tool, WireDoctor prefers honest heuristics over false certainty:

1. ⚡ **AOT / GraalVM Native Image Support:**
   Currently, WireDoctor is designed for **traditional JVM mode only**. Spring Boot 3+ AOT processing fundamentally changes bean instantiation. Running this under Native Image is untested and will likely yield incomplete data.

2. 👻 **Orphan Bean Heuristic (Weak Signal):**
   The tool reports "Orphan Beans" (beans with 0 incoming dependencies). This is a **heuristic, not a guarantee** that the bean is unused. Beans accessed dynamically via `ApplicationContext.getBean()`, event listeners, or scheduled tasks will appear as "orphaned". Since v0.6.0 the `ghostCandidates` section refines this (entry-point detection filters out controllers/listeners/runners), and opt-in ghost tracking measures actual invocation — but even a tracked "untouched" bean only means *not invoked during this run*, never "unused".

3. 💥 **Structural Cycles vs. Crashing Cycles:**
   If Spring encounters an *unresolvable* cycle (e.g., constructor-to-constructor), the app crashes (`BeanCurrentlyInCreationException`) before WireDoctor can report it. WireDoctor detects *resolved* cycles (via setter injection or proxies) that succeed silently. These are reported as structural design smells.

4. 🙈 **Early-Reference Cycle Blindspot (`allow-circular-references=true`):**
   Cycle detection uses `getDependenciesForBean()` which may not capture cycles resolved via Spring's early-reference mechanism (the 3-level cache earlySingletonObjects pathway). Only explicit `@DependsOn` and fully-registered constructor/setter dependencies are detected. Some silently resolved cycles might go unreported.

5. 🔭 **Bean Scopes (pinned by tests since v0.8.0):**
   `Prototype` and `@Lazy` bean *definitions* appear as graph nodes, but they are **never instantiated** by the analysis (zero-intrusion promise — a regression test proves it). Their proxy status can't be known without instantiating them, so they are skipped by the proxy scan and honestly counted in `proxies.notInstantiatedSkipped`. `FactoryBean`s appear under the factory's bean name; a consumer of the *product* gets its dependency edge recorded against the factory's name (Spring's own bookkeeping). Runtime-only facts about these scopes — how often a prototype is created, whether a lazy bean is ever touched — are outside a startup snapshot's reach.

See [KNOWN_ISSUES.md](KNOWN_ISSUES.md) for the full, versioned list.

---

## 🔮 Roadmap

Next up: **v1.0.0 — API & schema freeze + Maven Central launch.** Report schema stabilized, publish to Maven Central, launch.

Dropped (deliberately): **Memory Footprint Estimation** — honest per-bean heap numbers need a Java Agent; shallow size-of is a correctness trap. The Ghost Detector answers the same underlying question ("which beans are wasting resources?") without lying about bytes.

---

## 🤝 Contributing

Contributions are welcome — bug reports, docs, tests, and features. See **[CONTRIBUTING.md](CONTRIBUTING.md)** for build/test conventions and the zero-intrusion design posture, and please follow the **[Code of Conduct](CODE_OF_CONDUCT.md)**. New here? Look for issues labelled [`good first issue`](https://github.com/ddsha441981/wiredoctor/labels/good%20first%20issue).

Maintained by **[Deendayal Kumawat](https://github.com/ddsha441981)** · [LinkedIn](https://www.linkedin.com/in/deendayal-kumawat/) · [deendayal_kumawat@hotmail.com](mailto:deendayal_kumawat@hotmail.com)

## 📄 License

Dual-licensed under [MIT](LICENSE) OR Apache-2.0 — pick whichever suits your project.
