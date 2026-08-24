---
title: Roadmap
nav_order: 13
---

# Roadmap

---

## Shipped

| Version | Date | What |
|---------|------|------|
| v1.1.3 | 2026-08-24 | **Report readability** — coupling quadrant, Pareto curve, trend verdict bands, drill-downs on every ranking |
| v1.1.2 | 2026-08-24 | Report accuracy — type-collected beans are not ghosts, baseline diff noise masked |
| v1.1.1 | 2026-08-08 | Log format fix |
| v1.1.0 | 2026-08-22 | **Longitudinal Visibility** — startup time trend with sparkline, thread distribution with donut chart, EnvironmentPostProcessor migration, foreign ApplicationStartup respect |
| v1.0.0 | 2026-08 | **Stability Contract** — frozen JSON schema (`schemaVersion: 1`), frozen config property names, performance budget (< 5s on 1k beans), zero-intrusion guarantee |
| v0.10.0 | 2026-07-29 | Graph timing heat + critical path chips |
| v0.9.0 | 2026-07-20 | README restructure (problem-first), docs site |
| v0.8.0 | 2026-07-20 | WebFlux validated, bean-scope tests, ApplicationStartup guard, slow-bean jitter margin |
| v0.7.1 | 2026-07-20 | Gate verdicts in HTML, start.spring.io validated, sample/ |
| v0.7.0 | 2026-07-19 | Performance gates (startup-time + slow-bean dual-threshold) |
| v0.6.1 | 2026-07-19 | HTML tabbed console refresh, template extracted to classpath, ghost late-injection |
| v0.6.0 | 2026-07-19 | Ghost detector (passive candidates + opt-in first-touch tracking) |
| v0.5.0 | 2026-07-18 | Upgrade Guardian (condition diff + gate) |
| v0.4.0 | 2026-07-18 | CI gate marker, smell filtering, actuator, multi-profile baselines |
| v0.3.0 | 2026-07-18 | @Lazy simulator, smells, 5k-bean hardening |
| v0.2.0 | 2026-07-18 | Regression guard + critical path + self-contained HTML + compat matrix |
| v0.1.2 | 2026-07-17 | Crash-safety, no forced instantiation, demo boots |
| v0.1.0 | 2026-07-17 | Initial release — cycle detection, ghost beans, proxy count |

Full changelog: [CHANGELOG.md](https://github.com/ddsha441981/wiredoctor/blob/main/CHANGELOG.md)

---

## v1.2.0 — Architectural Boundaries & Dev Feedback

- **Module boundary violation detection** — `wiredoctor.module-boundaries` config to declare your multi-module architecture; WireDoctor flags cross-boundary dependencies that violate your declared layers.
- **DevTools restart integration** — instant per-restart smell/cycle/slow-bean diff, so you see architectural drift as you develop, not at CI time.

---

## Dropped (deliberately)

- **Memory Footprint Estimation** — honest per-bean heap numbers need a Java Agent; shallow size-of is a correctness trap. The Ghost Detector answers the same underlying question ("which beans are wasting resources?") without lying about bytes.
