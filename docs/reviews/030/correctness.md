# Correctness review — ticket 030 (client-authored annotations)

## Round 1

Verdict: The port preserves the experimental vertical slice across the new
module layout and the evidence-free contract. Client persistence round-trips,
the host merges user source facts with pack facts and removes them on deletion,
symbol-anchored `PURE` reaches `isAnnotatedPure` and derivative composition, the
split leaf now receives user contributions, and HTTP analysis loads annotations
from the request root. Compared with `experiment/client-authored-annotations`,
those paths are intact; the deliberate change that makes range records ignore a
hash miss is the main behavioral difference. Correctness gaps remain in anchor
coordinate conversion and stale-anchor handling, split region scoping, default
renderer visibility, and the evidence-removal test adaptation.

### Findings

1. **`analysis-engine/core/core/src/main/kotlin/dev/cint/core/user/UserAnnotationResolver.kt:37-42,68-70,161-181` — persisted byte columns are applied as UTF-16 string indices.**

   Neovim and tree-sitter columns are UTF-8 byte offsets within a line, but
   `lineStarts` is built with `text.forEachIndexed` and `offset` computes
   `start + col` on the Kotlin `String`. For any non-ASCII character before the
   anchor, `rangeText` and the derived `Contribution.startByte`/`endByte` point
   at the wrong characters. A symbol annotation on `"πfun make(): ..."` slices
   the range starting at character 7 instead of byte 7, fails the hash check,
   and disappears; a range annotation produces valid-looking spans with byte
   offsets that disagree with the published line/column span and with the
   split-shift arithmetic. The fake nodes in `UserAnnotationsTest` use
   character-based line/column values, so the ASCII-only suite cannot catch
   this.

   Fix: convert line plus byte-column coordinates to absolute UTF-8 byte
   offsets, slice `rangeText` from the UTF-8 byte array, and use those byte
   offsets for `startByte`/`endByte`. Add a resolver/host test with a real
   tree-sitter tree and a multi-byte prefix.

2. **`analysis-engine/core/core/src/main/kotlin/dev/cint/core/user/UserAnnotationResolver.kt:49-55,83-93` — a stale symbol anchor can rebind to an arbitrary node with the same text.**

   When no named declaration has the requested symbol, `matches` falls back to
   every descendant whose full text equals the symbol, not just declarations,
   functions, or properties. `verified` then accepts a node whose text hash
   equals the stored hash without requiring the stored range to be inside that
   node. A record for a removed or renamed `make` whose client hash is
   `sha256("make")` can therefore attach to a leftover call, identifier, or
   string containing `make` anywhere in the file, publishing a user source
   annotation at the wrong node. The two stale tests only cover an
   out-of-range hash and a hash of an older declaration, so this path is
   unpinned.

   Fix: restrict symbol rebinding to named declaration/function/property
   candidates (or a node with a matching declared name), require the stored
   range and/or a declaration-content hash for verification, and drop the
   record when no verified declaration exists. Add a stale test where the old
   word remains in a call or comment after the declaration is removed.

3. **`analysis-engine/core/core/src/main/kotlin/dev/cint/core/user/UserAnnotationResolver.kt:56-58` with `analysis-engine/core/core/src/test/kotlin/dev/cint/core/user/UserAnnotationsTest.kt:102-116` — range anchors ignore their content hash, so stale ranges keep applying to unrelated text.**

   Client-written range records always carry a `contentHash`, but the range
   branch selects the stored span without checking it. If the selected text is
   edited or deleted, the annotation still creates a contribution at the old
   coordinates, which may now cover unrelated code or an empty span. The new
   `clientRangeAnchorFallsBackAfterAHashMiss` test explicitly asserts that a
   mismatched hash is accepted, so the stale behavior is pinned as desired. A
   coordinate fallback is reasonable for a record with no hash, but applying a
   hash that no longer matches is indistinguishable from misapplying a stale
   annotation.

   Fix: if a hash is present and no longer matches the current range, treat the
   record as stale and drop it (or mark it stale); use coordinate-only fallback
   only for records without a hash or with an explicit range-anchor kind.
   Replace the hash-miss acceptance test with a stale-range test and a
   client-shaped fallback test.

