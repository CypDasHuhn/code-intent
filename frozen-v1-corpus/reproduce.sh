#!/usr/bin/env bash
# Reproducibility script for frozen V1 corpus
# Run this script from the repo root to regenerate all frozen artifacts

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENGINE_DIR="$REPO_ROOT/analysis-engine"
TESTING_GROUNDS="$REPO_ROOT/testing-grounds"
OUTPUT_DIR="$REPO_ROOT/frozen-v1-corpus"
CLI_BIN="$ENGINE_DIR/build/install/cint/bin/cint"
SERVER_BIN="$ENGINE_DIR/build/install/cint-server/bin/cint-server"

echo "Building V1 engine..."
cd "$ENGINE_DIR"
./gradlew :cli:installDist :server:installDist --quiet

echo "Capturing CLI outputs..."
mkdir -p "$OUTPUT_DIR"

# CLI outputs for each language (no LSP)
"$CLI_BIN" "$TESTING_GROUNDS/kotlin" --no-lsp > "$OUTPUT_DIR/cli-kotlin.json" 2>/dev/null
"$CLI_BIN" "$TESTING_GROUNDS/kotlin" --no-lsp --all > "$OUTPUT_DIR/cli-kotlin-all.json" 2>/dev/null
"$CLI_BIN" "$TESTING_GROUNDS/typescript" --no-lsp --lang typescript > "$OUTPUT_DIR/cli-typescript.json" 2>/dev/null
"$CLI_BIN" "$TESTING_GROUNDS/javascript" --no-lsp --lang javascript > "$OUTPUT_DIR/cli-javascript.json" 2>/dev/null
"$CLI_BIN" "$TESTING_GROUNDS/csharp" --no-lsp --lang csharp > "$OUTPUT_DIR/cli-csharp.json" 2>/dev/null
"$CLI_BIN" "$TESTING_GROUNDS/vue" --no-lsp --lang vue > "$OUTPUT_DIR/cli-vue.json" 2>/dev/null

echo "Capturing live server protocol transcripts (Unix socket)..."
# Use Python script for protocol captures
cd "$REPO_ROOT"
python3 << 'PYEOF'
import json
import socket
import tempfile
import subprocess
import time
from pathlib import Path

SERVER_BIN = Path("/home/cyp/repos/code-intent/analysis-engine/build/install/cint-server/bin/cint-server")
TESTING_GROUNDS = Path("/home/cyp/repos/code-intent/testing-grounds")
OUTPUT_DIR = Path("/home/cyp/repos/code-intent/frozen-v1-corpus")

kotlin_code = (TESTING_GROUNDS / "kotlin" / "Bookkeeping.kt").read_text()
typescript_code = (TESTING_GROUNDS / "typescript" / "Sample.ts").read_text()

def run_session(name, root_dir, requests):
    with tempfile.TemporaryDirectory() as tmpdir:
        socket_path = Path(tmpdir) / "server.sock"
        cmd = [str(SERVER_BIN), "--socket", str(socket_path), "--root", str(root_dir), "--no-lsp"]
        proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        
        for _ in range(50):
            if socket_path.exists():
                break
            time.sleep(0.1)
        else:
            stdout, stderr = proc.communicate(timeout=2)
            print(f"STDOUT: {stdout.decode()}")
            print(f"STDERR: {stderr.decode()}")
            raise RuntimeError("Server did not create socket")
        
        all_messages = []
        try:
            for i, req in enumerate(requests):
                sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
                sock.settimeout(10.0)
                sock.connect(str(socket_path))
                sock.sendall((json.dumps(req) + "\n").encode())
                
                buffer = ""
                start_time = time.time()
                while time.time() - start_time < 10:
                    try:
                        data = sock.recv(4096)
                        if not data:
                            break
                        buffer += data.decode()
                        while "\n" in buffer:
                            line, buffer = buffer.split("\n", 1)
                            if line.strip():
                                all_messages.append(json.loads(line))
                    except socket.timeout:
                        break
                sock.close()
                time.sleep(0.2)
        finally:
            proc.terminate()
            try:
                proc.wait(timeout=2)
            except:
                proc.kill()
        
        transcript_file = OUTPUT_DIR / f"protocol-{name}.ndjson"
        with open(transcript_file, "w") as f:
            for msg in all_messages:
                f.write(json.dumps(msg) + "\n")
        print(f"  Saved {len(all_messages)} messages to protocol-{name}.ndjson")

