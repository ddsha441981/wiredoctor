---
title: Module Boundaries
nav_order: 11
---

# Module Boundaries — catch hidden cross-module coupling (v1.2.0)

A modular monolith stays modular only as long as the modules don't reach into
each other's internals. Nothing in Java or Spring stops `orders` from wiring a
bean that lives in `billing`'s internal package — it compiles, it runs, and the
coupling is invisible until the day you try to extract `billing` into its own
service and discover forty edges you didn't know were there.

WireDoctor reads the **resolved** bean graph — what Spring actually wired, not
what the source imports suggest — and flags every edge that crosses from one
declared module into another module's **internal (non-API)** package.

**Opt-in and zero-overhead when off.** With no modules configured the detector
short-circuits and does no work.

---

## What counts as a violation

An edge `sourceBean → targetBean` is a violation when **all** hold:

1. Both beans map to a declared module (framework/unmapped beans are ignored).
2. The two modules are **different**.
3. The target's package matches **none** of your `api-packages` globs.

Same-module edges are the module's own business. Cross-module edges into a
declared API package are the whole point of an API — allowed. Everything else
is coupling you probably didn't mean to have.

A bean maps to the module whose configured prefix is the **longest** match for
its package, so nested modules resolve the way you'd expect:

| Package | `com.acme` → `core`, `com.acme.orders` → `orders` |
|---------|---------------------------------------------------|
| `com.acme.util` | `core` (only `com.acme` matches) |
| `com.acme.orders.internal` | `orders` (longer prefix wins) |
| `com.other` | *unmapped* — ignored |

Prefix matching respects segment boundaries: `com.acme.orders` does **not**
swallow `com.acme.ordersx`.

---

## Configuration

```yaml
wiredoctor:
  module-boundaries:
    modules:
      "[com.acme.orders]": orders
      "[com.acme.billing]": billing
    api-packages:
      - "*.api"        # com.acme.billing.api and its sub-packages are public
```

| Property | Description |
|----------|-------------|
| `wiredoctor.module-boundaries.modules` | Map of package-prefix → module name. Empty = feature off. |
| `wiredoctor.module-boundaries.api-packages` | Glob patterns for public surfaces. `*` matches any characters; a matched package's sub-packages are public too. |

### Map keys with dots need brackets

`modules` keys are package names, and Spring's relaxed binding reads a dot as a
nesting separator. An unquoted `com.acme.orders:` binds as `com` → `acme` →
`orders` nested objects — not the string key you meant — and the module
**silently never matches**. Wrap the key in `[...]`:

```yaml
# ✅ correct
wiredoctor.module-boundaries.modules:
  "[com.acme.orders]": orders

# ❌ wrong — module never resolves
wiredoctor.module-boundaries.modules:
  com.acme.orders: orders
```

In `.properties` (and `--args`/`SpringApplicationBuilder`), use index-style
brackets with no quotes:

```properties
wiredoctor.module-boundaries.modules[com.acme.orders]=orders
wiredoctor.module-boundaries.api-packages[0]=*.api
```

---

## Where results show up

- **Console**, at startup — a one-line summary per violation.
- **`wiredoctor-report.json`** — a `boundaryViolations` array, each entry
  `{sourceBean, targetBean, sourceModule, targetModule, targetPackage}`. Absent
  when the feature is off (additive; `schemaVersion` stays `1`).
- **HTML report → Boundaries tab** — one row per violating edge, bean names
  click through to the graph. The tab appears only when the feature ran; an
  empty-but-present result renders a "no violations" confirmation.

---

## Fixing a violation

Two honest options, no third:

1. **Expose an API.** If the dependency is legitimate, move the target type into
   the owning module's public package and add that package to `api-packages`.
   The edge is now a declared, intentional contract.
2. **Decouple.** If it isn't legitimate, invert it — an interface owned by the
   consumer, an event, or a move of the shared type to a common module.

Marking the edge as API just to silence the report is the one move that defeats
the purpose: it makes the coupling *look* intentional without making it so.

---

## Limitations

- Detection is over the **resolved singleton graph**. Reflective or
  programmatic lookups (`getBean(...)`) that Spring didn't record as a
  dependency are invisible here — the same honesty ceiling as the rest of
  WireDoctor's graph analysis.
- A bean whose type can't be resolved to a package can't be attributed to a
  module, so it's skipped rather than guessed at.
- Modules are declared by **package prefix**. If your module layout isn't
  reflected in package names, WireDoctor can't infer it.
