# R8 benchmark control plane

`r8-benchctl` turns a versioned TOML experiment into deterministic paired R8 jobs. Standalone
inputs, arguments, artifacts and CI runner labels live outside this repository and are selected by
an immutable experiment plan.

## Statistical unit

One CI job always contains both sides of one comparison:

- timing cells alternate `AB / BA / AB / BA ...`;
- diagnostic cells run `ABBA` by default with JFR, GC/safepoint, system-process and HotSpot
  compilation logs;
- control and candidate must reproduce every expected output hash;
- the report computes deltas inside each pod and only then aggregates paired deltas.

Group distribution fields remain pooled over completed cells for JSON compatibility;
Markdown labels them explicitly as pooled. `order_summary` also exposes completed AB/BA
counts, separate order means, their equally weighted `balanced_mean`, and the AB-minus-BA
wall gap in percentage points. This avoids weighting the more frequent order more heavily
when a schedule contains an odd number of pairs. A balanced estimate is unavailable until
both orders have completed, and ABBA diagnostic cells are not mixed into that estimator.
Optional counters require support in every contributing observation; partial support is
reported as unavailable, not as a measured subset. These summaries do not replace a
completeness check, noise qualification, or an independent confirmation run.

This deliberately does not compare an A pod with an unrelated B pod. `max_parallel` controls the
number of independent paired cells that may run at once.
Diagnostics have their own `diagnostics.max_parallel` bound, so JFR/JIT fan-out cannot accidentally
consume the timing lane's larger concurrency budget.
Set `diagnostics.layout = "sharded_pairs"` to expand each diagnostic comparison/runtime into two
independent `AB` and `BA` cells. This preserves balanced position evidence while allowing both
orders, and multiple comparisons, to use the diagnostic concurrency budget concurrently. Omitting
the field retains the single-cell `ABBA` layout and its existing plan fingerprint.

## Build and verify

```bash
cargo test --release --locked --manifest-path tools/benchmarks/r8-benchctl/Cargo.toml
cargo clippy --release --locked --all-targets \
  --manifest-path tools/benchmarks/r8-benchctl/Cargo.toml -- -D warnings
```

The checked-in example currently expands to 24 timing cells and three diagnostic cells:

```bash
benchctl=tools/benchmarks/r8-benchctl/target/release/r8-benchctl

"$benchctl" plan validate tools/benchmarks/r8-benchctl/examples/example-ci.toml
"$benchctl" plan expand tools/benchmarks/r8-benchctl/examples/example-ci.toml \
  --github-matrix --output /tmp/r8-benchmark-matrix.json
```

The input archive must contain `r8-arguments.txt`, with one R8 command-line argument per line.
`{input_dir}` and `{output_dir}` placeholders are expanded without invoking a shell. Output paths
and hashes are declared separately in `expected-outputs.tsv`.

## Immutable GCS protocol

The plan cannot contain commands or arbitrary per-cell destinations. Paths are derived from a
validated root plus immutable hashes:

```text
<root>/artifacts/sha256/<jar-sha256>/r8.jar
<root>/inputs/sha256/<archive-sha256>/input.tar.zst
<root>/experiments/<experiment-id>/<plan-sha256>/plan.toml
<root>/experiments/<experiment-id>/<plan-sha256>/cells/<cell-id>/
```

Artifacts and inputs are uploaded before their manifests. A completed cell uploads a
content-addressed `evidence.tar.zst` before publishing `result.json`; the JSON object is the cell's
completion marker. Hashing and bundle creation stream data, so multi-gigabyte inputs and JFR files
do not have to fit in controller memory.

Typical local preparation is:

```bash
"$benchctl" artifact publish --plan plan.toml --artifact-id control --jar control-r8.jar
"$benchctl" artifact publish --plan plan.toml --artifact-id candidate --jar candidate-r8.jar
"$benchctl" input publish --plan plan.toml \
  --archive input.tar.zst --expected-outputs expected-outputs.tsv

# Prints the exact upload and workflow-dispatch operations without changing external state.
"$benchctl" submit --plan plan.toml \
  --repository example/workflows --workflow r8-benchmark-matrix.yml --workflow-ref main \
  --tooling-ref 0123456789abcdef0123456789abcdef01234567 --dry-run
```

