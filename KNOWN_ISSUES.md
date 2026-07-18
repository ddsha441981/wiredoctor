# Known Issues

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
