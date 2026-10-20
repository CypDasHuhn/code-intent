# Correctness review — ticket 030 (client-authored annotations)

## Round 1

Verdict: The basic vertical slice works: nvim writes project-relative records
under `.cint/user-annotations.json`, the host parses them and injects a
`user:annotations` source layer, rulepack and user contributions merge by
role/byte span, deleting the file removes the user provenance, and a `PURE`
annotation promotes derivative use on the non-split analysis path. The
persistence/merge/delete path is nevertheless not stale-anchor-safe, range
anchors do not actually fall back to ranges, split-language analysis ignores
user annotations completely, and there are several destructive or
misattributing failure modes around malformed files, deletion, and project
root selection. The renderer distinction is also only demonstrable under a
custom configuration, not the shipped default policy.

### Findings

1. **`analysis-engine/frontend/src/main/kotlin/dev/cint/frontend/SplitAnalyzer.kt:46-48` with `analysis-engine/server/src/main/kotlin/dev/cint/server/LiveServer.kt:233-244`, `analysis-engine/server/src/main/kotlin/dev/cint/server/Main.kt:125`, and `analysis-engine/cli/src/main/kotlin/dev/cint/cli/Main.kt:76` — split-language analysis bypasses user annotations entirely.**

   Only `HostPipeline.analyze` now resolves `.cint/user-annotations.json` and
   passes the contributions to `RuleEngine`. The `SplitAnalyzer` leaf still
   calls `RuleEngine().analyze(planFor(...), file, tree, syntax, oracle, config)`
   with the default empty `userContributions`. Every split frontend therefore
   ignores persisted user annotations: they do not appear in the artifact and
   `PURE` cannot promote derivatives. Vue is in the shipped nvim language list,
   so this is a reachable acceptance gap, not only a theoretical one. Fix:
   thread a region-aware user-contribution resolver through
   `SplitAnalyzer.analyze` and its `leaf` callback (translating host-file spans
   into region-local spans and shifting results back), or make the leaf go
   through a pipeline configured for the host file. Add a split-frontend test
   with a persisted annotation and a `PURE` derivative assertion.

2. **`analysis-engine/core/src/main/kotlin/dev/cint/core/user/UserAnnotationResolver.kt:47-56,88-96,129-149` — stale symbol anchors can rebind to unrelated nodes or to leftover text instead of disappearing.**

   `selectSymbol` first matches named declarations by symbol, but if none exist
   it falls back to every descendant whose text equals the symbol; that includes
   string/comment/identifier nodes, not only declarations. The symbol path also
   ignores `contentHash`. If no candidate matches, the second `contentHash`
   branch can still accept the old `annotation.span` text, and for symbol
   records the hash was computed from the word only (`annotations.lua:137`), so
   renaming `make` to `makeFast` leaves `make` hashing successfully at the old
   word columns. A stale annotation can therefore survive a rename/deletion by
   attaching to a string, comment, another same-named declaration, or the
   prefix of the renamed identifier. The stale-anchor test only exercises an
   out-of-range line and cannot catch any of these cases. Fix: record an
   explicit anchor kind, restrict symbol fallback to declaration/function
   candidates, validate the resolved declaration rather than arbitrary node
   text, and add stale tests for rename, removal with the old text still
   present in a comment/string, and a same-named symbol elsewhere.

3. **`nvim-renderer/lua/cint/annotations.lua:125-145` with `UserAnnotationResolver.kt:47-66` — range annotations are hash-anchored, so the advertised range fallback is unreachable.**

   `record_for` always writes `contentHash = sha256(anchor.text)`, including
   for `annotate_range`, but the resolver only uses the stored span when
   `contentHash == null`. For a client-written range record, any edit inside the
   range changes the hash and returns no contribution; even inserting a line
   above the range changes the text sampled at the stored span and drops the
   annotation. `rangeFallbackKeepsTheStoredSelection` constructs a hand-made
   record without a hash, so it does not test what the client actually writes.
   Fix: choose one anchor contract (for example omit the hash for range anchors,
   or add an explicit range-fallback mode that is tried after a hash miss), then
   add a test that writes a client-shaped range record and edits/shifts outside
   the anchored lines.

4. **`nvim-renderer/lua/cint/annotations.lua:34-47,49-63,165-222` — a malformed annotation file is silently treated as empty and then overwritten.**

   `load` returns `{}` for any JSON decode failure or non-table payload, and
   `annotate`/`annotate_range` immediately save `kept + new`; an existing but
   corrupt or future-format file is therefore destroyed without a warning or
   backup. This is a realistic failure mode for a file that users are invited
   to edit by hand. Fix: distinguish “missing” from “present but unreadable”,
   abort mutations on the latter (with a message naming the file), and add a
   corrupt-file regression; optionally write a `.bak` before replacing.

