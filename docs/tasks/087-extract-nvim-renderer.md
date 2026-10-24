---
name: Extract the Neovim renderer
status: todo
depends-on: [086]
reviewers: [architecture, correctness]
---

## Goal

Move the Neovim renderer into its own repository against the versioned external protocol.

## Scope

- Create the code-intent-nvim repository.
- Move nvim-renderer code, tests, and docs.
- Pin the engine protocol version and test against a released engine binary.
- Update install and development instructions.

## Acceptance

- The renderer repo builds and tests against a released engine.
- The engine repo no longer contains Neovim-specific code.
- Cross-repo compatibility failures are explicit.

## Out of scope

- VS Code or other editors.

## Notes

- Do not extract before the protocol is versioned and stable.
