#!/usr/bin/env bash
set -euo pipefail

bucket=
sha1_file=
unpack=false

while (($#)); do
  case "$1" in
    -b)
      bucket=$2
      shift 2
      ;;
    -s)
      if (($# >= 2)) && [[ "$2" != -* ]]; then
        sha1_file=$2
        shift 2
      else
        shift
      fi
      ;;
    -u)
      unpack=true
      shift
      ;;
    -n)
      shift
      ;;
    -* )
      echo "unsupported download_from_google_storage argument: $1" >&2
      exit 2
      ;;
    *)
      if [ -n "$sha1_file" ]; then
        echo "multiple SHA-1 files supplied: $sha1_file and $1" >&2
        exit 2
      fi
      sha1_file=$1
      shift
      ;;
  esac
done

: "${R8_GCLOUD_PROXY:?R8_GCLOUD_PROXY must name a gcloud-compatible executable}"
[ -n "$bucket" ] || { echo "missing -b bucket" >&2; exit 2; }
[ -f "$sha1_file" ] || { echo "missing -s sha1 file: $sha1_file" >&2; exit 2; }

expected=$(tr -d '[:space:]' < "$sha1_file")
case "$expected" in
  *[!0-9a-fA-F]*|'')
    echo "invalid SHA-1 in $sha1_file" >&2
    exit 1
    ;;
esac
[ "${#expected}" -eq 40 ] || { echo "invalid SHA-1 length in $sha1_file" >&2; exit 1; }

archive=${sha1_file%.sha1}
"$R8_GCLOUD_PROXY" storage cp "gs://$bucket/$expected" "$archive"
actual=$(shasum -a 1 "$archive" | awk '{print $1}')
[ "$actual" = "$expected" ] || {
  echo "SHA-1 mismatch for $archive: expected $expected, got $actual" >&2
  exit 1
}

if $unpack; then
  case "$archive" in
    *.tar.gz|*.tgz)
      tar -xzf "$archive" -C "$(dirname "$archive")"
      ;;
    *.tar)
      tar -xf "$archive" -C "$(dirname "$archive")"
      ;;
    *.zip)
      unzip -q -o "$archive" -d "$(dirname "$archive")"
      ;;
    *)
      echo "unsupported archive format for -u: $archive" >&2
      exit 1
      ;;
  esac
fi
