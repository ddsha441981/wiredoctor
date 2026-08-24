---
title: Why WireDoctor
nav_order: 2
---

# Why WireDoctor?

Five real problems. One dependency.

| Pain | WireDoctor answer |
|------|-------------------|
| "Something changed between Boot 3.x and 4.x — our app is slower, what happened?" | **Upgrade Guardian** diffs autoconfiguration conditions across upgrades |
| "We have a bean cycle in CI — which PR introduced it?" | **Regression Guard** fails the PR the moment a new cycle appears |
| "Startup took 40 s in staging — which bean is the culprit?" | **Critical path** + **timing heat** in the Graph tab |
| "We suspect dead beans eating memory — how do we find them?" | **Ghost Detector** — passive candidates + opt-in first-touch tracking |
| "Our K8s pods are expensive because of slow cold-start — what do we cut?" | **Cost Guardian** gates on startup-time and slow-bean regressions |

---

## Add one dependency — get all of this

```xml
<dependency>
  <groupId>io.github.ddsha441981</groupId>
  <artifactId>wiredoctor-autoconfigure</artifactId>
  <version>1.1.4</version>
</dependency>
```

WireDoctor hooks into the real, resolved `ApplicationContext` at startup and writes:

- **`wiredoctor-report.html`** — interactive, self-contained, opens in any browser
- **`wiredoctor-report.json`** — the single source of truth for tooling
- **`/actuator/wiredoctor/*`** — live views (opt-in via `wiredoctor-actuator`)

Zero dashboard server. Zero configuration required. Zero intrusion — `@Lazy` and prototype beans are never instantiated by the analysis.

---

## How it fits in your workflow

**Local dev** — run once, open the HTML, answer "why is this slow?" in 30 seconds.

**Code review** — the reviewer opens the report diff and sees the exact cycle that was introduced.

**CI** — commit `wiredoctor-baseline.json`, arm the gates, and every PR that regresses startup time or adds a cycle fails automatically.

**Spring Boot upgrades** — bump the version, run once, and the condition diff tells you exactly which autoconfigurations stopped matching — before you debug a missing bean in production.

---

## The honesty contract

WireDoctor prefers honest heuristics over false certainty:

- Ghost beans are labeled `confidence: LOW` — the tool says what it *doesn't* know.
- Cycles are structural smells, not crash predictions — Spring may resolve them via proxies.
- No AOT / GraalVM native image support — the analysis skips gracefully with a single WARN.
- Reports are build artifacts, not source — add them to `.gitignore`.

See [Known Limitations](known-limitations.html) for the full list.
