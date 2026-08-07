# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.0.0] - 2026-08-08

API Freeze & Certification: the promises become contracts. From this release, `wiredoctor.*` config property names, the report JSON schema, and the performance budget are **frozen** — patch and minor releases within 1.x carry no breaking changes.

### Added
- 📌 **`schemaVersion: 1`** — the first field in every `wiredoctor-report.json`. Schema is now versioned and frozen; a breaking field change requires a new `schemaVersion` value and a major version bump.
- ⚡ **AOT / GraalVM native image graceful skip** — `NativeDetector.inNativeImage()` check at analysis start. In native-image mode: single `WARN` log, no crash, no incomplete report, no gate trip. JVM mode with AOT compilation is unaffected.
- ⏱️ **Performance budget test** (`WireDoctorPerformanceBudgetTest`) — CI-enforced: boot + analysis on 1 000 synthetic beans must complete within 30 s and produce a valid report with `schemaVersion: 1`.
- 📚 **Docs site foundation** (`mkdocs.yml` + `docs/index.md`) — GitHub Pages with Material theme; all existing guides wired in under Guides/Reference navigation.
- 📋 **Deprecation policy** in `docs/configuration.md` — properties deprecated ≥1 minor before removal; defaults frozen for the 1.x line.

### Changed
- 🔒 **`CycleDetector` and `WireDoctorHtmlReporter` sealed `final`** — implementation classes not meant for extension. Last-chance API surface review before the freeze.

### Compatibility
- Boot 2.7.x / 3.3.x / 3.5.x / 4.0.x — CI compat matrix green.
- WebFlux (Netty) — verified since v0.8.0.
- AOT / GraalVM native — skipped gracefully (see above).
- Java 17 / 21 / 25 — all green.

---

## [0.10.0] - 2026-07-29

Graph Timing: WireDoctor's core value — startup timing analysis — is now visible directly in the Graph tab, not just the Timing tab.

### Added
- 🔥 **Timing heat chip** — toggles node color (green→red, log scale) and size mapped to per-bean instantiation time, so expensive beans jump out at a glance. Off by default; cycle/ghost/proxy/orphan colors keep priority; untimed (never-instantiated) beans stay default.
- 🥇 **Critical path chip** — traces the startup critical path in gold through the graph (nodes glow, path edges widen) and dims everything else. Handles condensed `[cycle: a, b]` path entries by highlighting all members. Hidden when no timing data is available. Composes with filters and heat.
- 🔍 **Inspector timing line** — the node panel now shows `Instantiation: N ms` and an `on critical path` tag where applicable.
- 📊 **`beanTimings` JSON field** — the full per-bean instantiation timing map is serialized in the report (previously only `slowBeans` above the threshold survived).

## [0.9.0] - 2026-07-20

Front Door: a docs-only release. The README is rewritten as a problem-first funnel for launch, and everything that didn't belong in the project's front door is gone. No code changes.

### Changed
- 📖 **README rewritten** (271 → ~170 lines) — new "Why WireDoctor?" pain-point section; features regrouped by job (**See / Diagnose / Guard**) at 1–2 lines each with guide links, replacing the version-wise "New in vX" changelog-style sections (release history now points here); Quick start trimmed to dependency + baseline + gates; Documentation index table added; License section added (MIT OR Apache-2.0, matching the poms).
- ⚙️ **Configuration reference extracted** to `docs/configuration.md` — every property grouped by feature (core / gates / ghost tracking / production safety) with defaults and examples.
- 🧭 **CONTRIBUTING gains a README-discipline rule** — new features get 1–2 lines + a guide link; no "New in vX" sections may return.

### Removed
- 👤 **Author section** — profile links (X, Medium, Crates.io, Docker Hub) and an unrelated research-paper citation removed; a one-line maintainer credit (GitHub / LinkedIn / email) lives under Contributing.

### Fixed
- 📐 `docs/performance-gates.md` stated the `startup-time-relative-threshold` default as `0.10`; the code default is `0.20` — docs corrected.
- 🔗 `docs/report-tour.md` link updated to the renamed Quick-start anchor.

## [0.8.0] - 2026-07-20

Verification Debt: the original design plan's unfinished verification checks, closed before the v1.0.0 API freeze turns their answers into contracts — plus a jitter fix found in real-world gate validation.

