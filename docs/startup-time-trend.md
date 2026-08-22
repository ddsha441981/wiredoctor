# Startup Time Trend — track creep over time (v1.1.0)

Every baseline write appends a timestamped snapshot to `trendHistory[]` inside `wiredoctor-baseline.json`. The Timing tab renders a **sparkline** so you can see startup-time creep before the gate trips.

---

## How it works

When WireDoctor runs with `wiredoctor.baseline-write=true`, before writing the baseline:

1. Read the existing baseline JSON (if present) and extract `trendHistory[]`
2. Append a new entry: `{timestamp, totalStartupMs, slowBeanCount}`
3. Cap at `wiredoctor.trend-history-size` (default: 30 entries, `0` = unlimited)
4. Trim oldest entries if over cap
5. Write the updated baseline

The `totalStartupMs` field is omitted from entries when it was `null` (pre-v0.7.0 baselines where `ApplicationReadyEvent.getTimeTaken()` was unavailable). This lets the trend grow organically across Boot upgrades.

Example `trendHistory` in `wiredoctor-baseline.json`:

```json
{
  "trendHistory": [
    { "timestamp": 1723900800000, "totalStartupMs": 3420, "slowBeanCount": 2 },
    { "timestamp": 1723987200000, "totalStartupMs": 3580, "slowBeanCount": 3 },
    { "timestamp": 1724073600000, "totalStartupMs": 4759, "slowBeanCount": 5 }
  ]
}
```

## In the HTML report

The Timing tab shows a **sparkline chart** of `totalStartupMs` over time. Each point is a baseline write. The chart makes gradual creep visible — a 200ms drift per week becomes obvious before the `startup-time` gate trips on a single bad run.

## Configuration

| Property | Default | Description |
|----------|---------|-------------|
| `wiredoctor.trend-history-size` | `30` | Maximum entries to retain in `trendHistory[]`. `0` = unlimited. |

## When the trend matters most

- **GradBoot upgrades** — a minor Boot bump shouldn't add 2s to startup. The sparkline catches the creep before it becomes a production incident.
- **Dependency additions** — every new `@DependsOn` or eager bean shows up as a step on the line.
- **Periodic audits** — if you run baseline-write in a nightly CI job, the sparkline becomes your startup-time health dashboard.

## When to refresh the baseline

The `trendHistory` is a recording, not a measurement baseline. If you intentionally change the architecture (add beans, remove cycles), re-run with `baseline-write=true` to add a new data point at the new steady state. The old entries stay as historical context — they don't affect the regression guard.
