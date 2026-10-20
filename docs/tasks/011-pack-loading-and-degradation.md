---
name: pack loading and degradation
status: done
depends-on: [003]
reviewers: [correctness, architecture]
---

## Goal

Load rulepacks via JVM `ServiceLoader` (`META-INF/services`) with the
architecture.md §9 semantics: no runtime compiler, no classloader leaks,
graceful degradation, reload = daemon restart.

## Scope

- `ServiceLoader` discovery of `RulePack` providers; bundled packs register the
  same way as third-party packs.
- Graceful degradation: a missing, corrupt, or version-mismatched pack is
  reported and skipped; the engine keeps analyzing with what loaded.
- Surface the load errors from 003 (duplicate owner, unknown dependency, cycle)
  with a named error.
- A version/compatibility check against the frozen `api`.
- Reload is daemon restart; no hot-swap machinery is built.

## Acceptance

- A pack dropped on the classpath is discovered and contributes rules with no
  code change.
- A broken/mismatched pack is reported and skipped while the rest analyze.
- No runtime Kotlin compiler and no classloader leaks (documented check).
- `just test-engine` green.

## Out of scope

- Hot reload; optional dependencies (`§10`).

## Notes

- `:CintServerRestart` is the transparent reload path (015, 016).

## Outcome (2026-10-20)

`RulePackLoader` provides validated, immutable pack snapshots with explicit
`RulePack.apiVersion` (pre-construction annotation/method check), unique pack ids
and `<packId>:` rule ids, and deterministic skip-all handling for scheduler
conflicts. Discovery enumerates `META-INF/services/dev.cint.api.RulePack`
explicitly through the configured classloader instead of `java.util.ServiceLoader`
stream iteration; this approved deviation gives deterministic per-entry
degradation and is recorded here. Discovery is bounded (256 resources / 4096
lines), class-loading/linkage/raw-list failures degrade, and `:rulepacks:test`
is part of `just test-engine`. Host wiring and per-language pack selection remain
015. Both reviewers concur after the final rounds.