4. **`analysis-engine/core/host/src/main/kotlin/dev/cint/host/HostPipeline.kt:85-111` — split region scoping clamps out-of-region annotations instead of intersecting them with the region.**

   `scopeToRegion` selects records by line overlap only and then uses
   `coerceAtLeast(0)` on the line offsets. A range that starts before the
   `<script>` region or ends after it is mapped to local line 0 / the last line
   without adjusting the out-of-region columns, so it is republished over the
   surrounding host lines instead of being clipped or dropped. When the region
   text ends with a newline, `lastLine` also includes the following host line
   (the end-tag line); a range annotation there can become a zero-length
   contribution at the end of the region and shift back onto the end tag. The
   split test only covers a symbol annotation exactly on the region start line.

   Fix: compute the region's actual last line excluding the trailing newline,
   intersect the stored span with the region bounds in host coordinates, and
   map only the intersected span (or drop records that are not actually
   contained, if that is the contract). Add a boundary range test that starts
   before the region and one that ends on the end-tag line.

5. **`nvim-renderer/lua/cint/render/init.lua:122-142` with `nvim-renderer/lua/cint/core/config.lua:28-43` and `nvim-renderer/tests/annotations_spec.lua:75-80` — the shipped default policy never shows user-authored `PURE` or unknown-intent annotations.**

   The `U` marker is attached only when `next(opts) ~= nil`. Under the default
   configuration a `PURE` or custom user intent has no `tags` entry, resolves to
   the `SHOW` meaning, and `rules.SHOW` is `false`; `handler_opts` therefore
   returns an empty table and no extmark or marker is created. The acceptance
   case “appears in the next analysis/render” holds only for intents that
   already have a visible default tag, and the renderer test supplies a custom
   `PURE -> HIGHLIGHT` configuration, so the default path is untested.

   Fix: emit the user marker independently of the resolved meaning (or give the
   user source tier a visible default), and test rendering a user `PURE` and an
   unknown intent under the shipped configuration, including a merged
   pack-plus-user span.

6. **`analysis-engine/languages/treesitter/src/test/kotlin/dev/cint/treesitter/language/UserAnnotationPureTest.kt:89-91` — the evidence-removal adaptation slices UTF-8 byte offsets as UTF-16 string indices.**

   Replacing `it.evidence.contains(...)` with
   `text.substring(it.startByte, it.endByte)` reintroduces exactly the
   byte/character mismatch that ticket 032 removed. The current fixture is
   ASCII, but a non-ASCII source would make these assertions read the wrong
   slice and could produce a false pass or failure. Ticket 032’s contract calls
   for identifying spans via the shared byte-safe text helper.

   Fix: use a byte-safe slice (`text.toByteArray(Charsets.UTF_8).copyOfRange(...)`
   decoded as UTF-8) or move the shared `sourceText` helper into the core test
   fixtures and depend on it here. Add a non-ASCII span assertion.

### Concurs / Dissents

- Concurs: the client record shape (relative path, span, symbol, content hash,
  intents, note, `createdAt`) round-trips through the Kotlin parser/encoder,
  including unknown intent names, and the parser also accepts the versioned
  client object and the nested `anchor` form.
- Concurs: `HostPipeline` reads the annotation file per analysis from its
  configured root, path matching accepts project-relative and absolute source
  paths, and the CLI, live server, HTTP analyzer, and split leaf all pass a
  usable root. The HTTP path now selects the request root rather than a
  startup-fixed pipeline.
- Concurs: user contributions are seeded as fixed source facts before the
  strata, merged with pack contributions by role/byte span, and deletion
  removes the user provenance without disturbing pack output.
- Concurs: the `user:` rule namespace and `PURE` owner are rejected with named
  scheduler errors, so a loaded pack cannot collide with the user layer.
