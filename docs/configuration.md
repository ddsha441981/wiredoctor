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
| `wiredoctor.output-path` | project root | Directory where `wiredoctor-report.json` and `wiredoctor-report.html` are written. |
| `wiredoctor.scan-packages` | *(auto)* | Comma-separated package prefixes to analyze for orphan beans. By default, framework packages (`org.springframework`, `java.`, `org.apache`, …) are filtered out automatically. |
| `wiredoctor.slow-bean-threshold-ms` | `100` | Beans taking longer than this to instantiate are flagged as slow (report + console). |
| `wiredoctor.max-graph-nodes` | `2000` | Above this many beans, the *serialized* graph (JSON + HTML view) is capped to top-N by fan-in (cycle members always kept) so the browser doesn't freeze. Analysis itself — cycles, smells, critical path, baseline diff — always runs on the full graph. `0` = unlimited. |
| `wiredoctor.include-framework-smells` | `false` | Include framework beans in smell rankings. Off by default so every ranked bean is one you can actually refactor. |

```properties
wiredoctor.scan-packages=com.yourcompany.app,io.yourteam.service
wiredoctor.output-path=/path/to/your/reports
wiredoctor.slow-bean-threshold-ms=50
wiredoctor.max-graph-nodes=2000
```

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
