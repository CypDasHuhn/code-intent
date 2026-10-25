#!/usr/bin/env python3
import argparse
import hashlib
import json
import re
import socket
import sys
import tempfile
import time
from pathlib import Path

sys.dont_write_bytecode = True

PROTOCOL_VERSION = 4
EXPECTED_V1_COMMIT = "3ccea9e9cdb5a4e652182136866b24929bbed0b3"
EXPECTED_CORPUS_FORMAT = 3
LIVE_SCENARIOS = (
    "kotlin-analyze",
    "typescript-analyze",
    "config-flow",
    "suggest-flow",
    "suggest-disabled-flow",
    "restrictions-flow",
    "document-lifecycle",
    "status-flow",
    "error-flow",
)
HTTP_SCENARIOS = ("http-health", "http-analyze")
CLI_EXPECTED = {
    "cli-kotlin.json": ("kotlin", 11),
    "cli-kotlin-all.json": ("kotlin", 11),
    "cli-typescript.json": ("typescript", 2),
    "cli-javascript.json": ("javascript", 1),
    "cli-csharp.json": ("csharp", 1),
    "cli-vue.json": ("vue", 1),
}
CLI_FILES = {
    "cli-kotlin.json": {
        "Bookkeeping.kt",
        "Catches.kt",
        "Composition.kt",
        "ErrorHandling.kt",
        "Heuristics.kt",
        "LoggerInteractions.kt",
        "Mutation.kt",
        "NeighboursCli.kt",
        "PurityAssist.kt",
        "Scaffolds.kt",
        "ServiceIntents.kt",
    },
    "cli-kotlin-all.json": {
        "Bookkeeping.kt",
        "Catches.kt",
        "Composition.kt",
        "ErrorHandling.kt",
        "Heuristics.kt",
        "LoggerInteractions.kt",
        "Mutation.kt",
        "NeighboursCli.kt",
        "PurityAssist.kt",
        "Scaffolds.kt",
        "ServiceIntents.kt",
    },
    "cli-typescript.json": {"Sample.ts", "ServiceIntents.ts"},
    "cli-javascript.json": {"Sample.js"},
    "cli-csharp.json": {"Sample.cs"},
    "cli-vue.json": {"Sample.vue"},
}
EXPECTED_IDS = {
    "protocol-kotlin-analyze.ndjson": [1, 2, 3, 4, 5, 6, 7],
    "protocol-typescript-analyze.ndjson": [1, 2, 3],
    "protocol-config-flow.ndjson": list(range(1, 10)),
    "protocol-suggest-flow.ndjson": [1, 2, 3, 4, 5],
    "protocol-suggest-disabled-flow.ndjson": [1, 2, 3],
    "protocol-restrictions-flow.ndjson": [1, 2, 3, 4, 5],
    "protocol-document-lifecycle.ndjson": [1, 2, 3],
    "protocol-status-flow.ndjson": [1, 2, 3],
    "protocol-error-flow.ndjson": [1, 2, 3, 4, 5],
}
EXPECTED_ERRORS = {
    "protocol-status-flow.ndjson": {
        2: (-32000, "unknown method: cint/status"),
    },
    "protocol-error-flow.ndjson": {
        2: (-32000, "cint/restrictions/list requires protocol 4"),
        3: (
            -32000,
            "rulepack 'validation' field 'magicComment' is not a string list field",
        ),
        4: (-32000, "unknown rulepack: fixture-missing"),
    },
}
EXPECTED_SOURCE = {
    "protocol-kotlin-analyze.ndjson": "kotlin",
    "protocol-typescript-analyze.ndjson": "typescript",
    "protocol-config-flow.ndjson": "kotlin",
    "protocol-suggest-flow.ndjson": "kotlin",
    "protocol-suggest-disabled-flow.ndjson": "kotlin",
    "protocol-restrictions-flow.ndjson": "kotlin",
    "protocol-document-lifecycle.ndjson": "kotlin",
    "protocol-status-flow.ndjson": "kotlin",
    "protocol-error-flow.ndjson": "kotlin",
}
EXPECTED_TRUE = {
    "protocol-kotlin-analyze.ndjson": {2, 5, 7},
    "protocol-typescript-analyze.ndjson": {2, 3},
    "protocol-config-flow.ndjson": {9},
    "protocol-suggest-flow.ndjson": {3, 5},
    "protocol-suggest-disabled-flow.ndjson": {3},
    "protocol-restrictions-flow.ndjson": {5},
    "protocol-document-lifecycle.ndjson": {2, 3},
    "protocol-status-flow.ndjson": {3},
    "protocol-error-flow.ndjson": {5},
}
TRANSCRIPT_FILES = {f"protocol-{name}.ndjson" for name in LIVE_SCENARIOS}
TRANSCRIPT_FILES |= {f"protocol-{name}.ndjson" for name in HTTP_SCENARIOS}
SESSION_FILES = {f"protocol-{name}.session.json" for name in LIVE_SCENARIOS}
SESSION_FILES |= {"protocol-http.session.json"}
EXPECTED_FILES = set(CLI_EXPECTED) | TRANSCRIPT_FILES | SESSION_FILES
EXPECTED_FILES.add("renderer-visible-v1.json")
EXPECTED_REQUEST_METHODS = {
    "kotlin-analyze": [
        "initialize",
        "textDocument/didOpen",
        "cint/analyze",
        "cint/config/read",
        "cint/config/append",
        "cint/analyze",
        "cint/config/remove",
        "shutdown",
    ],
    "typescript-analyze": [
        "initialize",
        "textDocument/didOpen",
        "cint/analyze",
        "shutdown",
    ],
    "config-flow": [
        "initialize",
        "cint/config/read",
        "cint/config/append",
        "cint/config/read",
        "cint/config/append",
        "cint/config/read",
        "cint/config/remove",
        "cint/config/read",
        "shutdown",
    ],
    "suggest-flow": [
        "initialize",
        "textDocument/didOpen",
        "cint/suggest",
        "cint/suggest/cancel",
        "cint/suggest/log",
        "shutdown",
    ],
    "suggest-disabled-flow": [
        "initialize",
        "textDocument/didOpen",
        "cint/suggest",
        "shutdown",
    ],
    "restrictions-flow": [
        "initialize",
        "textDocument/didOpen",
        "cint/restrictions/list",
        "cint/restrictions/remove",
        "cint/restrictions/list",
        "shutdown",
    ],
    "document-lifecycle": [
        "initialize",
        "textDocument/didOpen",
        "textDocument/didChange",
        "cint/analyze",
        "textDocument/didClose",
        "shutdown",
    ],
    "status-flow": ["initialize", "cint/status", "shutdown"],
    "error-flow": [
        "initialize",
        "cint/restrictions/list",
        "cint/config/append",
        "cint/config/append",
        "shutdown",
    ],
}
REQUIRED_REQUEST_PARAMS = {
    "initialize": {"protocol"},
    "textDocument/didOpen": {"uri", "text", "version", "languageId"},
    "textDocument/didChange": {"uri", "text", "version", "languageId"},
    "textDocument/didClose": {"uri"},
    "cint/analyze": {"uri"},
    "cint/config/read": set(),
    "cint/config/append": {"packId", "language", "field", "value"},
    "cint/config/remove": {"packId", "language", "field", "value"},
    "cint/suggest": {"uri", "version"},
    "cint/suggest/cancel": {"uri", "requestId"},
    "cint/suggest/log": {"uri"},
    "cint/restrictions/list": {"uri"},
    "cint/restrictions/remove": {"id"},
    "cint/status": set(),
    "shutdown": set(),
}
PATH_RE = re.compile(r"(?<![A-Za-z0-9_<>.=:-])/(?!/)[A-Za-z0-9_.-][^\s\"'`<>]*")
WINDOWS_RE = re.compile(r"(?<![A-Za-z0-9_<>.=-])[A-Za-z]:[\\/][^\s\"'`<>]*")
ALLOWED_ABSOLUTE = {"/health", "/analyze", "/usr/bin/env"}
ALLOWED_PREFIXES = ("/test/",)


