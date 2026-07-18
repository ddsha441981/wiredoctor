# 🩺 WireDoctor
> *"Your bean graph has a story. WireDoctor reads it."*

WireDoctor is a runtime diagnostic and architectural analysis tool for Spring Boot. It works as an auto-configuration starter that hooks directly into the real, resolved Spring `ApplicationContext` without requiring any build-tool changes. **Zero-intrusion, zero-dashboard-server, pure insights.** See the [supported versions](#-supported-versions) table below — every listed combination is verified by CI on each build.

---

## ✨ Features

### New in v0.5.0
- 🩺 **Upgrade Guard (Autoconfig Condition Diff)**: Bumped Spring Boot and a feature quietly broke because an autoconfiguration stopped applying? WireDoctor now snapshots Spring Boot's **condition evaluation report** into your baseline and diffs it across builds — so a `matched → notMatched` flip is caught automatically. Your bean diff already shows *what* vanished; the condition diff shows **why**, with the exact `@ConditionalOnBean`/`@ConditionalOnClass` message. Gate it in CI with `wiredoctor.fail-on=condition-changed` (combinable with `new-cycle`). Validated on Spring PetClinic (Boot 4.1) — excluding one autoconfig surfaced the direct flip *and* the downstream cascade it triggered. → **[Upgrade Guard guide](docs/upgrade-guard.md)**
  ```
  [WireDoctor] Baseline Diff (vs wiredoctor-baseline-default.json):
    - Conditions: 2 changed | 0 added | 7 removed
    - CONDITION CHANGED: JacksonAutoConfiguration (matched -> excluded)
    - CONDITION CHANGED: JacksonJsonHttpMessageConverterConfiguration$... (matched -> notMatched)
  ```
  Backward compatible: a pre-v0.5.0 baseline (no condition data) skips the condition diff gracefully — the gate never trips on it.

### New in v0.3.0
- 💡 **@Lazy Suggestions to Break Cycles**: When a cycle is detected, WireDoctor doesn't just report it — it tells you how to fix it. The `lazySuggestions` report section (and a ranked console summary) lists which beans, if marked `@Lazy`, would break the cycle — ranked by cycles broken first, then smallest blast radius (fewest downstream dependents):
  ```
  [WireDoctor] @Lazy Suggestions to Break Cycles:
    1. Make 'alphaBean' @Lazy (breaks 1 cycle(s), impacts 1 bean(s))
    2. Make 'betaBean' @Lazy (breaks 1 cycle(s), impacts 1 bean(s))
  ```
- 📐 **Architecture Smell Metrics**: Classic architecture-health metrics computed on the *live, resolved* bean graph — what Spring actually wired (proxies, conditionals, profiles), not what the source declares. The `smells` report section includes top-10 **fan-in hotspots** (coupling / God Object smell), top-10 **fan-out hotspots** (Shotgun Surgery smell), and beans over Martin's **instability** threshold (`I = Ce/(Ca+Ce) ≥ 0.8`). Graph nodes in the HTML report are now sized by fan-in — the bigger the dot, the more beans depend on it.
- 🏋️ **Large-application hardening**: Verified against a 5,000-bean synthetic context. Above `wiredoctor.max-graph-nodes` (default 2000), the serialized graph is capped to the top-N beans by fan-in (cycle members always kept), with honest `graphTruncated` metadata and an HTML warning banner. Analysis — cycles, smells, critical path, baseline diff — always runs on the full graph; only the serialized view is capped.

### New in v0.2.0
- 🛡️ **Architectural Regression Guard**: Commit `wiredoctor-baseline.json` like a lockfile for your architecture, and **fail your PR when someone adds a bean cycle** via `wiredoctor.fail-on=new-cycle`. Fully opt-in, degrades gracefully when no baseline is configured. → **[CI gating guide](docs/ci-gating.md)**
- ⛓️ **Startup Critical Path**: The longest instantiation-weighted dependency chain gating your startup — the `criticalPath` report section and a console summary show which chain of beans your readiness time actually sits on (instantiation-weighted approximation; parallel init is not modeled).
- 📦 **Truly self-contained HTML**: The vis-network graph library is bundled and inlined into `wiredoctor-report.html` at generation time — the report renders fully offline.

### Since v0.1.0
- 🕸️ **Interactive HTML Visualizer**: Automatically generates `wiredoctor-report.html` (a single-file, React-free, Vis.js physics-based network graph) for immediate architectural visualization right in your browser. The report is fully self-contained — the vis-network graph library is bundled and inlined at generation time, so it renders completely offline.
- ⏱️ **Startup Timings**: Hooks into `ApplicationStartup` via `BufferingApplicationStartup` early in the lifecycle to measure and report exact bean instantiation times without reflection-heavy heuristics.
- 🔗 **Dependency Graph Analysis**: Directly hooks `ConfigurableListableBeanFactory.getDependenciesForBean` to view the completely resolved dependency graph.
- 🔄 **Circular Dependency Detection**: Runs a Tarjan's SCC cycle detector over the bean graph to find structural design smells, even if Spring resolves them via proxies/setters.
- 🎭 **Proxy Overhead Counter**: Scans for CGLIB and JDK proxies (e.g. `@Async`, `@Transactional`) in your bean graph to expose hidden indirection layers.

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

---

## 🚀 How to use

Just add the `wiredoctor-autoconfigure` dependency to your Spring Boot project. 

**Maven:**
```xml
<dependency>
    <groupId>io.github.ddsha441981</groupId>
    <artifactId>wiredoctor-autoconfigure</artifactId>
    <version>0.5.0</version>
</dependency>
```

**Gradle:**
```groovy
implementation 'io.github.ddsha441981:wiredoctor-autoconfigure:0.5.0'
```

WireDoctor runs automatically at application startup, generates a JSON report (`wiredoctor-report.json`) alongside an interactive dashboard (`wiredoctor-report.html`), and prints a clean diagnostic summary to your standard SLF4J logs.

### ⚙️ Configuration (Optional)
By default, WireDoctor automatically filters out internal infrastructure packages (like `org.springframework`, `java.`, `org.apache`, etc.) from the "Orphan Beans" list to reduce noise. 

If you want to explicitly define which packages should be analyzed for orphans, you can configure `wiredoctor.scan-packages` in your `application.properties`:
```properties
# Single package
wiredoctor.scan-packages=com.yourcompany.app

# Multiple packages (comma-separated)
wiredoctor.scan-packages=com.yourcompany.app,io.yourteam.service

# Configure the path where HTML and JSON reports are saved (default: project root)
wiredoctor.output-path=/path/to/your/reports

# Customize the threshold for flagging a bean as "slow" during instantiation (default: 100ms)
wiredoctor.slow-bean-threshold-ms=50

# --- Architectural Regression Guard (v0.2.0, opt-in) ---
# Path to the committed architecture baseline; enables the diff
wiredoctor.baseline=wiredoctor-baseline.json
# Run once with this to create/refresh the baseline (never diffs or gates)
wiredoctor.baseline-write=true
# CI gate: fail startup when a cycle not in the baseline appears
wiredoctor.fail-on=new-cycle

# --- Large-application graph cap (v0.3.0) ---
# Above this many beans, the SERIALIZED graph (JSON + HTML view) is capped to the
# top-N by fan-in (cycle members always kept) so the report stays reviewable and
# the browser doesn't freeze. Analysis itself (cycles, smells, critical path,
# baseline diff) ALWAYS runs on the full graph. 0 = unlimited. Default: 2000.
wiredoctor.max-graph-nodes=2000
```

See the **[CI gating guide](docs/ci-gating.md)** for the full "fail your PR on a new bean cycle" workflow.

### 🛑 Production Safety (Disabling WireDoctor)
WireDoctor is enabled by default. If you accidentally leave the dependency in your production build, you can completely disable the analyzer to prevent it from running, writing reports, or exposing bean structures by setting:
```properties
# application-prod.properties
wiredoctor.enabled=false
```

For what the reports expose, how to handle them, and WireDoctor's **offline-only** network behavior (its JVM does zero network I/O), see the **[security posture guide](docs/security-posture.md)**.

---

## 🔬 Epistemic Honesty & Known Limitations
Like any static/runtime analysis tool, WireDoctor prefers honest heuristics over false certainty:

1. ⚡ **AOT / GraalVM Native Image Support:**
   Currently, WireDoctor is designed for **traditional JVM mode only**. Spring Boot 3+ AOT processing fundamentally changes bean instantiation. Running this under Native Image is untested and will likely yield incomplete data.

2. 👻 **Orphan Bean Heuristic (Weak Signal):**
   The tool reports "Orphan Beans" (beans with 0 incoming dependencies). This is a **heuristic, not a guarantee** that the bean is unused. Beans accessed dynamically via `ApplicationContext.getBean()`, event listeners, or scheduled tasks will appear as "orphaned".

3. 💥 **Structural Cycles vs. Crashing Cycles:**
   If Spring encounters an *unresolvable* cycle (e.g., constructor-to-constructor), the app crashes (`BeanCurrentlyInCreationException`) before WireDoctor can report it. WireDoctor detects *resolved* cycles (via setter injection or proxies) that succeed silently. These are reported as structural design smells.

4. 🙈 **Early-Reference Cycle Blindspot (`allow-circular-references=true`):**
   Cycle detection uses `getDependenciesForBean()` which may not capture cycles resolved via Spring's early-reference mechanism (the 3-level cache earlySingletonObjects pathway). Only explicit `@DependsOn` and fully-registered constructor/setter dependencies are detected. Some silently resolved cycles might go unreported.

5. 🔭 **Tested Bean Scopes:**
   The analyzer focuses heavily on `Singleton` beans. `Prototype` beans or complex `FactoryBean` structures may not fully map out in the dependency graph until they are lazily instantiated during runtime.

---
## 🔮 Roadmap (v0.6.0 & Beyond)

Shipped: **v0.4.0** (Enterprise Fit — CI marker-file contract, actuator module, multi-profile baselines) and **v0.5.0** (Upgrade Guard — autoconfig condition diff). Next up:

1. 👻 **Real Lazy-Usage Tracking ("Ghost Bean" Detector, v0.6.0):**
   *Identifying beans that are instantiated and consume memory but are never actually invoked at runtime. Phase 1 is passive (zero new intrusion); Phase 2 is an opt-in, non-invasive access-tracking proxy.*
2. 💰 **Cost Guardian (v0.7.0):**
   *Startup-time and slow-bean regression gates — tie a PR that makes boot slower to a CI failure, framed around Kubernetes cold-start cost.*
3. 🧠 **Memory Footprint Estimation:**
   *Moving beyond measuring instantiation **time** to measuring object **space**. Exploring per-bean heap consumption metrics (potentially requiring Java Agent instrumentation for deep size-of calculations).*

---

## 🤝 Contributing

Contributions are welcome — bug reports, docs, tests, and features. See
**[CONTRIBUTING.md](CONTRIBUTING.md)** for build/test conventions and the
zero-intrusion design posture, and please follow the
**[Code of Conduct](CODE_OF_CONDUCT.md)**. New here? Look for issues labelled
[`good first issue`](https://github.com/ddsha441981/wiredoctor/labels/good%20first%20issue).

---

## 👤 Author

**Deendayal Kumawat** 

* 📧 **Email:** [deendayal_kumawat@hotmail.com](mailto:deendayal_kumawat@hotmail.com)
* 💼 **LinkedIn:** [deendayal-kumawat](https://www.linkedin.com/in/deendayal-kumawat/)
* 🐙 **GitHub:** [ddsha441981](https://github.com/ddsha441981)
* 𝕏 **X (Twitter):** [@ddsha44198](https://x.com/ddsha44198)
* 📝 **Medium:** [@ddsha441981](https://medium.com/@ddsha441981)
* 📦 **Maven Central:** [io.github.ddsha441981](https://mvnrepository.com/artifact/io.github.ddsha441981)
* 🦀 **Crates.io:** [ddsha441981](https://crates.io/users/ddsha441981)
* 🐳 **Docker Hub:** [ripdedup](https://hub.docker.com/u/ripdedup)

### 📚 Research Paper
*Kumawat, D. (2026). Project Lethe: Bio-Inspired Autonomous Edge Intelligence via Sub-Microsecond Continual Learning and Active Forgetting (4.2.0). Zenodo.* 
🔗 [https://doi.org/10.5281/zenodo.20531995](https://doi.org/10.5281/zenodo.20531995)
