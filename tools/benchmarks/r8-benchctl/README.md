# R8 benchmark control plane

`r8-benchctl` turns a versioned TOML experiment into deterministic paired R8 jobs. Standalone
inputs, arguments, artifacts and CI runner labels live outside this repository and are selected by
an immutable experiment plan.

## Statistical unit

One CI job always contains both sides of one comparison:

- timing cells alternate `AB / BA / AB / BA ...`;
- diagnostic cells run `ABBA` with JFR, GC/safepoint, system-process and HotSpot compilation logs;
- control and candidate must reproduce every expected output hash;
- the report computes deltas inside each pod and only then aggregates paired deltas.

This deliberately does not compare an A pod with an unrelated B pod. `max_parallel` controls the
number of independent paired cells that may run at once.
Diagnostics have their own `diagnostics.max_parallel` bound, so JFR/JIT fan-out cannot accidentally
consume the timing lane's larger concurrency budget.

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
