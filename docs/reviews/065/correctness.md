# Correctness Review — Ticket 065

**Verdict: FINDINGS**

1. `analysis-engine-v2/contracts/src/commonMain/kotlin/dev/cint/contracts/util/Identity.kt:74-80` (with `knowledge/Selector.kt:26-27`) — Selector hashing is no longer injective. `computeSelectorHash` now keys on `identityKey`, which omits `importPath`, `declaringType`, and `structuralPattern` and concatenates `:`/`#` without escaping: `FunctionSelector("a:b")` and `FunctionSelector("a", receiverType="b")` both produce `fn:a:b`. The prior `hashCode()` covered every data field, so this is a regression. Encode all selector fields length-prefixed (or hash a canonical serialized form) and add collision tests. Relatedly, `Selector.kt:21-23` requires a nonblank `qualifiedName`, so an import- or structural-pattern-only selector from `knowledge-model.md:44-52` is unrepresentable if such selectors are valid.

2. `.../util/Identity.kt:78` — `else -> selector.toString()` silently hashes arbitrary objects. Default `toString` embeds identity hash/class name, so the no-`hashCode`-leak goal fails for unknown types, and `SelectorKind.PATTERN` has no typed path. Restrict the parameter to a sealed selector type or fail explicitly; add a real pattern selector.

3. `.../data/Span.kt:21-24` (used by `Selector.kt:54`) — offsets multiply `Int` by 10000. `Span(0,10000,0,10000)` and `Span(1,0,1,0)` both offset to 10000, so `contains`/`intersects` and `SpanSelector` identity collide; lines >=214749 overflow into negative values. Validate `col < 10000`, compute in `Long`, and build the selector key from line/col rather than offsets.

4. `.../util/Errors.kt:82` — `Failure.getOrElse` throws instead of returning the supplied default. Through a `Result<String>` reference the override is reachable with a normal default, so fallback callers crash. Return `default` (or restructure `Failure`) and add `failure(...).getOrElse("x") == "x"`. Carried over from the committed version, but still live.

5. `.../knowledge/KnowledgeSnapshot.kt:15-18` — decoding a snapshot without `timestamp` silently substitutes wall-clock “now”, and any absent/unknown `version` becomes 1; stale or future payloads therefore decode as valid current snapshots. `@EncodeDefault` fixes encoding only. Require/validate version against supported values and fail, rather than inventing a timestamp.

6. `.../ports/Transports.kt:16` — `IpcTransport.receive` returns `ContractResult<IpcMessage?>`; `null` conflates “no message currently” with “stream closed”, and no error path distinguishes them. Make end-of-stream an explicit error/state or document and enforce null semantics. Ports otherwise stay narrow and platform-free.

SHA-256 changes are correct: padding, length encoding, endianness, and rotations match FIPS 180-4, `EMPTY` is computed and consistent, and UTF-8 is used. Current `linuxX64Test` results show IdentityTest 7, InvalidInputTest 8, and SerializationTest 43 tests with zero skips/failures, so the native path is genuinely exercised; `NodeHash` length framing is unambiguous. Remaining defects are selector/span identity, `getOrElse`, permissive snapshot decoding, and `receive` null ambiguity. Tests are non-vacuous but miss field-collision, offset-aliasing/overflow, and absent `version`/`timestamp` cases.

## Round 2

1. CONCUR — `Selector.kt:29-38,65-74,88-89,94-107`, `Identity.kt:76-92`: every field is length-framed and the `Selector` hierarchy is exhaustively typed; `IdentityTest.kt:89-138` covers delimiter, field, and line/column distinctions.

2. CONCUR — `Identity.kt:76-83`, `Selector.kt:79-92`: no `toString` fallback remains; `PatternSelector` is a typed member.

3. CONCUR — `Span.kt:24-27` uses Long offsets and `Selector.kt:65-74` keys on line/column, fixing aliasing and overflow. See new regression below.

4. CONCUR — `Errors.kt:47-49`: `getOrElse` is implemented once and returns the default on failure; `InvalidInputTest.kt:180-186` exercises it through `Result<String>`.

5. CONCUR — `KnowledgeSnapshot.kt:15-27` makes `version`/`timestamp` required and validates both; `InvalidInputTest.kt:188-207` covers absence and unsupported version. Removing `packDefaults`/pack fields is wire-incompatible with round-1 snapshots, acceptable only pre-release.

6. CONCUR — `Transports.kt:10-25` replaces null with explicit Message/Empty/Closed; `TransportTest.kt:14-33` covers the message path, though its Empty/Closed `assertIs` checks are tautological.

New regression: `Span.kt:16-18` and `InvalidInputTest.kt:51-56` now reject columns ≥10,000, so legitimate long-line/minified spans that previously constructed now throw. Replace the column cap with a packed Long offset (e.g. `line.toLong() shl 32 or col.toLong()`) and retain only nonnegative/order checks. Native SHA-256 is unchanged; build reports still show 8/11/43/1 tests, zero skips/failures per target.

Round 2 verdict: FINDINGS — six original issues resolved, one new over-strict validation regression.

## Round 3

CONCUR — `Span.kt:21-24,45-48` packs `(line << 32) | col` as Long; for validated nonnegative Int coordinates this preserves lexicographic ordering, so `contains`/`intersects` semantics are unchanged, and the maximum value `0x7FFFFFFF7FFFFFFF` cannot overflow. `InvalidInputTest.kt:51-67` meaningfully covers long columns, containment/intersection, the former line/column alias, Int-range ordering, and decode. It does not exercise containment across Int-range lines, but the packing argument makes that low risk.

CONCUR — `TransportTest.kt:21-42` now drives Message/Empty/Closed through `receive`; the assertions are no longer tautological.

CONCUR — `FunctionAnnotation.kt:11-49` no longer carries `rationale`; `SpanOverride` retains it per the model, and no test or production reference to the removed field remains.

No new correctness regression found.
