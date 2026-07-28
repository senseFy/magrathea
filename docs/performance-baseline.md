# Local Performance Baseline

- Version: `0.1.0-alpha.1`
- Recorded: 2026-07-26
- Environment: Apple M4 Pro, arm64, 48 GiB RAM, macOS 26.5, JBR 17.0.14
- Scope: deterministic local observations, not an SLA or physical-device performance claim

## Runtime observations

Times below come from Gradle-generated JUnit XML with millisecond resolution. They are useful for
detecting order-of-magnitude regressions on a comparable host; they are not JMH measurements or hard
single-run CI thresholds.

| Contract | Bounded behavior | JVM test time |
|---|---|---:|
| Semantic compaction integration | Proactive compaction, one-shot overflow recovery, canonical-history persistence, and context-state restoration | 121 ms across four tests |
| 1,000-chunk stream | Runtime uses rendezvous event delivery; a collector that does not retain snapshots completes all deltas | 59 ms |
| Slow collector | Provider does not start while the collector is suspended on `Started`, proving there is no buffered snapshot queue | 2 ms |
| 512-character tool result with 128-character budget | Oversized result becomes a fixed typed error before conversation/checkpoint persistence | 3 ms |
| Tool call budgets | Per-turn and persisted per-run limits, including follow-up, steering, and resume behavior | 10 ms across eight tests |
| Typed content-free telemetry matrix | Lifecycle, retry, latency, usage, tool, store, cancellation, and throwing-sink isolation | 15 ms across five tests |

The recorded `magrathea-runtime:jvmTest` run contained 123 logical tests with 451 ms total JUnit
suite time. The `magrathea-runtime-jvm-0.1.0-alpha.1.jar` was 289,731 bytes. The complete clean
`verifySdkRelease` gate for all modules and platforms took 18 minutes 7 seconds on the recorded
host; this aggregate build time is not a Runtime latency benchmark.

## Interpretation

- Correctness gates always enforce hard limits, token-budgeted context management, rendezvous backpressure,
  cancellation propagation, and exclusion of oversized payloads from persistence.
- Timing, RSS, ARC, and first-token latency should be compared only on a controlled environment.
- Android process behavior, iOS Instruments/ARC, real-network latency, and real browser application
  memory require their own platform evidence; Simulator, JVM, and browser-engine automation do not
  replace them.
