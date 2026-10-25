#!/usr/bin/env python3
import argparse
import hashlib
import json
import shutil
import socket
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
from pathlib import Path

PROTOCOL_VERSION = 4
FIXTURES_TOKEN = "<CORPUS_ROOT>/fixtures/sources"
SETTLE_SECONDS = 0.2
HTTP_START_ATTEMPTS = 5


class CaptureError(RuntimeError):
    pass


class Client:
    def __init__(self, sock):
        self.sock = sock
        self.buffer = ""
        self.messages = []

    def send(self, message):
        self.sock.sendall((json.dumps(message) + "\n").encode("utf-8"))

    def _read(self, deadline):
        remaining = deadline - time.time()
        if remaining <= 0:
            return False
        self.sock.settimeout(min(0.25, remaining))
        try:
            data = self.sock.recv(65536)
        except socket.timeout:
            return False
        if not data:
            raise CaptureError("server closed the socket before the transcript completed")
        self.buffer += data.decode("utf-8")
        while "\n" in self.buffer:
            line, self.buffer = self.buffer.split("\n", 1)
            if line.strip():
                self.messages.append(json.loads(line))
        return True

    def wait_for_id(self, request_id, timeout):
        deadline = time.time() + timeout
        while True:
            for message in self.messages:
                if message.get("id") == request_id and ("result" in message or "error" in message):
                    return message
            if not self._read(deadline):
                if time.time() >= deadline:
                    raise CaptureError(f"timed out waiting for response id {request_id}")
                time.sleep(0.01)

    def wait_for_method(self, method, start_index, timeout, predicate):
        deadline = time.time() + timeout
        while True:
            for message in self.messages[start_index:]:
                if message.get("method") == method and predicate(message):
                    return message
            if not self._read(deadline):
                if time.time() >= deadline:
                    raise CaptureError(f"timed out waiting for notification {method}")
                time.sleep(0.01)

    def drain(self, quiet_seconds):
        deadline = time.time() + quiet_seconds
        while time.time() < deadline:
            self._read(deadline)

    def drain_until_quiet(self, quiet_seconds):
        last_count = len(self.messages)
        deadline = time.time() + quiet_seconds
        while time.time() < deadline:
            if not self._read(deadline):
                return
            if len(self.messages) != last_count:
                last_count = len(self.messages)
                deadline = time.time() + quiet_seconds


def normalize(value, root, token):
    root_text = str(root)
    if isinstance(value, str):
        return value.replace(root_text, token)
    if isinstance(value, list):
        return [normalize(item, root, token) for item in value]
    if isinstance(value, dict):
        return {key: normalize(item, root, token) for key, item in value.items()}
    return value


def copy_fixture(source, destination):
    shutil.copytree(source, destination)
    cint_dir = destination / ".cint"
    if cint_dir.exists():
        shutil.rmtree(cint_dir)


def load_scenarios(fixtures):
    data = json.loads((fixtures / "scenarios.json").read_text(encoding="utf-8"))
    if data.get("formatVersion") != 1:
        raise CaptureError("fixtures/scenarios.json: unsupported formatVersion")
    return data["scenarios"]


def prepare_scenario(fixtures, scenarios, name, work_dir):
    if name not in scenarios:
        raise CaptureError(f"fixtures/scenarios.json: missing scenario {name}")
    spec = scenarios[name]
    source = spec["source"]
    root = work_dir / name
    copy_fixture(fixtures / "sources" / source, root)
    overlay = spec.get("overlay")
    if overlay is not None:
        overlay_root = fixtures / "overlays" / overlay
        if not overlay_root.is_dir():
            raise CaptureError(f"fixtures/scenarios.json: missing overlay {overlay}")
        shutil.copytree(overlay_root / ".cint", root / ".cint")
    return root


def scenario_token(scenarios, name):
    return FIXTURES_TOKEN + "/" + scenarios[name]["source"]


def start_live_server(server_bin, root, work_dir, name):
    socket_path = work_dir / f"{name}.sock"
    stdout_path = work_dir / f"{name}.stdout"
    stderr_path = work_dir / f"{name}.stderr"
    stdout = stdout_path.open("w")
    stderr = stderr_path.open("w")
    process = subprocess.Popen(
        [
            str(server_bin),
            "--socket",
            str(socket_path),
            "--root",
            str(root),
            "--no-lsp",
        ],
        stdout=stdout,
        stderr=stderr,
    )
    deadline = time.time() + 15
    while time.time() < deadline:
        if socket_path.exists():
            return process, socket_path, stdout, stderr, stderr_path
        if process.poll() is not None:
            stderr.close()
            raise CaptureError(
                f"live server exited during startup ({name}):\n{stderr_path.read_text()}"
            )
        time.sleep(0.05)
    process.terminate()
    stdout.close()
    stderr.close()
    raise CaptureError(f"live server did not create {socket_path}")


def stop_live_server(process, stdout, stderr):
    process.terminate()
    try:
        process.wait(timeout=5)
    except subprocess.TimeoutExpired:
        process.kill()
        process.wait(timeout=5)
    stdout.close()
    stderr.close()