### Added
- 🌊 **WebFlux/reactive validation** — an integration test now boots a REACTIVE (Netty, non-servlet) context in CI and asserts reports are written, startup timings populated and ghost analysis runs. Reactive entry points added to ghost detection: `WebHandler`, `WebSocketHandler`, `WebExceptionHandler` (joining `RouterFunction`) — a `RouterFunction` bean is pinned by test to never be flagged as a ghost. README supported-versions note added.
- 🔬 **Bean-scope behavior pinned by tests** — prototype definitions appear as graph nodes but are never instantiated (zero-intrusion promise, regression-tested); untouched `@Lazy` beans survive the full analysis untouched; `FactoryBean` product edges resolve to the factory's bean name; skipped not-instantiated beans are honestly counted. README Limitation #5 rewritten from vague to these test-backed facts.
- ⏱️ **`wiredoctor.slow-bean-margin-ms`** (default `20`) — jitter margin for the `slow-bean` gate: a NEW slow bean must exceed `threshold + margin` to trip; beans inside the margin band stay in the report but never fail CI. Born from real-world validation: start.spring.io tripped the gate at 101ms vs a 100ms threshold — measurement noise, not a regression. `0` restores exact pre-0.8.0 behavior. Margin surfaces in `gates.config` and the HTML gate card.

### Fixed
- 🤝 **Never overwrite a user-set `ApplicationStartup`** — the startup listener previously replaced any `ApplicationStartup` the application (or another tool) had installed. It now keeps a pre-existing non-default instance: buffering ones keep timings working (INFO), foreign ones get a WARN and the timing section degrades gracefully while every other analysis runs normally. Contract pinned by coexistence tests.

## [0.7.1] - 2026-07-20

Gate verdicts in the HTML report: the v0.7.0 performance gates and baseline diff results are now visible in the self-contained HTML console — no new tabs, no CI contract changes.

### Added
- 🚦 **`gates` report section** — `wiredoctor-report.json` (and the actuator view) now carries a `gates` key: `mode` (`off` / `write` / `diff` / `baseline-missing` / `baseline-unreadable`), `armed` (parsed `fail-on` list), `config` (the three thresholds), and — in diff mode — `baseline`, `failed` (fired gate signals, armed or not, same semantics as `wiredoctor-gate.status`), `startupTime` (baseline/current/delta/percent, recorded even when no regression tripped) and `newSlowBeans`.
- 🏷️ **Combined header verdict chip** — precedence: gate failed (red `GATE FAIL: <gates>`, with `+Xms` for startup-time) → cycles (red `N CYCLES`) → diff ran clean (green `GATES PASS`) → `HEALTHY`.
- 📋 **"Performance Gates" card in the Timing tab** — all four gates (`startup-time`, `slow-bean`, `new-cycle`, `condition-changed`) with threshold/config, actuals, and a PASS / FAIL / NOT RUN verdict chip; unarmed gates carry a muted `not armed` tag. When no baseline was diffed, the card shows the configured thresholds plus a hint on enabling gates in CI.

### Changed
- **Analyzer ordering** — the regression guard now runs BEFORE the JSON/HTML write so gate verdicts reach the report files. CI contract intact: the gate exception is still thrown only after all files are written.
- **Baseline files never carry `gates`** — the section is a per-run verdict, not part of the architecture snapshot; it is stripped from baseline serialization (like the graph-swap pattern).

## [0.7.0] - 2026-07-19

The "Cost Guardian" release: gate your CI pipeline on startup time regressions and new slow beans. Answers the P3 pain from market research — "K8s cold-start cost" — by making performance degradation visible and blockable before it ships.

### Added
- ⏱️ **Startup Time Gate** — `wiredoctor.fail-on=startup-time` trips when your app's cold-start time regresses beyond **both** an absolute threshold (default 500ms) and a relative threshold (default 10%) vs the baseline. Noise-tolerant by design: both thresholds must be exceeded to trip the gate. Configure via `wiredoctor.startup-time-absolute-threshold` (ms) and `wiredoctor.startup-time-relative-threshold` (fraction, e.g. 0.10 = 10%). The baseline snapshot now includes `totalStartupMs` (captured from `ApplicationReadyEvent.getTimeTaken()`, available in Boot 2.6+), and the diff logic compares it on every guarded run. Gate status written to `wiredoctor-gate.status` with `PASS` or `FAIL:startup-time` verdict.
- 🐌 **Slow Bean Gate** — `wiredoctor.fail-on=slow-bean` trips when a bean crosses your `slow-bean-threshold-ms` in the current run but was **not** slow in the baseline. Catches new performance bottlenecks (e.g., a developer adds a `@PostConstruct` that does I/O) before they escape to production. The baseline snapshot now includes `slowBeanThreshold` and the list of slow bean names; the diff compares both. A bean that's always been slow doesn't trip the gate — only **new** entries do.
- 🎯 **Noise-tolerant regression methodology** — startup timings are inherently noisy (JIT, CPU contention, disk I/O). The `startup-time` gate requires **both** absolute AND relative thresholds to be exceeded, so it won't trip on small absolute jitter (+50ms on a 5s baseline) or small relative changes (-2% on a fast 500ms baseline). The `slow-bean` gate compares bean names, not timings — it's a membership diff, not a timing diff.
- 📊 **Timing data in baselines** — `WireDoctorBaselineDiff.Snapshot` now includes `totalStartupMs: Long` and `slowBeanThreshold: Long` fields, written during baseline-write mode and loaded during diff mode. Backward-compatible: a pre-0.7.0 baseline (no timing data) skips the timing gates gracefully without tripping them.

