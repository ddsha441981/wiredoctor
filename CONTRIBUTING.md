# Contributing to WireDoctor

Thanks for your interest in WireDoctor. It is a startup-time diagnostic tool
for Spring Boot: it reads the *resolved* bean graph and reports cycles, slow
beans, coupling hotspots, and architectural regressions. Contributions of all
sizes are welcome — bug reports, docs, tests, and features.

This guide covers how to build the project, the conventions we follow, and what
we look for in a pull request.

---

## Project layout

WireDoctor is a Maven multi-module build:

| Module | What it is |
|--------|------------|
| `wiredoctor-autoconfigure` | The core diagnostic engine + Spring Boot auto-configuration. This is the published artifact and where most changes land. |
| `wiredoctor-actuator` | Optional module exposing the report over `/actuator/wiredoctor`. Kept separate so the core stays actuator-free. |
| `wiredoctor-test` | A demo Spring Boot app used to exercise WireDoctor end-to-end. Not published (`-P dev` profile only). |

A design principle worth knowing up front: **WireDoctor must never affect the
host application.** Analysis is defensively wrapped so no failure inside the
tool can crash startup, and the core artifact stays dependency-light (no
actuator, no network I/O). Keep changes consistent with that posture.

---

## Prerequisites

- **JDK 17 or 21** (CI builds on both; the library targets Java 17).
- **Maven 3.9+** (the bundled `mvn` is fine).

---

## Building and testing

```bash
# Compile + run the full test suite
mvn test

# Full verify: tests + the JaCoCo 80% line-coverage gate on the core module
mvn verify
```

`mvn verify` is what CI runs. The build goes red on any test failure **or** if
line coverage on `wiredoctor-autoconfigure` drops below 80%, so add tests with
your change.

### Running the demo app

```bash
mvn -pl wiredoctor-test spring-boot:run
```

On startup it prints the WireDoctor console summary and writes
`wiredoctor-report.json` / `wiredoctor-report.html` to the module directory —
handy for eyeballing a change against a real context.

---

## Branch and commit conventions

- Branch off `main`. Name branches by intent, e.g. `feat/actuator-endpoint`,
  `fix/npe-on-empty-graph`, `docs/security-posture`.
- Keep commits focused — one logical change per commit. We prefer conventional
  commit prefixes (`feat:`, `fix:`, `docs:`, `test:`, `chore:`) with a concise,
  imperative subject line.
- Never push directly to `main`; open a pull request.

**Releases and publishing:** WireDoctor is **not yet published to Maven
Central** — that happens at `v1.0.0`. Please don't add publishing steps,
version bumps, or release config in a feature PR; those are handled separately
as part of the release process.

---

## Pull requests

Before opening a PR:

1. `mvn verify` passes locally (tests green, coverage gate holds).
2. New behavior has tests. Bug fixes include a test that fails without the fix.
3. Public API changes are documented (Javadoc + `README` / `docs/` where
   relevant) and added to `CHANGELOG.md` under `[Unreleased]`.
4. The change respects the zero-intrusion posture — no path can crash the host
   app, and the core module gains no new runtime dependencies without
   discussion.

In the PR description, explain **what** changed and **why**, and note anything
you tested manually (e.g. against the demo app or a real application).

Keep the tone of new docs and messages grounded and honest — describe what the
tool actually does and its limitations, not what we wish it did. WireDoctor's
credibility rests on being precise about heuristics vs. facts.

---

## Good first issues

New here? Look for issues labelled
[`good first issue`](https://github.com/ddsha441981/wiredoctor/labels/good%20first%20issue)
— they're scoped to be approachable without deep knowledge of the codebase.
Improving test coverage, tightening docs, and adding edge-case handling to the
pure helper classes (`WireDoctorSmellDetector`, `WireDoctorBaselineResolver`,
`CycleDetector`) are all great starting points.

Have an idea but not sure it fits? Open an issue to discuss it before writing
code — we're happy to help scope it.

---

## Questions

Open a [GitHub issue](https://github.com/ddsha441981/wiredoctor/issues) or start
a discussion. Thanks for helping make WireDoctor better.