def fail(message):
    raise SystemExit(message)


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(65536), b""):
            digest.update(chunk)
    return digest.hexdigest()


def parse_ndjson(path):
    messages = []
    for line_number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if not line.strip():
            continue
        try:
            messages.append(json.loads(line))
        except json.JSONDecodeError as error:
            fail(f"{path.name}:{line_number}: invalid JSON: {error}")
    return messages


def load_json(path):
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as error:
        fail(f"{path.name}: invalid JSON: {error}")


def require(condition, message):
    if not condition:
        fail(message)


def response_map(messages, expected_ids, name):
    ids = [message["id"] for message in messages if "id" in message]
    require(len(ids) == len(set(ids)), f"{name}: duplicate response ids {ids}")
    require(ids == expected_ids, f"{name}: expected response ids {expected_ids}, got {ids}")
    return {message["id"]: message for message in messages if "id" in message}


def verify_cli(corpus_dir):
    for name, (language, count) in CLI_EXPECTED.items():
        path = corpus_dir / name
        require(path.is_file(), f"missing CLI output: {name}")
        data = load_json(path)
        require(
            set(data) == {"language", "intents", "files"},
            f"{name}: expected top-level keys language/intents/files, got {sorted(data)}",
        )
        require(data["language"] == language, f"{name}: expected language {language}")
        require(count == len(data["files"]), f"{name}: expected {count} files")
        require(len(data["intents"]) == 15, f"{name}: expected 15 intents")
        paths = {entry["path"] for entry in data["files"]}
        require(paths == CLI_FILES[name], f"{name}: unexpected file set {sorted(paths)}")
        for entry in data["files"]:
            require(
                set(entry) == {"path", "hash", "spans", "candidates"},
                f"{name}: unexpected file entry shape for {entry.get('path')}",
            )
            require(entry["spans"], f"{name}: {entry['path']} has no annotations")
            require(len(entry["hash"]) == 64, f"{name}: {entry['path']} hash is not sha256")