# Session 1: Kotlin analyze + config
run_session("kotlin-analyze", TESTING_GROUNDS / "kotlin", [
    {"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {"protocol": 4}},
    {"jsonrpc": "2.0", "id": 2, "method": "textDocument/didOpen", "params": {
        "uri": "file:///test/Bookkeeping.kt", "text": kotlin_code, "version": 1, "languageId": "kotlin"
    }},
    {"jsonrpc": "2.0", "id": 3, "method": "cint/analyze", "params": {"uri": "file:///test/Bookkeeping.kt"}},
    {"jsonrpc": "2.0", "id": 4, "method": "cint/config/read", "params": {}},
    {"jsonrpc": "2.0", "id": 5, "method": "cint/config/append", "params": {
        "packId": "logging", "language": "kotlin", "field": "methods", "value": "audit"
    }},
    {"jsonrpc": "2.0", "id": 6, "method": "cint/analyze", "params": {"uri": "file:///test/Bookkeeping.kt"}},
    {"jsonrpc": "2.0", "id": 7, "method": "cint/config/remove", "params": {
        "packId": "logging", "language": "kotlin", "field": "methods", "value": "audit"
    }},
    {"jsonrpc": "2.0", "id": 8, "method": "shutdown"},
])

# Session 2: TypeScript analyze
run_session("typescript-analyze", TESTING_GROUNDS / "typescript", [
    {"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {"protocol": 4}},
    {"jsonrpc": "2.0", "id": 2, "method": "textDocument/didOpen", "params": {
        "uri": "file:///test/Sample.ts", "text": typescript_code, "version": 1, "languageId": "typescript"
    }},
    {"jsonrpc": "2.0", "id": 3, "method": "cint/analyze", "params": {"uri": "file:///test/Sample.ts"}},
    {"jsonrpc": "2.0", "id": 4, "method": "shutdown"},
])

# Session 3: Config flow
run_session("config-flow", TESTING_GROUNDS / "kotlin", [
    {"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {"protocol": 4}},
    {"jsonrpc": "2.0", "id": 2, "method": "cint/config/read", "params": {}},
    {"jsonrpc": "2.0", "id": 3, "method": "cint/config/append", "params": {
        "packId": "logging", "language": "kotlin", "field": "methods", "value": "trace"
    }},
    {"jsonrpc": "2.0", "id": 4, "method": "cint/config/read", "params": {}},
    {"jsonrpc": "2.0", "id": 5, "method": "cint/config/append", "params": {
        "packId": "validation", "language": "kotlin", "field": "detectThrows", "value": "true"
    }},
    {"jsonrpc": "2.0", "id": 6, "method": "cint/config/read", "params": {}},
    {"jsonrpc": "2.0", "id": 7, "method": "cint/config/remove", "params": {
        "packId": "logging", "language": "kotlin", "field": "methods", "value": "trace"
    }},
    {"jsonrpc": "2.0", "id": 8, "method": "cint/config/read", "params": {}},
    {"jsonrpc": "2.0", "id": 9, "method": "shutdown"},
])

# Session 4: Suggest flow
run_session("suggest-flow", TESTING_GROUNDS / "kotlin", [
    {"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {"protocol": 4}},
    {"jsonrpc": "2.0", "id": 2, "method": "textDocument/didOpen", "params": {
        "uri": "file:///test/Bookkeeping.kt", "text": kotlin_code, "version": 1, "languageId": "kotlin"
    }},
    {"jsonrpc": "2.0", "id": 3, "method": "cint/suggest", "params": {
        "uri": "file:///test/Bookkeeping.kt", "version": 1, "anchorId": "test-anchor"
    }},
    {"jsonrpc": "2.0", "id": 4, "method": "cint/suggest/cancel", "params": {
        "uri": "file:///test/Bookkeeping.kt"
    }},
    {"jsonrpc": "2.0", "id": 5, "method": "shutdown"},
])

# Session 5: Restrictions flow
run_session("restrictions-flow", TESTING_GROUNDS / "kotlin", [
    {"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {"protocol": 4}},
    {"jsonrpc": "2.0", "id": 2, "method": "textDocument/didOpen", "params": {
        "uri": "file:///test/Bookkeeping.kt", "text": kotlin_code, "version": 1, "languageId": "kotlin"
    }},
    {"jsonrpc": "2.0", "id": 3, "method": "cint/restrictions/list", "params": {
        "uri": "file:///test/Bookkeeping.kt"
    }},
    {"jsonrpc": "2.0", "id": 4, "method": "shutdown"},
])

# Session 6: Document lifecycle
run_session("document-lifecycle", TESTING_GROUNDS / "kotlin", [
    {"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {"protocol": 4}},
    {"jsonrpc": "2.0", "id": 2, "method": "textDocument/didOpen", "params": {
        "uri": "file:///test/Bookkeeping.kt", "text": kotlin_code, "version": 1, "languageId": "kotlin"
    }},
    {"jsonrpc": "2.0", "id": 3, "method": "textDocument/didChange", "params": {
        "uri": "file:///test/Bookkeeping.kt", "text": kotlin_code + "\n// changed", "version": 2, "languageId": "kotlin"
    }},
    {"jsonrpc": "2.0", "id": 4, "method": "cint/analyze", "params": {"uri": "file:///test/Bookkeeping.kt"}},
    {"jsonrpc": "2.0", "id": 5, "method": "textDocument/didClose", "params": {"uri": "file:///test/Bookkeeping.kt"}},
    {"jsonrpc": "2.0", "id": 6, "method": "shutdown"},
])

print("Unix socket protocol captures complete!")
PYEOF

echo "Capturing HTTP server protocol (health, analyze)..."
cd "$REPO_ROOT"
python3 << 'PYEOF'
import json
import subprocess
import time
import requests
from pathlib import Path

SERVER_BIN = Path("/home/cyp/repos/code-intent/analysis-engine/build/install/cint-server/bin/cint-server")
TESTING_GROUNDS = Path("/home/cyp/repos/code-intent/testing-grounds")
OUTPUT_DIR = Path("/home/cyp/repos/code-intent/frozen-v1-corpus")

proc = subprocess.Popen(
    [str(SERVER_BIN), "--host", "127.0.0.1", "--port", "18765", "--root", str(TESTING_GROUNDS / "kotlin"), "--no-lsp"],
    stdout=subprocess.PIPE, stderr=subprocess.PIPE
)

try:
    for _ in range(50):
        try:
            resp = requests.get("http://127.0.0.1:18765/health", timeout=1)
            if resp.status_code == 200:
                break
        except:
            time.sleep(0.1)
    else:
        raise RuntimeError("HTTP server did not start")
    
    # Health
    resp = requests.get("http://127.0.0.1:18765/health")
    health = resp.json()
    with open(OUTPUT_DIR / "protocol-http-health.ndjson", "w") as f:
        f.write(json.dumps({"request": {"method": "GET", "path": "/health"}, "response": health}) + "\n")
    
    # Analyze
    kotlin_code = (TESTING_GROUNDS / "kotlin" / "Bookkeeping.kt").read_text()
    resp = requests.post("http://127.0.0.1:18765/analyze", json={
        "path": "Bookkeeping.kt",
        "root": str(TESTING_GROUNDS / "kotlin"),
        "text": kotlin_code,
        "language": "kotlin"
    })
    analyze = resp.json()
    with open(OUTPUT_DIR / "protocol-http-analyze.ndjson", "w") as f:
        f.write(json.dumps({
            "request": {"method": "POST", "path": "/analyze", "body": {"path": "Bookkeeping.kt", "language": "kotlin"}},
            "response": analyze
        }) + "\n")
    
    print("  Saved HTTP protocol transcripts")
    
finally:
    proc.terminate()
    try:
        proc.wait(timeout=2)
    except:
        proc.kill()
PYEOF

echo "All captures complete! Artifacts in $OUTPUT_DIR"
ls -la "$OUTPUT_DIR"