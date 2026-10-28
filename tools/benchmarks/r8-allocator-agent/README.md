# R8 allocator timing agent

This opt-in Java agent measures aggregate wall and current-thread CPU time spent inside
`LinearScanRegisterAllocator.allocateRegisters()`. It instruments any compatible R8 jar at class
load time, so control and candidate jars do not need separate profiler builds.

Build it with pinned ASM and ASM Commons jars:

```bash
tools/benchmarks/r8-allocator-agent/build.sh \
  /path/to/asm.jar /path/to/asm-commons.jar /tmp/r8-allocator-agent.jar
```

Then add the agent to a benchmark JVM:

```bash
-javaagent:/tmp/r8-allocator-agent.jar=/tmp/allocator-timing.json
```

The atomic JSON output contains invocation count plus summed allocator wall and CPU nanoseconds.
Use it as an early screening metric; whole-program wall time and memory still require the normal
paired benchmark gates.

When R8 partial compilation is used, filter measurements to the exact holders selected by the
corpus manifest. Otherwise the agent would also count allocator work from the cheaper D8 side:

```bash
-javaagent:/tmp/r8-allocator-agent.jar=/tmp/allocator-timing.json\;holders=/tmp/holders.txt
```

`holders.txt` contains one class descriptor such as `Lexample/Foo;` per line. An empty or unreadable
filter is rejected instead of silently measuring the wrong workload.

The filtered sum is not automatically an unbiased estimate of the full allocator population.
Expensive-method priorities and class expansion can over-represent methods where a candidate has a
different local effect. A holder-filtered corpus must pass the known-effect calibration contract;
if directions differ, use method/stratum-weighted replay or the full gate rather than adjusting the
tolerance around the observed result.