### Changed
- **Gate status format extended** — `wiredoctor-gate.status` now includes timing-specific metadata when timing gates are armed: `startupTimeMs=<current>`, `baselineStartupTimeMs=<baseline>`, `newSlowBeans=<count>`. Line 1 contract unchanged: `PASS` or `FAIL:startup-time,slow-bean` (comma-separated).
- **`fail-on` property accepts four gates** — `new-cycle`, `condition-changed`, `startup-time`, `slow-bean` — combinable in any order via comma-separated list.

### Tests
- 205 autoconfigure tests green (25 new: startup-time gate trip/pass with absolute/relative thresholds, slow-bean gate trip/pass, baseline backward-compat, timing data serialization, noise-tolerance edge cases). All Boot lines (2.7.18, 3.3.x, 3.5.x, 4.0.x) re-verified in CI.

### Documentation
- New guide: [docs/performance-gates.md](docs/performance-gates.md) — full walkthrough of `startup-time` and `slow-bean` gates with CI integration examples (GitHub Actions, GitLab CI, Jenkins), multi-profile baseline usage, troubleshooting (gate trips unexpectedly, gate never trips, null timing data), and when-to-use guidance.
- Updated: [docs/ci-gating.md](docs/ci-gating.md) — gate table now lists all four gates with "Since" column and backward-compat notes.

## [0.6.1] - 2026-07-19

The report UI, rebuilt as a professional diagnostic console. No analysis changes —
this release is entirely about how the existing data is presented.

### Changed
- 🖥️ **HTML report redesigned as a tabbed console** — Overview / Graph / Ghosts /
  Smells / Timing / Conditions. Every report section now has a home in the HTML
  (smells, critical path, conditions and ghosts were previously JSON-only):
  - **Header** with active profile, bean/edge counts, and a live health chip
    (green HEALTHY / red "N CYCLES").
  - **Overview**: six KPI cards, bean-composition bar, critical-path chain with
    per-bean timings, top coupling hotspots, ghost-candidate preview.
  - **Graph**: search with focus-and-zoom, filter toggles (user / framework /
    cycles-only / ghosts-only), click-to-inspect node panel (fan-in/out,
    dependency list, status pills). Problems are colored (red cycle, purple
    ghost, pink proxy, amber orphan); healthy beans stay neutral — calm when
    healthy, loud when not. Truncation banner and vis-missing fallback retained.
  - **Ghosts**: Phase 1 candidates and Phase 2 touched/untouched/untrackable
    side by side, with an "also candidate" cross-signal badge; disclaimers
    rendered in-page.
  - **Conditions**: all outcomes with count pills and a live class-name filter.
  - Deep-linking (`#ghosts` opens that tab), dark professional theme, monospace
    data, no gradients.
- **Template extracted to the classpath** (`/wiredoctor/report-template.html`)
  instead of a Java text block — the UI is now editable without touching Java.
  The reporter injects data/version/vis-network into it; a missing template
  degrades to a logged error (JSON report unaffected).
- **Ghost data in the HTML**: the report is written at startup with the Ghosts
  tab in "results pending" state (ghosts are only knowable at shutdown); when
  tracking is enabled, the shutdown writer re-renders `wiredoctor-report.html`
  with the final ghost results injected.
- Offline promise unchanged: vis-network still bundled and inlined; the page
  renders fully offline.

### Tests
- 194 autoconfigure tests green (4 new: injection contract, null-ghost startup
  render, shutdown ghost render, tab structure). Boot 2.7.18 compat re-verified.

## [0.6.0] - 2026-07-19

The "Ghost Hunt" release: WireDoctor answers "which beans are wasting resources?" in two
phases with two trust postures — a passive heuristic that's always on, and real
first-touch tracking behind an explicit opt-in. The zero-intrusion default is untouched
and now regression-tested.

### Added
- 👻 **Ghost Candidates (Phase 1 — passive, always on)** — the new `ghostCandidates`
  report section refines the orphan list by crossing three signals: eagerly
  instantiated ∧ 0 incoming dependency edges ∧ no entry point detectable from
  metadata. Entry-point detection covers `@Controller` (and meta-annotated stereotypes
  like `@RestController`), `@Scheduled`/`@EventListener` holders (including private and
  inherited methods), messaging listeners (`@KafkaListener`, `@RabbitListener`, ...),
  framework-invoked interfaces (`CommandLineRunner`, `ApplicationRunner`, `Lifecycle`,
  servlet/filter types, `HealthIndicator`, ...), and `@Configuration`/`@Aspect`
  definition-time classes. Detection deliberately errs broad — a false entry-point
  match only shrinks the list; classification failures exclude conservatively.
  The section carries `confidence: LOW`, an in-payload disclaimer, and honest exclusion
  counts (`entryPointsExcluded`, `notInstantiatedExcluded`). The raw `orphanBeans` list
  is unchanged — candidates sit beside it, not instead of it. Console summary (top 10)
  included.