def verify_transcript(corpus_dir, name, messages):
    expected_ids = EXPECTED_IDS[name]
    responses = response_map(messages, expected_ids, name)
    source = EXPECTED_SOURCE[name]
    token = f"<CORPUS_ROOT>/fixtures/sources/{source}"

    initialize = responses[1]["result"]
    require(initialize["protocol"] == PROTOCOL_VERSION, f"{name}: protocol mismatch")
    require(initialize["root"] == token, f"{name}: unnormalized root {initialize['root']}")
    require(
        initialize["languages"] == ["kotlin", "javascript", "typescript", "vue", "csharp"],
        f"{name}: language list changed",
    )
    for request_id, response in responses.items():
        error = EXPECTED_ERRORS.get(name, {}).get(request_id)
        actual_error = response.get("error")
        if error is not None:
            require(actual_error is not None, f"{name}: id {request_id} expected an error")
            require(
                (actual_error["code"], actual_error["message"]) == error,
                f"{name}: id {request_id} error changed: {actual_error}",
            )
        else:
            require(actual_error is None, f"{name}: id {request_id} unexpected error {actual_error}")
    for request_id in EXPECTED_TRUE[name]:
        require(
            responses[request_id].get("result") is True,
            f"{name}: id {request_id} expected result true",
        )

    methods = [message.get("method") for message in messages if "method" in message]
    if name == "protocol-kotlin-analyze.ndjson":
        overlays = {
            3: {},
            4: {"logging": {"kotlin": {"methods": ["audit"]}}},
            6: {},
        }
        for request_id, overlay in overlays.items():
            require(
                responses[request_id]["result"]["overlay"] == overlay,
                f"{name}: id {request_id} overlay mismatch",
            )
        require(
            [message["params"]["analysisRevision"] for message in messages if message.get("method") == "cint/publishTags"] == [1, 2, 3, 4, 5],
            f"{name}: tag revision sequence changed",
        )
        require(
            [message["params"]["suggestionRevision"] for message in messages if message.get("method") == "cint/publishCandidates"] == [1, 2, 3, 4, 5],
            f"{name}: candidate revision sequence changed",
        )
    elif name == "protocol-config-flow.ndjson":
        validation = {"validation": {"predicates": ["fixturePredicate"]}}
        both = {
            "validation": {"predicates": ["fixturePredicate"]},
            "logging": {"kotlin": {"methods": ["trace"]}},
        }
        logging = {"logging": {"kotlin": {"methods": ["trace"]}}}
        overlays = {2: {}, 3: validation, 4: validation, 5: both, 6: both, 7: logging, 8: logging}
        for request_id, overlay in overlays.items():
            require(
                responses[request_id]["result"]["overlay"] == overlay,
                f"{name}: id {request_id} overlay mismatch",
            )
        require(not methods, f"{name}: unexpected notifications")
    elif name == "protocol-suggest-flow.ndjson":
        enabled = responses[2]["result"]
        require(
            enabled
            == {
                "accepted": True,
                "cancelled": False,
                "count": 19,
                "restrictions": 0,
            },
            f"{name}: enabled suggest result changed: {enabled}",
        )
        candidates = [
            message["params"]
            for message in messages
            if message.get("method") == "cint/publishCandidates"
        ]
        require(len(candidates) == 2, f"{name}: expected two candidate publishes")
        require(candidates[-1]["suggestionRevision"] == 2, f"{name}: revision mismatch")
        require(len(candidates[-1]["candidates"]) == 19, f"{name}: candidate count changed")
        require(len(candidates[-1]["groups"]) == 12, f"{name}: group count changed")
        require(
            any(candidate["source"] == "ai:fake" for candidate in candidates[-1]["candidates"]),
            f"{name}: fake AI enrichment missing",
        )
    elif name == "protocol-suggest-disabled-flow.ndjson":
        disabled = responses[2]["result"]
        require(
            disabled == {"accepted": False, "count": 0, "disabled": True},
            f"{name}: disabled suggest result changed: {disabled}",
        )
    elif name == "protocol-restrictions-flow.ndjson":
        listed = responses[2]["result"]["restrictions"]
        require(len(listed) == 1, f"{name}: expected one restriction")
        require(listed[0]["id"] == "fixture-restriction", f"{name}: restriction id changed")
        require(listed[0]["path"] == "file:///test/Bookkeeping.kt", f"{name}: restriction path changed")
        require(
            responses[3]["result"] == {"accepted": True, "removed": True},
            f"{name}: restriction removal changed",
        )
        require(
            responses[4]["result"] == {"restrictions": []},
            f"{name}: restriction list not empty after removal",
        )
    elif name == "protocol-document-lifecycle.ndjson":
        tags = [message["params"] for message in messages if message.get("method") == "cint/publishTags"]
        candidates = [message["params"] for message in messages if message.get("method") == "cint/publishCandidates"]
        require(
            [(item["version"], item["revision"], item["analysisRevision"]) for item in tags]
            == [(1, 0, 1), (2, 0, 2), (2, 0, 3)],
            f"{name}: tag lifecycle sequence changed",
        )
        require(
            [(item["version"], item["suggestionRevision"]) for item in candidates]
            == [(1, 1), (2, 2), (2, 3)],
            f"{name}: candidate lifecycle sequence changed",
        )
    elif name == "protocol-typescript-analyze.ndjson":
        require(
            [message["params"]["analysisRevision"] for message in messages if message.get("method") == "cint/publishTags"] == [1, 2],
            f"{name}: tag revision sequence changed",
        )
    return len(messages)


