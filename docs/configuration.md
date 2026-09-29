---
title: Configuration
nav_order: 4
---

# Configuration Reference

Every WireDoctor property in one place. All properties are optional — WireDoctor works out of the box with zero configuration.

## Core

| Property | Default | Description |
|----------|---------|-------------|
| `wiredoctor.enabled` | `true` | Master switch. `false` completely disables the analyzer — no analysis, no reports, no bean-structure exposure. Set this in `application-prod.properties` if the dependency ships to production. |
| `wiredoctor.output-path` | project root | Directory where `wiredoctor-report.json` and `wiredoctor-report.html` are written. Set it to `target` (or `build`) if your build lints the source tree — see the note below. |
| `wiredoctor.scan-packages` | *(auto)* | Comma-separated package prefixes to analyze for orphan beans. By default, framework packages (`org.springframework`, `java.`, `org.apache`, …) are filtered out automatically. |
| `wiredoctor.slow-bean-threshold-ms` | `100` | Beans taking longer than this to instantiate are flagged as slow (report + console). |
| `wiredoctor.max-graph-nodes` | `2000` | Above this many beans, the *serialized* graph (JSON + HTML view) is capped to top-N by fan-in (cycle members always kept) so the browser doesn't freeze. Analysis itself — cycles, smells, critical path, baseline diff — always runs on the full graph. `0` = unlimited. |
| `wiredoctor.include-framework-smells` | `false` | Include framework beans in smell rankings. Off by default so every ranked bean is one you can actually refactor. |

```properties
wiredoctor.scan-packages=com.yourcompany.app,io.yourteam.service
wiredoctor.output-path=target
wiredoctor.slow-bean-threshold-ms=50
wiredoctor.max-graph-nodes=2000
```

### Set `output-path` if your build lints the source tree

By default the report lands in the project root, where source-tree linters will
find it. The HTML embeds vis.js and egjs, whose license headers contain `http://`
URLs, so any project using Spring's `nohttp-checkstyle` — that is, every Spring
project and every build that inherited Spring's parent — fails its **next** build
on a file nobody wrote:

```
[ERROR] wiredoctor-report.html:[17,43] (extension) NoHttp: http:// URLs are not
        allowed but got 'http://almende.com'. Use https:// instead.
[ERROR] Failed to execute goal maven-checkstyle-plugin:check
        (nohttp-checkstyle-validation): You have 11 Checkstyle violations.
```

```properties
wiredoctor.output-path=target
```

`target/` is already outside the lint scope and already ignored by git, so this
also keeps the report out of commits and diffs.

### `scan-packages` also cleans the smell rankings

Naming your own packages does more than filter orphan beans: without it,
WireDoctor's own beans are ranked in your architecture report
(`com.wiredoctor.WireDoctorAutoConfiguration` as a coupling hotspot,
`wireDoctorAnalyzer` as unstable), alongside framework beans you cannot refactor.

```properties
wiredoctor.scan-packages=com.yourcompany.app
```

With that set, every ranked bean is one you own. It is the single highest-leverage
property for first-run signal quality.

## Regression Guard & Gates (opt-in — CI only)

| Property | Default | Description |
|----------|---------|-------------|
| `wiredoctor.baseline` | *(unset)* | Path to the committed architecture baseline. Setting it enables the diff. |
| `wiredoctor.baseline-write` | `false` | `true` writes/refreshes the baseline (never diffs or gates on that run). |
| `wiredoctor.fail-on` | `""` | Comma-separated gates that fail startup after the diff: `new-cycle`, `condition-changed`, `startup-time`, `slow-bean`. Empty = report-only. |
| `wiredoctor.startup-time-absolute-threshold` | `500` | ms. Startup must regress by more than this **AND** the relative threshold to trip `startup-time`. |
| `wiredoctor.startup-time-relative-threshold` | `0.20` | Fraction (0.20 = 20%). The other half of the dual-threshold AND condition. |
| `wiredoctor.slow-bean-margin-ms` | `20` | Jitter margin for the `slow-bean` gate: a *new* slow bean must exceed `threshold + margin` to trip. Beans inside the margin band are reported but never fail CI. `0` = exact pre-v0.8.0 behavior. |
| `wiredoctor.trend-history-size` | `30` | Cap on `trendHistory[]` entries kept in the baseline file. Each `baseline-write` run appends one `{timestamp, totalStartupMs, slowBeanCount}` entry and trims the oldest beyond the cap. `0` = unlimited. See [Startup Time Trend](startup-time-trend.html). |

