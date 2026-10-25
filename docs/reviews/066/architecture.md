# Architecture Review — Ticket 066

Verdict: FINDINGS

1. **Corpus is not self-contained.** `capture.py:409-437` copies inputs from `--testing-grounds` and synthesizes `.cint/suggestions.yml`/`ai-restrictions.json`, but only outputs are frozen. `README.md:67` implies `<REPO_ROOT>/testing-grounds/<language>` is the capture root; that path is mutable, includes a tracked `.cint/config.overlay.yml`, and is not what produced the transcripts. V2 cannot replay or audit inputs. Fix: add `fixtures/` with exact sources/synthesized `.cint`, hash them, and resolve the token there.

2. **CLI captures bypass isolation.** `reproduce.sh:47-52` runs the CLI against live `$TESTING_GROUNDS/*`, while `README.md:67` claims temp copies with `.cint` removed. Local `.cint` state alters `cli-*.json`. Fix: use stripped temp copies for CLI too.

3. **Transcripts omit requests.** `README.md:48` stores no client requests; scenarios live only in `capture.py:471-1077`. V2 tests cannot replay parameters/state. Fix: store paired request/response records or versioned session JSON.

4. **Reproduction unpinned/destructive.** `reproduce.sh:13-14` deletes the canonical corpus before capture; `reproduce.sh:33-37` builds any `analysis-engine` checkout, never asserting `f22b596`. Failed runs leave it partial; dirty trees silently rewrite the reference. Fix: assert/record the commit, stage in temp, verify, then atomically replace.

5. **Manifest lacks provenance.** `manifest.json:1-26` has only artifact hashes and protocol version: no format version, V1 commit, generator/verifier hashes, fixture hashes, or time (`README.md:80` is prose). Fix: add `formatVersion`, `v1Commit`, `captureSha256`, fixture hashes.

6. **Verification incomplete/host-specific.** `verify.py:77` hardcodes `/home/cyp`; `verify.py:136-140` only parses the renderer snapshot, never checking it against transcripts; `verify.py:94-107` checks manifest-to-file, not corpus-to-manifest; `verify.py:61-65` ignores duplicate IDs/order. Fix: reject absolute paths outside synthetic URIs, compare the derived snapshot, enforce manifest key equality, detect duplicate IDs.

7. **Known differences not a contract.** `KNOWN_DIFFERENCES.md:9-11` and `24-29` leave transport/protocol "TBD"; `KNOWN_DIFFERENCES.md:286-287` mixes line-by-line and semantic comparison, contradicting `README.md:6`. Tests cannot distinguish intentional deviations from capture failures. Fix: structured per-transcript/field allow-list, decided versus tentative, one comparison rule.

8. **Boundary/ordering non-determinism.** `capture.py:206-223` stops at the last response without a quiet drain, so trailing notifications may be included or missed; annotation/candidate/pack order is never declared canonical. Fix: fixed drain window and canonical ordering (or order-insensitive compare).

9. **HTTP port race.** `capture.py:298-322` binds then closes a port before server start; another process can claim it. Fix: let the server bind port 0 and report its port, or retry.

The layout cleanly isolates throwaway capture tooling from V1 engine modules and V2 source, and generated artifacts are normalized (no absolute paths, fixed timestamps, deterministic fake provider). The gap is contract completeness: inputs/requests are not frozen, CLI isolation is inconsistent, and provenance/verification do not pin what generated the reference. Fixing these makes the corpus replayable rather than a set of server-side snapshots.

## Round 2

1. **CONCUR** — `README.md:3,67`; `manifest.json:35-56`; `fixtures/` matches `3ccea9e:testing-grounds` blobs; `capture.py:108-131`.
2. **CONCUR** — `reproduce.sh:70-77,96-108` copies fixture sources and removes `.cint`.
3. **CONCUR** — `README.md:48`; session steps carry requests/text; `verify.py:339-384`.
4. **CONCUR** — `reproduce.sh:8-17,39-42,79-87,118-127` pins, stages, verifies, swaps/restores.
5. **CONCUR** — `manifest.json:57-75`; enforced by `verify.py:386-456`.
6. **CONCUR** — `verify.py:119-122,161-165,426-456,493-507,547-553`.
7. **CONCUR** — `known-differences.json:5-23,25-263` has modes/statuses/tickets; `KNOWN_DIFFERENCES.md:5,294-298` defers to it.
8. **CONCUR** — `capture.py:79-88,208-218`; ordering at `manifest.json:64-69`.
9. **CONCUR** — `capture.py:305-309,426-441` retries fresh ports.

Pin change is justified: `3ccea9e:analysis-engine` equals `HEAD:analysis-engine` (`91f8882`), while `f22b596` differs by 400 files; fixtures equal the pinned tree. Round 1's pin was stale.

**DISSENT (minor).** `README.md:64` and `manifest.json:58` claim byte-identical captures, but `sourceHead` (`manifest.json:74`) changes with workspace HEAD. Also `reproduce.sh:49` stages current fixtures and `capture.py` regenerates their hashes, so fixture drift is self-accepted. Fix: verify staged fixtures/contract against the pre-existing manifest, or remove/unhash `sourceHead`. Otherwise the corpus is self-contained and stable.

## Round 3

**CONCUR.** `manifest.json` no longer records `sourceHead` (top-level keys at `verify.py:617-631`), and `README.md:64` now scopes byte-identical recaptures across checkouts. Preflight compares staged fixtures and the contract against the existing manifest (`reproduce.sh:64-66`, `verify.py:581-610`), so drift fails instead of being re-hashed. Final verification replays sessions against the pinned server (`reproduce.sh:132-135`), and `renameat2(RENAME_EXCHANGE)` swaps atomically (`reproduce.sh:27-45,137-138`), leaving the old corpus untouched on failure. The corpus is now stable, self-contained, and reproducible.