A compatible workflow accepts only the plan URI, its canonical fingerprint, and an immutable
40-character tooling commit. It can build the Rust controller once, verify its binary hash in every
cell, download jars and inputs by SHA, and avoid mutable CI artifacts.

## Evidence and reports

```bash
"$benchctl" matrix collect --plan plan.toml --output-dir collected
"$benchctl" matrix verify --plan plan.toml --result-root collected
"$benchctl" matrix report --plan plan.toml --result-root collected \
  --format markdown --output report.md
```

Every complete manifest embeds the expanded cell, actual JDK and cgroup environment, ordered raw
runs, wall/user/system/RSS/peak footprint/instruction/cycle fields, exact output hashes, and SHA-256
for every evidence file. The bundle verifier rejects path traversal, extra files, missing files,
size changes and hash changes.

Generate the machine-readable contracts with `schema plan` and `schema evidence`. Consumers must
reject unknown fields, unsupported schema versions, changed plan fingerprints, unsafe paths,
unverified artifacts and partial evidence.

## Register allocator screening corpus

Development builds can emit one JSONL record per method immediately after linear-scan register
allocation:

This requires matching compiler-side profiling hooks, supplied separately from these standalone
tools. A system property alone does not add instrumentation to an arbitrary R8 artifact.

```bash
java \
  -Dcom.android.tools.r8.regalloc.corpusProfile=/tmp/allocator-profile.jsonl \
  -cp r8.jar com.android.tools.r8.R8 ...
```

Each record contains the method and holder identity plus instruction, block, phi, live-interval,
wide-value, constrained-register, active/inactive peak, spill, split, invoke-range and exception-CFG
shape, together with per-method allocator wall and current-thread CPU cost. Profiling is completely
disabled when the property is absent.

`corpus summarize` reports feature prevalence before selection. `corpus select` retains every
configured genuinely rare allocator path and fills the remaining budget with a deterministic
proportional sample across pressure, phi, interval, instruction-count and allocator-path strata.
The priority section can force the most expensive wall/CPU methods into the sample so a small
corpus retains allocator-time coverage instead of merely retaining method-count coverage:

```bash
"$benchctl" corpus summarize --profile /tmp/allocator-profile.jsonl

"$benchctl" corpus select \
  --profile /tmp/allocator-profile.jsonl \
  --config tools/benchmarks/r8-benchctl/examples/allocator-corpus.toml \
  --manifest /tmp/allocator-corpus.json \
  --partial-patterns /tmp/allocator-partial-patterns.txt \
  --holder-filter /tmp/allocator-holders.txt

"$benchctl" corpus verify \
  --profile /tmp/allocator-profile.jsonl \
  --manifest /tmp/allocator-corpus.json
```

The pattern file is directly consumable by R8's experimental partial-compilation property. The
example `run-partial-corpus.sh` wraps an existing standalone runner so that selected holders take
the full R8 path while the rest of the unchanged closed world takes the cheaper D8 path. Exact
output hashes must still match between compared artifacts for this reduced workload. The manifest
reports both the selected method count and the larger class-expanded `partial_methods` count. The
optional holder-filter file is directly consumable by the allocator timing agent.

For allocator-specific calibration, the sibling `r8-allocator-agent` instruments any control or
candidate jar at load time and reports aggregate `allocateRegisters()` wall and thread-CPU time.
This avoids diluting an allocator effect with the fixed D8 side of partial compilation. It is still
an early-screening metric, not a replacement for whole-program wall time.

A corpus is not promotable until it reproduces known effects. `corpus calibrate` compares measured
JSON observations with a checked-in expectation plan and exits non-zero if a regression changes
direction, a neutral case escapes its tolerance, or a known correctness failure disappears:

```bash
"$benchctl" corpus calibrate \
  --plan tools/benchmarks/r8-benchctl/examples/allocator-corpus-calibration.toml \
  --observations /tmp/allocator-calibration-observations.json \
  --output /tmp/allocator-calibration-report.json
```

This corpus is an early/intermediate screen. Whole-program wall time, RSS, cgroup peak, GC and the
terminal retain/reject decision continue to require the full production bundle.

The design and correctness requirements for a future persistent pre-allocation replay tier are in
[`REPLAY_CORPUS.md`](REPLAY_CORPUS.md). The timing agent is intentionally not presented as replay.