```properties
# One-time baseline capture (commit the file):
wiredoctor.baseline=wiredoctor-baseline.json
wiredoctor.baseline-write=true

# CI profile — diff and gate:
wiredoctor.baseline=wiredoctor-baseline.json
wiredoctor.baseline-write=false
wiredoctor.fail-on=new-cycle,startup-time,slow-bean
```

Gates write `wiredoctor-gate.status` (`PASS`/`FAIL`) and `wiredoctor-diff.json` for CI inspection. Full walkthroughs: [Performance Gates](performance-gates.html) · [CI gating](ci-gating.html) · [Upgrade Guard](upgrade-guard.html).

## Ghost Tracking (opt-in — dev/staging only)

| Property | Default | Description |
|----------|---------|-------------|
| `wiredoctor.ghost-tracking.enabled` | `false` | Wraps eligible user beans in a thin first-touch counting proxy. Off by default: the tracking `BeanPostProcessor` is never registered at all (regression-tested passivity). |
| `wiredoctor.ghost-tracking.exclude` | *(unset)* | Comma-separated bean names to never wrap — reported as `untrackable:excluded`, never silently hidden. |

```properties
wiredoctor.ghost-tracking.enabled=true
wiredoctor.ghost-tracking.exclude=legacySoapClient,nativeBridge
```

Results land in `wiredoctor-ghost-report.json` at shutdown, or live via `/actuator/wiredoctor/ghosts`. Details: [Ghost Detector guide](ghost-detector.html).

## Module Boundaries (opt-in — multi-module architectures)

Declare your modules by package prefix and WireDoctor flags **hidden coupling**: an edge from one module into another module's *internal* (non-API) package. It compiles and runs fine today — which is exactly why it goes unnoticed until the modules can no longer be pulled apart.

| Property | Default | Description |
|----------|---------|-------------|
| `wiredoctor.module-boundaries.modules` | *(empty)* | Map of package-prefix → module name. Empty = feature off (zero overhead — the detector short-circuits). A bean is assigned to the module whose configured prefix is the **longest** match for its package, so nested modules (`com.acme` vs `com.acme.orders`) resolve correctly. |
| `wiredoctor.module-boundaries.api-packages` | *(empty)* | Glob patterns for each module's **public** surface, e.g. `*.api`. A cross-module edge whose target package matches one of these is allowed; any other cross-module edge is a violation. `*` matches any characters; a matched package's sub-packages count as public too. |

```yaml
wiredoctor:
  module-boundaries:
    modules:
      "[com.acme.orders]": orders
      "[com.acme.billing]": billing
    api-packages:
      - "*.api"
```

Violations show up in the console at startup, in `wiredoctor-report.json` under `boundaryViolations`, and in the **Boundaries** tab of the HTML report. All three are absent entirely when no modules are configured — the section is additive and `schemaVersion` stays `1`. Details: [Module Boundaries guide](module-boundaries.html).

### Gotcha: map keys with dots need brackets

`modules` is a `Map` whose keys **are package names, and package names contain dots**. Spring's relaxed binding reads a dot as a nesting separator, so an unquoted `com.acme.orders:` key binds as nested objects (`com` → `acme` → `orders`), not the single string key you meant — and the module silently never matches anything. Wrap the whole key in `[...]`:

```yaml
# ✅ correct — the dotted key is taken literally
wiredoctor.module-boundaries.modules:
  "[com.acme.orders]": orders

# ❌ wrong — binds as com/acme/orders nesting; the module never resolves
wiredoctor.module-boundaries.modules:
  com.acme.orders: orders
```

In a `.properties` file (or in `--args`/`SpringApplicationBuilder` properties) the same key uses index-style brackets, no surrounding quotes:

```properties
wiredoctor.module-boundaries.modules[com.acme.orders]=orders
wiredoctor.module-boundaries.api-packages[0]=*.api
```

## Production Safety

WireDoctor is enabled by default. If the dependency accidentally ships to production:

```properties
# application-prod.properties
wiredoctor.enabled=false
```

For what the reports expose and WireDoctor's offline-only network behavior (its JVM does zero network I/O), see the [security posture guide](security-posture.html).

---

## v1.0.0 Stability Contract

All `wiredoctor.*` property names listed above are **frozen** as of v1.0.0:

- A property will not be removed without being deprecated for **at least one minor release** first.
- Deprecated properties log a `WARN` on startup; the old name remains functional until the next major.
- The report JSON field names (`schemaVersion`, `beanCategories`, `dependencies`, `smells`, `gates`, etc.) are frozen at `schemaVersion: 1`. A field rename or removal requires a new `schemaVersion` value and a **major version bump**.
- Default values will not change in patch or minor releases.

If you pin the dependency at `1.0.x`, you are guaranteed no breaking config or schema changes until `2.0.0`.
