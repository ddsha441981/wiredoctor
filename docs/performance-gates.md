# Performance Gates

**Available since:** v0.7.0

Beyond bean cycles and condition changes, WireDoctor can now gate your CI pipeline on **startup time regressions** and **new slow beans**. This is the "Cost Guardian" layer — catch performance degradation before it ships.

---

## Quick Start

1. **Capture a baseline** (one-time):
   ```properties
   wiredoctor.baseline=wiredoctor-baseline.json
   wiredoctor.baseline-write=true
   wiredoctor.slow-bean-threshold-ms=100
   ```
   Run your app once, commit `wiredoctor-baseline.json`.

2. **Enable gates** (in CI):
   ```properties
   wiredoctor.baseline=wiredoctor-baseline.json
   wiredoctor.baseline-write=false
   wiredoctor.fail-on=startup-time,slow-bean

   # Startup time gate thresholds (both must be exceeded to trip)
   wiredoctor.startup-time-absolute-threshold=500
   wiredoctor.startup-time-relative-threshold=0.10

   # Slow bean gate uses your slow-bean-threshold-ms
   wiredoctor.slow-bean-threshold-ms=100
   ```

3. **Read the verdict in CI**:
   ```bash
   # WireDoctor writes wiredoctor-gate.status on every run
   cat wiredoctor-gate.status
   # PASS or FAIL
   ```

---

## How It Works

### Startup Time Gate

**Problem:** A dependency upgrade or refactor silently adds 2 seconds to your cold-start time. In a Kubernetes environment with frequent pod churn, that's money — delayed readiness = over-provisioned replicas.

**Solution:** The `startup-time` gate compares your current `totalStartupMs` against the baseline and trips when **both thresholds** are exceeded:

- **Absolute threshold** (`startup-time-absolute-threshold`, default 500ms): The raw millisecond regression.
- **Relative threshold** (`startup-time-relative-threshold`, default 0.20 = 20%): The percentage regression.

**Example:**
```
Baseline: 4000ms
Current:  4600ms
Regression: +600ms (+15%)

With defaults (500ms, 10%):
  - Absolute: 600ms > 500ms ✅
  - Relative: 15% > 10% ✅
  - Gate: FAIL
```

**Noise tolerance:** Startup timings are inherently noisy (JIT warm-up, CPU contention, disk I/O). By requiring **both** thresholds, the gate won't trip on small absolute jitter (+50ms on a 5s baseline) or small relative changes (-2% on a fast 500ms baseline).

### Slow Bean Gate

**Problem:** A developer adds a `@PostConstruct` that does I/O, or instantiates a bean with a synchronous HTTP call in its constructor. It works, but it's slow — and you only notice when startup time creeps up.

**Solution:** The `slow-bean` gate trips when a bean crosses your `slow-bean-threshold-ms` in the current run but was **not** slow in the baseline. It's not about absolute time — it's about **new** bottlenecks.

**Example:**
```
Baseline slow beans: [dataSource (150ms)]
Current slow beans:  [dataSource (152ms), orderService (142ms)]

Gate trips with:
  WireDoctorRegressionException: 1 bean(s) crossed the slow threshold (100ms) 
  vs baseline: [orderService (142ms)]
```

**Why it matters:** A bean that's always been slow is tracked, but the gate only trips when a **new** one appears — that's the signal for "something changed."

**Jitter margin (v0.8.0):** JVM timing is noisy — a bean that measured 98ms yesterday can measure 101ms today with zero code change. If your threshold is 100ms, that 1ms of jitter would fail CI. So the gate only trips when a new bean exceeds `slow-bean-threshold-ms + slow-bean-margin-ms` (default margin: 20ms). Beans inside the margin band still appear in the report's slow-bean list — they just don't fail the build. This mirrors the dual-threshold noise tolerance of the startup-time gate. Set `wiredoctor.slow-bean-margin-ms=0` for exact pre-v0.8.0 behavior.

```
threshold=100ms, margin=20ms:
  newBean at 101ms  → reported as slow, gate does NOT trip (jitter band)
  newBean at 120ms  → reported as slow, gate does NOT trip (band edge)
  newBean at 121ms+ → gate TRIPS (past the band — real signal)
```

This default was chosen from real-world validation: running against start.spring.io, `requestMappingHandlerAdapter` measured 101ms against a 100ms threshold and tripped the gate — pure measurement noise, not a regression.

---

## Configuration Reference

| Property | Default | Description |
|----------|---------|-------------|
| `wiredoctor.fail-on` | `""` | Comma-separated gate names. Add `startup-time` and/or `slow-bean`. Combinable with `new-cycle`, `condition-changed`. |
| `wiredoctor.startup-time-absolute-threshold` | `500` | Milliseconds. Startup must regress by **more than this** to trip (AND condition). |
| `wiredoctor.startup-time-relative-threshold` | `0.20` | Fraction (0.20 = 20%). Startup must regress by **more than this percentage** to trip (AND condition). |
| `wiredoctor.slow-bean-threshold-ms` | `100` | Milliseconds. Beans taking longer than this are logged and included in baseline. The `slow-bean` gate trips on **new entries** crossing this threshold. |
| `wiredoctor.slow-bean-margin-ms` | `20` | Milliseconds. Jitter margin for the `slow-bean` gate: a new bean must exceed `threshold + margin` to trip. `0` = exact pre-v0.8.0 behavior. |

