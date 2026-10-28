# Register allocator layout replay

This standalone Java runner replays a versioned register-allocator operation trace without running
the rest of R8. It is intended for fast screening of data-layout changes. A replay result is not a
replacement for a closed-world R8 benchmark.

Build and test:

```bash
tools/benchmarks/r8-regalloc-layout-replay/build.sh --test
```

Build an ordinary R8 distribution using the repository's normal pinned build environment:

```bash
tools/gradle.py r8
```

The result is `build/libs/r8.jar`. These standalone tools do not change the compiler build,
dependency or license-aggregation pipeline. Trace capture additionally requires a compatible
compiler build with the opt-in schema-versioned capture hooks. Those compiler-side hooks are
separate from this tooling-only topic; setting a property does not add them to an arbitrary jar.

Create the exact-method filter from a verified allocator corpus manifest:

```bash
tools/benchmarks/r8-benchctl/target/release/r8-benchctl corpus trace-filter \
  --profile allocator-profile.jsonl \
  --manifest corpus-manifest.json \
  --output layout-methods.txt
```

Capture one trace with the normal standalone R8 launcher:

```bash
tools/benchmarks/r8-regalloc-layout-replay/capture.sh \
  r8.jar input layout-methods.txt TRACE_FINGERPRINT layout-trace.bin capture-output
```

Run one layout:

```bash
java -jar tools/benchmarks/r8-regalloc-layout-replay/build/r8-regalloc-layout-replay.jar \
  --trace layout-trace.bin \
  --expect-fingerprint TRACE_FINGERPRINT \
  --list dense \
  --positions packed \
  --warmup 5 \
  --iterations 20 \
  --output result.json
```

List backends are `linked`, `array`, and primitive stable-order `dense`. Position-table backends are
`legacy`, dynamically growing `packed`, and full-limit `eager-packed`. Every replay validates the
recorded iterator order and position-table query results. Results contain wall time, current-thread
CPU time, current-thread allocated bytes, the trace SHA-256, and a backend-independent checksum.

Run paired, alternating separate JVMs through the Rust controller:

```bash
tools/benchmarks/r8-benchctl/target/release/r8-benchctl replay layout-pair \
  --runner tools/benchmarks/r8-regalloc-layout-replay/build/r8-regalloc-layout-replay.jar \
  --java-bin /path/to/java \
  --trace layout-trace.bin \
  --expect-fingerprint TRACE_FINGERPRINT \
  --candidate-list dense \
  --candidate-positions packed \
  --pairs 5 \
  --result-dir replay-results
```

Create smaller replay corpora from one capture without rerunning R8:

```bash
tools/benchmarks/r8-benchctl/target/release/r8-benchctl replay trace-slice \
  --trace layout-trace.bin \
  --methods layout-methods-128.txt \
  --output layout-trace-128.bin
```

Use `examples/allocator-layout-replay.toml` for the promotion replay and the `-128` and `-64`
variants for screening calibration. A smaller corpus is accepted only when paired AB/BA runs keep
the direction of both known improvements and known regressions. Method count alone is not a useful
size bound: a few allocator-heavy methods can dominate most trace events.

Recommended local funnel:

1. Three pairs on the calibrated 128-method slice, with no in-process warmup and one measured pass.
2. Five pairs on the full captured trace for small or noisy effects.
3. A reduced closed-world R8 bundle for compiler integration and exact output hashes.
4. The full private R8 bundle for final wall/RSS/GC and retain/reject decisions.

The capture is the only stage that needs the private R8 input. Trace slicing and layout replay need
only a JDK and the generic runner/controller artifacts.

## Build dependencies

An R8 build uses the repository's pinned Gradle, JDKs, local Maven mirrors and compiler dependency
closure. Follow that compiler version's build instructions; this tooling does not bypass its
dependency or license checks. A capture artifact must be identified separately from a clean
uninstrumented timing artifact.

The standalone replay runner has no R8 compile-time dependency and builds with `javac`/`jar`. It is
run with the same JDK major version used by the capture. The paired controller is the Rust
`r8-benchctl` binary and does not need Gradle or the R8 checkout at runtime.