- 🔬 **First-Touch Ghost Tracking (Phase 2 — opt-in, dev/staging)** —
  `wiredoctor.ghost-tracking.enabled=true` wraps eligible user singletons in a thin
  counting proxy whose advice does exactly one thing: flip an `AtomicBoolean` on first
  invocation. No timing, no argument capture, no hot-path logging — measured overhead
  **~180 ns/call** after first touch (warmed JDK 21; dominated by proxy dispatch itself).
  A `ContextClosedEvent` listener writes `wiredoctor-ghost-report.json` at shutdown:
  touched / untouched / untrackable — never "unused"; the disclaimer is embedded in the
  payload. `wiredoctor.ghost-tracking.exclude` skips named beans (reported as
  `untrackable: excluded`).
- 🛡️ **Eligibility guards (skip + report, never break)** — framework beans (counted in
  `frameworkSkipped`), already-proxied beans (`@Transactional`/`@Async` — the tracker
  runs at `LOWEST_PRECEDENCE` and never double-wraps; integration-tested to preserve
  existing advice), `FactoryBean`s/`BeanPostProcessor`s/AOP infrastructure, interface-less
  final classes, non-singletons, and the user exclude list. Any wrapping error logs a
  warning and returns the bean **unwrapped** — a diagnostic tool must never turn a
  working bean into a broken one.