- Concurs: symbol-anchored `PURE` reaches `RuleContext.isUserPure`,
  `CapabilityView.isAnnotatedPure`, and derivative composition through both
  direct and transitive property uses; the pure test exercises that path.
- Concurs: a malformed nvim file aborts the mutation, preserves the original,
  and writes a backup; a Kotlin parse failure degrades to no user annotations
  rather than failing analysis.
- Dissents: findings 1-6.

### Residual observations (not filed)

- The persisted `note` is no longer surfaced after evidence removal; it is
  available only in `.cint/user-annotations.json` and the client list command.
  If the note is meant to be visible in analysis output, it needs a remaining
  contract field.
- `PURE` on a visual range does not promote derivatives: the resolver keeps the
  exact selected range, while `isUserPure` requires an exact function/declaration
  span. Only symbol anchors currently participate.
- `UserAnnotationsJson.parse` ignores the version, silently drops invalid
  entries, and defaults missing coordinates to zero; host-side corruption is
  reported only to stderr.
- There is no end-to-end test that feeds a client-produced file through
  `HostPipeline` and back to the live payload, no editor/daemon restart test,
  and no assertion that deleting a persisted `PURE` record removes derivative
  promotion.
- `HostPipeline.root` defaults to `null`, so library callers that omit it
  silently opt out of user annotations; the shipped hosts supply it.
- Static review only; no source edit, build, test, lint, or formatter command
  was run by this role, per `docs/workflow.md`.

## Round 2

Verdict: The byte-column conversion, stale-symbol candidate restriction,
range-hash rejection, default `U` marker, and byte-safe pure assertions are
addressed in the current sources and tests. The split clipping and trailing
end-tag handling are also corrected in isolation, but the new strict range hash
verification interacts badly with clipping: a real client range that crosses
the region boundary is clipped and then dropped because its stored hash still
covers the original host selection. The new clipping test hides this by using a
record with no hash. The stale-symbol code is sound, but its non-declaration
regression test passes through an empty syntax model and does not exercise the
removed fallback. No other regression was found in the user-pure, HTTP
request-root, or renderer paths.

### Findings

1. **`analysis-engine/core/host/src/main/kotlin/dev/cint/host/HostPipeline.kt:81,85-127` with `analysis-engine/core/core/src/main/kotlin/dev/cint/core/user/UserAnnotationResolver.kt:58-61` and `analysis-engine/tests/rulepacks-tests/src/test/kotlin/dev/cint/rulepacks/tests/user/SplitUserAnnotationsTest.kt:25-56` — clipped split ranges retain their original hash and are now rejected.**

   `scopeToRegion` intersects the stored span with the region and copies the
   `contentHash` unchanged. The resolver then hashes the clipped local text and
   returns `null` when it differs from the hash of the original host selection.
   The Neovim writer always stores a hash (`nvim-renderer/lua/cint/ops/annotations.lua`),
   so any client range that actually starts before the region or ends on the
   end-tag line will be silently dropped instead of clipped. The new clipping
   test omits `contentHash`, so it exercises only the legacy hashless fallback;
   the tested clipping behavior is unreachable for client-produced records.

   Fix: verify a range against its original host span before remapping, then
   recompute the hash for the clipped local selection (or explicitly decide that
   clipped records are stale and drop them). Add a hashed range that starts
   before the region and a hashed range that ends on the end-tag line; both
   should assert the agreed outcome rather than relying on a hashless record.

2. **`analysis-engine/core/core/src/test/kotlin/dev/cint/core/user/UserAnnotationsTest.kt:115-131` — the stale non-declaration test does not pin the removed all-descendant fallback.**

   The test resolves against `emptyModel`, so `candidates` is empty before the
   new restriction is applied. It would still pass if `candidates` again scanned
   every descendant. The production restriction in
   `UserAnnotationResolver.kt:133-150` is correct, but the regression test is
   vacuous.

   Fix: use a syntax model containing some functions/declarations but no `make`,
   retain a `make` identifier or call in the tree, and assert that no
   contribution is produced. A second assertion with a real tree-sitter parse
   would also cover the real syntax profiles.

