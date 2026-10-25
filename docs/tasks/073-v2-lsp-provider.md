---
name: V2 LSP semantic provider
status: done
depends-on: [064, 065]
reviewers: [correctness, architecture]
---

## Goal

Implement the mandatory SemanticProvider with managed, attached, and editor-proxy modes.

## Scope

- LSP session: initialize, didOpen/didChange, hover/definition/symbol/diagnostics, shutdown.
- Transports: stdio, Unix socket, TCP.
- Editor proxy through HostSemanticChannel over IPC or a test channel.
- Hard errors for unresolved/startup-failed providers; no syntax-only fallback.

## Acceptance

- All three modes pass against a scripted fake LSP server.
- No LSP protocol types leak into contracts or the pipeline.
- Startup, timeout, and crash paths report actionable errors.

## Out of scope

- Language selection and index integration.

## Notes

- Start as one lsp module; split lsp-client/lsp-editor only if it grows.
