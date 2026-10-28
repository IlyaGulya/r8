#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 4 ]]; then
  echo "Usage: $0 <ignored-r8.jar> <input-dir> <output-dir> <mode>" >&2
  exit 2
fi

input_dir=$2
output_dir=$3
mode=$4
mkdir -p "$output_dir"

cp "$input_dir/expected-base.zip" "$output_dir/base.zip"
cp "$input_dir/expected-feature-1.zip" "$output_dir/feature-1.zip"
cp "$input_dir/expected-mapping.txt" "$output_dir/mapping.txt"
cp "$input_dir/expected-art-profile-1.txt" "$output_dir/art-profile-1.txt"
printf '0.01 real 0.02 user 0.00 sys\n' >"$output_dir/run.log"
cat >"$output_dir/time.txt" <<'EOF'
        User time (seconds): 0.02
        System time (seconds): 0.00
        Elapsed (wall clock) time (h:mm:ss or m:ss): 0:00.01
        Maximum resident set size (kbytes): 1024
EOF

if [[ "$mode" == "diagnostic" ]]; then
  printf 'fake jfr\n' >"$output_dir/r8.jfr"
  printf 'fake jfc\n' >"$output_dir/diagnostic.jfc"
  printf 'fake gc\n' >"$output_dir/gc-safepoint.log"
  printf '<hotspot_log/>\n' >"$output_dir/hotspot-compilation.xml"
  printf '1\t1\t1.0\t1024\tjava\n' >"$output_dir/system-processes.tsv"
elif [[ "$mode" != "no-jfr" ]]; then
  echo "Unknown profile mode: $mode" >&2
  exit 2
fi

outputs=(base.zip feature-1.zip mapping.txt art-profile-1.txt)
if command -v sha256sum >/dev/null; then
  (
    cd "$output_dir"
    sha256sum "${outputs[@]}" >sha256.txt
  )
else
  (
    cd "$output_dir"
    shasum -a 256 "${outputs[@]}" >sha256.txt
  )
fi
