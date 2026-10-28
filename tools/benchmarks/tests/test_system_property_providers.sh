#!/usr/bin/env bash
# Reproduce the Provider string bug, then verify the opt-in resolver with stock Gradle.
set -euo pipefail
if [[ $# -ne 5 ]]; then
  echo "Usage: $0 <gradle-home> <jdk-home> <junit-jar> <hamcrest-jar> <new-output-dir>" >&2
  exit 2
fi
gradle_home=$(cd "$1" && pwd)
jdk_home=$(cd "$2" && pwd)
junit_jar=$3
hamcrest_jar=$4
output=$5
test -f "$junit_jar"
test -f "$hamcrest_jar"
test ! -e "$output"
mkdir "$output"
script_dir=$(cd "$(dirname "$0")" && pwd)
resolver=$(cd "$script_dir/.." && pwd)/resolve_test_system_property_providers.gradle
unset JAVA_TOOL_OPTIONS _JAVA_OPTIONS JDK_JAVA_OPTIONS
export JAVA_HOME="$jdk_home" FIXTURE_JUNIT_JAR="$junit_jar" FIXTURE_HAMCREST_JAR="$hamcrest_jar"
for mode in negative resolved; do
  cp -R "$script_dir/fixtures/provider-properties" "$output/$mode"
  mkdir "$output/$mode/fixture-classes"
done
set +e
"$gradle_home/bin/gradle" --offline --no-daemon --max-workers=2 --no-configuration-cache \
  --console=plain -p "$output/negative" test > "$output/negative.log" 2>&1
negative_exit=$?
set -e
test "$negative_exit" -ne 0
grep -q 'ProviderPropertyFixtureTest.*FAILED' "$output/negative.log"
grep -q 'unresolved provider property: provider(?)' \
  "$output/negative/build/test-results/test/TEST-ProviderPropertyFixtureTest.xml"
"$gradle_home/bin/gradle" --offline --no-daemon --max-workers=2 --no-configuration-cache \
  --console=plain --init-script "$resolver" -p "$output/resolved" test > "$output/resolved.log" 2>&1
python3 - "$output/resolved/build/test-results/test/TEST-ProviderPropertyFixtureTest.xml" <<'PY'
import sys
import xml.etree.ElementTree as ET

suite = ET.parse(sys.argv[1]).getroot()
assert int(suite.attrib['tests']) == 1, suite.attrib
assert int(suite.attrib['failures']) == 0, suite.attrib
assert int(suite.attrib['errors']) == 0, suite.attrib
assert int(suite.attrib['skipped']) == 0, suite.attrib
print('Provider directory/boolean resolved; plain property unchanged; one actual test passed.')
PY
printf '%s\n' 'Negative reproduction and positive property-resolution fixture passed; not an R8 result.'
