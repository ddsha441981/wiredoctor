# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.1.0] - 2026-04-07

### Added
- **Interactive HTML Visualizer (`WireDoctorHtmlReporter`)**: Generates a self-contained, zero-dependency `wiredoctor-report.html` file using Java 17 Text Blocks and Vis.js. Provides a Dark-Mode Glassmorphism dashboard with an interactive, physics-based network graph of the application's beans.
- **Node Color-Coding**: Visualizer intuitively color-codes beans (Green for Safe, Red for Danger/Cycles, Pink for Proxies, Orange for Orphans).
- **Core Analyzer:** Introduced `wiredoctor-autoconfigure` module to act as a zero-friction Spring Boot starter.
- **Startup Timings Insight:** Implemented an interceptor via `SpringApplicationRunListener` to inject a `BufferingApplicationStartup` early in the lifecycle. This precisely tracks how much time each bean takes to instantiate without any reflection guessing.
- **Bean Dependency Graph Extraction:** Hooked into Spring's native `ConfigurableListableBeanFactory.getDependenciesForBean` for 100% accurate, resolved dependency edge mapping instead of relying on rudimentary `@Autowired` source scraping.
- **Cycle Detection Engine:** Implemented Tarjan's Strongly Connected Components (SCC) algorithm to parse the dependency graph and identify structural circular dependencies (e.g., cycles masked by `@Lazy` or setter injections that Spring tolerates but flag architectural smells).
- **Proxy Overhead Counter:** Added runtime tracking (`AopUtils.isCglibProxy()`) to measure indirection layers wrapped around beans via annotations like `@Async` or `@Transactional`.
- **Dual Reporting Layer**:
  - **JSON Exporter:** Automatically serializes complete deep-dive findings into a robust `wiredoctor-report.json` schema.
  - **Standard Logging:** A developer-friendly SLF4J summary (`log.info`) covering top 5 slowest beans, detected cycle chains, and proxy counts.
- **Adversarial Test Suite:** Added a `wiredoctor-test` module to actively test edge conditions (e.g., deliberate cycles, synthetic slow startup, and async proxies) ensuring high epistemic honesty and heuristic reliability.

### Changed
- **Robust Startup Capture**: Removed `ApplicationContextInitializer` and completely refactored the startup interception to use `ApplicationListener<ApplicationStartingEvent>`. This guarantees the `BufferingApplicationStartup` is injected safely across all Spring Boot versions (2.x, 3.x, and 4.x) without "Double Registration" bugs or dropped startup steps.
- **Real-World Validation**: Successfully validated against `spring-petclinic` and `start.spring.io` (Spring Initializr running Boot 4.0.x), mapping 400+ beans and rendering exact proxy and startup timings without conflict.

### Fixed
- **Double Registration Bug**: Fixed an issue where `WireDoctorContextInitializer` would overwrite the `BufferingApplicationStartup` instance. Removed dead `WireDoctorRunListener` code as `WireDoctorStartupListener` successfully handles startup interception independently.
- **Orphan Bean Noise**: Added default exclusion logic for internal `org.springframework`, `java.`, and `org.apache` packages. Users can still explicitly override this using the `wiredoctor.scan-packages` property.
- **Epistemic Honesty for Cycle Detection**: Documented the "Early-Reference Cycle Blindspot" acknowledging that cycles resolved silently by Spring's 3-level cache (when `allow-circular-references=true`) may bypass `getDependenciesForBean()` and remain unreported.
