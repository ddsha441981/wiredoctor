# Thread Distribution — per-thread bean map (v1.1.0)

WireDoctor records which thread instantiated each bean and reports a per-thread breakdown in the Timing tab. It answers one question honestly: **did anything actually leave the `main` thread?**

---

## How it works

`WireDoctorBufferingApplicationStartup` overrides `start(String)` and tags every `spring.beans.instantiate` step with `threadName = Thread.currentThread().getName()` at step-creation time — the only point where the instantiating thread is still on the stack. Spring already tags that step with `beanName`, so the two combine without extra bookkeeping.

The analyzer then builds:

- **`beanThreadMap`** — bean name → *set* of thread names that instantiated it (a set, so a prototype created on several threads keeps all of them)
- **`threadToBeans`** — thread name → list of beans instantiated on that thread
- **`threadToCount`** — thread name → bean count

```json
{
  "threadDistribution": {
    "perThread": {
      "main": ["dataSource", "entityManagerFactory", "transactionManager"],
      "app-bg-1": ["warmCacheBean"]
    },
    "counts": { "main": 3, "app-bg-1": 1 }
  }
}
```

Thread names are whatever the JVM and your executor produce — `main` plus the `threadNamePrefix` of the `bootstrapExecutor` you registered. There are no fixed names like `task-1` to expect.

## In the HTML report

The Timing tab renders a **donut chart** sized by bean count per thread, next to a table of thread / bean count / share, ordered by count descending. When every bean landed on one thread the card shows a `parallel init not active` pill instead of implying something is wrong.

The `perThread` bean lists are present in `wiredoctor-report.json` but are not yet surfaced in the HTML card — only the counts are. Drilling into "which beans ran on `app-bg-1`" means reading the JSON for now.

## Why `main` almost always dominates

Spring does **not** parallelise bean initialisation by default, and no Boot version has. Background initialisation is opt-in per bean and needs two things:

1. a bean marked `@Bean(bootstrap = Bean.Bootstrap.BACKGROUND)` (Spring Framework 6.2+ / Boot 3.4+), and
2. a `bootstrapExecutor` bean for it to run on

Without both, a 600-bean application legitimately reports 100% on `main`. That is not a misconfiguration, and no property — `@Lazy` placement, bean-definition overriding, `@Async` — changes it. `@Async` affects method invocation, not instantiation.

Measured on Spring Cloud Config Server (616 beans, Boot 4.1):

```
main:               610 beans   (99.8%)
configserver-bg-1:    1 bean    → the one @Bean(bootstrap = BACKGROUND)
```

## What it is good for

- **Confirming background bootstrap actually took effect.** You marked a slow bean `BACKGROUND` and gave it an executor — did it move off `main`, or is it silently still blocking startup? This is the check that answers it, and the one case where the card earns its place.
- **Spotting the thread a bean unexpectedly landed on** when several executors are in play.
- **Reading the donut as a coverage number.** One slice means your startup is fully serial, so slow-bean timings add up linearly and the critical path is the whole story.

## Limitations

- Only beans that went through `spring.beans.instantiate` appear. `@Lazy` and prototype beans never triggered during the run are absent by design.
- Distribution only — which beans ran on which threads. Not a swimlane timeline and not blocking inference; those need per-thread start/end timestamps plus a dependency overlay (a v1.2+ research item).
- Requires WireDoctor's own `ApplicationStartup` to be installed. If your application sets a foreign `ApplicationStartup`, WireDoctor respects it, logs a warning, and the `threadDistribution` section is omitted — all other analysis still runs.
- The donut's centre count sums the per-thread counts, so a prototype instantiated on two threads is counted once per thread.
