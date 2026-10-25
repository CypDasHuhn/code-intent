#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
repo="$(cd "$root/.." && pwd)"
snapshot="$root/tests/fixtures/syntax/v1-kotlin-trees.json"
generated="$root/syntax-treesitter/src/commonTest/kotlin/dev/cint/v2/syntax/treesitter/V1KotlinSnapshot.kt"
corpus="$repo/frozen-v1-corpus/fixtures/sources/kotlin"
v1_commit="3ccea9e9cdb5a4e652182136866b24929bbed0b3"

python3 - "$repo/frozen-v1-corpus/manifest.json" "$v1_commit" <<'PY'
import json
import sys

manifest_path, expected = sys.argv[1], sys.argv[2]
with open(manifest_path, encoding="utf-8") as handle:
    manifest = json.load(handle)
if manifest.get("v1Commit") != expected:
    raise SystemExit(f"frozen manifest v1Commit {manifest.get('v1Commit')!r} != {expected!r}")
PY
git -C "$repo" diff --quiet "$v1_commit" HEAD -- analysis-engine || {
  echo "analysis-engine does not match frozen V1 commit $v1_commit" >&2
  exit 1
}
if [ -n "$(git -C "$repo" status --porcelain -- analysis-engine)" ]; then
  echo "analysis-engine has uncommitted changes; refusing to capture drifted V1 source" >&2
  exit 1
fi

"$root/scripts/regen-v1-syntax-snapshot.sh" "$repo" "$corpus" "$snapshot" "$generated"
"$root/scripts/verify-v1-syntax-snapshot.sh"
echo "wrote $snapshot and $generated"
