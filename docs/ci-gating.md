---
title: CI gating
nav_order: 5
---

# Fail your PR when someone adds a bean cycle

WireDoctor's **Architectural Regression Guard** turns your Spring context into an
architectural fitness function: you commit a baseline snapshot of the bean graph,
and CI fails any pull request that introduces a *new* dependency cycle.

No annotation scanning, no ArchUnit rule maintenance — the guard diffs the graph
that Spring **actually resolved** at startup.

---

## How it works

1. **Baseline** — you commit `wiredoctor-baseline.json` (a snapshot of beans,
   edges, and cycles) to your repo, like a lockfile for your architecture.
2. **Diff** — on every startup with `wiredoctor.baseline` set, WireDoctor diffs
   the live graph against the baseline and writes `wiredoctor-diff.json` plus a
   machine-readable verdict file `wiredoctor-gate.status` (since 0.4.0).
3. **Gate** — with `wiredoctor.fail-on=new-cycle`, a cycle that is not in the
   baseline throws `WireDoctorRegressionException` *after* analysis completes
   (the diff file is always written first), so the JVM exits non-zero and the
   CI job goes red.

Everything is opt-in and degrades gracefully: no baseline configured → no diff;
baseline file missing → info log, no gate, no error. WireDoctor never fails your
app unless you explicitly asked it to via `fail-on`.

> Cycle identity is the **exact bean set**. If an existing cycle grows by one
> bean, that counts as a *new* cycle — growing a tangle is a regression too.

---

## Step 1 — create and commit the baseline

**Record the baseline the same way CI will run the app.** The graph WireDoctor sees
depends on what is on the classpath, so a baseline captured under one launch method
and gated under another produces a diff full of differences you did not make.

CI runs the packaged jar (Step 3), so build and record from the jar:

```bash
./mvnw -DskipTests package
java -jar target/*.jar \
  --wiredoctor.baseline=wiredoctor-baseline.json \
  --wiredoctor.baseline-write=true
```

{: .warning }
> Do not record the baseline with `./mvnw spring-boot:run` if CI gates on the jar.
> `spring-boot:run` keeps `spring-boot-devtools` on the classpath while
> `spring-boot-maven-plugin` excludes it from the repackaged jar. On
> spring-petclinic that single difference shows up as **12 removed beans**
> (`classPathFileSystemWatcher`, `LocalDevToolsAutoConfiguration`,
> `DevToolsDataSourceAutoConfiguration`, …) and a **31% startup-time delta** —
> enough to trip `startup-time` on a build where nobody changed a line of code.
>
> Same rule for anything else that moves the graph: keep the active profiles and
> `spring.main.web-application-type` identical between the baseline run and the
> gate run.

Then commit the file:

```bash
git add wiredoctor-baseline.json
git commit -m "chore: commit WireDoctor architecture baseline"
```

`baseline-write` mode never diffs or gates — it just accepts the current
architecture as the new truth. Re-run it whenever you *intentionally* change
the architecture and want the baseline to move.

## Step 2 — configure the gate for CI

Add a CI-only profile (e.g. `application-archcheck.properties`):

```properties
wiredoctor.baseline=wiredoctor-baseline.json
wiredoctor.fail-on=new-cycle
# keep report noise out of CI logs if you like:
# wiredoctor.output-path=target
```

## Step 3 — the GitHub Actions job

The gate trips at `ApplicationReadyEvent`, so any way of fully starting the
context works. The simplest is to boot the packaged jar and let the exit code
speak:

{: .warning }
> **Do not gate through `./mvnw spring-boot:run`.** With devtools on the classpath
> it launches `main` on its own restart thread; `WireDoctorRegressionException` is
> logged, that thread dies, and the Maven build still reports `BUILD SUCCESS` and
> exits **0**. The gate fires and CI goes green anyway:
>
> ```
> [WireDoctor] REGRESSION GATE TRIPPED (wiredoctor.fail-on=new-cycle):
>              2 new cycle(s) introduced vs baseline.
> ...
> [INFO] BUILD SUCCESS
> ```
>
> Booting the jar gives the exit code 1 that CI needs. If you must use a Maven
> goal, gate on the verdict file instead — it is written before the exception:
>
> ```bash
> grep -q '^FAIL' target/wiredoctor-gate.status && exit 1
> ```

```yaml
name: Architecture Gate

on:
  pull_request:
    branches: [ "main" ]

jobs:
  bean-cycle-gate:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v7

      - uses: actions/setup-java@v5
        with:
          java-version: '21'
          distribution: 'temurin'
          cache: maven

      - name: Build
        run: ./mvnw -B -DskipTests package

      - name: Fail on new bean cycle
        run: >
          java -jar target/*.jar
          --spring.profiles.active=archcheck
          --spring.main.web-application-type=none

      - name: Upload architecture diff
        if: failure()
        uses: actions/upload-artifact@v4
        with:
          name: wiredoctor-diff
          path: wiredoctor-diff.json
```

