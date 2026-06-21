# WireDoctor

"Your bean graph has a story. WireDoctor reads it."

WireDoctor is a runtime diagnostic tool for Spring Boot dependency injection and startup. It works as an auto-configuration starter that hooks directly into the real, resolved Spring `ApplicationContext` without requiring any build-tool changes.

## Features (v0.1.0 MVP)
- **Startup Timings**: Hooks into `ApplicationStartup` via `BufferingApplicationStartup` to measure and report exact bean instantiation times.
- **Dependency Graph Analysis**: Directly hooks `ConfigurableListableBeanFactory.getDependenciesForBean` to view the resolved dependency graph.
- **Circular Dependency Detection**: Runs a Tarjan's SCC cycle detector over the bean graph to find structural design smells, even if Spring resolves them via proxies/setters.
- **Proxy Overhead Counter**: Scans for CGLIB proxies (e.g. `@Async`, `@Transactional`) in your bean graph.

## How to use
Just add it to your Spring Boot project dependencies. WireDoctor runs automatically at application startup, generates a JSON report (`wiredoctor-report.json`), and prints a clean diagnostic summary to standard output.
