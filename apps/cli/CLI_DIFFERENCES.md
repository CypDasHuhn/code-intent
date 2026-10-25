# V2 CLI differences from the frozen V1 corpus

The V2 CLI (`cint`) keeps the V1 JSON vocabulary wherever the semantics are
unchanged. The frozen corpus remains read-only; this document is the CLI's
allow-list companion to `frozen-v1-corpus/known-differences.json`.

## Deliberate changes

- Top-level `intents` is removed. Intents live on each annotation.
- `files[].spans` is renamed to `files[].annotations`.
- `files[].diagnostics` is added and carries analysis errors.
- `meta` is added with deterministic fields only: `formatVersion`, `version`,
  `sourceEpoch`, `completeness`, and `fileCount`.
- `meta.timestamp` and `meta.elapsedMs` are intentionally omitted. They are not
  reproducible, and deterministic CLI output takes priority over the sketch in
  `KNOWN_DIFFERENCES.md`.
- Candidate records keep the V1 flat span fields but use the V2 candidate
  vocabulary (`reason` enum name). Absent optional provider/model/rationale
  fields are omitted rather than emitted as nulls; `path` is only emitted when
  the candidate itself carries one.
- Files and arrays are sorted deterministically: files by relative path,
  annotations and candidates by span then identity fields, intents
  alphabetically.

## Preserved shape

- `language`, `files[].path`, and `files[].hash` are unchanged. Hashes use the
  V1 canonical text form (CRLF normalized, one trailing newline removed).
- Language ids and aliases are canonicalized before configuration and output
  (`kt` becomes `kotlin`, `cs` becomes `csharp`, and so on).
- Annotation records retain `role`, `intents`, `source`, `confidence`, the flat
  span fields, `nodeAnchor`, and `provenance`.
- The default file set excludes files with no annotations, candidates, or
  diagnostics; `--all` includes all discovered files.
- File roots use the file's parent as the project root and report the file name
  relative to that parent.

## Runtime differences exposed by the CLI

- V2 has no syntax-only path and the batch CLI has no editor host channel.
  `--no-lsp` and `--provider editor` are rejected; use `--provider managed` or
  `--provider attached`.
- `--init` materializes pack defaults into `<root>/.cint/knowledge.json`
  (or the path passed with `--knowledge`). `--knowledge` without `--init` is an
  invalid argument; no-init analysis uses an in-memory store and never writes a
  project knowledge file.
- `--full-rescan` invalidates target paths before analysis.
- `--format ndjson` emits one record per file plus a final meta record.

## Provisional detection and 084 deferrals

Default analysis is wired to `DefaultRulePacks`, the language-neutral rule
registry in `analysis/default-rulepacks` covering every default-enabled bundled
rule. The following named deferrals belong to ticket 084:

- `088-v2-detection-fact-parity` — full intent-fact parity and confidence values.
- `089-v2-bookkeeping-validation-parity` — declaration bookkeeping and validation gating.
- `090-v2-composition-parity` — scaffold, derivative-use, and return-category parity.
- `091-v2-candidate-parity` — candidate emission, identity, and grouping parity.
- `092-v2-service-init-parity` — constructor injection and service-init lifecycle parity.
- `093-v2-non-kotlin-parity` — JavaScript, TypeScript, Vue, C#, and C facts and spans.

## Exit codes

`0` success, `2` invalid arguments, `3` missing root, `4` missing
provider/oracle, `5` missing grammar, `6` knowledge error, `7` analysis failure,
`8` output failure, `1` internal error. Errors are a single JSON object on
stderr with `error.code`, `error.kind`, `error.message`, and stable details.