5. **`nvim-renderer/lua/cint/annotations.lua:225-253` — `unannotate` removes unrelated annotations on the cursor line.**

   The deletion predicate is `matches_path and (matches_symbol or covers)`.
   `covers` is true for any record whose line range contains the cursor, so
   unannotating symbol `b` also deletes a symbol annotation for `a` when both
   are on the same line, and with an empty `<cword>` it deletes every record on
   the line. The current test only has one symbol record, so it does not pin
   this. Fix: for records that carry `symbol`, require the symbol/column match;
   use line coverage only for symbol-less range records. Add a test with two
   symbols on one line and a range record overlapping the cursor line.

6. **`analysis-engine/server/src/main/kotlin/dev/cint/server/Main.kt:109-119` with `analysis-engine/host/src/main/kotlin/dev/cint/host/HostPipeline.kt:72-87` — HTTP `/analyze` loads user annotations from the server startup root, not the request root.**

   When `request.config == null`, the shared `defaultPacks` pipeline is used,
   whose `root` was fixed at startup, while `request.root` is used for the
   frontend/oracle/split paths. A request for another project therefore reads
   the wrong `.cint/user-annotations.json` (or the right file is never found).
   Fix: select/cache a pipeline by the request root, or pass the resolved root
   into the annotation-loading path. Add a test where the request root differs
   from the server root.

7. **`nvim-renderer/lua/cint/render.lua:67-82,121-140` with `nvim-renderer/lua/cint/config.lua:27-39` — user-authored annotations are not visible under the shipped default policy.**

   The `"U"` sign and any extmark are only applied when the resolved rule
   produces non-empty `opts`; with the default resolution (`default = "SHOW"`)
   and `SHOW = false`, and with `PURE`/unknown user intents absent from the
   default `tags` table, a user annotation resolves to `SHOW`, produces no
   options, and is not rendered at all. The nvim test only exercises a custom
   `PURE -> HIGHLIGHT` configuration, so the “appears in the next
   analysis/render” acceptance case is not covered under defaults. Fix: emit a
   dedicated user marker independently of the meaning rule, or map unknown user
   intents to a visible default in the base policy, and test rendering with the
   default config.

### Concurs / Dissents

- Concurs: the engine round-trip preserves path, symbol, content hash, range,
  intents, note and `createdAt`; unknown intents survive encoding/parsing, and
  user contributions use `role = SOURCE_INTENT` with a distinct
  `user:annotations` contributor id rather than a fake rulepack.
- Concurs: on the non-split host path, user source contributions are merged by
  role/byte span with pack contributions, the merged annotation carries both
  provenance entries, and removing the file removes the user provenance while
  leaving pack output intact (`HostUserAnnotationsTest`).
- Concurs: `PURE` user annotations reach composition through
  `RuleContext.isUserPure`/`CapabilityView` and `DerivativeComposition`, and the
  pure test demonstrates derivative promotion via both direct and transitive
  property uses. The gap is only the split path in finding 1.
- Concurs: explicit reload is a valid choice for the ticket’s “live refresh
  strategy”, and the nvim reload command requests a fresh analysis after
  reading the file.
- Dissents: findings 1–7 above.

### Residuals (not filed as findings)

- `UserAnnotationsJson.parse` silently drops invalid entries and falls back to
  zero ranges for missing coordinates; a syntactically valid but partially bad
  file can lose individual annotations with no diagnostic.
- The manual JSON encoder escapes only `\ " \n \r \t`; other control characters
  and non-BMP text are emitted raw. The nvim side uses `vim.json.encode`, so the
  two encoders are not equivalent.
- `Path.of(recordPath)` inside the resolver is outside the host’s `runCatching`
  parse guard; an invalid path string can throw out of analysis rather than
  being treated as a bad record.
- `isUserPure` uses exact-span `covering`, so a user `PURE` annotation only
  affects a function when the resolver selected that exact function node;
  annotating a property or an individual use does not affect composition.
- There is no end-to-end test that feeds an nvim-produced JSON file to the
  engine, no editor/server restart test, and the pure integration test bypasses
  `HostPipeline` file loading. The host merge test also does not assert that a
  persisted `PURE` record promotes derivatives.
- nvim writes use a temporary file plus `os.rename`, but there is no lock or
  read-modify-write coordination; concurrent clients are last-writer-wins.

Static review only; no source edit, build, test, or formatter command was run by
this role, per `workflow.md`.
