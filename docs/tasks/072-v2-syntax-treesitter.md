---
name: V2 tree-sitter syntax adapter
status: done
depends-on: [064, 065]
reviewers: [correctness, architecture]
---

## Goal

Implement SyntaxEngine with tree-sitter: parse, incremental tree, changed ranges, node hashes, and fact extraction.

## Scope

- Kotlin first, then the existing V1 language set as grammars are wired.
- Tree-sitter cinterop and native library loading.
- Parse/diff/nodeHash/facts behind the contract.
- Fixture parity with the frozen V1 syntax facts.

## Acceptance

- Kotlin fixture parity is green.
- No file IO, knowledge, or semantic-provider access in the adapter.
- Missing grammars fail explicitly.

## Out of scope

- Semantic facts, LSP, index, and language facade.

## Notes

- The Rust syntax-kernel option remains a later performance decision; keep the contract stable.