def exchange(client, step):
    start = len(client.messages)
    message = step["request"]
    client.send(message)
    if "id" in message:
        response = client.wait_for_id(message["id"], step.get("timeout", 20))
        wait = step.get("wait")
        if wait is not None:
            method, predicate = wait
            client.wait_for_method(method, start, step.get("timeout", 20), predicate)
        return response
    wait = step.get("wait")
    if wait is not None:
        method, predicate = wait
        client.wait_for_method(method, start, step.get("timeout", 20), predicate)
    else:
        client.drain(step.get("drain", 0.3))
    return None


def run_live_session(server_bin, work_dir, output_dir, name, root, token, steps):
    process, socket_path, stdout, stderr, stderr_path = start_live_server(
        server_bin, root, work_dir, name
    )
    sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    sock.settimeout(20)
    try:
        sock.connect(str(socket_path))
        client = Client(sock)
        responses = {}
        normalized_steps = []
        request_ids = []
        for step in steps:
            start = len(client.messages)
            response = exchange(client, step)
            client.drain_until_quiet(SETTLE_SECONDS)
            end = len(client.messages)
            if response is not None:
                responses[response["id"]] = response
            request = step["request"]
            if "id" in request:
                request_ids.append(request["id"])
            normalized_steps.append(
                {
                    "request": normalize(request, root, token),
                    "messages": [normalize(item, root, token) for item in client.messages[start:end]],
                }
            )
        expected_ids = set(request_ids)
        if len(request_ids) != len(expected_ids) or request_ids != sorted(request_ids):
            raise CaptureError(f"{name}: request ids must be unique and increasing, got {request_ids}")
        response_ids = [message["id"] for message in client.messages if "id" in message]
        if len(response_ids) != len(set(response_ids)) or response_ids != sorted(response_ids):
            raise CaptureError(
                f"{name}: response ids must be unique and increasing, got {response_ids}"
            )
        missing = sorted(expected_ids - set(responses))
        extra = sorted(set(responses) - expected_ids)
        if missing or extra:
            raise CaptureError(
                f"{name}: response id mismatch missing={missing} extra={extra}\n"
                + "\n".join(json.dumps(item) for item in client.messages)
            )
        for step in steps:
            request_id = step["request"].get("id")
            if request_id is None:
                continue
            response = responses[request_id]
            expected_error = step.get("expect_error")
            if expected_error is not None:
                actual = response.get("error", {}).get("message", "")
                if expected_error not in actual:
                    raise CaptureError(
                        f"{name}: id {request_id} expected error containing {expected_error!r}, got {response}"
                    )
            elif "error" in response:
                raise CaptureError(
                    f"{name}: id {request_id} returned an unexpected error: {response['error']}"
                )
            check = step.get("check")
            if check is not None:
                check(response)
        normalized = [normalize(item, root, token) for item in client.messages]
        target = output_dir / f"protocol-{name}.ndjson"
        with target.open("w", encoding="utf-8") as handle:
            for item in normalized:
                handle.write(json.dumps(item, ensure_ascii=False) + "\n")
        session = {
            "formatVersion": 1,
            "protocol": PROTOCOL_VERSION,
            "scenario": name,
            "sourceFixture": "fixtures/sources/" + token.rsplit("/", 1)[-1],
            "steps": normalized_steps,
        }
        session_target = output_dir / f"protocol-{name}.session.json"
        session_target.write_text(
            json.dumps(session, indent=2, sort_keys=True) + "\n", encoding="utf-8"
        )
        print(f"  {target.name}: {len(normalized)} messages")
        print(f"  {session_target.name}: {len(normalized_steps)} steps")
    except Exception:
        try:
            stderr.flush()
        except Exception:
            pass
        detail = stderr_path.read_text() if stderr_path.exists() else ""
        raise CaptureError(f"{name} failed:\n{detail}") from None
    finally:
        sock.close()
        stop_live_server(process, stdout, stderr)


def wait_for_http(url, timeout):
    deadline = time.time() + timeout
    last_error = None
    while time.time() < deadline:
        try:
            with urllib.request.urlopen(url, timeout=1) as response:
                return json.loads(response.read().decode("utf-8"))
        except Exception as error:
            last_error = error
            time.sleep(0.1)
    raise CaptureError(f"HTTP server did not become healthy: {last_error}")


def free_port():
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