### Concurs / Dissents

- Concurs: finding 1 is fixed. `lineStarts` and offsets are byte-based, range
  text is sliced from UTF-8 bytes, and `startByte`/`endByte` are byte offsets.
  `resolverUsesUtf8ByteColumns` now catches the previous character-index
  behavior.
- Concurs: finding 2 is fixed in production code. Candidates are limited to
  named functions/declarations/properties, and a hashless record still requires
  the stored range to be inside the candidate. A stale record whose old word
  remains only in a call or comment no longer rebinds.
- Concurs: finding 3 is fixed. A present range hash must match the current
  range text, and the old hash-miss acceptance test is replaced by a stale
  test plus a hashless fallback test.
- Dissents: finding 4 is only partially resolved. The last-line calculation
  excludes the trailing newline and the new tests drop end-tag-line records, but
  the clipping path conflicts with the new range-hash check as described above.
- Concurs: finding 5 is fixed. A user-authored span receives `sign_text = "U"`
  even when `SHOW` is `false`, and the new spec covers the shipped default
  policy with merged pack-plus-user provenance.
- Concurs: finding 6 is fixed. `UserAnnotationPureTest.slice` now decodes the
  UTF-8 byte range, and the fixture has a non-ASCII prefix.

### Residual observations (not filed)

- Symbol verification still accepts a candidate when its full node text hashes
  to the stored hash without requiring the stored range to be inside it. With
  the client's symbol hash this normally means the node text must equal the
  symbol, but a stale symbol can still rebind to another same-named declaration
  whose span happens to contain the old range. A two-declaration test would pin
  the intended identity rule.
- The Round 1 concurrence that `user:` rule ids and `PURE` ownership are
  rejected is no longer true: `RuleSchedulerTest` now asserts
  `userPrefixedPackRuleRemainsValid` and `packCanOwnPureIntent`. This appears to
  be the deliberate open-intent resolution, and `USER_CONTRIBUTOR_ID` remains
  under the reserved `core:` prefix, so I do not treat it as a correctness
  regression.
- The split clipping tests use ASCII, so they do not distinguish the new
  byte-based line lengths from character lengths; a non-ASCII region line would
  strengthen the boundary coverage.
- Static review only; no source edit, build, test, lint, or formatter command
  was run by this role, per `docs/workflow.md`.

## Round 3

Verdict: Both Round 2 findings are addressed. `HostPipeline.scopeToRegion` now
verifies a hashed record against its original host span before remapping and
then derives the hash from the clipped local selection, so a real client range
that crosses a region boundary is clipped and kept rather than dropped; the
hashless path keeps the coordinate fallback. The stale-symbol test now resolves
against a syntax model that has real candidates (`keep`, `ref`) and a tree that
still contains the `make` identifier, so it exercises the removed
all-descendant fallback instead of passing through an empty candidate list. No
regression was found in the changed paths: the callback host is correct at each
split level, the non-split path is unchanged, and the unrelated import removals
are clean. One new finding remains, independent of the two fixes: the Lua writer
and the Kotlin resolver disagree on hashing a range whose last selected line is
empty, so such a valid client range annotation is silently dropped in every
path.

### Findings