---

## CI Integration

### GitHub Actions

```yaml
- name: Run tests with baseline check
  run: mvn verify -Dwiredoctor.baseline-write=false

- name: Check gate status
  run: |
    if grep -q FAIL target/wiredoctor-gate.status; then
      echo "::error::WireDoctor performance gate tripped"
      exit 1
    fi
```

### GitLab CI

```yaml
test:
  script:
    - mvn verify -Dwiredoctor.baseline-write=false
    - |
      if grep -q FAIL target/wiredoctor-gate.status; then
        echo "WireDoctor performance gate tripped"
        exit 1
      fi
```

### Jenkins

```groovy
stage('Test') {
  steps {
    sh 'mvn verify -Dwiredoctor.baseline-write=false'
    script {
      def status = readFile('target/wiredoctor-gate.status').trim()
      if (status == 'FAIL') {
        error 'WireDoctor performance gate tripped'
      }
    }
  }
}
```

---

## Multi-Profile Baselines

Performance gates work seamlessly with multi-profile baselines (introduced in v0.4.0). Use the `{profiles}` token:

```properties
wiredoctor.baseline=wiredoctor-baseline-{profiles}.json
```

This resolves to:
- `wiredoctor-baseline-default.json` (no active profiles)
- `wiredoctor-baseline-dev.json` (`--spring.profiles.active=dev`)
- `wiredoctor-baseline-prod.json` (`--spring.profiles.active=prod`)

Each baseline snapshot includes `totalStartupMs` and `slowBeans` specific to that profile's bean graph.

---

## When to Use

### ✅ Good use cases

- **Pre-production gate**: Catch startup regressions in PR checks before they hit staging.
- **Dependency upgrade validation**: Did that Spring Boot bump or library upgrade add cold-start latency?
- **Kubernetes cost control**: Slow readiness = longer HPA scale-up = over-provisioned pods = higher cloud bills.
- **Periodic audits**: Run with gates enabled in a nightly build to surface gradual creep.

### ❌ When NOT to use

- **Flaky CI runners**: If your CI environment has high timing variance (shared VMs, noisy neighbors), tune thresholds higher or disable the gates.
- **Fast iteration loops**: Leave gates **off** in local dev — enable them in CI only.
- **Micro-optimizations**: A 5ms regression isn't worth blocking a PR. Set thresholds proportional to your actual baseline (e.g., 10% of a 3s baseline = 300ms, not 50ms).

---

## Troubleshooting

### Gate trips unexpectedly

**Symptom:** `startup-time` gate trips, but the regression seems negligible.

**Check:**
1. What are your thresholds? Defaults are 500ms + 10% — conservative for most apps.
2. Is the baseline stale? Re-capture it on the current codebase.
3. Is the CI environment noisy? Compare several runs to see variance.

**Fix:** Tune thresholds higher, or disable the gate if timing variance is too high.

### Gate never trips

**Symptom:** You added a slow bean, but the gate didn't fail.

**Check:**
1. Is `wiredoctor.baseline-write=false` in CI? Write mode bypasses gates (by design).
2. Is the bean's instantiation time below your `slow-bean-threshold-ms`?
3. Was the bean already slow in the baseline? The gate only trips on **new** slow beans.

**Fix:** Lower your threshold, or re-capture a baseline without the slow bean.

### `totalStartupMs` is null in baseline

**Symptom:** Baseline has `"totalStartupMs": null`.

**Cause:** Spring Boot < 2.6, or `ApplicationReadyEvent.getTimeTaken()` returned null.

**Fix:** Upgrade to Boot 2.6+ (introduced timing in `ApplicationReadyEvent`), or accept that timing gates won't work on older Boot versions.

---

## Examples

### Example 1: Strict gate for microservices

```properties
# Target: 2s cold start, tolerate 200ms jitter
wiredoctor.startup-time-absolute-threshold=200
wiredoctor.startup-time-relative-threshold=0.05
wiredoctor.slow-bean-threshold-ms=50
wiredoctor.fail-on=startup-time,slow-bean
```

### Example 2: Relaxed gate for monoliths

```properties
# Target: 10s cold start, tolerate 1s jitter
wiredoctor.startup-time-absolute-threshold=1000
wiredoctor.startup-time-relative-threshold=0.15
wiredoctor.slow-bean-threshold-ms=200
wiredoctor.fail-on=startup-time,slow-bean
```

### Example 3: Slow-bean gate only

```properties
# Don't gate on total time, only on new bottlenecks
wiredoctor.fail-on=slow-bean
wiredoctor.slow-bean-threshold-ms=100
```

---

## See Also

- [CI Gating Guide](ci-gating.md) — Overview of all gates (`new-cycle`, `condition-changed`, `startup-time`, `slow-bean`)
- [Upgrade Guard](upgrade-guard.md) — Gate on condition evaluation changes