def capture_http_attempt(server_bin, work_dir, output_dir, root, token, attempt):
    port = free_port()
    stdout_path = work_dir / f"http-{attempt}.stdout"
    stderr_path = work_dir / f"http-{attempt}.stderr"
    with stdout_path.open("w") as stdout, stderr_path.open("w") as stderr:
        process = subprocess.Popen(
            [
                str(server_bin),
                "--host",
                "127.0.0.1",
                "--port",
                str(port),
                "--root",
                str(root),
                "--no-lsp",
            ],
            stdout=stdout,
            stderr=stderr,
        )
        try:
            deadline = time.time() + 10
            health = None
            while time.time() < deadline:
                if process.poll() is not None:
                    stderr.flush()
                    raise CaptureError(
                        f"HTTP server exited during startup on port {port}:\n"
                        + stderr_path.read_text()
                    )
                try:
                    health = wait_for_http(f"http://127.0.0.1:{port}/health", 0.5)
                    break
                except CaptureError:
                    time.sleep(0.1)
            if health is None:
                raise CaptureError(f"HTTP server did not become healthy on port {port}")

            health_record = {
                "request": {"method": "GET", "path": "/health"},
                "response": health,
            }
            (output_dir / "protocol-http-health.ndjson").write_text(
                json.dumps(normalize(health_record, root, token)) + "\n",
                encoding="utf-8",
            )

            text = (root / "Bookkeeping.kt").read_text(encoding="utf-8")
            request_body = {
                "path": "Bookkeeping.kt",
                "root": str(root),
                "text": text,
                "language": "kotlin",
            }
            request = urllib.request.Request(
                f"http://127.0.0.1:{port}/analyze",
                data=json.dumps(request_body).encode("utf-8"),
                headers={"Content-Type": "application/json"},
                method="POST",
            )
            try:
                with urllib.request.urlopen(request, timeout=30) as response:
                    status = response.status
                    payload = json.loads(response.read().decode("utf-8"))
            except urllib.error.HTTPError as error:
                raise CaptureError(
                    f"HTTP /analyze failed with {error.code}: {error.read().decode('utf-8')}"
                ) from None
            if status != 200:
                raise CaptureError(f"HTTP /analyze returned {status}")
            analyze_record = {
                "request": {
                    "method": "POST",
                    "path": "/analyze",
                    "body": {
                        "path": "Bookkeeping.kt",
                        "root": str(root),
                        "language": "kotlin",
                    },
                },
                "response": payload,
            }
            (output_dir / "protocol-http-analyze.ndjson").write_text(
                json.dumps(normalize(analyze_record, root, token)) + "\n",
                encoding="utf-8",
            )
            session = {
                "formatVersion": 1,
                "protocol": PROTOCOL_VERSION,
                "scenario": "http",
                "transport": "http",
                "portMode": "ephemeral-retry",
                "steps": [
                    {
                        "request": normalize(health_record["request"], root, token),
                        "response": normalize(health_record["response"], root, token),
                    },
                    {
                        "request": normalize(analyze_record["request"], root, token),
                        "response": normalize(analyze_record["response"], root, token),
                    },
                ],
            }
            (output_dir / "protocol-http.session.json").write_text(
                json.dumps(session, indent=2, sort_keys=True) + "\n", encoding="utf-8"
            )
            print(f"  protocol-http-health.ndjson: {health}")
            print("  protocol-http-analyze.ndjson: captured")
            print("  protocol-http.session.json: 2 steps")
        finally:
            process.terminate()
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)


def run_http_capture(server_bin, work_dir, output_dir, root, token):
    last_error = None
    for attempt in range(1, HTTP_START_ATTEMPTS + 1):
        try:
            capture_http_attempt(
                server_bin, work_dir, output_dir, root, token, attempt
            )
            return
        except CaptureError as error:
            last_error = error
            time.sleep(0.1)
    raise CaptureError(
        f"HTTP capture failed after {HTTP_START_ATTEMPTS} attempts: {last_error}"
    )


def candidate_waiter(uri, version):
    def predicate(message):
        params = message.get("params", {})
        return params.get("uri") == uri and params.get("version") == version

    return ("cint/publishCandidates", predicate)