1. **`nvim-renderer/lua/cint/ops/annotations.lua:154-163,177` with `analysis-engine/core/core/src/main/kotlin/dev/cint/core/support/hashing/Hashing.kt:5-8` and `analysis-engine/core/core/src/main/kotlin/dev/cint/core/user/UserAnnotationResolver.kt:76-78` — a range ending on an empty line hashes differently in the client and the engine and is silently dropped.**

   `range_anchor` joins the selected buffer lines with `"\n"` and
   `record_for` hashes that string raw. When the last selected line is empty,
   the joined text ends with `"\n"` (for example `"code\n"`), and the client
   stores `sha256("code\n")`. `contentHash` canonicalizes with
   `replace("\r\n", "\n").removeSuffix("\n")`, so the resolver hashes the same
   selection as `sha256("code")` and rejects the record as stale at
   `UserAnnotationResolver.kt:77`; in split files the new host-span check
   (`HostPipeline.kt:99-102`) rejects it earlier. The user sees the range
   annotation persisted and listed but never applied, and re-annotating the same
   empty-terminated selection reproduces the drop. `CintAnnotate` accepts
   linewise ranges (`commands.lua:161-168`), so selecting a block through a
   blank separator line is enough to trigger this. The nvim spec only hashes a
   single non-empty line (`annotations_spec.lua:47`), and every Kotlin test
   synthesizes hashes with `contentHash`, so the two layers never meet and the
   mismatch is unpinned.

   Fix: make the writer canonicalize exactly like `contentHash` (normalize
   `"\r\n"` and strip one trailing `"\n"`) before hashing, or make the engine
   hash the exact selected bytes; pick one canonical form and state it. Add a
   cross-language test: persist a two-line range whose last line is empty
   through the Lua writer shape and assert the resolver produces a
   contribution (a resolver-level fixture plus an nvim assertion on the stored
   hash).

### Concurs / Dissents

- Concurs: Round 2 finding 1 is fixed. `scopeToRegion` verifies the stored hash
  against the original host text before any remapping and recomputes
  `contentHash` from the clipped local selection, so clipping no longer
  conflicts with the range-hash rejection. The hashless branch still passes
  `null` through.
- Concurs: the new split tests pin the claimed behavior. The start-before-region
  case and the end-tag-line case both carry a client-shaped hash and a
  non-empty last line, the stale case hashes only the region text (so it fails
  if the host-span check is removed while a clip-only hash would otherwise be
  accepted), and the start case uses non-ASCII in both the prefix and the
  region so character-based offsets and line lengths would fail the byte-column
  assertions.
- Concurs: Round 2 finding 2 is fixed. `staleSymbolDoesNotRebindToANonDeclaration`
  supplies `keep`/`ref` candidates and keeps a `make` identifier in the tree;
  restoring the old `candidates`-as-all-descendants fallback would make the
  identifier a verified candidate and the assertion fail.
- Concurs: the callback signature change is threaded correctly. `analyze`
  passes `host` once, every leaf call passes `userContributionsFor(host, file,
  ...)`, the recursion re-enters `analyze` with the current region text as its
  host, and the CLI, both `LiveServer` split paths, the server HTTP path, and
  the tests all adapt to the five-argument shape. The default lambda has the
  same arity.
- Concurs: the non-split path is unchanged (`userContributionsFor(source,
  source, tree, syntax)` with no region), and client-produced symbol records
  are not newly dropped: they store `sha256(symbol)` with the symbol span, so
  the pre-check hashes the same word the resolver's range fallback would.
- Concurs: the three `just lint` import removals (`ScopeFact`,
  `ErrorHandlingConfig`, `validationPipeline`) had no remaining references and
  do not change behavior.
- Dissents: finding 1 above; no dissent against the Round 2 fixes.

### Residual observations (not filed)

- Nested split recursion still passes the immediate region text as `host` while
  persisted records stay in root-file coordinates, so a language whose region
  contains another split region would drop or misplace its annotations. The
  current Vue frontend cannot produce nested `script_element` regions, and the
  behaviour predates this change, so it is latent rather than a regression.
- `lineLength` counts the `"\r"` of a CRLF line, so a record clipped to the end
  of the region gets an `endCol` one byte past what Neovim shows for that line.
  This is pre-existing, affects only split clipping on CRLF files, and the hash
  stays internally consistent because both sides slice the same bytes.
- All split fixtures use a single-line region with `startCol = 0`. The typical
  Vue shape (leading newline in `raw_text` plus non-zero `startCol`) and
  multi-line local column mapping are therefore not pinned by a test; they look
  correct by inspection.
- `scopeToRegion` materializes a fresh UTF-8 copy of the whole host file for
  every record; this is allocation cost per region, not a correctness issue.
- Static review only; no source edit, build, test, lint, or formatter command
  was run by this role, per `docs/workflow.md`.

## Round 4

