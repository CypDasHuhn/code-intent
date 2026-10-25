#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
snapshot="$root/tests/fixtures/syntax/v1-kotlin-trees.json"
generated="$root/sources/syntax-treesitter/src/commonTest/kotlin/dev/cint/v2/syntax/treesitter/V1KotlinSnapshot.kt"
corpus="$root/frozen-v1-corpus/fixtures/sources/kotlin"
manifest="$root/frozen-v1-corpus/manifest.json"

python3 - "$manifest" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as handle:
    manifest = json.load(handle)
if not manifest.get("v1Commit"):
    raise SystemExit("frozen manifest has no v1Commit")
PY

tmp="$(mktemp -d)"
cleanup() { rm -rf "$tmp"; }
trap cleanup EXIT

v1_commit="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["v1Commit"])' "$manifest")"
git -C "$root" archive "$v1_commit" analysis-engine | tar -x -C "$tmp"
"$root/scripts/regen-v1-syntax-snapshot.sh" "$tmp" "$corpus" "$snapshot" "$generated"
"$root/scripts/verify-v1-syntax-snapshot.sh"
echo "wrote $snapshot and $generated"
