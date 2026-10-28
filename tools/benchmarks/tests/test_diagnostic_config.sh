#!/usr/bin/env bash
# Native JDK launcher smoke test. No real compiler input or performance claims.
set -euo pipefail
if [[ $# -ne 2 ]]; then
  echo "Usage: $0 <jdk-home> <new-output-directory>" >&2
  exit 2
fi
jdk_home=$(cd "$1" && pwd)
output_root=$2
unset JAVA_TOOL_OPTIONS _JAVA_OPTIONS JDK_JAVA_OPTIONS R8_ARGUMENTS_FILE \
  R8_EXTRA_JVM_ARGS R8_EXTRA_JVM_ARGS_FILE R8_JFR_BIN R8_JFR_BASE_SETTINGS \
  R8_SYSTEM_SAMPLE_INTERVAL
test ! -e "$output_root"
mkdir "$output_root"
script_dir=$(cd "$(dirname "$0")" && pwd)
bench_root=$(cd "$script_dir/.." && pwd)
mkdir "$output_root/classes" "$output_root/input"
"$jdk_home/bin/javac" --release 11 -d "$output_root/classes" \
  "$script_dir/fixtures/com/android/tools/r8/R8.java"
"$jdk_home/bin/jar" --create --file "$output_root/launcher-fixture.jar" -C "$output_root/classes" .
printf '%s\n' --launcher-fixture > "$output_root/input/r8-arguments.txt"
for mode in no-jfr diagnostic; do
  env JAVA_BIN="$jdk_home/bin/java" R8_JAVA_HEAP_SIZE=128m \
    R8_ACTIVE_PROCESSOR_COUNT=2 R8_THREAD_COUNT=2 R8_GC=g1 R8_COMPACT_OBJECT_HEADERS=false \
    bash "$bench_root/run_standalone_r8_benchmark.sh" "$output_root/launcher-fixture.jar" \
      "$output_root/input" "$output_root/$mode" "$mode"
  grep -q 'launcher-fixture-checksum=2088960' "$output_root/$mode/run.log"
done
test ! -e "$output_root/no-jfr/diagnostic.jfc"
test ! -e "$output_root/no-jfr/r8.jfr"
test ! -e "$output_root/no-jfr/hotspot-compilation.xml"
test ! -e "$output_root/no-jfr/gc-safepoint.log"
test -s "$output_root/diagnostic/r8.jfr"
test -s "$output_root/diagnostic/hotspot-compilation.xml"
python3 - "$output_root/diagnostic/diagnostic.jfc" <<'PY'
import sys
import xml.etree.ElementTree as ET

events = {event.attrib['name']: event for event in ET.parse(sys.argv[1]).getroot().findall('event')}
for name in ['jdk.ObjectCount', 'jdk.ObjectCountAfterGC']:
    settings = {setting.attrib['name']: setting.text for setting in events[name].findall('setting')}
    assert settings['enabled'] == 'false', (name, settings)
for name in ['jdk.GarbageCollection', 'jdk.CompilerInlining', 'jdk.ObjectAllocationSample']:
    settings = {setting.attrib['name']: setting.text for setting in events[name].findall('setting')}
    assert settings['enabled'] == 'true', (name, settings)
print('diagnostic event configuration verified')
PY
"$jdk_home/bin/jfr" print --json --events jdk.GarbageCollection,jdk.ObjectCount,jdk.ObjectCountAfterGC --stack-depth 0 \
  "$output_root/diagnostic/r8.jfr" > "$output_root/diagnostic/gc-events.json"
python3 - "$output_root/diagnostic/gc-events.json" <<'PY'
import json
import sys

with open(sys.argv[1]) as stream:
    events = json.load(stream)['recording']['events']
assert not any(event['type'] in ['jdk.ObjectCount', 'jdk.ObjectCountAfterGC'] for event in events), \
    'heap-census events must be absent from the actual recording'
gc_events = [event for event in events if event['type'] == 'jdk.GarbageCollection']
assert gc_events, 'allocation fixture must exercise actual GC events'
causes = [event['values']['cause'] for event in gc_events]
assert not any('heapinspection' in ''.join(char.lower() for char in cause if char.isalnum())
               for cause in causes), causes
print('actual GC events observed without heap-inspection requests:', len(gc_events))
PY
printf '%s\n' 'Launcher fixture passed; not an R8 correctness or performance result.'
