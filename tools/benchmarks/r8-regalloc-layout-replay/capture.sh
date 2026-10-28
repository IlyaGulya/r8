#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 6 ]]; then
  echo "Usage: $0 <r8.jar> <input-dir> <method-filter> <fingerprint> <trace> <run-output-dir>" >&2
  exit 2
fi

ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
R8_JAR=$1
INPUT_DIR=$2
METHOD_FILTER=$3
FINGERPRINT=$4
TRACE=$5
RUN_OUTPUT_DIR=$6

for file in "$R8_JAR" "$METHOD_FILTER"; do
  if [[ ! -f "$file" ]]; then
    echo "Required file does not exist: $file" >&2
    exit 2
  fi
done
if [[ ! -d "$INPUT_DIR" ]]; then
  echo "Input directory does not exist: $INPUT_DIR" >&2
  exit 2
fi
if [[ -z "$FINGERPRINT" ]]; then
  echo "Fingerprint must not be empty" >&2
  exit 2
fi

mkdir -p "$RUN_OUTPUT_DIR"
JVM_ARGS_FILE="$RUN_OUTPUT_DIR/layout-trace-jvm-args.txt"
{
  printf '%s\n' "-Dcom.android.tools.r8.regalloc.layoutTrace=$TRACE"
  printf '%s\n' "-Dcom.android.tools.r8.regalloc.layoutTraceMethods=$METHOD_FILTER"
  printf '%s\n' "-Dcom.android.tools.r8.regalloc.layoutTraceFingerprint=$FINGERPRINT"
} >"$JVM_ARGS_FILE"

R8_EXTRA_JVM_ARGS_FILE="$JVM_ARGS_FILE" \
  "$ROOT/tools/benchmarks/run_standalone_r8_benchmark.sh" \
  "$R8_JAR" "$INPUT_DIR" "$RUN_OUTPUT_DIR" no-jfr

if [[ ! -s "$TRACE" ]]; then
  echo "R8 completed without producing a layout trace: $TRACE" >&2
  exit 1
fi

echo "$TRACE"
