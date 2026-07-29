# Sample Reports

Real WireDoctor output captured from actual runs — open the `.html` files directly in a browser (fully self-contained, no server needed).

| Folder | Source | Notes |
|--------|--------|-------|
| `test1/` | Early WireDoctor run (older report format) | Minimal app, legacy schema |
| `test2/` | **start.spring.io** (Spring Initializr, Boot 4.0.x, 390 beans) on WireDoctor **0.10.0** | Shows the v0.10.0 Graph tab: **Timing heat** (per-bean instantiation heat map) and **Critical path** (gold highlight) chips, backed by the full `beanTimings` map (386 timed beans) |

The screenshots in the **[report tour](../docs/report-tour.md)** were captured from `test2/wiredoctor-report.html`.
