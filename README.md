# 🩺 WireDoctor
> *"Your bean graph has a story. WireDoctor reads it."*

WireDoctor is a runtime diagnostic and architectural analysis tool for Spring Boot. It works as an auto-configuration starter that hooks directly into the real, resolved Spring `ApplicationContext` without requiring any build-tool changes. **Zero-intrusion, zero-dashboard-server, pure insights.** Compatible with Spring Boot **2.4+ and 3.x** (startup timings require `BufferingApplicationStartup`, available since Boot 2.4; a full verified compatibility matrix is planned for v0.2.0).

---

## ✨ Features (v0.1.0)
- 🕸️ **Interactive HTML Visualizer**: Automatically generates `wiredoctor-report.html` (a single-file, React-free, Vis.js physics-based network graph) for immediate architectural visualization right in your browser. *Note: the report data is fully embedded, but the graph library loads from a CDN — internet access is required for the graph view (the sidebar stats work offline).*
- ⏱️ **Startup Timings**: Hooks into `ApplicationStartup` via `BufferingApplicationStartup` early in the lifecycle to measure and report exact bean instantiation times without reflection-heavy heuristics.
- 🔗 **Dependency Graph Analysis**: Directly hooks `ConfigurableListableBeanFactory.getDependenciesForBean` to view the completely resolved dependency graph.
- 🔄 **Circular Dependency Detection**: Runs a Tarjan's SCC cycle detector over the bean graph to find structural design smells, even if Spring resolves them via proxies/setters.
- 🎭 **Proxy Overhead Counter**: Scans for CGLIB and JDK proxies (e.g. `@Async`, `@Transactional`) in your bean graph to expose hidden indirection layers.

---

## 🚀 How to use

Just add the `wiredoctor-autoconfigure` dependency to your Spring Boot project. 

**Maven:**
```xml
<dependency>
    <groupId>io.github.ddsha441981</groupId>
    <artifactId>wiredoctor-autoconfigure</artifactId>
    <version>0.1.1</version>
</dependency>
```

**Gradle:**
```groovy
implementation 'io.github.ddsha441981:wiredoctor-autoconfigure:0.1.1'
```

WireDoctor runs automatically at application startup, generates a JSON report (`wiredoctor-report.json`) alongside an interactive dashboard (`wiredoctor-report.html`), and prints a clean diagnostic summary to your standard SLF4J logs.

### ⚙️ Configuration (Optional)
By default, WireDoctor automatically filters out internal infrastructure packages (like `org.springframework`, `java.`, `org.apache`, etc.) from the "Orphan Beans" list to reduce noise. 

If you want to explicitly define which packages should be analyzed for orphans, you can configure `wiredoctor.scan-packages` in your `application.properties`:
```properties
# Single package
wiredoctor.scan-packages=com.yourcompany.app

# Multiple packages (comma-separated)
wiredoctor.scan-packages=com.yourcompany.app,io.yourteam.service

# Configure the path where HTML and JSON reports are saved (default: project root)
wiredoctor.output-path=/path/to/your/reports

# Customize the threshold for flagging a bean as "slow" during instantiation (default: 100ms)
wiredoctor.slow-bean-threshold-ms=50
```

### 🛑 Production Safety (Disabling WireDoctor)
WireDoctor is enabled by default. If you accidentally leave the dependency in your production build, you can completely disable the analyzer to prevent it from running, writing reports, or exposing bean structures by setting:
```properties
# application-prod.properties
wiredoctor.enabled=false
```

---

## 🔬 Epistemic Honesty & Known Limitations
Like any static/runtime analysis tool, WireDoctor prefers honest heuristics over false certainty:

1. ⚡ **AOT / GraalVM Native Image Support:**
   Currently, WireDoctor is designed for **traditional JVM mode only**. Spring Boot 3+ AOT processing fundamentally changes bean instantiation. Running this under Native Image is untested and will likely yield incomplete data.

2. 👻 **Orphan Bean Heuristic (Weak Signal):**
   The tool reports "Orphan Beans" (beans with 0 incoming dependencies). This is a **heuristic, not a guarantee** that the bean is unused. Beans accessed dynamically via `ApplicationContext.getBean()`, event listeners, or scheduled tasks will appear as "orphaned".

3. 💥 **Structural Cycles vs. Crashing Cycles:**
   If Spring encounters an *unresolvable* cycle (e.g., constructor-to-constructor), the app crashes (`BeanCurrentlyInCreationException`) before WireDoctor can report it. WireDoctor detects *resolved* cycles (via setter injection or proxies) that succeed silently. These are reported as structural design smells.

4. 🙈 **Early-Reference Cycle Blindspot (`allow-circular-references=true`):**
   Cycle detection uses `getDependenciesForBean()` which may not capture cycles resolved via Spring's early-reference mechanism (the 3-level cache earlySingletonObjects pathway). Only explicit `@DependsOn` and fully-registered constructor/setter dependencies are detected. Some silently resolved cycles might go unreported.

5. 🔭 **Tested Bean Scopes:**
   The analyzer focuses heavily on `Singleton` beans. `Prototype` beans or complex `FactoryBean` structures may not fully map out in the dependency graph until they are lazily instantiated during runtime.

---
## 🔮 Roadmap (v0.2.0 & Beyond)

We are actively researching advanced diagnostic capabilities for the next major release:

1. 👻 **Real Lazy-Usage Tracking ("Ghost Bean" Detector):**
   *Identifying beans that are instantiated and consume memory but are never actually invoked at runtime. This will likely involve a lightweight, non-invasive access-tracking proxy mechanism.*
2. 🧠 **Memory Footprint Estimation:**
   *Moving beyond measuring instantiation **time** to measuring object **space**. Exploring per-bean heap consumption metrics (potentially requiring Java Agent instrumentation for deep size-of calculations).*
3. 🛠️ **Smart Resolution Insights:**
   *Providing actionable, AI-driven suggestions when structural issues are found (e.g., suggesting exactly which dependency to mark as `@Lazy` to safely break a cycle without side effects).*

---

## 👤 Author

**Deendayal Kumawat** 

* 📧 **Email:** [deendayal_kumawat@hotmail.com](mailto:deendayal_kumawat@hotmail.com)
* 💼 **LinkedIn:** [deendayal-kumawat](https://www.linkedin.com/in/deendayal-kumawat/)
* 🐙 **GitHub:** [ddsha441981](https://github.com/ddsha441981)
* 𝕏 **X (Twitter):** [@ddsha44198](https://x.com/ddsha44198)
* 📝 **Medium:** [@ddsha441981](https://medium.com/@ddsha441981)
* 📦 **Maven Central:** [io.github.ddsha441981](https://mvnrepository.com/artifact/io.github.ddsha441981)
* 🦀 **Crates.io:** [ddsha441981](https://crates.io/users/ddsha441981)
* 🐳 **Docker Hub:** [ripdedup](https://hub.docker.com/u/ripdedup)

### 📚 Research Paper
*Kumawat, D. (2026). Project Lethe: Bio-Inspired Autonomous Edge Intelligence via Sub-Microsecond Continual Learning and Active Forgetting (4.2.0). Zenodo.* 
🔗 [https://doi.org/10.5281/zenodo.20531995](https://doi.org/10.5281/zenodo.20531995)