def verify_http(corpus_dir):
    health_path = corpus_dir / "protocol-http-health.ndjson"
    analyze_path = corpus_dir / "protocol-http-analyze.ndjson"
    require(health_path.is_file() and analyze_path.is_file(), "missing HTTP transcripts")
    health = parse_ndjson(health_path)
    analyze = parse_ndjson(analyze_path)
    require(len(health) == 1 and len(analyze) == 1, "HTTP transcripts must contain one record")
    require(health[0]["response"] == {"status": "ok"}, "HTTP health shape changed")
    response = analyze[0]["response"]
    require(response["language"] == "kotlin", "HTTP analyze language changed")
    require(len(response["files"]) == 1, "HTTP analyze file count changed")
    entry = response["files"][0]
    require(entry["path"] == "Bookkeeping.kt", "HTTP analyze path changed")
    require(len(entry["spans"]) == 11, "HTTP analyze annotation count changed")
    require(entry["candidates"] == [], "HTTP analyze candidate count changed")
    require(len(response["intents"]) == 15, "HTTP analyze intent count changed")
    return health, analyze


def verify_sessions(corpus_dir, transcripts):
    for timeline in (LIVE_SCENARIOS, HTTP_SCENARIOS):
        for scenario in timeline:
            if scenario.startswith("http-"):
                rest = scenario[5:]
                name = f"protocol-http-{rest}.ndjson"
                session_name = "protocol-http.session.json"
            else:
                name = f"protocol-{scenario}.ndjson"
                session_name = f"protocol-{scenario}.session.json"
            path = corpus_dir / session_name
            require(path.is_file(), f"missing session file: {session_name}")
            session = load_json(path)
            require(session.get("formatVersion") == 1, f"{session_name}: formatVersion")
            require(session.get("protocol") == PROTOCOL_VERSION, f"{session_name}: protocol")
            if scenario.startswith("http-"):
                require(session.get("scenario") == "http", f"{session_name}: scenario")
                require(session.get("transport") == "http", f"{session_name}: transport")
                require(session.get("portMode") == "ephemeral-retry", f"{session_name}: portMode")
                steps = session["steps"]
                require(len(steps) == 2, f"{session_name}: expected two steps")
                require(
                    steps[0]["request"] == {"method": "GET", "path": "/health"},
                    f"{session_name}: health request shape",
                )
                analyze_request = steps[1]["request"]
                require(
                    set(analyze_request) == {"method", "path", "body"},
                    f"{session_name}: analyze request shape",
                )
                require(analyze_request["method"] == "POST", f"{session_name}: analyze method")
                require(analyze_request["path"] == "/analyze", f"{session_name}: analyze path")
                require(
                    set(analyze_request["body"]) == {"path", "root", "language"},
                    f"{session_name}: analyze body shape",
                )
                require(
                    analyze_request["body"]["root"]
                    == "<CORPUS_ROOT>/fixtures/sources/kotlin",
                    f"{session_name}: analyze root token",
                )
                if scenario == "http-health":
                    require(steps[0]["response"] == transcripts[name][0]["response"], f"{session_name}: health response")
                else:
                    require(steps[1]["response"] == transcripts[name][0]["response"], f"{session_name}: analyze response")
                continue
            require(session.get("scenario") == scenario, f"{session_name}: scenario")
            source = session.get("sourceFixture")
            require(source == f"fixtures/sources/{EXPECTED_SOURCE[name]}", f"{session_name}: source fixture")
            steps = session["steps"]
            require(steps, f"{session_name}: no steps")
            request_ids = [step["request"]["id"] for step in steps if "id" in step["request"]]
            require(
                request_ids == EXPECTED_IDS[name],
                f"{session_name}: request ids {request_ids}",
            )
            methods = [step["request"].get("method") for step in steps]
            require(
                methods == EXPECTED_REQUEST_METHODS[scenario],
                f"{session_name}: request methods changed: {methods}",
            )
            for index, step in enumerate(steps, 1):
                request = step["request"]
                require(request.get("jsonrpc") == "2.0", f"{session_name}: step {index} jsonrpc")
                method = request.get("method")
                require(method in REQUIRED_REQUEST_PARAMS, f"{session_name}: step {index} unknown method")
                params = request.get("params")
                require(isinstance(params, dict), f"{session_name}: step {index} params")
                require(
                    REQUIRED_REQUEST_PARAMS[method] <= set(params),
                    f"{session_name}: step {index} missing params for {method}",
                )
                require("request" in step and "messages" in step, f"{session_name}: bad step")
            expected_protocol = 3 if scenario == "error-flow" else 4
            require(
                steps[0]["request"]["params"]["protocol"] == expected_protocol,
                f"{session_name}: initialize protocol",
            )
            flattened = [message for step in steps for message in step["messages"]]
            require(
                flattened == transcripts[name],
                f"{session_name}: session messages do not match transcript",
            )