- 🔁 **Circular-reference safety** — the tracking post-processor implements
  `SmartInstantiationAwareBeanPostProcessor.getEarlyBeanReference` (the same contract as
  Spring's own auto-proxy creator), so beans in resolvable cycles
  (`allow-circular-references=true`) track cleanly instead of failing context refresh.
- 🌐 **Live actuator view** — `/actuator/wiredoctor/{section}` serves any top-level
  report section; `/actuator/wiredoctor/ghosts` serves the **live** tracking state
  (invocations up to this moment) for long-running staging runs. Without the opt-in it
  answers an honest `DISABLED` placeholder with the enable instructions. Core remains
  actuator-free.
- 📖 **Docs** — new [ghost-detector guide](docs/ghost-detector.md) (two-phase model,
  eligibility table, honest-interpretation checklist); security-posture updated
  (ghost report files, `/ghosts` selector); README feature section + roadmap sync.

### Changed
- **Passivity is now regression-tested**: with the default configuration the test suite
  asserts zero WireDoctor `BeanPostProcessor` in the factory chain and raw (unproxied)
  user beans — the v0.1.1 zero-intrusion promise, made mechanical.
- README roadmap: Ghost Detector marked shipped; *Memory Footprint Estimation*
  documented as deliberately dropped (honest per-bean heap numbers need a Java Agent;
  shallow size-of is a correctness trap).

### Tests
- 201 tests green (190 autoconfigure + 11 actuator; up from 156): entry-point matrix,
  eligibility matrix, passivity regression, advice-preservation on pre-proxied beans,
  touched-vs-ghost integration with shutdown report, exclude list, hot-path benchmark.
- Real-world validation on the demo app recorded in
  `plan/v0.6.0-realworld-validation.md` — including the two bugs it caught
  (circular-reference crash, self-tracking noise), both fixed before release.

## [0.5.0] - 2026-07-18

The "Upgrade Guardian" release: WireDoctor stops at describing your bean graph and
starts guarding it across Spring Boot upgrades — catching the autoconfiguration
condition flips that silently break apps.

### Added
- 🩺 **Autoconfig Condition Diff (the Upgrade Guard)** — WireDoctor now snapshots
  Spring Boot's `ConditionEvaluationReport` (the same data `--debug` renders) into the
  report and baseline as a `conditions` section: one stable outcome per
  autoconfiguration class — `matched`, `notMatched` (with the condition message as
  `reason`), `excluded`, or `unconditional`. The regression guard diffs these across
  builds and produces a `conditionDiff` in `wiredoctor-diff.json`:
  `changed` (outcome flips like `matched → notMatched` — the headline), `added`/`removed`
  (autoconfig classes that appear/vanish, e.g. from a Boot version bump). Where the bean
  diff shows *what* changed (beans gone), the condition diff shows *why* (which condition
  flipped), with the exact `@ConditionalOnBean`/`@ConditionalOnClass` message. Read-only
  access to an object Boot already keeps in memory — zero new intrusion. Validated on
  Spring PetClinic (Boot 4.1): excluding one autoconfiguration surfaced the direct flip,
  a downstream `@ConditionalOnBean` cascade, and 16 vanished beans in a single diff.
- 🚦 **`condition-changed` regression gate** — `wiredoctor.fail-on=condition-changed`
  fails the build (in CI) when an autoconfiguration condition outcome flips vs the
  baseline. Combinable with `new-cycle` via the comma-separated contract
  (`wiredoctor.fail-on=new-cycle,condition-changed`); the `wiredoctor-gate.status` marker
  lists every fired gate (`FAIL:new-cycle,condition-changed`) and records
  `conditionsChanged=N`. A pre-0.5.0 baseline without condition data is handled
  gracefully: the condition diff is **skipped** (`conditionDiff=skipped`), the verdict
  stays `PASS`, and the gate never trips — full backward compatibility.

### Documentation
- 📘 **Upgrade Guard guide** (`docs/upgrade-guard.md`) — the Boot-upgrade workflow
  (baseline on the old version, diff on the new, gate in CI), how to read the condition
  diff, and the backward-compatibility contract. Written from the real PetClinic
  validation. Linked from the README; `ci-gating.md` and `security-posture.md` updated
  for the new gate and the autoconfig-surface disclosure.

## [0.4.0] - 2026-07-18

### Added
- 🚦 **CI marker-file contract** — every completed baseline diff now writes
  `wiredoctor-gate.status` next to the reports. Line 1 is the contract:
  `PASS` or `FAIL:new-cycle`; following `key=value` lines are informational
  (baseline name, cycle/bean counts, whether the hard gate was armed).
  Written whether or not `wiredoctor.fail-on` is set, so teams can gate softly
  (`grep -q '^PASS' wiredoctor-gate.status`) without log parsing or relying on
  JVM exit codes. Stale markers are deleted at the start of every guarded run —
  absence means "no verdict", so a crashed or skipped diff can never leave a
  previous run's verdict behind.
- 🧹 **User-bean smell filtering** — the `smells` rankings (`highFanIn`,
  `highFanOut`, `unstable`) now default to user-defined beans only. On real
  apps the coupling hotspots are dominated by framework infrastructure
  (`WebMvcAutoConfiguration`, `environment`, endpoint handler mappings) that
  users cannot refactor, burying the actionable signal. The new
  `wiredoctor.include-framework-smells` property (default `false`) restores the
  full framework-included rankings. The report records `smells.frameworkFiltered`
  so a consumer can tell "no smells" from "framework filtered out". The full
  `smells.fanIn` map is never filtered — HTML node sizing keeps every node's
  true coupling weight. As a side effect, the synthetic
  `...ApplicationContext@hash` fan-in entries (which carry a per-boot JVM
  identity hash) no longer surface in the rankings.
- 🩺 **Actuator endpoint (new `wiredoctor-actuator` module)** — an optional,
  separately-published artifact exposing the diagnostic report over
  `/actuator/wiredoctor`. Read-only: it serves the in-memory report the core
  produced at application-ready time (no analysis is triggered by an HTTP call,
  the `BeanFactory` is never touched), and returns `{status: "PENDING"}` until
  startup analysis completes. The endpoint auto-configures only when Actuator
  is already on the classpath, WireDoctor is enabled, and the `wiredoctor`
  endpoint is exposed per the standard `management.endpoints.web.exposure`
  rules. **The core `wiredoctor-autoconfigure` artifact remains actuator-free**
  — Actuator appears only in this module (verified by its dependency tree), so
  the v0.1.1 classpath-neutrality guarantee never regresses. The core analyzer
  now retains its last report in memory (`WireDoctorAnalyzer.getLastReport()`)
  to back the endpoint.
- 🎚️ **Multi-profile baselines** — the `wiredoctor.baseline` path now accepts a
  `{profiles}` token, replaced at runtime with a stable key derived from the
  active profiles (sorted, dash-joined, or `default` when none are active). So
  `wiredoctor-baseline-{profiles}.json` diffs a `prod` run against
  `wiredoctor-baseline-prod.json` and a `dev` run against
  `wiredoctor-baseline-dev.json` — profile-specific bean graphs compare
  like-with-like instead of churning against one shared baseline. A missing
  per-profile baseline degrades gracefully through the existing
  "baseline missing → skip" path. Paths without the token are unchanged (single
  shared baseline, as before). The report now records `activeProfiles`, and the
  `wiredoctor-gate.status` marker gains a `profiles=` line for traceability.

### Fixed
- **Smell-filter classification gaps** (found in real-world testing on Spring
  Initializr, 391 beans): well-known Spring infrastructure singletons registered
  outside `beanDefinitionNames` (`environment`, `systemProperties`,
  `systemEnvironment`, `applicationStartup`, `messageSource`) no longer surface
  in the filtered smell rankings; and when `wiredoctor.scan-packages` is
  configured it now also acts as the user-bean allowlist for the smell rankings
  (consistent with the orphan-bean logic), keeping third-party autoconfig beans
  (e.g. `com.azure.spring…`) out of the actionable lists.

### Documentation
- 🔒 **Security posture guide** (`docs/security-posture.md`) — what the reports
  expose (bean names ≈ internal architecture, edges, profiles, timings; never
  credentials or bean state), recommendations (gitignore reports, disable in
  production, guard the actuator endpoint), and the **offline-only promise**:
  WireDoctor's JVM does zero network I/O ever — an auditable claim with a
  one-line grep to verify it, plus the one honest nuance (the generated HTML's
  browser-side CDN fallback for vis-network when the bundled copy is missing).
  Linked from the README.
- 🤝 **Community scaffolding** — `CONTRIBUTING.md` (build/test conventions, the
  zero-intrusion posture, PR expectations, good-first-issue guidance),
  `CODE_OF_CONDUCT.md` (Contributor Covenant 2.1), GitHub issue forms
  (bug report + feature request, with WireDoctor-specific fields), and a pull
  request template.

## [0.3.0] - 2026-07-18

The "from report to advice" release: WireDoctor stops just describing your
architecture and starts telling you what to do about it — and survives
enterprise-scale contexts while doing so.

### Added
- 💡 **Counterfactual @Lazy Simulator** — when cycles are detected, the report
  now includes a `lazySuggestions` section listing which beans, if marked
  `@Lazy`, would break each cycle. Ranked by cycles broken (desc), then fan-in
  blast radius (asc), then name for deterministic output. The console prints
  the top 5 with impact counts. Empty array (not absent) when no cycles exist.
  Pure computation over the already-built graph — zero-intrusion guarantees hold.
- 📐 **Architecture Smell Metrics** — new `smells` report section computed on
  the live resolved graph: top-10 fan-in hotspots (`highFanIn`, with dependents
  listed), top-10 fan-out hotspots (`highFanOut`, with dependencies listed),
  and beans over Martin's instability threshold (`unstable`,
  `I = Ce/(Ca+Ce) ≥ 0.8`, fan-out ≥ 2 floor to skip trivial leaves). Console
  prints top 3 per category. HTML graph nodes are now sized by fan-in
  (sqrt-scaled) with fan-in shown in the hover tooltip.