def main(argv):
    parser = argparse.ArgumentParser()
    parser.add_argument("--server-bin", required=True, type=Path)
    parser.add_argument("--fixtures", required=True, type=Path)
    parser.add_argument("--output-dir", required=True, type=Path)
    parser.add_argument("--v1-commit", required=True)
    args = parser.parse_args(argv)

    server_bin = args.server_bin.resolve()
    fixtures = args.fixtures.resolve()
    output_dir = args.output_dir.resolve()
    output_dir.mkdir(parents=True, exist_ok=True)
    scenarios = load_scenarios(fixtures)

    with tempfile.TemporaryDirectory() as temporary:
        work_dir = Path(temporary)
        kotlin_analyze_root = prepare_scenario(fixtures, scenarios, "kotlin-analyze", work_dir)
        typescript_analyze_root = prepare_scenario(
            fixtures, scenarios, "typescript-analyze", work_dir
        )
        config_root = prepare_scenario(fixtures, scenarios, "config-flow", work_dir)
        suggest_root = prepare_scenario(fixtures, scenarios, "suggest-flow", work_dir)
        disabled_root = prepare_scenario(
            fixtures, scenarios, "suggest-disabled-flow", work_dir
        )
        restrictions_root = prepare_scenario(
            fixtures, scenarios, "restrictions-flow", work_dir
        )
        lifecycle_root = prepare_scenario(
            fixtures, scenarios, "document-lifecycle", work_dir
        )
        status_root = prepare_scenario(fixtures, scenarios, "status-flow", work_dir)
        error_root = prepare_scenario(fixtures, scenarios, "error-flow", work_dir)
        http_root = prepare_scenario(fixtures, scenarios, "http-analyze", work_dir)

        restrictions_uri = "file:///test/Bookkeeping.kt"
        bookkeeping = (kotlin_analyze_root / "Bookkeeping.kt").read_text(encoding="utf-8")
        sample_ts = (typescript_analyze_root / "Sample.ts").read_text(encoding="utf-8")
        neighbours = (suggest_root / "NeighboursCli.kt").read_text(encoding="utf-8")
        bookkeeping_uri = "file:///test/Bookkeeping.kt"
        typescript_uri = "file:///test/Sample.ts"
        neighbours_uri = "file:///test/NeighboursCli.kt"

        def result_is(expected):
            def check(response):
                if response.get("result") != expected:
                    raise CaptureError(f"expected result {expected!r}, got {response}")

            return check

        def suggest_enabled(response):
            result = response.get("result", {})
            if result.get("accepted") is not True or result.get("count", 0) < 1:
                raise CaptureError(f"suggest was not enabled as expected: {response}")
            if "restrictions" not in result:
                raise CaptureError(f"protocol 4 suggest response lacks restrictions: {response}")

        def suggest_disabled(response):
            result = response.get("result", {})
            if result.get("disabled") is not True:
                raise CaptureError(f"suggest was not disabled as expected: {response}")

        def restrictions_present(response):
            records = response.get("result", {}).get("restrictions", [])
            if not records:
                raise CaptureError(f"restriction fixture was not listed: {response}")

        def restrictions_removed(response):
            if response.get("result", {}).get("accepted") is not True:
                raise CaptureError(f"restriction removal was not accepted: {response}")

        def restrictions_empty(response):
            if response.get("result", {}).get("restrictions") != []:
                raise CaptureError(f"restriction fixture survived removal: {response}")

        run_live_session(
            server_bin,
            work_dir,
            output_dir,
            "kotlin-analyze",
            kotlin_analyze_root,
            scenario_token(scenarios, "kotlin-analyze"),
            [
                {
                    "request": {
                        "jsonrpc": "2.0",
                        "id": 1,
                        "method": "initialize",
                        "params": {"protocol": PROTOCOL_VERSION},
                    }
                },
                {
                    "request": {
                        "jsonrpc": "2.0",
                        "method": "textDocument/didOpen",
                        "params": {
                            "uri": bookkeeping_uri,
                            "text": bookkeeping,
                            "version": 1,
                            "languageId": "kotlin",
                        },
                    },
                    "wait": candidate_waiter(bookkeeping_uri, 1),
                },
                {
                    "request": {
                        "jsonrpc": "2.0",
                        "id": 2,
                        "method": "cint/analyze",
                        "params": {"uri": bookkeeping_uri},
                    },
                    "check": result_is(True),
                },
                {
                    "request": {
                        "jsonrpc": "2.0",
                        "id": 3,
                        "method": "cint/config/read",
                        "params": {},
                    }
                },
                {
                    "request": {
                        "jsonrpc": "2.0",
                        "id": 4,
                        "method": "cint/config/append",
                        "params": {
                            "packId": "logging",
                            "language": "kotlin",
                            "field": "methods",
                            "value": "audit",
                        },
                    }
                },
                {
                    "request": {
                        "jsonrpc": "2.0",
                        "id": 5,
                        "method": "cint/analyze",
                        "params": {"uri": bookkeeping_uri},
                    },
                    "check": result_is(True),
                },
                {
                    "request": {
                        "jsonrpc": "2.0",
                        "id": 6,
                        "method": "cint/config/remove",
                        "params": {
                            "packId": "logging",
                            "language": "kotlin",
                            "field": "methods",
                            "value": "audit",
                        },
                    }
                },
                {
                    "request": {
                        "jsonrpc": "2.0",
                        "id": 7,
                        "method": "shutdown",
                        "params": {},
                    },
                    "check": result_is(True),
                },
            ],
        )

        run_live_session(
            server_bin,
            work_dir,
            output_dir,
            "typescript-analyze",
            typescript_analyze_root,
            scenario_token(scenarios, "typescript-analyze"),
            [
                {
                    "request": {
                        "jsonrpc": "2.0",
                        "id": 1,
                        "method": "initialize",
                        "params": {"protocol": PROTOCOL_VERSION},
                    }
                },
                {
                    "request": {
                        "jsonrpc": "2.0",
                        "method": "textDocument/didOpen",
                        "params": {
                            "uri": typescript_uri,
                            "text": sample_ts,
                            "version": 1,
                            "languageId": "typescript",
                        },
                    },
                    "wait": candidate_waiter(typescript_uri, 1),
                },
                {
                    "request": {
                        "jsonrpc": "2.0",
                        "id": 2,
                        "method": "cint/analyze",
                        "params": {"uri": typescript_uri},
                    },
                    "check": result_is(True),
                },
                {
                    "request": {
                        "jsonrpc": "2.0",
                        "id": 3,
                        "method": "shutdown",
                        "params": {},
                    },
                    "check": result_is(True),
                },
            ],
        )

        run_live_session(
            server_bin,
            work_dir,
            output_dir,
            "config-flow",
            config_root,
            scenario_token(scenarios, "config-flow"),
            [
                {
                    "request": {
                        "jsonrpc": "2.0",
                        "id": 1,
                        "method": "initialize",
                        "params": {"protocol": PROTOCOL_VERSION},
                    }
                },
                {
                    "request": {
                        "jsonrpc": "2.0",
                        "id": 2,
                        "method": "cint/config/read",
                        "params": {},
                    }
                },
                {
                    "request": {
                        "jsonrpc": "2.0",
                        "id": 3,
                        "method": "cint/config/append",
                        "params": {
                            "packId": "validation",
                            "language": "kotlin",
                            "field": "predicates",
                            "value": "fixturePredicate",
                        },
                    }
                },
                {
                    "request": {
                        "jsonrpc": "2.0",
                        "id": 4,
                        "method": "cint/config/read",
                        "params": {},
                    }
                },
                {
                    "request": {
                        "jsonrpc": "2.0",
                        "id": 5,
                        "method": "cint/config/append",
                        "params": {
                            "packId": "logging",
                            "language": "kotlin",
                            "field": "methods",
                            "value": "trace",
                        },
                    }
                },
                {
                    "request": {
                        "jsonrpc": "2.0",
                        "id": 6,
                        "method": "cint/config/read",
                        "params": {},
                    }
                },
                {
                    "request": {
                        "jsonrpc": "2.0",
                        "id": 7,
                        "method": "cint/config/remove",
                        "params": {
                            "packId": "validation",
                            "language": "kotlin",
                            "field": "predicates",
                            "value": "fixturePredicate",
                        },
                    }
                },
                {
                    "request": {
                        "jsonrpc": "2.0",
                        "id": 8,
                        "method": "cint/config/read",
                        "params": {},
                    }
                },
                {
                    "request": {
                        "jsonrpc": "2.0",
                        "id": 9,
                        "method": "shutdown",
                        "params": {},
                    },
                    "check": result_is(True),
                },
            ],
        )

        run_live_session(
            server_bin,
            work_dir,
            output_dir,
            "suggest-flow",
            suggest_root,
            scenario_token(scenarios, "suggest-flow"),
            [
                {
                    "request": {
                        "jsonrpc": "2.0",
                        "id": 1,
                        "method": "initialize",
                        "params": {"protocol": PROTOCOL_VERSION},
                    }
                },
                {
                    "request": {
                        "jsonrpc": "2.0",
                        "method": "textDocument/didOpen",
                        "params": {
                            "uri": neighbours_uri,
                            "text": neighbours,
                            "version": 1,
                            "languageId": "kotlin",
                        },
                    },
                    "wait": candidate_waiter(neighbours_uri, 1),
                },
                {
                    "request": {
                        "jsonrpc": "2.0",
                        "id": 2,
                        "method": "cint/suggest",
                        "params": {
                            "uri": neighbours_uri,
                            "version": 1,
                            "requestId": "frozen-v1-suggest",
                        },
                    },
                    "timeout": 30,
                    "check": suggest_enabled,
                },
                {
                    "request": {
                        "jsonrpc": "2.0",
                        "id": 3,
                        "method": "cint/suggest/cancel",
                        "params": {
                            "uri": neighbours_uri,
                            "requestId": "frozen-v1-suggest",
                        },
                    },
                    "check": result_is(True),
                },
                {
                    "request": {
                        "jsonrpc": "2.0",
                        "id": 4,
                        "method": "cint/suggest/log",
                        "params": {"uri": neighbours_uri},
                    }
                },
                {
                    "request": {
                        "jsonrpc": "2.0",
                        "id": 5,
                        "method": "shutdown",
                        "params": {},
                    },
                    "check": result_is(True),
                },
            ],
        )

        run_live_session(
            server_bin,
            work_dir,
            output_dir,
            "suggest-disabled-flow",
            disabled_root,
            scenario_token(scenarios, "suggest-disabled-flow"),
            [
                {
                    "request": {
                        "jsonrpc": "2.0",
                        "id": 1,
                        "method": "initialize",
                        "params": {"protocol": PROTOCOL_VERSION},
                    }
                },
                {
                    "request": {
                        "jsonrpc": "2.0",
                        "method": "textDocument/didOpen",
                        "params": {
                            "uri": bookkeeping_uri,
                            "text": bookkeeping,
                            "version": 1,
                            "languageId": "kotlin",
                        },
                    },
                    "wait": candidate_waiter(bookkeeping_uri, 1),
                },
                {
                    "request": {
                        "jsonrpc": "2.0",
                        "id": 2,
                        "method": "cint/suggest",
                        "params": {"uri": bookkeeping_uri, "version": 1},
                    },
                    "check": suggest_disabled,
                },
                {
                    "request": {
                        "jsonrpc": "2.0",
                        "id": 3,
                        "method": "shutdown",
                        "params": {},
                    },
                    "check": result_is(True),
                },
            ],
        )

        run_live_session(
            server_bin,
            work_dir,
            output_dir,
            "restrictions-flow",
            restrictions_root,
            scenario_token(scenarios, "restrictions-flow"),
            [
                {
                    "request": {
                        "jsonrpc": "2.0",
                        "id": 1,
                        "method": "initialize",
                        "params": {"protocol": PROTOCOL_VERSION},
                    }
                },
                {
                    "request": {
                        "jsonrpc": "2.0",
                        "method": "textDocument/didOpen",
                        "params": {
                            "uri": restrictions_uri,
                            "text": bookkeeping,
                            "version": 1,
                            "languageId": "kotlin",
                        },
                    },
                    "wait": candidate_waiter(restrictions_uri, 1),
                },
                {
                    "request": {
                        "jsonrpc": "2.0",
                        "id": 2,
                        "method": "cint/restrictions/list",
                        "params": {"uri": restrictions_uri},
                    },
                    "check": restrictions_present,
                },
                {
                    "request": {
                        "jsonrpc": "2.0",
                        "id": 3,
                        "method": "cint/restrictions/remove",
                        "params": {"id": "fixture-restriction"},
                    },
                    "check": restrictions_removed,
                },
                {
                    "request": {
                        "jsonrpc": "2.0",
                        "id": 4,
                        "method": "cint/restrictions/list",
                        "params": {"uri": restrictions_uri},
                    },
                    "check": restrictions_empty,
                },
                {
                    "request": {
                        "jsonrpc": "2.0",
                        "id": 5,
                        "method": "shutdown",
                        "params": {},
                    },
                    "check": result_is(True),
                },
            ],
        )

        run_live_session(
            server_bin,
            work_dir,
            output_dir,
            "document-lifecycle",
            lifecycle_root,
            scenario_token(scenarios, "document-lifecycle"),
            [
                {
                    "request": {
                        "jsonrpc": "2.0",
                        "id": 1,
                        "method": "initialize",
                        "params": {"protocol": PROTOCOL_VERSION},
                    }
                },
                {
                    "request": {
                        "jsonrpc": "2.0",
                        "method": "textDocument/didOpen",
                        "params": {
                            "uri": bookkeeping_uri,
                            "text": bookkeeping,
                            "version": 1,
                            "languageId": "kotlin",
                        },
                    },
                    "wait": candidate_waiter(bookkeeping_uri, 1),
                },
                {
                    "request": {
                        "jsonrpc": "2.0",
                        "method": "textDocument/didChange",
                        "params": {
                            "uri": bookkeeping_uri,
                            "text": bookkeeping + "\n// changed",
                            "version": 2,
                            "languageId": "kotlin",
                        },
                    },
                    "wait": candidate_waiter(bookkeeping_uri, 2),
                },
                {
                    "request": {
                        "jsonrpc": "2.0",
                        "id": 2,
                        "method": "cint/analyze",
                        "params": {"uri": bookkeeping_uri},
                    },
                    "check": result_is(True),
                },
                {
                    "request": {
                        "jsonrpc": "2.0",
                        "method": "textDocument/didClose",
                        "params": {"uri": bookkeeping_uri},
                    },
                    "drain": 0.3,
                },
                {
                    "request": {
                        "jsonrpc": "2.0",
                        "id": 3,
                        "method": "shutdown",
                        "params": {},
                    },
                    "check": result_is(True),
                },
            ],
        )

        run_live_session(
            server_bin,
            work_dir,
            output_dir,
            "status-flow",
            status_root,
            scenario_token(scenarios, "status-flow"),
            [
                {
                    "request": {
                        "jsonrpc": "2.0",
                        "id": 1,
                        "method": "initialize",
                        "params": {"protocol": PROTOCOL_VERSION},
                    }
                },
                {
                    "request": {
                        "jsonrpc": "2.0",
                        "id": 2,
                        "method": "cint/status",
                        "params": {},
                    },
                    "expect_error": "unknown method: cint/status",
                },
                {
                    "request": {
                        "jsonrpc": "2.0",
                        "id": 3,
                        "method": "shutdown",
                        "params": {},
                    },
                    "check": result_is(True),
                },
            ],
        )

        run_live_session(
            server_bin,
            work_dir,
            output_dir,
            "error-flow",
            error_root,
            scenario_token(scenarios, "error-flow"),
            [
                {
                    "request": {
                        "jsonrpc": "2.0",
                        "id": 1,
                        "method": "initialize",
                        "params": {"protocol": 3},
                    }
                },
                {
                    "request": {
                        "jsonrpc": "2.0",
                        "id": 2,
                        "method": "cint/restrictions/list",
                        "params": {"uri": bookkeeping_uri},
                    },
                    "expect_error": "cint/restrictions/list requires protocol 4",
                },
                {
                    "request": {
                        "jsonrpc": "2.0",
                        "id": 3,
                        "method": "cint/config/append",
                        "params": {
                            "packId": "validation",
                            "language": "kotlin",
                            "field": "magicComment",
                            "value": "fixture",
                        },
                    },
                    "expect_error": "rulepack 'validation' field 'magicComment' is not a string list field",
                },
                {
                    "request": {
                        "jsonrpc": "2.0",
                        "id": 4,
                        "method": "cint/config/append",
                        "params": {
                            "packId": "fixture-missing",
                            "language": "kotlin",
                            "field": "methods",
                            "value": "audit",
                        },
                    },
                    "expect_error": "unknown rulepack: fixture-missing",
                },
                {
                    "request": {
                        "jsonrpc": "2.0",
                        "id": 5,
                        "method": "shutdown",
                        "params": {},
                    },
                    "check": result_is(True),
                },
            ],
        )

        print("Capturing HTTP protocol...")
        run_http_capture(
            server_bin,
            work_dir,
            output_dir,
            http_root,
            scenario_token(scenarios, "http-analyze"),
        )
        build_renderer_snapshot(output_dir)
        write_manifest(output_dir, fixtures, args.v1_commit)



