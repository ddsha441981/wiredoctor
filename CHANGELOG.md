# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.1.0] - 2026-06-22

### Added
- **Interactive HTML Visualizer (`WireDoctorHtmlReporter`)**: Generates a self-contained, zero-dependency `wiredoctor-report.html` file using Java 17 Text Blocks and Vis.js. Provides a Dark-Mode Glassmorphism dashboard with an interactive, physics-based network graph of the application's beans.
- **Node Color-Coding**: Visualizer color-codes beans automatically (Red for Cycles, Purple for Proxies, Grey for Orphans, Blue for Standard).
- **Core Analyzer:** Introduced `wiredoctor-autoconfigure` module to act as a zero-friction Spring Boot starter.
- **Startup Timings Insight:** Implemented an interceptor via `SpringApplicationRunListener` to inject a `BufferingApplicationStartup` early in the lifecycle. This precisely tracks how much time each bean takes to instantiate without any reflection guessing.
- **Bean Dependency Graph Extraction:** Hooked into Spring's native `ConfigurableListableBeanFactory.getDependenciesForBean` for 100% accurate, resolved dependency edge mapping instead of relying on rudimentary `@Autowired` source scraping.
- **Cycle Detection Engine:** Implemented Tarjan's Strongly Connected Components (SCC) algorithm to parse the dependency graph and identify structural circular dependencies (e.g., cycles masked by `@Lazy` or setter injections that Spring tolerates but flag architectural smells).
- **Proxy Overhead Counter:** Added runtime tracking (`AopUtils.isCglibProxy()`) to measure indirection layers wrapped around beans via annotations like `@Async` or `@Transactional`.
- **Dual Reporting Layer:**
  - **JSON Exporter:** Automatically serializes complete deep-dive findings into a robust `wiredoctor-report.json` schema.
  - **Console Summary:** A developer-friendly `System.out` summary covering top 5 slowest beans, detected cycle chains, and proxy counts.
- **Adversarial Test Suite:** Added a `wiredoctor-test` module to actively test edge conditions (e.g., deliberate cycles, synthetic slow startup, and async proxies) ensuring high epistemic honesty and heuristic reliability.

### Changed
- **Robust Startup Capture**: Migrated startup listener registration from `SpringApplicationRunListener` to `ApplicationContextInitializer`. This forces `BufferingApplicationStartup` injection exactly at the context refresh phase, preventing interference and resets by framework components like Spring Boot DevTools.
- **Real-World Validation**: Successfully validated against `spring-petclinic` (mapping 400+ beans and rendering the exact proxy counts) and integrated cleanly into independent enterprise projects without conflict.