def replay_sessions(corpus_dir, server_bin, capture):
    server_bin = server_bin.resolve()
    require(server_bin.is_file(), f"replay server is missing: {server_bin}")
    fixtures = corpus_dir / "fixtures"
    scenarios = capture.load_scenarios(fixtures)
    with tempfile.TemporaryDirectory() as temporary:
        work_dir = Path(temporary)
        for scenario in LIVE_SCENARIOS:
            name = f"protocol-{scenario}.session.json"
            session = load_json(corpus_dir / name)
            root = capture.prepare_scenario(fixtures, scenarios, scenario, work_dir)
            token = capture.scenario_token(scenarios, scenario)
            process = stdout = stderr = sock = None
            try:
                process, socket_path, stdout, stderr, _ = capture.start_live_server(
                    server_bin, root, work_dir, f"replay-{scenario}"
                )
                sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
                sock.settimeout(30)
                sock.connect(str(socket_path))
                client = capture.Client(sock)
                for index, step in enumerate(session["steps"], 1):
                    expected = step["messages"]
                    start = len(client.messages)
                    client.send(step["request"])
                    deadline = time.time() + 30
                    while len(client.messages) - start < len(expected):
                        if not client._read(deadline):
                            if time.time() >= deadline:
                                fail(f"replay {scenario}: step {index} timed out")
                            time.sleep(0.01)
                    client.drain_until_quiet(capture.SETTLE_SECONDS)
                    actual = [
                        capture.normalize(message, root, token)
                        for message in client.messages[start:]
                    ]
                    require(
                        actual == expected,
                        f"replay {scenario}: step {index} messages differ",
                    )
            except Exception as error:
                fail(f"replay {scenario}: {error}")
            finally:
                if sock is not None:
                    sock.close()
                if process is not None:
                    capture.stop_live_server(process, stdout, stderr)
            print(f"  replay {scenario}: {len(session['steps'])} steps match")
        http_root = capture.prepare_scenario(fixtures, scenarios, "http-analyze", work_dir)
        replay_output = work_dir / "http-replay"
        replay_output.mkdir()
        capture.run_http_capture(
            server_bin,
            work_dir,
            replay_output,
            http_root,
            capture.scenario_token(scenarios, "http-analyze"),
        )
        for name in ("protocol-http-health.ndjson", "protocol-http-analyze.ndjson"):
            require(
                parse_ndjson(replay_output / name) == parse_ndjson(corpus_dir / name),
                f"replay http: {name} differs",
            )
        require(
            load_json(replay_output / "protocol-http.session.json")
            == load_json(corpus_dir / "protocol-http.session.json"),
            "replay http: session differs",
        )
        print("  replay http: health and analyze match")


