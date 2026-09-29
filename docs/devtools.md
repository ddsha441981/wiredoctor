---
title: DevTools Restart Diff
nav_order: 12
---

# DevTools restart diff — architectural drift, while you're still typing

The CI gates catch a regression when you open the PR. This catches it ten
seconds after you hit save.

With `spring-boot-devtools` on the classpath, every source change already
restarts your context. WireDoctor rides that restart: it diffs the graph it just
resolved against the one from the previous run and logs a single line telling you
what moved.

```
[WireDoctor] Restart: +1 slow bean (paymentService 340ms), cycles unchanged
[WireDoctor] Restart: +1 cycle, +3 beans
[WireDoctor] Restart: -1 cycle resolved, startup +180ms
```

No report to open, no browser, no CI run — one line in the console you're already
watching.

---

## What the line reports

It states only what actually changed, with one exception: cycles are **always**
called out, because a freshly introduced cycle is the regression a restart is
most likely to sneak past you.

| Fragment | Meaning |
|----------|---------|
| `+2 slow beans (paymentService 340ms, +1 more)` | beans that crossed the slow threshold this run but weren't slow last run |
| `+1 cycle` / `-1 cycle resolved` | a new dependency cycle appeared, or one you had is now gone |
| `cycles unchanged` | the honest all-clear on the thing that matters most |
| `startup +180ms` | readiness time regressed since the last restart |
| `+3 beans` / `-2 beans` | net change in the wired bean count |

The newly-slow-bean check reuses the same jitter margin as the `slow-bean` CI
gate (`wiredoctor.slow-bean-margin-ms`), so a bean bouncing across the threshold
by a millisecond doesn't nag you on every save.

---

## When it runs (and when it stays quiet)

Nothing to configure. It's on whenever the conditions hold and silent otherwise:

- **DevTools must be on the classpath.** WireDoctor keys off DevTools'
  `RestartClassLoader`. No DevTools → the feature never engages, zero overhead on
  a normal boot.
- **There has to be a previous run to compare against.** The very first start in
  a workspace has nothing to diff, so it says nothing; from the second restart on,
  you get the line.
- **It never throws.** A missing or unreadable previous report is a silent no-op,
  not a stack trace in your dev console.

Because DevTools is a dev-only dependency — Spring's `spring-boot-maven-plugin`
leaves it out of the repackaged jar — this feedback simply doesn't exist in
production. There's no prod switch to remember.

---

## What it doesn't touch

Console only. The restart diff writes **nothing** to `wiredoctor-report.json`,
the HTML report, or `wiredoctor-gate.status`, and it adds no fields —
`schemaVersion` stays `1`. It reuses the existing baseline-diff engine instead of
adding a second one, so what it counts as a "new cycle" or a "slow bean" is
exactly what the [CI regression guard](ci-gating.html) counts. Same definitions,
two speeds: this one for the inner loop, the gate for the pull request.