def read_transcript(output_dir, name):
    return [json.loads(line) for line in (output_dir / name).read_text(encoding="utf-8").splitlines() if line.strip()]


def find_message(messages, method=None, request_id=None, predicate=None):
    for message in messages:
        if method is not None and message.get("method") != method:
            continue
        if request_id is not None and message.get("id") != request_id:
            continue
        if predicate is not None and not predicate(message):
            continue
        return message
    raise CaptureError(f"renderer snapshot could not find message method={method} id={request_id}")


def renderer_snapshot(output_dir):
    analyze = read_transcript(output_dir, "protocol-kotlin-analyze.ndjson")
    suggest = read_transcript(output_dir, "protocol-suggest-flow.ndjson")
    disabled = read_transcript(output_dir, "protocol-suggest-disabled-flow.ndjson")
    restrictions = read_transcript(output_dir, "protocol-restrictions-flow.ndjson")
    config = read_transcript(output_dir, "protocol-config-flow.ndjson")
    lifecycle = read_transcript(output_dir, "protocol-document-lifecycle.ndjson")
    status = read_transcript(output_dir, "protocol-status-flow.ndjson")
    errors = read_transcript(output_dir, "protocol-error-flow.ndjson")
    health = read_transcript(output_dir, "protocol-http-health.ndjson")[0]
    http_analyze = read_transcript(output_dir, "protocol-http-analyze.ndjson")[0]

    initialize = find_message(analyze, request_id=1)["result"]
    tags = find_message(
        analyze,
        method="cint/publishTags",
        predicate=lambda message: message["params"]["uri"] == "file:///test/Bookkeeping.kt"
        and message["params"]["version"] == 1,
    )["params"]
    candidates = find_message(
        suggest,
        method="cint/publishCandidates",
        predicate=lambda message: message["params"]["suggestionRevision"] == 2,
    )["params"]
    suggest_result = find_message(suggest, request_id=2)["result"]
    disabled_result = find_message(disabled, request_id=2)["result"]
    suggest_log = find_message(suggest, request_id=4)["result"]
    restriction_list = find_message(restrictions, request_id=2)["result"]
    restriction_remove = find_message(restrictions, request_id=3)["result"]
    restriction_empty = find_message(restrictions, request_id=4)["result"]

    config_reads = []
    for request_id in (2, 4, 6, 8):
        result = find_message(config, request_id=request_id)["result"]
        config_reads.append({"requestId": request_id, "overlay": result.get("overlay", {})})

    lifecycle_publishes = []
    for message in lifecycle:
        if message.get("method") in ("cint/publishTags", "cint/publishCandidates"):
            params = message["params"]
            lifecycle_publishes.append(
                {
                    "method": message["method"],
                    "uri": params["uri"],
                    "version": params["version"],
                    "revision": params.get("revision"),
                    "analysisRevision": params.get("analysisRevision"),
                    "suggestionRevision": params.get("suggestionRevision"),
                    "annotations": len(params.get("annotations", [])),
                    "candidates": len(params.get("candidates", [])),
                    "groups": len(params.get("groups", [])),
                }
            )

    status_error = find_message(status, request_id=2)["error"]

    snapshot = {
        "description": "Projection of the V1 wire payloads consumed by nvim-renderer. Values are copied from the protocol transcripts in this directory; no renderer rendering logic is reimplemented here.",
        "protocol": 4,
        "transport": {
            "requestShape": "JSON-RPC 2.0 NDJSON over one persistent Unix socket per session",
            "clientNotificationsWithoutIds": ["textDocument/didOpen", "textDocument/didChange", "textDocument/didClose"],
            "handshake": {"protocol": 4},
        },
        "initialize": {
            "protocol": initialize["protocol"],
            "root": initialize["root"],
            "languages": initialize["languages"],
            "suggestions": initialize["suggestions"],
            "configPackIds": [pack["packId"] for pack in initialize["config"]["packs"]],
            "configOverlay": initialize["config"]["overlay"],
        },
        "tags": {
            "uri": tags["uri"],
            "version": tags["version"],
            "hash": tags["hash"],
            "revision": tags["revision"],
            "analysisRevision": tags["analysisRevision"],
            "annotationCount": len(tags["annotations"]),
            "annotations": tags["annotations"],
        },
        "candidates": {
            "uri": candidates["uri"],
            "version": candidates["version"],
            "analysisRevision": candidates["analysisRevision"],
            "suggestionRevision": candidates["suggestionRevision"],
            "groupCount": len(candidates["groups"]),
            "candidateCount": len(candidates["candidates"]),
            "groups": candidates["groups"],
            "candidates": candidates["candidates"],
        },
        "documentLifecycle": lifecycle_publishes,
        "suggest": {
            "enabled": suggest_result,
            "disabled": disabled_result,
            "log": suggest_log,
            "progressNotifications": [
                message["params"]
                for message in suggest
                if message.get("method") == "cint/suggest/progress"
            ],
        },
        "restrictions": {
            "listed": restriction_list,
            "removed": restriction_remove,
            "afterRemoval": restriction_empty,
        },
        "configFlow": {
            "reads": config_reads,
        },
        "status": {
            "httpHealth": health["response"],
            "liveStatusError": status_error,
            "httpAnalyzeTopLevelKeys": sorted(http_analyze["response"].keys()),
            "httpAnalyzeFileKeys": sorted(http_analyze["response"]["files"][0].keys()),
        },
        "errorFlow": {
            "requestedProtocol": 3,
            "negotiatedProtocol": find_message(errors, request_id=1)["result"]["protocol"],
            "restrictionsProtocolGate": find_message(errors, request_id=2)["error"],
            "invalidConfigField": find_message(errors, request_id=3)["error"],
            "unknownRulepack": find_message(errors, request_id=4)["error"],
        },
        "notes": [
            "cint/publishTags carries revision and analysisRevision but no suggestionRevision.",
            "cint/publishCandidates carries analysisRevision and suggestionRevision but no revision.",
            "Config append/remove republishes open documents and increments analysisRevision.",
            "The deterministic fake provider emits no cint/suggest/progress notifications in this corpus.",
            "The V1 live server has no cint/status method; status is HTTP /health only.",
        ],
    }
    return snapshot


