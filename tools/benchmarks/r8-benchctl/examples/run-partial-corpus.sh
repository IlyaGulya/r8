#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 5 || $# -gt 6 ]]; then
  echo "Usage: $0 <runner> <r8.jar> <input-dir> <output-dir> <partial-patterns> [mode]" >&2
  exit 2
fi

runner=$1
r8_jar=$2
input_dir=$3
output_dir=$4
partial_patterns_file=$5
mode=${6:-no-jfr}

for required in "$runner" "$r8_jar" "$partial_patterns_file"; do
  if [[ ! -f "$required" ]]; then
    echo "Required file does not exist: $required" >&2
    exit 2
  fi
done
if [[ ! -d "$input_dir" ]]; then
  echo "Input directory does not exist: $input_dir" >&2
  exit 2
fi

partial_patterns=$(tr -d '\r\n' <"$partial_patterns_file")
if [[ -z "$partial_patterns" ]]; then
  echo "Partial include pattern file is empty" >&2
  exit 2
fi

partial_property="-Dcom.android.tools.r8.experimentalPartialShrinkingIncludePatterns=$partial_patterns"
if [[ -n "${R8_ALLOCATOR_CORPUS_PROFILE:-}" ]]; then
  profile_property="-Dcom.android.tools.r8.regalloc.corpusProfile=$R8_ALLOCATOR_CORPUS_PROFILE"
  export R8_EXTRA_JVM_ARGS="${R8_EXTRA_JVM_ARGS:-} $partial_property $profile_property"
else
  export R8_EXTRA_JVM_ARGS="${R8_EXTRA_JVM_ARGS:-} $partial_property"
fi

exec "$runner" "$r8_jar" "$input_dir" "$output_dir" "$mode"
