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
  <version>1.0.0</version>
</dependency>
```

Run your app once — `wiredoctor-report.json` and `wiredoctor-report.html` appear in the project root. Open the HTML file in any browser — no server needed.

---

## Documentation

| Guide | What it covers |
|-------|----------------|
| [Report tour](report-tour.md) | Every tab of the HTML console, explained with real screenshots |
| [CI gating](ci-gating.md) | Fail your PR on a new bean cycle — the full workflow |
| [Performance gates](performance-gates.md) | Startup-time and slow-bean gates, thresholds, noise tolerance |
| [Upgrade Guard](upgrade-guard.md) | Catching silent autoconfiguration changes across Boot upgrades |
| [Ghost Detector](ghost-detector.md) | Passive candidates + opt-in first-touch tracking |
| [Security posture](security-posture.md) | What the reports expose and WireDoctor's offline-only promise |
| [Configuration reference](configuration.md) | Every property, grouped by feature, with defaults |

---

## Supported Versions

| Spring Boot | Java 17 | Java 21 | Java 25 |
|-------------|:-------:|:-------:|:-------:|
| 2.7.x       | ✅      | ✅      | ✅      |
| 3.3.x       | ✅      | ✅      | ✅      |
| 3.5.x       | ✅      | ✅      | ✅      |
| 4.0.x       | ✅      | ✅      | ✅      |

- **WebFlux** (reactive/Netty): verified since v0.8.0.
- **AOT / GraalVM native image**: not supported — analysis skips gracefully with a WARN (no crash, no incomplete report). Set `wiredoctor.enabled=false` in your native profile.

---

## v1.0.0 Stability Contract

From v1.0.0, these are **frozen contracts**:

- **Report JSON schema** — `schemaVersion: 1` is the first field. Breaking changes require a major version bump.
- **Config property names** — `wiredoctor.*` names are stable; properties deprecated for ≥1 minor before removal.
- **Performance budget** — analysis overhead < 5 000 ms on a 1 000-bean context; enforced by CI.
- **Zero-intrusion promise** — `@Lazy`/prototype beans are never instantiated; ghost tracking is opt-in; network I/O is never performed.

---

## License

MIT OR Apache-2.0 — [GitHub](https://github.com/ddsha441981/wiredoctor)
