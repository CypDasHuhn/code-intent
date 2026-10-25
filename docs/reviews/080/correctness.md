# 080 correctness review

## Round 1

Verdict: **FINDINGS**

1. `analysis-engine-v2/apps/cli/src/main/kotlin/dev/cint/v2/cli/Cli.kt:89-92,129-134,251-281` — File roots are accepted by `resolveRoot`/`discoverFiles` but passed as `rootPath`. `LanguageSupportFactory.kt:164,180-193` requires a directory, so file-rooted runs fail with exit 2. Fix: pass `root.parent` as `rootPath`, or omit it so marker discovery runs.

2. `Cli.kt:212-220` (with `RuntimeConfig.kt:37`) — `providerConfigs` and `initializeLanguages` are keyed by raw `--lang`, while runtime uses `descriptor.id`. `--lang kt --provider managed --provider-command X` silently ignores `X`; `--init --lang kt` materializes nothing. `Cli.kt:152` also emits the alias as `language`. Fix: canonicalize aliases before building the config and emit the descriptor id.

3. `Cli.kt:91,208-211` (with `DefaultRuntime.kt:370-376`) — `--knowledge` without `--init` opens a persistent store, materializes defaults, and writes on shutdown, contradicting `CLI_DIFFERENCES.md:36-37`. Fix: only enable persistence under `--init`, or make the no-init path read-only.

4. `Cli.kt:288` — The skip test checks every absolute-path element. A root under any directory named `build`, `out`, `target`, or `.git` is silently skipped entirely, yielding success with zero files. Fix: compare root-relative segments and never skip the root.

5. `CliOptions.kt:74,153-158`, `Cli.kt:320-323` — Unknown languages are unvalidated: a typo returns exit 0 with an empty document, or exit 7 if a matching extension exists. `--provider editor` is advertised (`CliOptions.kt:53`) but always fails because no host channel is supplied (`LanguageSupportFactory.kt:205-216`). Fix: validate language/alias as INVALID_ARGUMENTS and reject or stop advertising editor mode.

6. `Cli.kt:40-46`, `CliOutput.kt:176-196` — `explicitNulls`/`encodeDefaults` plus the fallback path make every candidate emit `path` and null `provider`/`model`/`rationale`, while `CLI_DIFFERENCES.md:17-18` calls the latter optional and frozen candidates omit them. Fix: omit absent optionals and drop or document the injected path.

7. `Cli.kt:145-150` — Filtering keeps files only when annotations or candidates exist, so diagnostic-only files vanish from `files[]` and `meta`, undermining `files[].diagnostics`. Fix: retain files with diagnostics.

8. `CliTest.kt:223-245,117-157,159-204` — The corpus test compares only key names, never values. Failure tests inject errors at runtime construction; init has no no-init negative; file roots, aliases, full-rescan, NDJSON, output failure, and real-runtime provider/grammar failures are untested. Fix: add real-runtime corpus parity and those paths.

Summary: Argument parsing, exit-code mapping, deterministic ordering, the `cint-v2` name, and the absence of V1 dependencies are sound. The gaps are file-root handling, alias/provider/knowledge plumbing, a root-skip filter that can erase the analysis, unvalidated language/editor flags, candidate-field drift, hidden diagnostic-only files, and tests that do not prove corpus parity.

## Round 2

Verdict: **PASS**

1. CONCUR — file roots use `target.parent` (`Cli.kt:89-93,282-287`); test `CliTest.kt:276-303`.
2. CONCUR — aliases are canonicalized at parse time (`CliOptions.kt:154-159,184`) and used for provider/init config (`Cli.kt:217-225`) and output (`Cli.kt:157`); test `CliTest.kt:317-351`.
3. CONCUR — `--knowledge` without `--init` is rejected (`CliOptions.kt:168-170`) and no-init creates no store; test `CliTest.kt:353-368`.
4. CONCUR — the skip check is root-relative (`Cli.kt:304-318`); test `CliTest.kt:305-315`.
5. CONCUR — unsupported languages are rejected (`CliOptions.kt:154-159`) and editor mode is no longer parsed (`CliOptions.kt:207-215`); test `CliTest.kt:140-143`.
6. CONCUR — nulls are omitted (`Cli.kt:34-52`) and the candidate fallback path is removed (`CliOutput.kt:176-193`); test `CliTest.kt:400-442`.
7. CONCUR — diagnostic-only files are retained (`Cli.kt:146-155`); test `CliTest.kt:370-398`.
8. CONCUR — 19 tests cover happy and failure paths, including real runtime analysis (`CliTest.kt:498-552`). Exact value parity is still not asserted, but `CLI_DIFFERENCES.md:50-62` explicitly defers `084.cli-corpus-parity` and the other parity gaps to ticket 084.

`BundledRuleEvaluators` covers all 14 default-enabled pack rules, with specs matching `bundled-packs.json`, and is the default registry in both `DefaultIntentPipeline.kt:27` and `RuntimeComponents.kt:58`, so the real CLI pipeline now runs; `BundledRuleEvaluatorsTest.kt:22-94` exercises it. The 084 deferral is explicit in that KDoc (`BundledRuleEvaluators.kt:14-20`) and in `CLI_DIFFERENCES.md`. Build/test execution was not run, so compilation and test passing remain unverified.
