#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
BUILD="$ROOT/build"
CLASSES="$BUILD/classes"
JAVAC="${JAVA_HOME:+$JAVA_HOME/bin/}javac"
JAR="${JAVA_HOME:+$JAVA_HOME/bin/}jar"
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"

mkdir -p "$CLASSES"
find "$CLASSES" -type f -delete

"$JAVAC" \
  --release 11 \
  -Xlint:all \
  -Werror \
  -d "$CLASSES" \
  "$ROOT/src/com/android/tools/r8/benchmarks/regalloc/LayoutReplayMain.java"

"$JAR" \
  --create \
  --file "$BUILD/r8-regalloc-layout-replay.jar" \
  --main-class com.android.tools.r8.benchmarks.regalloc.LayoutReplayMain \
  -C "$CLASSES" .

if [[ "${1:-}" == "--test" ]]; then
  "$JAVA" -jar "$BUILD/r8-regalloc-layout-replay.jar" --self-test
fi

echo "$BUILD/r8-regalloc-layout-replay.jar"
