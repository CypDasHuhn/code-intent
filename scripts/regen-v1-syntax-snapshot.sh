#!/usr/bin/env bash
set -euo pipefail

if [ "$#" -ne 4 ]; then
  echo "usage: $0 <source-root> <corpus-dir> <snapshot-out> <generated-out>" >&2
  exit 2
fi

source_root="$1"
corpus="$2"
snapshot="$3"
generated="$4"
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
harness="$root/tools/v1-syntax-capture/V1SyntaxCaptureTest.kt"
renderer="$root/tools/v1-syntax-capture/render_snapshot.py"
v1_test_dir="$source_root/analysis-engine/languages/treesitter/src/test/kotlin/dev/cint/treesitter/tmp"

mkdir -p "$v1_test_dir" "$(dirname "$snapshot")" "$(dirname "$generated")"
cp "$harness" "$v1_test_dir/V1SyntaxCaptureTest.kt"
cleanup() {
  rm -f "$v1_test_dir/V1SyntaxCaptureTest.kt"
  rmdir "$v1_test_dir" 2>/dev/null || true
}
trap cleanup EXIT

CINT_V1_CORPUS="$corpus" \
CINT_V1_SNAPSHOT_OUT="$snapshot" \
CINT_V1_COMMIT="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["v1Commit"])' "$root/frozen-v1-corpus/manifest.json")" \
  "$source_root/analysis-engine/gradlew" --no-daemon -p "$source_root/analysis-engine" \
  :treesitter:test --tests 'dev.cint.treesitter.tmp.V1SyntaxCaptureTest' --rerun-tasks

python3 "$renderer" "$snapshot" "$generated"