def verify_fixture_baseline(corpus_dir, baseline_path):
    baseline = load_json(baseline_path.resolve())
    require(
        baseline.get("formatVersion") == EXPECTED_CORPUS_FORMAT,
        "fixture baseline: formatVersion",
    )
    require(
        baseline.get("v1Commit") == EXPECTED_V1_COMMIT,
        "fixture baseline: v1Commit is not pinned",
    )
    fixture_files = {
        path.relative_to(corpus_dir).as_posix()
        for path in (corpus_dir / "fixtures").rglob("*")
        if path.is_file()
    }
    require(
        set(baseline.get("fixtures", {})) == fixture_files,
        "fixture baseline: fixture key set drifted",
    )
    for name, expected_hash in baseline["fixtures"].items():
        require(
            sha256(corpus_dir / name) == expected_hash,
            f"fixture baseline: fixture drifted: {name}",
        )
    contract = corpus_dir / "known-differences.json"
    require(contract.is_file(), "fixture baseline: missing known-differences.json")
    require(
        baseline.get("contracts") == {"known-differences.json": sha256(contract)},
        "fixture baseline: known-differences.json drifted",
    )


def verify_manifest(corpus_dir):
    manifest_path = corpus_dir / "manifest.json"
    require(manifest_path.is_file(), "manifest.json is missing")
    manifest = load_json(manifest_path)
    require(
        set(manifest)
        == {
            "formatVersion",
            "protocolVersion",
            "v1Commit",
            "generatedAtPolicy",
            "pathTokens",
            "ordering",
            "generator",
            "contracts",
            "fixtures",
            "files",
        },
        "manifest: unexpected top-level keys",
    )
    require(manifest.get("formatVersion") == EXPECTED_CORPUS_FORMAT, "manifest: formatVersion")
    require(manifest.get("protocolVersion") == PROTOCOL_VERSION, "manifest: protocolVersion")
    require(manifest.get("v1Commit") == EXPECTED_V1_COMMIT, "manifest: v1Commit is not pinned")
    require(
        manifest.get("generatedAtPolicy") == "omitted so repeated captures remain byte-identical",
        "manifest: generatedAtPolicy",
    )
    require(set(manifest["pathTokens"]) == {"<CORPUS_ROOT>"}, "manifest: pathTokens")
    require(
        manifest["ordering"]
        == {
            "protocolMessages": "server emission order, compared exactly",
            "annotations": "server emission order",
            "candidates": "server emission order",
            "configPacks": "server emission order",
        },
        "manifest: ordering contract",
    )
    require(set(manifest["files"]) == EXPECTED_FILES, "manifest: file key set mismatch")
    for name, expected_hash in manifest["files"].items():
        path = corpus_dir / name
        require(path.is_file(), f"manifest: missing file {name}")
        require(sha256(path) == expected_hash, f"manifest: hash mismatch for {name}")
    generator_names = {"capture.py", "verify.py", "reproduce.sh"}
    require(set(manifest["generator"]) == generator_names, "manifest: generator keys")
    for name in sorted(generator_names):
        path = corpus_dir / name
        require(path.is_file(), f"manifest: missing generator {name}")
        require(sha256(path) == manifest["generator"][name], f"manifest: generator hash {name}")
    fixture_files = {
        path.relative_to(corpus_dir).as_posix()
        for path in (corpus_dir / "fixtures").rglob("*")
        if path.is_file()
    }
    require(set(manifest["fixtures"]) == fixture_files, "manifest: fixture key set mismatch")
    for name, expected_hash in manifest["fixtures"].items():
        require(sha256(corpus_dir / name) == expected_hash, f"manifest: fixture hash mismatch {name}")
    contract = corpus_dir / "known-differences.json"
    require(contract.is_file(), "manifest: missing known-differences.json")
    require(
        manifest["contracts"] == {"known-differences.json": sha256(contract)},
        "manifest: contract hash mismatch",
    )
    generated = set()
    for pattern in ("cli-*.json", "protocol-*.ndjson", "protocol-*.session.json"):
        generated |= {path.name for path in corpus_dir.glob(pattern) if path.is_file()}
    generated.add("renderer-visible-v1.json")
    require(generated == EXPECTED_FILES, f"manifest: on-disk artifact set mismatch {sorted(generated ^ EXPECTED_FILES)}")
    require((corpus_dir / "fixtures").is_dir(), "manifest: fixtures directory missing")