Verdict: The Round 3 finding is fixed on both sides of the contract. The Lua
writer now canonicalizes range text exactly like the engine (`\r\n` to `\n`,
then one trailing `\n` removed) before hashing, so a linewise range ending on an
empty line stores the hash the resolver expects; the nvim spec pins the writer
against the raw joined-text hash, and the new Kotlin test independently mirrors
the canonical form and exercises the resolver with a selection whose text ends
in `\n`. The `gsub` multi-return is consumed safely, the multi-line end-column
case is the one the new tests cover, the added fixture blank line does not
disturb the earlier assertions, and symbol records and ordinary ranges still
round-trip because their text is unchanged by canonicalization. No new finding
and no regression was found, so the review loop can close.

### Findings

None.

### Concurs / Dissents

- Concurs: Round 3 finding 1 is fixed. `content_hash`
  (`annotations.lua:165-169`) replaces `\r\n` and strips one trailing `\n`,
  matching `Hashing.kt:5-8`; `record_for` uses it for every record. The symbol
  anchor still hashes `anchor.text == symbol`, which contains neither `\r` nor a
  trailing newline, so symbol records are unchanged.
- Concurs: the Lua multi-return is safe. Both `gsub` calls are single-variable
  assignments, which take the first result (the string), and the replacement
  count is not appended to the `sha256` argument. Both replacements are
  global, and `"\n$"` can only match the final newline, so `"a\n\n"` becomes
  `"a\n"` exactly as `removeSuffix("\n")` does.
- Concurs: the nvim spec pins the writer. The fixture now ends with a blank
  line, `annotate_range(2, 3)` selects `"val third = 2"` and the empty line, and
  the spec asserts `sha256("val third = 2")` and rejects
  `sha256("val third = 2\n")`, so the old raw hash would fail.
- Concurs: test isolation holds. The added blank line leaves line 0 and line 1
  text unchanged, the single-line range assertion still hashes the current line
  exactly (canonicalization is a no-op without a trailing newline), the
  annotation count is reset to zero before the new block, and
  `unannotate_range(2, 3)` contains the new record so the buffer is clean for
  the malformed-file test.
- Concurs: the Kotlin test mirrors the writer rather than reusing `contentHash`:
  `writerContentHash` performs its own `MessageDigest` over
  `replace("\r\n", "\n").removeSuffix("\n")`, and the span `Span(0, 0, 1, 0)`
  slices text ending in `\n`. It fails if the resolver stops stripping the
  newline and pins the actual writer shape, not just the helper.
- Concurs: multi-line end columns are covered by the new cases. The writer's
  `endCol = #last` is a byte length (zero for an empty last line), and the
  resolver addresses the same end position (`line + 1`, column 0) whose slice
  ends in `\n`; non-empty last lines are unchanged. CRLF is symmetric by
  construction because the writer joins buffer lines with `\n` while the host
  slice's `\r\n` is normalized on the Kotlin side.
- Concurs: the range and symbol shapes still round-trip. Only selections that
  end with a newline change hash, and those were precisely the records that
  previously never resolved; re-annotating replaces them at the same position.
- Dissents: none.

### Residual observations (not filed)

- Neither new test exercises the CRLF branch: there is no `fileformat=dos` or
  literal-`\r` fixture, so the `\r\n` normalization agreement is checked by
  inspection rather than execution. The rules are identical on both sides, and
  a one-sided change would still be caught by the existing assertions for the
  newline case.
- The contract is now pinned at both ends (a Lua hash assertion and an
  independent Kotlin canonicalization helper) but still not by a single
  artifact written in Lua and fed through `UserAnnotationsJson` to the resolver;
  the cross-language fixture gap recorded in the api-fidelity review remains.
- Records persisted before this fix whose selection ended on an empty line
  still carry the old raw hash and remain stale. They never resolved before, so
  no working behaviour is lost and no migration is required; the user
  re-annotates to replace them.
- Static review only; no source edit, build, test, lint, or formatter command
  was run by this role, per `docs/workflow.md`.