- 🏋️ **Large-Context Hardening** — new `wiredoctor.max-graph-nodes` property
  (default 2000, 0 = unlimited, lenient parsing). Above the cap, the
  serialized `graph` section is truncated to the top-N beans by fan-in with
  cycle participants always retained; `graphTruncated` /
  `graphNodesTotal` / `graphNodesKept` metadata is added and the HTML report
  shows a warning banner. Analysis (cycles, smells, critical path, baseline
  diff) always runs on the full graph, and `baseline-write` always persists
  the full graph so future diffs stay accurate. Verified with a 5,000-bean
  synthetic context test.

## [0.2.0] - 2026-07-17

The category-defining release: WireDoctor stops being a snapshot visualizer and
becomes an **architectural fitness function** that gates CI.

### Highlights
- 🛡️ **Architectural Regression Guard** — commit a baseline of your bean graph
  like a lockfile, and **fail your PR when someone adds a bean cycle**
  (`wiredoctor.fail-on=new-cycle`). See [docs/ci-gating.md](docs/ci-gating.md).
- ⛓️ **Startup Critical Path** — the longest instantiation-weighted dependency
  chain gating your readiness time, in the report and console.
- 📦 **Truly self-contained HTML** — the graph library is now bundled and
  inlined; the report renders fully offline. The v0.1.1 "honest docs" caveat
  is retired.
- ✅ **Verified compatibility matrix** — Boot 2.7 / 3.3 / 3.5 / 4.0 × Java
  17 / 21 / 25, green in CI and published in the README.

### Added
- **Architectural Regression Guard** (opt-in, off by default):
  - `wiredoctor.baseline=<path>` — diff the live bean graph against a committed
    baseline JSON; differences are logged and written to `wiredoctor-diff.json`.
  - `wiredoctor.baseline-write=true` — accept the current architecture as the
    new baseline (never diffs or gates).
  - `wiredoctor.fail-on=new-cycle` — throw `WireDoctorRegressionException`
    after analysis completes so a CI run exits non-zero. This is the ONLY
    exception WireDoctor ever lets escape, and only by explicit request.
  - Cycle identity is the exact bean set: an existing cycle that grows counts
    as a new cycle.
  - `WireDoctorBaselineDiff` is a pure, Spring-free diff engine (12 unit tests);
    7 integration tests cover the gate end-to-end, including a real
    cyclic app tripping it.
  - Graceful degradation everywhere: missing baseline → info log; unreadable
    baseline → warning; diff write failure → error log. Never a crash.
- **Startup Critical Path**: SCC-condensed dependency DAG → longest
  instantiation-weighted path (iterative Kahn topological pass, O(V+E); cycle
  super-nodes weigh the sum of their members). New `criticalPath` report
  section (`totalMs`, `percentOfReadiness`, `path`, disclaimer) plus a one-line
  console chain. Degrades gracefully when startup timings are unavailable
  (non-buffering startup). Carries an explicit disclaimer: instantiation-weighted
  approximation; parallel init is not modeled.
