# Ticket 066 — Correctness Review

**Verdict: FINDINGS**

1. **`verify.py:94-107,132-134`** — Manifest verification only hashes entries listed in `manifest["files"]`; it never asserts the expected file set. `verify_cli` iterates a glob, so deleting all `cli-*.json` files (or one file plus its manifest entry) still passes. Semantic checks are also absent: an analyze response need only be a non-error response (not `true`), and suggest enabled/disabled flags, restriction contents, and config overlays are not asserted. `EXPECTED_METHODS` covers only six transcripts. **Fix:** assert the exact expected filenames and manifest key set, and assert per-id response shape/values (analyze `true`, suggest `disabled`/`accepted`, restriction counts, overlay transitions).

2. **`verify.py:76-79`** — The absolute-path guard is three hardcoded literals and scans transcripts only. It would miss `/home/alice`, `/private/var/...`, or leaks in CLI/HTTP/renderer files, despite `README.md:64` claiming detection of unnormalized absolute paths. **Fix:** scan every corpus file generically, allowing only `<REPO_ROOT>`, `/test/...` URIs, and documented path tokens.

3. **`reproduce.sh:13-14,55-61`** (`capture.py:267-271`) — The checked-in corpus is deleted before build/capture, and transcripts are written directly into it. Any later build, capture, or verify failure leaves the corpus missing or partially regenerated; the trap removes only `WORK_DIR`. **Fix:** capture into a temporary staging directory and replace the corpus only after `verify.py` succeeds, restoring on failure.

4. **`capture.py:298-305`; `README.md:51`** — `free_port()` binds and closes a socket before the server binds the same port, a TOCTOU race that can fail reproduction nondeterministically. The README claims `--port 0`, but the code passes the probed port (and the server prints the requested, not actual, port). **Fix:** retry server startup on bind failure or discover the actual bound port; correct the README.

5. **`KNOWN_DIFFERENCES.md:150,152,174`** — The V1 annotation, provenance, and candidate examples show a `span` object. The frozen wire uses flat `startLine/startCol/endLine/endCol/startByte/endByte` fields. As this document is the V2 allow-list, the examples are misleading. **Fix:** replace `span` examples with the actual frozen shape.

6. **`capture.py:1225`** (and generated `renderer-visible-v1.json`) — The note says every publish carries `analysisRevision` and `suggestionRevision`. In fact `publishTags` has `revision` + `analysisRevision` but no `suggestionRevision`; `publishCandidates` has `analysisRevision` + `suggestionRevision` but no `revision`. **Fix:** correct the note.

Analyze responses are present with contiguous ids, and `protocol-document-lifecycle.ndjson` includes the analyze response (id 2). Capture uses one persistent live connection per session with protocol 4 (protocol 3 intentionally in the error flow). Suggest enabled/disabled and restriction list/remove results are real engine behavior, including fake-provider AI-enriched candidates, not harness errors. Paths are normalized and no V1 source or testing-grounds files are modified. The remaining defects concern verifier coverage, non-atomic corpus replacement, and documentation accuracy.

## Round 2

Verdict: CONCUR on the six Round-1 findings. Two new correctness findings remain:
the session "replay" check does not assert request methods or params, and
`sourceHead` makes `manifest.json` non-reproducible across workspace HEADs.

### Findings

1. **`frozen-v1-corpus/verify.py:370-380` (with `README.md:48,64` and the
   `session files ... replayable` line at `verify.py:545`) — the session check is
   not a replay and does not validate request methods or params.**
   `verify_sessions` checks only the request-id list, source fixture, and
   `flattened == transcripts`; it never compares `step["request"]` to an expected
   method/params or re-sends it. A session whose stored request method is changed
   while its stored `messages` and manifest hashes are kept consistent still
   passes, because the transcript messages are the only server evidence. The
   `.ndjson` transcript remains authoritative, so a V2 consumer replaying the
   session can issue a different request than the transcript records.
   **Fix:** assert a per-scenario `(id, method)` (and required params) map, as the
   Round-1 `EXPECTED_METHODS` intended, or replay each session against the pinned
   server in the reproduction path.

2. **`frozen-v1-corpus/reproduce.sh:10` and `capture.py:1349-1350` with
   `manifest.json:74` and `README.md:64` — `sourceHead` makes repeated captures
   non-byte-identical across HEADs.**
   `SOURCE_HEAD` is the workspace `HEAD`, not the pinned V1 commit, and it is
   written into the hashed manifest while the manifest policy and README promise
   byte-identical repeated captures. A capture at a different commit changes
   `manifest.json` even when the engine/fixtures/transcripts are unchanged.
   **Fix:** remove `sourceHead` from the reproducibility contract (or qualify the
   claim to a fixed source HEAD); keep provenance outside the hashed output. This
   is the same residual the architecture Round-2 report filed.

### Concurs / Dissents

- Concurs: finding 1 is addressed for exact file sets, manifest key equality,
  expected response ids, and per-id response values (`verify.py:66-112,
  191-318, 386-456`); the remaining method/replay gap is new finding 1 above.
- Concurs: finding 2 is addressed. `verify_paths` scans every corpus file and
  uses a generic absolute-path regex (`verify.py:119-122, 493-507`), including
  CLI/HTTP/renderer JSON; the fixtures contain only the allowed `/test/` URI.
- Concurs: finding 3 is addressed. `reproduce.sh:11-37,47-52,118-127` stages the
  corpus, verifies it, and only then swaps; the trap restores the old corpus on
  failure. Residual, not filed: the two-`mv` swap is not atomic for SIGKILL or a
  mid-swap crash, and the README's "atomically" is stronger than the code.
- Concurs: finding 4 is addressed. `capture.py:305-309,428-441` retries fresh
  ports five times, and `README.md:51` no longer claims `--port 0`.
- Concurs: finding 5 is addressed. `KNOWN_DIFFERENCES.md:33-44,55-64,150-159`
  now uses flat `startLine/startCol/endLine/endCol/startByte/endByte`.
- Concurs: finding 6 is addressed. `capture.py:1229-1235,1289-1291` and the
  generated `renderer-visible-v1.json` now state the distinct `publishTags` /
  `publishCandidates` field sets.
- Dissents: none on the six; new findings 1-2 above.

### Residual observations

- `verify_sessions` also does not assert the HTTP request methods (`GET`/`POST`),
  though it does assert the paths (`verify.py:359-366`).
- `verify_known_differences` validates an entry's shape but not that its
  `transcripts` references exist; wildcard entries are not checked
  (`verify.py:459-491`).
- Static review only; no source edit, build, test, lint, or formatter command was
  run by this role. The green handover is assumed per `docs/workflow.md`.

## Round 3

Verdict: CONCUR. Both Round-2 findings are genuinely fixed.

- `sourceHead` is gone from `capture.py:1314-1367`, `reproduce.sh:5-13`, the manifest and `README.md`; no HEAD- or time-dependent field remains in the generated artifacts, and all file/fixture/generator hashes match. Recaptures across workspace HEAD are byte-identical by construction.
- `verify.py:121-197` pins request methods and required params; `verify.py:510-578` re-sends every live session request against the pinned server and compares normalized messages, then re-captures HTTP and compares the session.
- `reproduce.sh:60-67` checks staged fixtures and `known-differences.json` against the existing manifest before capture.
- `reproduce.sh:27-44,138` uses `renameat2(RENAME_EXCHANGE)` only after verification, leaving the old corpus untouched until the atomic swap.

Residual, not filed: HTTP replay regenerates the request rather than replaying the stored HTTP body, but the regenerated session and transcripts must match exactly. Static review only; no build, test, or script run.
