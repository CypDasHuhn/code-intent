#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
version="${CINT_ENGINE_VERSION:-0.1.0}"
release_root="$root/build/release"
staging="$release_root/cint-engine-$version"
artifact="$release_root/cint-engine-$version.tar.gz"
schema="$root/orchestration/ipc/protocol.schema.json"

cli="$root/apps/cli/build/install/cint"
server="$root/apps/server/build/install/cint-server"
mcp="$root/apps/mcp/build/install/cint-mcp"

for path in "$cli" "$server" "$mcp" "$schema"; do
  if [ ! -e "$path" ]; then
    echo "FAIL: missing $path; run 'just install' first" >&2
    exit 2
  fi
done

rm -rf "$staging" "$artifact" "$artifact.sha256"
mkdir -p "$staging/cint" "$staging/cint-server" "$staging/cint-mcp"
cp -a "$cli/." "$staging/cint/"
cp -a "$server/." "$staging/cint-server/"
cp -a "$mcp/." "$staging/cint-mcp/"
cp "$schema" "$staging/protocol.schema.json"
printf '%s\n' "$version" > "$staging/VERSION"

python3 - "$schema" "$staging/RELEASE.json" "$version" "$root" <<'PY'
import json
import subprocess
import sys

schema_path, manifest_path, version, root = sys.argv[1:]
with open(schema_path, encoding="utf-8") as handle:
    schema = json.load(handle)
try:
    commit = subprocess.check_output(
        ["git", "-C", root, "rev-parse", "HEAD"], text=True, stderr=subprocess.DEVNULL
    ).strip()
except Exception:
    commit = None
manifest = {
    "name": "cint-engine",
    "version": version,
    "protocolVersion": schema["x-protocol-version"],
    "protocolFingerprint": schema["x-schema-fingerprint"],
    "supportedProtocolVersions": schema.get("x-supported-versions"),
    "sourceCommit": commit,
    "contents": ["cint", "cint-server", "cint-mcp", "protocol.schema.json", "VERSION"],
}
with open(manifest_path, "w", encoding="utf-8") as handle:
    json.dump(manifest, handle, indent=2, sort_keys=True)
    handle.write("\n")
PY

tar -czf "$artifact" -C "$release_root" "cint-engine-$version"
(
  cd "$release_root"
  sha256sum "$(basename "$artifact")" > "$(basename "$artifact").sha256"
)

echo "$artifact"
echo "$artifact.sha256"
cat "$artifact.sha256"
