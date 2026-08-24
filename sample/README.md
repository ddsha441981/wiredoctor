# Sample Reports

Real WireDoctor output captured from actual runs — open the `.html` files directly in a browser (fully self-contained, no server needed).

| Folder | Source | Notes |
|--------|--------|-------|
| `v1.1.4/` | **start.spring.io** (Spring Initializr, Boot 4.1.x, 429 beans, 436 edges) on WireDoctor **1.1.4** | The current set: report + JSON + baseline + diff + ghost report + gate status. One real cycle, a slow `@PostConstruct` bean, an untouched exporter, first-touch tracking on, and three baseline writes behind the trend chart |
| `test1/` | Early WireDoctor run (older report format) | Minimal app, legacy schema |
| `test2/` | **start.spring.io** on WireDoctor **0.10.0** | Kept for comparison: the pre-1.1.0 report shape (no thread distribution, no trend history) |
| `test3/`, `pipeline_test/` | Larger app / CI pipeline run | A `ci-` prefixed set showing gate status and diff output as CI writes them |

The screenshots and GIFs in the **[report tour](../docs/report-tour.md)** are captured from `v1.1.4/wiredoctor-report.html` by `tools/capture-media.js`.