def build_renderer_snapshot(output_dir):
    snapshot = renderer_snapshot(output_dir)
    (output_dir / "renderer-visible-v1.json").write_text(
        json.dumps(snapshot, indent=2, sort_keys=True) + "\n", encoding="utf-8"
    )
    print("  renderer-visible-v1.json: generated")


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(65536), b""):
            digest.update(chunk)
    return digest.hexdigest()


def write_manifest(output_dir, fixtures, v1_commit):
    files = {}
    patterns = ("cli-*.json", "protocol-*.ndjson", "protocol-*.session.json")
    for pattern in patterns:
        for path in sorted(output_dir.glob(pattern)):
            if path.is_file():
                files[path.name] = sha256(path)
    for name in ("renderer-visible-v1.json",):
        path = output_dir / name
        if not path.is_file():
            raise CaptureError(f"manifest: missing generated artifact {name}")
        files[name] = sha256(path)

    fixture_hashes = {}
    for path in sorted(fixtures.rglob("*")):
        if path.is_file():
            fixture_hashes[path.relative_to(output_dir).as_posix()] = sha256(path)

    generator = {}
    for name in ("capture.py", "verify.py", "reproduce.sh"):
        path = output_dir / name
        if not path.is_file():
            raise CaptureError(f"manifest: missing generator file {name}")
        generator[name] = sha256(path)

    contract_path = output_dir / "known-differences.json"
    if not contract_path.is_file():
        raise CaptureError("manifest: missing known-differences.json")
    contracts = {"known-differences.json": sha256(contract_path)}

    manifest = {
        "formatVersion": 3,
        "protocolVersion": PROTOCOL_VERSION,
        "v1Commit": v1_commit,
        "generatedAtPolicy": "omitted so repeated captures remain byte-identical",
        "pathTokens": {
            "<CORPUS_ROOT>": "frozen-v1-corpus directory at capture time",
        },
        "ordering": {
            "protocolMessages": "server emission order, compared exactly",
            "annotations": "server emission order",
            "candidates": "server emission order",
            "configPacks": "server emission order",
        },
        "generator": generator,
        "contracts": contracts,
        "fixtures": fixture_hashes,
        "files": files,
    }
    (output_dir / "manifest.json").write_text(
        json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8"
    )


if __name__ == "__main__":
    try:
        main(sys.argv[1:])
    except CaptureError as error:
        print(f"capture failed: {error}", file=sys.stderr)
        sys.exit(1)