- **CI gating guide** (`docs/ci-gating.md`): complete GitHub Actions recipe —
  create the baseline, configure the gate, fail the PR, upload
  `wiredoctor-diff.json` as an artifact on failure.
- **Compatibility matrix CI** (`.github/workflows/compat.yml`) and a
  supported-versions table in the README backed by it.

### Changed
- **HTML report is truly self-contained**: vis-network 10.0.1 (standalone UMD,
  MIT/Apache-2.0) is bundled as a classpath resource and inlined into the HTML
  at generation time with an attribution comment. The CDN is only a fallback
  when the bundled resource is missing. README wording updated — the
  "self-contained" claim is mechanically true again.
- Analyzer now collects **all** bean instantiation timings (max-merge for
  nested instantiate steps) instead of only slow ones, to feed the critical
  path; readiness time comes from `ApplicationReadyEvent.getTimeTaken()`.
- README compatibility claim replaced by the CI-verified matrix (floor remains
  Boot 2.4 for startup timings; 2.7+ is what CI guarantees).

### Notes
- 62 tests green in `wiredoctor-autoconfigure`. Not published to Maven
  Central — per the production roadmap, publishing resumes at v1.0.0.

## [0.1.2] - 2026-07-17

Internal engineering release: no user-visible features. Test suite, typed
properties, iterative Tarjan, and a CI coverage gate make every future
release safe to ship.

### Added
- **Test suite (29 tests, 87% line coverage)**: `CycleDetector` unit tests (self-loop, nested SCCs, disconnected graphs, 10k-node deep chain and giant cycle), `WireDoctorProperties` binding/parsing tests, and 7 end-to-end integration tests that boot a real Spring context and pin down every v0.1.1 trust guarantee (bad config never crashes host, `@Lazy` beans never instantiated, `enabled=false` truly disables, read-only filesystem degrades to log-only, repeated analysis is idempotent).
- **`WireDoctorProperties` (@ConfigurationProperties("wiredoctor"))**: typed binding with all defaults in one place; generated `spring-configuration-metadata.json` gives IDE autocomplete for `wiredoctor.*` keys. The slow-bean threshold binds leniently (a malformed value degrades to the 100ms default with a warning, never a bind failure).
- **JaCoCo coverage gate**: build fails under 80% line coverage on `wiredoctor-autoconfigure`.
- **CI matrix**: `mvn verify` on Java 17 and 21; JaCoCo report uploaded as a build artifact.

### Changed
- **Iterative Tarjan**: `CycleDetector` now uses an explicit frame stack instead of recursion — arbitrarily deep dependency chains can no longer cause `StackOverflowError`.
- **Orphan-bean list**: the application's own `@SpringBootApplication` class is skipped (it always has 0 incoming dependencies; listing it was noise).
- Raw `Environment.getProperty` reads replaced by the typed properties class throughout the analyzer.

### Fixed
- Configuration metadata was silently never generated in v0.1.x: JDK 23+ disables annotation processing by default; the compiler plugin now sets `proc=full`.

## [0.1.1] - 2026-07-17

Trust patch: WireDoctor must never hurt the host application.

### Fixed
- **[HIGH] Crash-safety**: the entire analysis is now wrapped in `try/catch(Throwable)` — no failure inside WireDoctor (bad configuration, unexpected Spring state) can ever fail host application startup. Previously a malformed `wiredoctor.slow-bean-threshold-ms` (e.g. `50ms`) crashed the host app with "Application run failed".
- **[HIGH] Zero-intrusion — forced bean instantiation**: the proxy scan no longer calls `getBean()` for every bean definition. It only inspects beans that are already instantiated (`containsSingleton`), so `@Lazy` singletons, prototypes, and `FactoryBean` products are never force-instantiated at report time. Skipped beans are reported honestly as `notInstantiatedSkipped`.
- **[HIGH] Classpath pollution**: removed the `spring-boot-starter-actuator` compile dependency (`BufferingApplicationStartup` lives in spring-boot core). Host apps no longer silently get actuator + micrometer on their classpath. Dependencies are now only `spring-boot-autoconfigure`, `slf4j-api`, and `jackson-databind`.
- **Threshold parsing**: `wiredoctor.slow-bean-threshold-ms` is safe-parsed; invalid values log a warning and fall back to the 100ms default.
- **Demo app boots out-of-the-box**: `wiredoctor-test` now sets `spring.main.allow-circular-references=true` so its intentional AlphaBean↔BetaBean demo cycle boots flag-free and WireDoctor detects it.
- **HTML report offline behavior**: when the vis-network CDN is unreachable, the report now shows a clear notice instead of a silent blank canvas (sidebar stats always work offline).

