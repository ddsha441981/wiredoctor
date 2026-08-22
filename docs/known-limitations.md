# Known Limitations

WireDoctor prefers honest heuristics over false certainty. Here is what it cannot (and will not pretend to) do.

---

## 1. AOT / GraalVM Native Image — not supported

WireDoctor is designed for **traditional JVM mode only**. When running inside a GraalVM native image (detected at runtime via `NativeDetector.inNativeImage()`), the entire analysis is skipped automatically with a single `WARN` log line — no crash, no incomplete report, no gates.

Set `wiredoctor.enabled=false` in your native profile to silence the warning.

Boot 3+ AOT *compilation* (not native-image runtime) is unaffected — analysis runs normally in JVM mode even when the app was compiled with AOT processing enabled.

---

## 2. Orphan Bean Heuristic (Weak Signal)

The tool reports "Orphan Beans" (beans with 0 incoming dependencies). This is a **heuristic, not a guarantee** that the bean is unused. Beans accessed dynamically via `ApplicationContext.getBean()`, event listeners, or scheduled tasks will appear as "orphaned".

Since v0.6.0 the `ghostCandidates` section refines this (entry-point detection filters out controllers/listeners/runners), and opt-in ghost tracking measures actual invocation — but even a tracked "untouched" bean only means *not invoked during this run*, never "unused".

---

## 3. Structural Cycles vs. Crashing Cycles

If Spring encounters an *unresolvable* cycle (e.g., constructor-to-constructor), the app crashes (`BeanCurrentlyInCreationException`) before WireDoctor can report it.

WireDoctor detects *resolved* cycles (via setter injection or proxies) that succeed silently. These are reported as structural design smells.

---

## 4. Early-Reference Cycle Blindspot (`allow-circular-references=true`)

Cycle detection uses `getDependenciesForBean()` which may not capture cycles resolved via Spring's early-reference mechanism (the 3-level cache `earlySingletonObjects` pathway). Only explicit `@DependsOn` and fully-registered constructor/setter dependencies are detected. Some silently resolved cycles might go unreported.

---

## 5. Bean Scopes

`Prototype` and `@Lazy` bean *definitions* appear as graph nodes, but they are **never instantiated** by the analysis (zero-intrusion promise — a regression test proves it). Their proxy status can't be known without instantiating them, so they are skipped by the proxy scan and honestly counted in `proxies.notInstantiatedSkipped`.

`FactoryBean`s appear under the factory's bean name; a consumer of the *product* gets its dependency edge recorded against the factory's name (Spring's own bookkeeping).

Runtime-only facts about these scopes — how often a prototype is created, whether a lazy bean is ever touched — are outside a startup snapshot's reach.

---

See [Known Issues](https://github.com/ddsha441981/wiredoctor/issues) for the full, versioned list.
