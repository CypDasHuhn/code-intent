#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
CORPUS_DIR="$SCRIPT_DIR"
FIXTURES_DIR="$CORPUS_DIR/fixtures"
V1_COMMIT_REF="3ccea9e"
V1_COMMIT="$(git -C "$REPO_ROOT" rev-parse "$V1_COMMIT_REF^{commit}")"
STAGE_DIR="$(mktemp -d "$REPO_ROOT/.frozen-v1-corpus.stage.XXXXXX")"
WORK_DIR="$(mktemp -d)"
V1_ROOT="$WORK_DIR/v1"
V1_ENGINE_DIR="$V1_ROOT/analysis-engine"
CLI_BIN="$V1_ENGINE_DIR/build/install/cint/bin/cint"
SERVER_BIN="$V1_ENGINE_DIR/build/install/cint-server/bin/cint-server"

cleanup() {
  if [[ -d "$STAGE_DIR" ]]; then
    rm -rf "$STAGE_DIR"
  fi
  if [[ -d "$WORK_DIR" ]]; then
    rm -rf "$WORK_DIR"
  fi
}
trap cleanup EXIT INT TERM

atomic_exchange() {
  python3 - "$1" "$2" <<'PY'
import ctypes
import os
import sys

libc = ctypes.CDLL(None, use_errno=True)
result = libc.renameat2(
    -100,
    os.fsencode(sys.argv[1]),
    -100,
    os.fsencode(sys.argv[2]),
    2,
)
if result != 0:
    error = ctypes.get_errno()
    raise SystemExit(f"renameat2 exchange failed: {os.strerror(error)}")
PY
}

if ! git -C "$REPO_ROOT" cat-file -e "$V1_COMMIT^{commit}"; then
  echo "missing V1 commit: $V1_COMMIT" >&2
  exit 1
fi
if [[ ! -d "$FIXTURES_DIR" ]]; then
  echo "missing fixtures directory: $FIXTURES_DIR" >&2
  exit 1
fi

if [[ ! -f "$CORPUS_DIR/manifest.json" ]]; then
  echo "missing baseline manifest: $CORPUS_DIR/manifest.json" >&2
  exit 1
fi
shopt -s dotglob nullglob
cp -a "$CORPUS_DIR"/* "$STAGE_DIR"/
shopt -u dotglob nullglob
rm -rf "$STAGE_DIR/__pycache__"
PYTHONDONTWRITEBYTECODE=1 python3 "$STAGE_DIR/verify.py" \
  --corpus-dir "$STAGE_DIR" \
  --fixture-baseline "$CORPUS_DIR/manifest.json"
(
  cd "$STAGE_DIR"
  rm -f cli-*.json protocol-*.ndjson protocol-*.session.json \
    renderer-visible-v1.json manifest.json
)

run_cli() {
  local target="$1"
  shift
  local stderr_file="$WORK_DIR/cli-${target##*/}.stderr"
  if ! "$CLI_BIN" "$@" > "$WORK_DIR/cli-output.json" 2> "$stderr_file"; then
    echo "CLI capture failed for $target" >&2
    cat "$stderr_file" >&2
    exit 1
  fi
  mv "$WORK_DIR/cli-output.json" "$STAGE_DIR/$target"
}

make_cli_root() {
  local language="$1"
  local name="$2"
  local root="$WORK_DIR/cli-$name"
  cp -a "$STAGE_DIR/fixtures/sources/$language" "$root"
  rm -rf "$root/.cint"
  printf '%s\n' "$root"
}

mkdir -p "$V1_ROOT"
git -C "$REPO_ROOT" archive "$V1_COMMIT" analysis-engine | tar -x -C "$V1_ROOT"

echo "Pinned V1 commit: $V1_COMMIT"
echo "Building pinned V1 engine..."
(
  cd "$V1_ENGINE_DIR"
  ./gradlew :cli:installDist :server:installDist
)

for binary in "$CLI_BIN" "$SERVER_BIN"; do
  if [[ ! -x "$binary" ]]; then
    echo "missing executable: $binary" >&2
    exit 1
  fi
done

echo "Capturing CLI outputs from isolated fixture copies..."
kotlin_root="$(make_cli_root kotlin kotlin)"
kotlin_all_root="$(make_cli_root kotlin kotlin-all)"
typescript_root="$(make_cli_root typescript typescript)"
javascript_root="$(make_cli_root javascript javascript)"
csharp_root="$(make_cli_root csharp csharp)"
vue_root="$(make_cli_root vue vue)"
run_cli "cli-kotlin.json" "$kotlin_root" --no-lsp
run_cli "cli-kotlin-all.json" "$kotlin_all_root" --no-lsp --all
run_cli "cli-typescript.json" "$typescript_root" --no-lsp --lang typescript
run_cli "cli-javascript.json" "$javascript_root" --no-lsp --lang javascript
run_cli "cli-csharp.json" "$csharp_root" --no-lsp --lang csharp
run_cli "cli-vue.json" "$vue_root" --no-lsp --lang vue

echo "Capturing live and HTTP protocol from frozen fixtures..."
PYTHONDONTWRITEBYTECODE=1 python3 "$STAGE_DIR/capture.py" \
  --server-bin "$SERVER_BIN" \
  --fixtures "$STAGE_DIR/fixtures" \
  --output-dir "$STAGE_DIR" \
  --v1-commit "$V1_COMMIT"

echo "Verifying and replaying staged corpus..."
PYTHONDONTWRITEBYTECODE=1 python3 "$STAGE_DIR/verify.py" \
  --corpus-dir "$STAGE_DIR" \
  --server-bin "$SERVER_BIN"

chmod --reference="$CORPUS_DIR" "$STAGE_DIR"
atomic_exchange "$CORPUS_DIR" "$STAGE_DIR"

echo "All captures complete: $CORPUS_DIR"
ls -la "$CORPUS_DIR"
