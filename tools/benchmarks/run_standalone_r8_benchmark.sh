#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 3 ]]; then
  echo "Usage: $0 <r8.jar> <input-dir> <output-dir> [no-jfr|jfr|diagnostic]" >&2
  echo "Environment: JAVA_BIN, R8_JAVA_HEAP_SIZE, R8_ACTIVE_PROCESSOR_COUNT," >&2
  echo "  R8_THREAD_COUNT, R8_GC=(default|g1|parallel|zgc)," >&2
  echo "  R8_COMPACT_OBJECT_HEADERS=(true|false), R8_EXTRA_JVM_ARGS," >&2
  echo "  R8_ARGUMENTS_FILE, R8_JFR_BIN, R8_JFR_BASE_SETTINGS," >&2
  echo "  R8_SYSTEM_SAMPLE_INTERVAL" >&2
  exit 2
fi

r8_jar=$1
input_dir=$2
output_dir=$3
profile_mode=${4:-no-jfr}
java_heap_size=${R8_JAVA_HEAP_SIZE:-22g}
active_processor_count=${R8_ACTIVE_PROCESSOR_COUNT:-10}
r8_thread_count=${R8_THREAD_COUNT:-$active_processor_count}
arguments_file=${R8_ARGUMENTS_FILE:-"$input_dir/r8-arguments.txt"}

if [[ ! -f "$arguments_file" ]]; then
  echo "R8 arguments file does not exist: $arguments_file" >&2
  exit 2
fi

r8_arguments=()
while IFS= read -r argument || [[ -n "$argument" ]]; do
  if [[ -z "$argument" ]]; then
    continue
  fi
  argument=${argument//\{input_dir\}/$input_dir}
  argument=${argument//\{output_dir\}/$output_dir}
  r8_arguments+=("$argument")
done <"$arguments_file"

if [[ ${#r8_arguments[@]} -eq 0 ]]; then
  echo "R8 arguments file is empty: $arguments_file" >&2
  exit 2
fi

mkdir -p "$output_dir"
rm -f \
  "$output_dir/base.zip" \
  "$output_dir/feature-1.zip" \
  "$output_dir/mapping.txt" \
  "$output_dir/art-profile-1.txt" \
  "$output_dir/r8.jfr" \
  "$output_dir/diagnostic.jfc" \
  "$output_dir/gc-safepoint.log" \
  "$output_dir/hotspot-compilation.xml" \
  "$output_dir/system-processes.tsv" \
  "$output_dir/time.txt" \
  "$output_dir/run.log"

java_bin=${JAVA_BIN:-java}
jfr_args=()
if [[ "$profile_mode" == "jfr" ]]; then
  jfr_args+=(
    "-XX:StartFlightRecording=filename=$output_dir/r8.jfr,settings=profile,dumponexit=true"
  )
elif [[ "$profile_mode" == "diagnostic" ]]; then
  java_home=$(
    "$java_bin" -XshowSettings:properties -version 2>&1 |
      awk -F ' = ' '$1 ~ /^[[:space:]]*java.home$/ { print $2; exit }'
  )
  if [[ -z "$java_home" ]]; then
    echo "Unable to resolve java.home from: $java_bin" >&2
    exit 2
  fi
  jfr_bin=${R8_JFR_BIN:-"$java_home/bin/jfr"}
  jfr_base_settings=${R8_JFR_BASE_SETTINGS:-"$java_home/lib/jfr/profile.jfc"}
  if [[ ! -x "$jfr_bin" ]]; then
    echo "JFR tool is not executable: $jfr_bin" >&2
    exit 2
  fi
  if [[ ! -f "$jfr_base_settings" ]]; then
    echo "JFR base settings do not exist: $jfr_base_settings" >&2
    exit 2
  fi
  "$jfr_bin" configure \
    --input "$jfr_base_settings" \
    compiler=detailed \
    gc=all \
    method-profiling=max \
    allocation-profiling=medium \
    jdk.CompilerPhase#threshold=0ms \
    --output "$output_dir/diagnostic.jfc"
  jfr_args+=(
    "-XX:+UnlockDiagnosticVMOptions"
    "-XX:+DebugNonSafepoints"
    "-XX:+LogCompilation"
    "-XX:LogFile=$output_dir/hotspot-compilation.xml"
    "-Xlog:gc*,safepoint:file=$output_dir/gc-safepoint.log:time,uptime,level,tags"
    "-XX:StartFlightRecording=filename=$output_dir/r8.jfr,settings=$output_dir/diagnostic.jfc,dumponexit=true"
  )
elif [[ "$profile_mode" != "no-jfr" ]]; then
  echo "Unknown profile mode: $profile_mode" >&2
  exit 2
fi

jvm_tuning_args=()
extra_jvm_args=()

if [[ -n "${R8_EXTRA_JVM_ARGS:-}" ]]; then
  read -r -a extra_jvm_args <<<"$R8_EXTRA_JVM_ARGS"
fi

case "${R8_GC:-default}" in
  default)
    ;;
  g1)
    jvm_tuning_args+=("-XX:+UseG1GC")
    ;;
  parallel)
    jvm_tuning_args+=("-XX:+UseParallelGC")
    ;;
  zgc)
    jvm_tuning_args+=("-XX:+UseZGC")
    ;;
  *)
    echo "Unknown R8_GC value: ${R8_GC}" >&2
    exit 2
    ;;
esac

case "${R8_COMPACT_OBJECT_HEADERS:-false}" in
  false)
    ;;
  true)
    jvm_tuning_args+=("-XX:+UseCompactObjectHeaders")
    ;;
  *)
    echo "R8_COMPACT_OBJECT_HEADERS must be true or false" >&2
    exit 2
    ;;
esac

system_monitor_pid=
stop_system_monitor() {
  if [[ -n "$system_monitor_pid" ]]; then
    kill "$system_monitor_pid" 2>/dev/null || true
    wait "$system_monitor_pid" 2>/dev/null || true
  fi
}
trap stop_system_monitor EXIT INT TERM

if [[ "$profile_mode" == "diagnostic" ]]; then
  system_sample_interval=${R8_SYSTEM_SAMPLE_INTERVAL:-5}
  (
    while true; do
      sample_time=$(date +%s)
      ps -axo pid=,pcpu=,rss=,comm= |
        awk -v sample_time="$sample_time" \
          '$2 + 0 >= 1.0 { print sample_time "\t" $1 "\t" $2 "\t" $3 "\t" $4 }'
      sleep "$system_sample_interval"
    done
  ) >"$output_dir/system-processes.tsv" &
  system_monitor_pid=$!
fi

# The Rust controller measures this process tree with wait4 and samples cgroup memory. Keeping
# resource accounting outside this launcher makes the benchmark work on minimal runner images
# that do not ship GNU time and gives identical measurement semantics on Linux and macOS.
"$java_bin" \
  "-Xmx$java_heap_size" \
  "-XX:ActiveProcessorCount=$active_processor_count" \
  ${jvm_tuning_args[@]+"${jvm_tuning_args[@]}"} \
  ${jfr_args[@]+"${jfr_args[@]}"} \
  ${extra_jvm_args[@]+"${extra_jvm_args[@]}"} \
  -cp "$r8_jar" \
  com.android.tools.r8.R8 \
  --thread-count "$r8_thread_count" "${r8_arguments[@]}" \
  >"$output_dir/run.log" 2>&1
