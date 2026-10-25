#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
snapshot="$root/tests/fixtures/syntax/v1-kotlin-trees.json"
generated="$root/sources/syntax-treesitter/src/commonTest/kotlin/dev/cint/v2/syntax/treesitter/V1KotlinSnapshot.kt"
digest="$root/tests/fixtures/syntax/v1-kotlin-snapshot.sha256"
manifest="$root/frozen-v1-corpus/manifest.json"
corpus="$root/frozen-v1-corpus/fixtures/sources/kotlin"

python3 - "$manifest" "$snapshot" "$generated" "$corpus" "$digest" <<'PY'
import hashlib
import json
import sys

manifest_path, snapshot_path, generated_path, corpus, digest_path = sys.argv[1:]
with open(manifest_path, encoding="utf-8") as handle:
    manifest = json.load(handle)
frozen_commit = manifest["v1Commit"]
with open(snapshot_path, encoding="utf-8") as handle:
    snapshot = json.load(handle)
if snapshot.get("v1Commit") != frozen_commit:
    raise SystemExit(f"snapshot v1Commit {snapshot.get('v1Commit')!r} != frozen {frozen_commit!r}")
with open(generated_path, encoding="utf-8") as handle:
    generated = handle.read()
if f'const val V1_COMMIT: String = "{frozen_commit}"' not in generated:
    raise SystemExit("generated V1KotlinSnapshot.V1_COMMIT does not match the frozen manifest")
for name, entry in snapshot["files"].items():
    with open(f"{corpus}/{name}", encoding="utf-8") as handle:
        expected_source = handle.read()
    if entry["source"] != expected_source:
        raise SystemExit(f"{name}: snapshot source drifted from frozen corpus")
    canonical = []
    for kind, start_byte, end_byte, start_line, start_col, end_line, end_col, named, depth in entry["nodes"]:
        canonical.append(
            f'{"|".join(str(value) for value in (depth, kind, start_byte, end_byte, start_line, start_col, end_line, end_col))}|'
            f'{"1" if named else "0"}\n'
        )
    fingerprint = hashlib.sha256("".join(canonical).encode()).hexdigest()
    if fingerprint != entry["fingerprint"]:
        raise SystemExit(f"{name}: snapshot fingerprint does not match its nodes")
with open(digest_path, encoding="utf-8") as handle:
    expected_digests = dict(reversed(line.split()) for line in handle.read().splitlines() if line.strip())
actual = {
    "v1-kotlin-trees.json": hashlib.sha256(open(snapshot_path, "rb").read()).hexdigest(),
    "V1KotlinSnapshot.kt": hashlib.sha256(open(generated_path, "rb").read()).hexdigest(),
}
if actual != expected_digests:
    raise SystemExit(f"snapshot digests drifted: expected {expected_digests}, got {actual}")
print(f"V1 syntax snapshot self-checks passed for frozen commit {frozen_commit}")
PY

tmp="$(mktemp -d)"
cleanup() { rm -rf "$tmp"; }
trap cleanup EXIT

python3 "$root/tools/v1-syntax-capture/render_snapshot.py" "$snapshot" "$tmp/rendered.kt"
cmp "$generated" "$tmp/rendered.kt"

git -C "$root" archive "$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["v1Commit"])' "$manifest")" analysis-engine | tar -x -C "$tmp"

"$root/scripts/regen-v1-syntax-snapshot.sh" "$tmp" "$corpus" "$tmp/regenerated.json" "$tmp/regenerated.kt"
cmp "$snapshot" "$tmp/regenerated.json"
cmp "$generated" "$tmp/regenerated.kt"
echo "V1 syntax snapshot regenerated from pinned source and matched checked-in files"
