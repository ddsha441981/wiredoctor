# WireDoctor

"Your bean graph has a story. WireDoctor reads it."

WireDoctor is a runtime diagnostic tool for Spring Boot dependency injection and startup. It works as an auto-configuration starter that hooks directly into the real, resolved Spring `ApplicationContext` without requiring any build-tool changes.

## Features (v0.1.0)
- **Interactive HTML Visualizer**: Automatically generates `wiredoctor-report.html` (a self-contained, React-free, Vis.js physics-based network graph) for immediate architectural visualization right in your browser.
- **Startup Timings**: Hooks into `ApplicationStartup` via `BufferingApplicationStartup` (resiliently via `ApplicationContextInitializer`) to measure and report exact bean instantiation times.
- **Dependency Graph Analysis**: Directly hooks `ConfigurableListableBeanFactory.getDependenciesForBean` to view the resolved dependency graph.
- **Circular Dependency Detection**: Runs a Tarjan's SCC cycle detector over the bean graph to find structural design smells, even if Spring resolves them via proxies/setters.
- **Proxy Overhead Counter**: Scans for CGLIB proxies (e.g. `@Async`, `@Transactional`) in your bean graph.

## How to use
Just add it to your Spring Boot project dependencies. WireDoctor runs automatically at application startup, generates a JSON report (`wiredoctor-report.json`) alongside an interactive dashboard (`wiredoctor-report.html`), and prints a clean diagnostic summary to standard output.

## Epistemic Honesty & Known Limitations
Like any static/runtime analysis tool, WireDoctor prefers honest heuristics over false certainty:

1. **AOT / GraalVM Native Image Support:**
   Currently, WireDoctor is designed for **traditional JVM mode only**. Spring Boot 3+ AOT processing fundamentally changes bean instantiation (bypassing much of the runtime reflection and `ApplicationStartup` intercepts). Running this under Native Image is untested and will likely yield incomplete data.

2. **Orphan Bean Heuristic (Weak Signal):**
   The tool reports "Orphan Beans" (beans with 0 incoming dependencies). This is a **heuristic, not a guarantee** that the bean is unused. Beans accessed dynamically via `ApplicationContext.getBean()`, event listeners, or scheduled tasks will appear as "orphaned" in the static DI graph.

3. **Structural Cycles vs. Crashing Cycles:**
   If Spring encounters an *unresolvable* cycle (e.g., constructor-to-constructor), the app crashes (`BeanCurrentlyInCreationException`) before WireDoctor can report it. WireDoctor detects *resolved* cycles (via setter injection or proxies) that succeed silently. These are reported as structural design smells, not fatal errors.

4. **Tested Bean Scopes:**
   The analyzer focuses heavily on `Singleton` beans. `Prototype` beans or complex `FactoryBean` structures may not fully map out in the dependency graph until they are lazily instantiated during runtime.
