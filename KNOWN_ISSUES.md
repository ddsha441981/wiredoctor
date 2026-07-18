# Known Issues

## v0.3.0 — Smell Metrics Dominated by Framework Beans

**Impact:** Medium / Feature-value  
**Affected Feature:** Architecture Smell Metrics (`smells` report section)  
**Status:** ✅ Fixed in v0.4.0 (user-bean filtering, default on)

### Description

On a real application (Spring Initializr, 389 beans, Boot 4.x), the smell
rankings are dominated by framework infrastructure, drowning out user beans:

- `highFanIn` top 10: 8–9 of 10 are framework beans
  (`WebMvcAutoConfiguration$EnableWebMvcConfiguration` fan-in 26,
  `AnnotationConfigServletWebServerApplicationContext@459f703f` fan-in 21,
  `environment`, `mvcConversionService`, ...). Only 1 user bean
  (`initializrMetadataProvider`, fan-in 6) surfaces.
- `unstable` list: 10 of 10 are framework beans (endpoint handler mappings,
  `tomcatWebServerFactoryCustomizer`, `healthEndpoint`, ...) — all I=1.0.
  Zero user beans; the list is pure noise for the tool's audience.
- The v0.2.0 edge-noise bug (see below) leaks into the hotspot list: the
  `...ApplicationContext@459f703f` entry embeds a JVM identity hash and will
  "move" every boot.

### Why it happens

The metrics are honest — framework beans genuinely ARE the coupling hotspots
of any Boot app. But users can't refactor framework wiring, so ranking it
first buries the actionable signal.

### Fix (shipped v0.4.0)

The framework classification (`frameworkPkgs`, now `WireDoctorBeanClassifier`)
is reused to narrow the smell rankings to user beans by default:
- `highFanIn` / `highFanOut` / `unstable` list user-defined beans only.
- New `wiredoctor.include-framework-smells` property (default `false`) restores
  the full framework-included rankings.
- `smells.frameworkFiltered` records whether filtering was applied, so a
  consumer can distinguish "no smells" from "framework filtered out".
- The full `smells.fanIn` map stays unfiltered — HTML node sizing keeps every
  node's true coupling weight.

The console summary prints the (filtered) rankings, so it shows user-bean
smells only by default. The synthetic `...ApplicationContext@hash` entries are
classified framework and no longer leak into the rankings — resolving the
identity-hash churn noted above.

### Discovered

Real-world testing on Spring Initializr (389 beans) with v0.3.0 — 2026-07-18.
Confirmed working in the same run: truncation (`max-graph-nodes=100` → top 100
of 389 by fan-in, banner + honest metadata), empty `lazySuggestions` on a
cycle-free app (graceful), and the v0.4.0 `wiredoctor-gate.status` marker
(`PASS`, correct counts).

---

## v0.2.0 — Edge Reference Noise in Baseline Diff

**Impact:** Minor / Cosmetic  
**Affected Feature:** Regression Guard baseline diff (`wiredoctor-diff.json`)  
**Status:** Tracked for future fix

### Description

When comparing against a baseline, the diff report includes spurious edge additions/removals caused by JVM object reference changes in ApplicationContext and BeanFactory instances.

**Example:**
```json
"addedEdges": [
  "restClientSsl -> org.springframework.context.annotation.AnnotationConfigApplicationContext@298f0a0b"
],
"removedEdges": [
  "restClientSsl -> org.springframework.context.annotation.AnnotationConfigApplicationContext@45cec376"
]
```

The object reference (`@298f0a0b` vs `@45cec376`) changes every application boot, causing these edges to appear as "changed" even though the dependency structure is identical.

### Workaround

**Core regression signals are unaffected:**
- ✅ `addedBeansCount` / `removedBeansCount` — accurate
- ✅ `newCyclesCount` / `resolvedCyclesCount` — accurate
- ⚠️ `addedEdgesCount` / `removedEdgesCount` — includes noise

**When using the CI gate:**
- `fail-on: new-cycle` works correctly (ignores edge noise)
- `fail-on: new-bean` would also work correctly (not implemented yet)

**Manual review:**
Filter out edges containing `@<hex>` patterns when reviewing diff files.

### Root Cause

Bean names include `toString()` representations for framework singleton beans (ApplicationContext, BeanFactory), which embed JVM object identity hashes. These hashes are non-deterministic across boots.

### Potential Fix (v0.2.1 or later)

Normalize edge labels before diff:
- Strip `@<hex>` suffixes from bean names in both baseline and current snapshots
- OR: Use canonical bean names (via `BeanDefinition.getBeanClassName()`) instead of `toString()` for framework beans

### Discovered

Real-world testing on Spring Initializr (273 beans, Boot 4.0.7) — 2026-07-18

### Priority

**Low.** Does not block CI gating use case; purely a diff-report quality issue. Will prioritize based on user feedback after v0.2.0 adoption.
