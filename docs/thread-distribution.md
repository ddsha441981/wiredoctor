# Thread Distribution — per-thread bean map (v1.1.0)

WireDoctor captures which beans were instantiated on which threads during Spring Boot's parallel initialization phase, and reports a per-thread breakdown in the Timing tab.

---

## How it works

Spring Boot 3.2+ initializes beans across multiple threads. `WireDoctorBufferingApplicationStartup` tags each `spring.beans.instantiate` step with `threadName = Thread.currentThread().getName()`. The analyzer then builds:

- **`beanThreadMap`** — bean name → set of thread names that instantiated it
- **`threadToBeans`** — thread name → ordered list of beans instantiated on that thread
- **`threadToCount`** — thread name → bean count

The report section:

```json
{
  "threadDistribution": {
    "perThread": {
      "main": ["dataSource", "entityManagerFactory", "transactionManager"],
      "task-1": ["tomcatServletWebServerFactory", "dispatcherServlet"],
      "task-2": ["redisConnectionFactory", "redisTemplate"]
    },
    "counts": {
      "main": 3,
      "task-1": 2,
      "task-2": 2
    }
  }
}
```

## In the HTML report

The Timing tab renders a **donut chart** showing thread distribution — each slice is a thread, sized by bean count. Hover reveals the bean list. This answers: "Are my beans spreading across threads as expected, or is everything bottlenecked on `main`?"

## What it reveals

- **Parallel initialization coverage** — if `main` dominates, your `spring.main.allow-bean-definition-overriding` or `@Lazy` placement may be preventing parallelism.
- **Unexpected thread assignments** — a bean on `task-3` that you expected on `main` may indicate an `@Async` or `TaskExecutor` dependency you didn't know about.
- **Thread contention hotspots** — a thread with 50+ beans is a candidate for splitting.

## Limitations

- Only captures beans that went through `spring.beans.instantiate` — `@Lazy` and `Prototype` beans that were never triggered are absent (by design).
- Thread names are JVM-dependent (`main`, `task-1`, etc.) and may vary across Boot versions or custom `TaskExecutor` configurations.
- The `threadName` tag requires Boot 3.2+ where the tag system is available. On older versions, the thread distribution section is omitted gracefully.
