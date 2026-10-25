---
name: MCP provider configuration and language startup
status: in-progress
depends-on: [097]
reviewers: [correctness, architecture]
---

## Goal

Make `cint-mcp` usable on new repositories in every supported language, not just
Kotlin, by exposing provider configuration and failing with actionable errors
when no oracle can be resolved.

## Scope

- Add a documented `cint-mcp` provider configuration surface, for example a
  `--provider-config PATH` JSON file and/or repeated
  `--provider-command LANGUAGE=COMMAND` flags, wired into
  `RuntimeConfig.providerCommands` / `providerConfigs` / `languageOptions`.
- Keep managed, attached, and editor modes representable. Provide sensible
  managed defaults only where they are genuinely available; otherwise fail with
  a clear precondition error naming the missing command/endpoint.
- Add a `repo.languages` (or equivalent) tool that lists supported languages,
  resolved provider mode, and whether a provider is configured/available.
- Ensure `analysis.run` on a new repo never silently falls back to syntax-only;
  the missing-oracle error must tell the agent what to configure.
- Tests: config parsing, Kotlin + at least one non-Kotlin language selection,
  managed/attached/editor handling, missing provider errors, and the languages
  report.
- Update usage/docs and the MCP golden path.

## Acceptance

- An MCP client can start `cint-mcp` for a supported non-Kotlin repo with a
  configured provider and run analysis.
- Starting without a usable provider fails explicitly and points to the config.
- No syntax-only production path is introduced.
- `just build`, `just test-engine`, `just lint`, `just graph-guard` pass.

## Out of scope

- Implementing new language servers or changing the language set.
- Editor-proxy channels and AI fallback.
- New detection semantics.

## Notes

- The runtime already carries provider commands/configs; this ticket exposes and
  validates them through the MCP app.
- This is the startup half of the MCP golden path.