### Changed
- **Honest docs**: README no longer claims the HTML report is "self-contained" — report data is embedded but the graph library loads from a CDN (true self-containment is planned for v0.2.0). Compatibility claim corrected from "2.x, 3.x, 4.x" to "2.4+ and 3.x" pending a verified matrix.
- **Single listener registration**: `WireDoctorStartupListener` is now registered only via `META-INF/spring.factories` (the mechanism Boot uses for `ApplicationStartingEvent`-time listeners); the redundant `ApplicationListener.imports` entry was removed.

## [0.1.0] - 2026-04-07

### Added
- **Bean Category Summary**: Added detailed categorization of beans (User-defined vs Framework, Application vs Infrastructure) to reports and console logs.
- **Slow Bean Profiling**: Tracks and highlights beans taking longer than a configurable threshold to instantiate (default 100ms).
- **Configurable Output Path**: Added `wiredoctor.output-path` property to customize where HTML and JSON reports are saved.
- **Configurable Threshold**: Added `wiredoctor.slow-bean-threshold-ms` to adjust the slow bean detection threshold.
- **Maven Central Readiness**: Added SPDX `MIT OR Apache-2.0` license headers to all Java files and `licenses`/`developers` metadata to root `pom.xml`.
- **Professional JavaDocs**: Added comprehensive, Spring-style class and method level JavaDocs.
- **Interactive HTML Visualizer (`WireDoctorHtmlReporter`)**: Generates a self-contained, zero-dependency `wiredoctor-report.html` file using Java 17 Text Blocks and Vis.js. Provides a Dark-Mode Glassmorphism dashboard with an interactive, physics-based network graph of the application's beans.
- **Node Color-Coding**: Visualizer intuitively color-codes beans (Green for Safe, Red for Danger/Cycles, Pink for Proxies, Orange for Orphans).
- **Core Analyzer:** Introduced `wiredoctor-autoconfigure` module to act as a zero-friction Spring Boot starter.
- **Startup Timings Insight:** Implemented an interceptor via `SpringApplicationRunListener` to inject a `BufferingApplicationStartup` early in the lifecycle. This precisely tracks how much time each bean takes to instantiate without any reflection guessing.
- **Bean Dependency Graph Extraction:** Hooked into Spring's native `ConfigurableListableBeanFactory.getDependenciesForBean` for 100% accurate, resolved dependency edge mapping instead of relying on rudimentary `@Autowired` source scraping.
- **Cycle Detection Engine:** Implemented Tarjan's Strongly Connected Components (SCC) algorithm to parse the dependency graph and identify structural circular dependencies (e.g., cycles masked by `@Lazy` or setter injections that Spring tolerates but flag architectural smells).
- **Proxy Overhead Counter:** Added runtime tracking (`AopUtils.isCglibProxy()`) to measure indirection layers wrapped around beans via annotations like `@Async` or `@Transactional`.
- **Dual Reporting Layer**:
  - **JSON Exporter:** Automatically serializes complete deep-dive findings into a robust `wiredoctor-report.json` schema.
  - **Standard Logging:** A developer-friendly SLF4J summary (`log.info`) covering top 5 slowest beans, detected cycle chains, and proxy counts.
- **Adversarial Test Suite:** Added a `wiredoctor-test` module to actively test edge conditions (e.g., deliberate cycles, synthetic slow startup, and async proxies) ensuring high epistemic honesty and heuristic reliability.

### Changed
- **Robust Startup Capture**: Removed `ApplicationContextInitializer` and completely refactored the startup interception to use `ApplicationListener<ApplicationStartingEvent>`. This guarantees the `BufferingApplicationStartup` is injected safely across all Spring Boot versions (2.x, 3.x, and 4.x) without "Double Registration" bugs or dropped startup steps.
- **Real-World Validation**: Successfully validated against `spring-petclinic` and `start.spring.io` (Spring Initializr running Boot 4.0.x), mapping 400+ beans and rendering exact proxy and startup timings without conflict.

### Fixed
- **Double Registration Bug**: Fixed an issue where `WireDoctorContextInitializer` would overwrite the `BufferingApplicationStartup` instance. Removed dead `WireDoctorRunListener` code as `WireDoctorStartupListener` successfully handles startup interception independently.
- **Orphan Bean Noise**: Added default exclusion logic for internal `org.springframework`, `java.`, and `org.apache` packages. Users can still explicitly override this using the `wiredoctor.scan-packages` property.
- **Self-Orphan Fix**: The `WireDoctorAnalyzer` bean itself is no longer incorrectly flagged as an orphan.
- **Epistemic Honesty for Cycle Detection**: Documented the "Early-Reference Cycle Blindspot" acknowledging that cycles resolved silently by Spring's 3-level cache (when `allow-circular-references=true`) may bypass `getDependenciesForBean()` and remain unreported.
