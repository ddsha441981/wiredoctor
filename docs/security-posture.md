# Security posture

WireDoctor is a startup-time diagnostic tool. It reads your application's
resolved bean graph and writes reports about it. This page documents exactly
what those reports contain, how to handle them, and WireDoctor's network
behavior — so you can make an informed decision about running it in each
environment.

---

## What the reports expose

At `ApplicationReadyEvent`, WireDoctor writes `wiredoctor-report.json` and
`wiredoctor-report.html` (and, when a baseline is configured,
`wiredoctor-diff.json` and `wiredoctor-gate.status`). These contain a
structural map of your application's internals:

- **Bean names** — including your own `@Component` / `@Bean` names, which often
  mirror class and package names (e.g. `paymentGatewayClient`,
  `internalFraudScoringService`). Taken together these approximate your
  **internal architecture**.
- **Dependency edges** — which bean depends on which, i.e. how your components
  are wired.
- **Cycles, fan-in/fan-out hotspots, instability metrics** — where your
  coupling concentrates.
- **Active profiles** (`activeProfiles`) — e.g. `prod`, `db`, `eu-west`.
- **Startup timings** — per-bean instantiation durations and the critical path.
- **Autoconfiguration outcomes** (`conditions`, since v0.5.0) — which Spring Boot
  autoconfigurations applied, were excluded, or did not match, plus the condition
  message for non-matches. This reveals your autoconfig surface (which starters and
  optional features are active) — the same class of internal-architecture information
  as bean names, and it lives in the report and baseline files. The same
  recommendations below apply: gitignore the reports and baseline, and treat them as
  internal build artifacts.

None of this is a secret in the cryptographic sense — there are no credentials,
tokens, connection strings, or user data in the report. WireDoctor reads bean
*metadata* (names, types, dependency edges, timings), never bean *state* or
configuration values. But the aggregate is a fairly complete blueprint of your
service's internal design, and that is information you may not want to publish.

---

## Recommendations

### 1. Keep reports out of version control

The report files are build artifacts, not source. Add them to `.gitignore`:

```gitignore
wiredoctor-report.json
wiredoctor-report.html
wiredoctor-diff.json
wiredoctor-gate.status
```

The one deliberate exception is the **baseline** file
(`wiredoctor-baseline.json` or a per-profile `wiredoctor-baseline-<profile>.json`),
which is *meant* to be committed — it is the reference the regression guard
diffs against, like a lockfile for your architecture. Committing it publishes
the same bean-graph blueprint into your repo, so treat a private repo as the
assumption. If your repo is public and you are not comfortable exposing the
graph, do not commit the baseline (you lose the regression guard, not the rest
of the tool).

### 2. Disable WireDoctor in production

WireDoctor is a development and CI aid. In production it adds startup work and
writes a blueprint of your service to the working directory for no runtime
benefit. Disable it:

```properties
wiredoctor.enabled=false
```

With `enabled=false`, WireDoctor performs **no analysis, writes no reports, and
prints no console output** — the auto-configuration backs off entirely. A
common pattern is to enable it only outside production, e.g. in
`application-prod.properties`:

```properties
# application-prod.properties
wiredoctor.enabled=false
```

or leave it on by default and disable per environment via
`WIREDOCTOR_ENABLED=false`.

### 3. Guard the Actuator endpoint

The optional `wiredoctor-actuator` module exposes the report over
`/actuator/wiredoctor`. Anyone who can reach that endpoint can read the same
architecture blueprint over HTTP.

- The endpoint is **not exposed by default** — it only becomes available when
  you explicitly opt in via Spring Boot's standard exposure setting, e.g.
  `management.endpoints.web.exposure.include=wiredoctor` (or `*`).
- If you expose it, protect the actuator surface the way you protect every
  other actuator endpoint: bind management to a separate, non-public port
  (`management.server.port`) and/or require authentication on `/actuator/**`.
- Do not expose it on a public interface. Prefer disabling WireDoctor entirely
  in production (recommendation 2) over exposing this endpoint there.

---

## The offline-only promise

**WireDoctor's JVM code performs no network I/O — ever.** There are no HTTP
clients, sockets, URL connections, telemetry, update checks, or "phone home"
calls anywhere in the tool. All analysis, JSON generation, and HTML generation
happen locally from data already in the running context. Your bean graph never
leaves the machine WireDoctor runs on.

This is an auditable claim, not a marketing one — you can verify it yourself:

```bash
# No network client types anywhere in WireDoctor's source:
grep -rniE 'HttpClient|URLConnection|new Socket|RestTemplate|WebClient|okhttp' \
  wiredoctor-autoconfigure/src/main/java wiredoctor-actuator/src/main/java
# → no matches
```

### The one nuance: the generated HTML's CDN fallback

The vis-network graph library is **bundled inside WireDoctor** and inlined
directly into `wiredoctor-report.html`, so the report is self-contained and
renders offline with no external requests. This is the normal path.

The single exception is a *browser-side* fallback: if the bundled library is
somehow missing from the classpath, the generated HTML instead references the
library from a public CDN (`unpkg.com`). To be precise about where that request
would come from:

- It is **not** WireDoctor's JVM making a request — it is a `<script src>` tag
  in the HTML that *your browser* would fetch when you open the report.
- It only appears when the bundled resource could not be read (a warning is
  logged when this happens).
- It affects only rendering of the interactive graph in the HTML view; the
  JSON report and all analysis are entirely local regardless.

If you require a hard guarantee that opening the report triggers zero external
requests even in this edge case, view the `wiredoctor-report.json` directly (it
never references anything external), or confirm the bundled library is present
on the classpath (the normal case, in which the HTML is fully self-contained).
