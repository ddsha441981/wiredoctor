# Upgrade Guard — catch what a Spring Boot upgrade silently changed

You bumped Spring Boot. The build is green, the app starts. Three weeks later a feature
is quietly broken because an autoconfiguration stopped applying and 16 beans disappeared —
and nothing told you.

This is the single most common upgrade pain: **behavior changes that don't crash anything.**
Spring Boot 4's autoconfiguration modularization made it worse — having a library on the
classpath is no longer enough for its configuration to apply, so beans that used to exist
silently don't.

WireDoctor's Upgrade Guard turns "diff the `--debug` condition report by hand" into an
automatic, CI-gateable check.

## The idea: cause and effect, together

WireDoctor already diffs your **bean graph** across builds (the *effect*: which beans
appeared or vanished). Since v0.5.0 it also diffs the **autoconfiguration condition
report** (the *cause*: which `@ConditionalOnClass` / `@ConditionalOnBean` outcomes
flipped). Together they answer both halves of the question:

- *What changed?* — 16 beans are gone.
- *Why?* — `JacksonAutoConfiguration` went `matched → excluded`, which cascaded into
  `JacksonJsonHttpMessageConverterConfiguration` going `matched → notMatched` because its
  `@ConditionalOnBean(JsonMapper)` no longer found a bean.

No other tool connects those two. ArchUnit and Spring Modulith analyze bytecode statically —
they never see what Spring actually wired. Startup profilers measure time, not conditions.

## Workflow

### 1. Write a baseline on the OLD version (before the upgrade)

```properties
wiredoctor.baseline=wiredoctor-baseline-{profiles}.json
wiredoctor.baseline-write=true
```

Run the app once. WireDoctor writes the baseline including a `conditions` section — one
stable outcome per autoconfiguration class: `matched`, `notMatched` (with the condition
message as `reason`), `excluded`, or `unconditional`. Commit that file.

### 2. Upgrade Spring Boot, then diff on the NEW version

```properties
wiredoctor.baseline=wiredoctor-baseline-{profiles}.json
# (baseline-write removed — now it diffs)
```

WireDoctor writes `wiredoctor-diff.json` with a `conditionDiff` section and logs a summary:

```
[WireDoctor] Baseline Diff (vs wiredoctor-baseline-default.json):
  - Beans: +0 -16 | Edges: +0 -22 | New cycles: 0 | Resolved cycles: 0
  - Conditions: 2 changed | 0 added | 7 removed
  - CONDITION CHANGED: JacksonAutoConfiguration (matched -> excluded)
  - CONDITION CHANGED: JacksonJsonHttpMessageConverterConfiguration$... (matched -> notMatched)
```

### 3. Gate it in CI

```properties
wiredoctor.fail-on=condition-changed
# combine with the cycle gate if you like:
# wiredoctor.fail-on=new-cycle,condition-changed
```

When a condition outcome flips, the app fails at startup (in your CI run) with a clear
message, and `wiredoctor-gate.status` records the verdict for log-free gating:

```
FAIL:condition-changed
baseline=wiredoctor-baseline-default.json
conditionsChanged=2
gateArmed=true
```

## Reading the condition diff

| Field | Meaning | This is the signal when… |
|---|---|---|
| `changed` | An autoconfig's outcome flipped (e.g. `matched → notMatched`) | **This is the headline.** A behavior you relied on turned on or off. |
| `added` | An autoconfig class is evaluated now but wasn't in the baseline | The Boot upgrade changed the candidate list — expected churn. |
| `removed` | An autoconfig class in the baseline is no longer evaluated | A module moved or was removed — often paired with vanished beans. |

Each `changed` entry with a `notMatched` outcome carries a `newReason` — the actual
condition message, e.g.:

> `@ConditionalOnBean (types: tools.jackson.databind.json.JsonMapper; SearchStrategy: all)
> did not find any beans of type ...JsonMapper`

That message is usually the fastest path to *why* — it names the exact condition that
stopped matching.

## Backward compatibility

A baseline written before v0.5.0 has no `conditions` section. WireDoctor detects this and
**skips the condition diff** rather than reporting every current autoconfig as "added" —
`wiredoctor-gate.status` shows `conditionDiff=skipped` and the verdict stays `PASS`. The
`condition-changed` gate never trips on an old baseline. To enable the condition diff,
re-run once with `baseline-write=true` on your current version to refresh the baseline.

## What it costs you: nothing at runtime

The condition data comes from the `ConditionEvaluationReport` Spring Boot already keeps in
memory (the same object `--debug` renders). WireDoctor reads it — no agent, no extra
startup work, no `getBean()` calls, no network. Validated on Spring PetClinic (Boot 4.1):
299 conditions captured, app behavior identical with WireDoctor present.

## Keep configuration consistent between baseline-write and diff runs

Same caveat as any baseline diff: config that changes which autoconfigs apply (profiles,
`spring.autoconfigure.exclude`, actuator exposure) will show up as condition changes. Write
and diff under the same profile and settings so the diff reflects the *upgrade*, not a
config difference.
