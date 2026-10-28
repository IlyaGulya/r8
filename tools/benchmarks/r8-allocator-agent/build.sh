#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 3 ]]; then
  echo "Usage: $0 <asm.jar> <asm-commons.jar> <output-agent.jar>" >&2
  exit 2
fi

asm=$1
asm_commons=$2
output=$3
for dependency in "$asm" "$asm_commons"; do
  if [[ ! -f "$dependency" ]]; then
    echo "Missing dependency: $dependency" >&2
    exit 2
  fi
done

root=$(cd "$(dirname "$0")" && pwd)
temporary=$(mktemp -d /tmp/r8-allocator-agent.XXXXXX)
cleanup() {
  find "$temporary" -depth -delete
}
trap cleanup EXIT INT TERM

mkdir -p "$temporary/classes" "$temporary/staging"
javac --release 11 -cp "$asm:$asm_commons" -d "$temporary/classes" \
  "$root/src/com/android/tools/r8/benchmarks/agent/AllocatorTimingAgent.java" \
  "$root/src/com/android/tools/r8/benchmarks/agent/AllocatorTimingRecorder.java"
cp -R "$temporary/classes/." "$temporary/staging/"
(
  cd "$temporary/staging"
  jar xf "$asm"
  jar xf "$asm_commons"
  find META-INF -type f \( -name '*.SF' -o -name '*.RSA' -o -name '*.DSA' \) -delete 2>/dev/null || true
)
printf 'Premain-Class: com.android.tools.r8.benchmarks.agent.AllocatorTimingAgent\n' \
  >"$temporary/MANIFEST.MF"
mkdir -p "$(dirname "$output")"
jar cfm "$output" "$temporary/MANIFEST.MF" -C "$temporary/staging" .