def verify_known_differences(corpus_dir):
    contract = load_json(corpus_dir / "known-differences.json")
    require(contract.get("formatVersion") == 1, "known-differences: formatVersion")
    require(contract.get("protocolVersion") == PROTOCOL_VERSION, "known-differences: protocolVersion")
    comparison = contract.get("comparison", {})
    require(
        comparison.get("protocol", {}).get("mode") == "ordered-exact",
        "known-differences: protocol comparison",
    )
    require(
        comparison.get("cli", {}).get("mode") == "semantic-json",
        "known-differences: cli comparison",
    )
    require(
        comparison.get("renderer", {}).get("mode") == "exact-json",
        "known-differences: renderer comparison",
    )
    statuses = set(contract.get("statusValues", []))
    require(statuses == {"decided", "tentative"}, "known-differences: statusValues")
    differences = contract.get("differences")
    require(isinstance(differences, list) and differences, "known-differences: differences")
    seen = set()
    for entry in differences:
        for key in ("id", "status", "scope", "field", "transcripts", "reason", "ticket"):
            require(key in entry, f"known-differences: missing {key}")
        require(entry["id"] not in seen, f"known-differences: duplicate id {entry['id']}")
        seen.add(entry["id"])
        require(entry["status"] in statuses, f"known-differences: bad status {entry['status']}")
        require(entry["scope"] in {"protocol", "cli", "renderer", "files", "all"}, f"known-differences: bad scope {entry['scope']}")
        require(isinstance(entry["transcripts"], list) and entry["transcripts"], f"known-differences: transcripts {entry['id']}")
        require(re.fullmatch(r"[0-9]{3}-[a-z0-9-]+", entry["ticket"]), f"known-differences: ticket {entry['ticket']}")
        require(entry["reason"].strip(), f"known-differences: empty reason {entry['id']}")


