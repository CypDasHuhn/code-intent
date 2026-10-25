---
name: V2 languages facade
status: todo
depends-on: [065, 072, 073]
reviewers: [correctness, architecture]
---

## Goal

Bind language descriptors to concrete syntax and semantic providers and return a non-null LanguageSupport.

## Scope

- Language descriptors: extensions, parser id, provider mode, root markers, profile.
- Kotlin entry first; add existing V1 languages without changing the facade.
- Selection policy for managed, attached, and editor providers.
- Hard error when no oracle can be resolved.

## Acceptance

- Languages is the only module importing source adapters.
- A Kotlin file resolves syntax and provider correctly.
- Missing provider fails before analysis.

## Out of scope

- Parser/provider internals, index, and runtime.

## Notes

- Keep language entries as data, not class hierarchies.
- Deferred to 078: move or replace `MarkerRootLocator` filesystem traversal with runtime root-location wiring. `languages` ships a default locator only as an overridable seam.
- Deferred to 078: environment provider commands and provider defaults are supplied or overridden by runtime configuration. The shipped Kotlin command is only an overridable data default in `languages`.