`--spring.main.web-application-type=none` makes the app exit right after
startup instead of serving forever — the context still fully starts, so the
guard still runs. If your app needs a web context to boot, run the gate from a
`@SpringBootTest` smoke test instead; the exception fails the test the same way.

## What a red PR looks like

```
[WireDoctor] Baseline Diff (vs wiredoctor-baseline.json):
  - Beans: +3 -0 | Edges: +5 -1 | New cycles: 1 | Resolved cycles: 0
  - NEW CYCLE: [orderService, paymentService]
[WireDoctor] Saved baseline diff to: /workspace/wiredoctor-diff.json
[WireDoctor] REGRESSION GATE TRIPPED (wiredoctor.fail-on=new-cycle): 1 new cycle(s) introduced vs baseline. Failing the application as configured.
...
com.wiredoctor.WireDoctorRegressionException
```

The uploaded `wiredoctor-diff.json` artifact shows exactly which beans and
edges changed, so the author can see the cycle without reproducing locally.

## Updating the baseline on purpose

When an architectural change is intentional, regenerate the baseline (Step 1)
in the same PR and commit it. Reviewers then see the baseline diff — the
architecture change becomes an explicit, reviewable artifact instead of silent
drift.

---

## Gating without exit codes: `wiredoctor-gate.status` (since 0.4.0)

Some build setups can't (or don't want to) rely on the JVM exit code — e.g. the
app is booted by a wrapper script, runs as a `@SpringBootTest`, or the team
wants a *soft* gate that reports without failing the build. For these,
WireDoctor writes a machine-readable verdict file next to the reports on
**every completed diff**, whether or not `fail-on` is configured:

```
FAIL:new-cycle
baseline=wiredoctor-baseline.json
newCycles=1
resolvedCycles=0
addedBeans=3
removedBeans=0
gateArmed=false
```

**The contract is line 1 only:** `PASS`, or `FAIL:<gate>` (comma-separated if
multiple gates ever trip). The remaining `key=value` lines are informational
and may grow in future versions — don't parse positionally.

Semantics worth relying on:

- **Absence means "no verdict."** The file is deleted at the start of every
  guarded run and only written after a diff completes — a stale verdict from a
  previous run can never leak into a run that crashed or skipped the diff
  (missing/corrupt baseline, `baseline-write` mode).
- **Written before the hard gate throws**, so CI can read it even after the
  JVM died red.
- The verdict is independent of `fail-on`: `FAIL:new-cycle` with
  `gateArmed=false` means "a new cycle exists, but you chose not to fail the
  app over it."

A Maven/Gradle-friendly gate step becomes a one-liner:

```bash
# hard gate, no log parsing:
grep -q '^PASS' wiredoctor-gate.status || { cat wiredoctor-diff.json; exit 1; }
```

Or as a soft gate in GitHub Actions — warn on the PR without failing it:

```yaml
      - name: Architecture check (soft)
        run: |
          if ! grep -q '^PASS' wiredoctor-gate.status; then
            echo "::warning::WireDoctor: $(head -1 wiredoctor-gate.status) — see wiredoctor-diff.json"
          fi
```

> **Keep configuration consistent between baseline-write and diff runs.** The
> diff compares the *live* graph against the snapshot, so any config that
> changes which beans exist will show up as added/removed beans. A common
> example: writing the baseline with `management.endpoints.web.exposure.include=wiredoctor`
> set but diffing without it reports `removedBeans:[wireDoctorEndpoint]` —
> technically correct, practically noise. Write and diff under the same profile
> and exposure settings.

---

### Available gates

| `wiredoctor.fail-on` | Trips when… | Since |
|----------------------|-------------|-------|
| `new-cycle`          | a cycle appears that is not in the baseline (exact bean-set identity) | 0.2.0 |
| `condition-changed`  | an autoconfiguration condition outcome flips vs the baseline (e.g. `matched → notMatched`) — the Upgrade Guard, see [upgrade-guard.md](upgrade-guard.html) | 0.5.0 |
| `startup-time`       | startup time regresses beyond **both** absolute + relative thresholds vs baseline — see [performance-gates.md](performance-gates.html) | 0.7.0 |
| `slow-bean`          | a bean crosses `slow-bean-threshold-ms` that was **not** slow in the baseline — see [performance-gates.md](performance-gates.html) | 0.7.0 |

Gates combine via a comma-separated list — `wiredoctor.fail-on=new-cycle,condition-changed,startup-time,slow-bean`
arms all four, and the `FAIL:` line lists every gate that fired (e.g.
`FAIL:new-cycle,startup-time`). The `condition-changed` gate never trips against a
pre-0.5.0 baseline that has no condition data — the marker shows `conditionDiff=skipped`
and the verdict stays `PASS`. Similarly, `startup-time` and `slow-bean` gates gracefully skip
when the baseline has no timing data (e.g., Boot < 2.6 where `ApplicationReadyEvent.getTimeTaken()`
is unavailable). The property stays forward-compatible as new gates are added.
