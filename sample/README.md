# Sample Reports

Real WireDoctor output captured from actual runs — open the `.html` files directly in a browser (fully self-contained, no server needed).

| Folder | Source | Notes |
|--------|--------|-------|
| `test1/` | Early WireDoctor run (older report format) | Minimal app, legacy schema |
| `test2/` | **start.spring.io** (Spring Initializr, Boot 4.0.x, 390 beans) on WireDoctor **0.7.1** | Performance gates armed (`fail-on=startup-time,slow-bean`) — the `slow-bean` gate genuinely tripped; report shows the full v0.7.1 gate verdict UI |

The screenshots in the **[report tour](../docs/report-tour.md)** were captured from `test2/wiredoctor-report.html`.
