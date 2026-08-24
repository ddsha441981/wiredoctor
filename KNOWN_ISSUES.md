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

## v0.2.0 — Edge Reference Noise in Baseline Diff — FIXED in 1.1.2

**Impact:** Minor / Cosmetic
**Affected Feature:** Regression Guard baseline diff (`wiredoctor-diff.json`)
**Status:** Fixed in 1.1.2

### Description

The diff could report edge additions/removals that nobody caused, from two
sources of names the developer never chose:

1. **Framework object identity hashes.** Beans resolved by type enter the graph
   through `ObjectUtils.identityToString()`, e.g.
   `restClientSsl -> ...AnnotationConfigApplicationContext@298f0a0b`.
2. **Spring Data positional counters.** `jpa.named-queries#0`,
   `jpa.OwnerRepository.fragments#0`, `data-jpa.repository-aot-processor#0` — the
   number is a registration counter, so the same three repositories can land on
   different numbers between runs and every edge on them flips.

### Correction to the original report

The first version of this entry said the identity hash "changes every
application boot". That is wrong, and the wrong version is worse than the bug:
the default identity hashCode comes from a thread-local xorshift generator, so
an identical single-threaded startup order produces **identical** hashes run
after run. Re-running the petclinic gate reproduced `@3568f9d2` and `@13c3c1e1`
byte-for-byte. The churn appears only when allocation order changes — a new
autoconfiguration, a different profile, a JDK or Boot upgrade. Intermittent
noise, which is harder to trust than constant noise.

### Fix

`WireDoctorBaselineDiff.Snapshot.canonical()` masks both patterns on **both**
sides of the comparison — live run and parsed baseline — so a baseline written
before 1.1.2 still diffs cleanly with no regeneration:

- `@<8 hex digits>` → `@<id>`
- `named-queries#N` / `.fragments#N` / `repository-aot-processor#N` → `#<n>`

The counter rule is anchored to the shapes Spring Data emits rather than a blind
`#\d+`, which would also flatten inner-bean and nested-configuration names that
are stable and meaningful.

### Discovered

Real-world testing on Spring Initializr (273 beans, Boot 4.0.7) — 2026-07-18
Re-verified and corrected on spring-petclinic (Boot 4.x) — 2026-08-24
