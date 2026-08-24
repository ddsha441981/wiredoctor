---
title: Startup Time Trend
nav_order: 10
---

# Startup Time Trend — track creep over time (v1.1.0, chart rebuilt in v1.1.3)

Every baseline write appends a timestamped snapshot to `trendHistory[]` inside `wiredoctor-baseline.json`. The Timing tab renders it as a chart so you can see startup-time creep before the gate trips — and, since v1.1.3, says whether a slowdown is explained by the app having grown.

---

## How it works

When WireDoctor runs with `wiredoctor.baseline-write=true`, before writing the baseline:

1. Read the existing baseline JSON (if present) and extract `trendHistory[]`
2. Append a new entry: `{timestamp, totalStartupMs, slowBeanCount, beanCount}`
3. Cap at `wiredoctor.trend-history-size` (default: 30 entries, `0` = unlimited)
4. Trim oldest entries if over cap
5. Write the updated baseline

The `totalStartupMs` field is omitted from entries when it was `null` (pre-v0.7.0 baselines where `ApplicationReadyEvent.getTimeTaken()` was unavailable). This lets the trend grow organically across Boot upgrades.

`beanCount` (v1.1.3) is the same number the report's dependencies section shows. Entries written before v1.1.3 have no `beanCount`, and that is handled rather than guessed — see the verdict rules below.

Example `trendHistory` in `wiredoctor-baseline.json`:

```json
{
  "trendHistory": [
    { "timestamp": 1723900800000, "totalStartupMs": 3420, "slowBeanCount": 2, "beanCount": 388 },
    { "timestamp": 1723987200000, "totalStartupMs": 3580, "slowBeanCount": 3, "beanCount": 391 },
    { "timestamp": 1724073600000, "totalStartupMs": 4759, "slowBeanCount": 5, "beanCount": 391 }
  ]
}
```

## In the HTML report

The Timing tab shows a two-axis chart: `totalStartupMs` as a solid line (left axis) and `beanCount` as a dashed line (right axis). Each point is a baseline write, so gradual creep becomes visible — a 200ms drift per week is obvious long before the `startup-time` gate trips on a single bad run.

Since v1.1.4 the chart also renders on ordinary diff runs (`baseline-write=false`) — it plots the baseline's history, which is what you have committed. The current run is not plotted: it is not a baseline entry, and pretending otherwise would put an unrecorded point on a committed trend.

Any interval that crosses the **`startup-time` gate's own thresholds** (both must hold — default `500ms` AND `20%`; see [Performance gates](performance-gates.html)) gets a coloured band, and the caption below states the verdict for the latest interval:

| Band | Meaning |
|------|---------|
| 🟥 red | slower, and the bean count barely moved — **unexplained by bean count** |
| 🟧 amber | slower, and the app grew by ≥1% more beans |
| 🟧 amber | slower, but one of the two runs predates `beanCount`, so nothing can be ruled explained or not |
| 🟩 green | faster by the same margins |
| no band | inside the gate's thresholds |

Two things the chart deliberately does not claim. Both axes span the observed range rather than starting at zero, so the line exaggerates small absolute changes — read the numbers, not the slope. And a flat bean count with rising startup is **not** proof of a code regression: a bigger dataset, a slower CI runner or a cold cache draw exactly the same line. The band says where to look, not what is wrong.

The dashed bean-count line is drawn in segments, so it never bridges a run that recorded no count.

**The sparkline only appears in reports produced by a `baseline-write=true` run, and only from the second write onward.** `trendHistory[]` lives in the baseline file, not in the per-run report: a normal diff/gate run does not read it back, so its report shows the "Need at least 2 baseline writes" placeholder even when the baseline already holds 30 entries. To see the trend, look at the report from your baseline-refresh job (a nightly CI run is the natural place), or read `trendHistory[]` out of `wiredoctor-baseline.json` directly.

## Configuration

| Property | Default | Description |
|----------|---------|-------------|
| `wiredoctor.trend-history-size` | `30` | Maximum entries to retain in `trendHistory[]`. `0` = unlimited. |

## When the trend matters most

- **Boot upgrades** — a minor Boot bump shouldn't add 2s to startup. The sparkline catches the creep before it becomes a production incident.
- **Dependency additions** — every new `@DependsOn` or eager bean shows up as a step on the line.
- **Periodic audits** — if you run baseline-write in a nightly CI job, the sparkline becomes your startup-time health dashboard.

## When to refresh the baseline

The `trendHistory` is a recording, not a measurement baseline. If you intentionally change the architecture (add beans, remove cycles), re-run with `baseline-write=true` to add a new data point at the new steady state. The old entries stay as historical context — they don't affect the regression guard.
