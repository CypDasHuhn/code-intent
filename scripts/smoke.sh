#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cli="$root/apps/cli/build/install/cint/bin/cint"
server="$root/apps/server/build/install/cint-server/bin/cint-server"
mcp="$root/apps/mcp/build/install/cint-mcp/bin/cint-mcp"

test_bin() {
    if [[ ! -x "$1" ]]; then
        echo "missing distribution binary: $1" >&2
        exit 1
    fi
}
test_bin "$cli"
test_bin "$server"
test_bin "$mcp"

"$cli" --version | grep -q 'cint'

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

set +e
"$cli" "$work/missing" >"$work/cli.out" 2>"$work/cli.err"
cli_status=$?
"$server" >"$work/server.out" 2>"$work/server.err"
server_status=$?
set -e
[[ $cli_status -eq 3 ]] || { echo "CLI missing-root status $cli_status" >&2; exit 1; }
grep -q 'ROOT_NOT_FOUND' "$work/cli.err"
[[ $server_status -eq 2 ]] || { echo "server usage status $server_status" >&2; exit 1; }
grep -q 'usage: cint-server' "$work/server.err"

"$mcp" --version | grep -q 'code-intent-mcp'
printf '%s\n' \
    '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"smoke","version":"1"}}}' \
    '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}' \
    '{"jsonrpc":"2.0","id":3,"method":"shutdown","params":{}}' \
    | "$mcp" --root "$work" >"$work/mcp.out" 2>"$work/mcp.err"
grep -q 'code-intent-mcp' "$work/mcp.out"
grep -q 'analysis.run' "$work/mcp.out"

sock="$work/cint.sock"
"$server" --socket "$sock" --root "$work" >"$work/live.out" 2>"$work/live.err" &
server_pid=$!
for _ in $(seq 1 100); do
    [[ -S "$sock" ]] && break
    sleep 0.05
done
[[ -S "$sock" ]] || { echo 'live server did not create its socket' >&2; cat "$work/live.err" >&2; exit 1; }
python3 - "$sock" <<'PY'
import json
import socket
import sys

sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
sock.settimeout(10)
sock.connect(sys.argv[1])
reader = sock.makefile('rwb', buffering=0)

def send(message):
    reader.write((json.dumps(message) + '\n').encode())

def receive():
    line = reader.readline()
    if not line:
        raise SystemExit('live server closed before responding')
    return json.loads(line)

send({'jsonrpc': '2.0', 'id': 1, 'method': 'initialize', 'params': {'protocol': 4}})
init = receive()
assert init.get('id') == 1 and 'result' in init, init
send({'jsonrpc': '2.0', 'id': 2, 'method': 'cint/status', 'params': {}})
status = receive()
assert status.get('id') == 2 and 'result' in status, status
send({'jsonrpc': '2.0', 'id': 3, 'method': 'shutdown', 'params': {}})
shutdown = receive()
assert shutdown.get('id') == 3 and shutdown.get('result') is True, shutdown
sock.close()
PY
wait "$server_pid"
echo "smoke passed"
