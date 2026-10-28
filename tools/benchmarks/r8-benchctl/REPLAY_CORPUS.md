# Persistent register allocator replay corpus

The reduced closed-world corpus is safe today because R8 still owns the complete application and
all graph-lens state. A persistent allocator replay can be much faster, but instruction bytes alone
are not a valid replay unit. LIR constants reference dex items and the reconstructed IR depends on
the optimized method, graph lens, prototype changes, conversion options and application view.

## Capture boundary

Capture each selected method after whole-program optimization and immediately before register
allocation. A replay entry must contain:

- the optimized LIR instruction bytes, constant pool, position table and try/catch table;
- method identity, access flags, rewritten prototype and conversion options;
- graph-lens, naming-lens, min-api, debug/release and allocator-option fingerprints;
- compiler commit, input hash and deterministic corpus-manifest hash;
- expected IR structural hash, register-assignment hash and allocator counters.

The file header must make the corpus invalid when any of these fingerprints changes. Reusing a
replay across an incompatible compiler or application state is an error, not a cache miss that can
be ignored.

## Replay runner

The runner reconstructs the minimal `AppView` and dex-item factory from a versioned application
snapshot, resolves every LIR constant, rebuilds IR, then runs liveness construction and
`LinearScanRegisterAllocator`. Methods may run independently once shared application state is
frozen. A worker must discard its mutated IR after one iteration and reconstruct it from captured
LIR before the next iteration.

For every method the runner records:

- wall and current-thread CPU time;
- active/inactive peaks, spills, split intervals and registers used;
- invoke-range and constrained-register path counts;
- the register-assignment/correctness fingerprint.

Aggregate reports are paired by method and preserve corpus weights. Any missing method, changed
fingerprint or reconstruction failure invalidates the run.

## Promotion policy

Replay is an early correctness/performance screen. It deliberately excludes whole-program
interactions, output writing, GC pressure from other phases and peak memory. A result is promoted
to the reduced closed-world corpus, then to the full production bundle. Only the full bundle can
support a terminal retain/reject decision.

## Implementation order

1. Add a versioned LIR snapshot codec with round-trip structural-hash tests.
2. Capture and reload the minimal dex-item/application context with strict fingerprints.
3. Reconstruct one method and require identical allocator counters and register assignment.
4. Add deterministic parallel replay over the selected manifest.
5. Calibrate against the same known regression, neutral and correctness cases as the closed-world
   corpus.

Until steps 1–3 exist, the filtered allocator timing agent is the safe phase-isolated metric. It
must not be described as persistent replay.
