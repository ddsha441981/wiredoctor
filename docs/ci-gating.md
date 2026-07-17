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
   the live graph against the baseline and writes `wiredoctor-diff.json`.
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

Run your app once in baseline-write mode (locally or in a one-off CI job):

```bash
./mvnw spring-boot:run \
  -Dspring-boot.run.arguments="--wiredoctor.baseline=wiredoctor-baseline.json --wiredoctor.baseline-write=true"
```

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

### Available gates

| `wiredoctor.fail-on` | Trips when… | Since |
|----------------------|-------------|-------|
| `new-cycle`          | a cycle appears that is not in the baseline (exact bean-set identity) | 0.2.0 |

More gates (startup-time regression, god-bean growth) are planned; the property
accepts a comma-separated list so existing configs stay forward-compatible.