def verify_paths(corpus_dir):
    for path in sorted(corpus_dir.rglob("*")):
        if not path.is_file() or "__pycache__" in path.parts:
            continue
        try:
            text = path.read_text(encoding="utf-8")
        except UnicodeDecodeError:
            continue
        scan_text = re.sub(r"https?://[^\s\"'`<>]+", "", text)
        for match in list(PATH_RE.finditer(scan_text)) + list(WINDOWS_RE.finditer(scan_text)):
            value = match.group(0)
            require(
                value in ALLOWED_ABSOLUTE or value.startswith(ALLOWED_PREFIXES),
                f"{path.relative_to(corpus_dir)}: absolute path leak {value!r}",
            )


def main(argv):
    parser = argparse.ArgumentParser()
    parser.add_argument("--corpus-dir", required=True, type=Path)
    parser.add_argument("--server-bin", type=Path)
    parser.add_argument("--fixture-baseline", type=Path)
    args = parser.parse_args(argv)
    corpus_dir = args.corpus_dir.resolve()

    if args.fixture_baseline is not None:
        verify_fixture_baseline(corpus_dir, args.fixture_baseline)
        print("  fixture baseline: fixtures and contract unchanged")
        return

    verify_paths(corpus_dir)
    print("  path scan: clean")

    verify_cli(corpus_dir)
    print(f"  CLI outputs: {len(CLI_EXPECTED)} valid")
    kotlin = (corpus_dir / "cli-kotlin.json").read_bytes()
    kotlin_all = (corpus_dir / "cli-kotlin-all.json").read_bytes()
    require(kotlin == kotlin_all, "cli-kotlin-all.json is not byte-identical to cli-kotlin.json")
    print("  CLI --all equivalence: byte-identical")

    transcripts = {}
    for name in sorted(TRANSCRIPT_FILES):
        path = corpus_dir / name
        require(path.is_file(), f"missing transcript: {name}")
        messages = parse_ndjson(path)
        transcripts[name] = messages
        if name in EXPECTED_IDS:
            count = verify_transcript(corpus_dir, name, messages)
            print(f"  {name}: {count} messages, semantic checks passed")
        else:
            print(f"  {name}: parsed")

    health, analyze = verify_http(corpus_dir)
    transcripts["protocol-http-health.ndjson"] = health
    transcripts["protocol-http-analyze.ndjson"] = analyze
    print("  protocol-http-health.ndjson: semantic checks passed")
    print("  protocol-http-analyze.ndjson: semantic checks passed")

    sys.path.insert(0, str(corpus_dir))
    import capture

    verify_sessions(corpus_dir, transcripts)
    print(f"  session files: {len(SESSION_FILES)} requests and messages validated")
    if args.server_bin is not None:
        print("  replaying sessions against pinned server")
        replay_sessions(corpus_dir, args.server_bin, capture)
    else:
        print("  replay: skipped without --server-bin")

    expected_snapshot = capture.renderer_snapshot(corpus_dir)
    actual_snapshot = load_json(corpus_dir / "renderer-visible-v1.json")
    require(actual_snapshot == expected_snapshot, "renderer-visible-v1.json does not match its derivation")
    print("  renderer-visible-v1.json: matches transcript projection")

    verify_known_differences(corpus_dir)
    print("  known-differences.json: machine contract valid")

    verify_manifest(corpus_dir)
    print("  manifest.json: provenance, fixture and artifact hashes match")
    print("Corpus verification passed")


if __name__ == "__main__":
    main(sys.argv[1:])
