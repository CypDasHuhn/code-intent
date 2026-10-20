# Rulepack support plan

Goal: rulepacks describe detection semantics, not AST traversal, config
decoding, or emit plumbing. Grammar knowledge stays in syntax profiles;
generic facts and builders live in `api`/`core`.

## Target model

A rule should read like:

```kotlin
override fun run(view: View): List<Emit> =
    view.declarations()
        .filter { it.hasAnnotation(config.annotation) || it.hasMagicComment(config.magicComment) }
        .flatMap { decl -> listOf(decl.emit()) + decl.writes().map { it.emit() } }
```

## Support layers

1. **Semantic roles in syntax profiles.** Profiles map grammar node kinds to
   declaration, annotation, modifier, comment, property, write, call, and guard
   roles. Rulepacks never compare raw node kinds.
2. **Semantic facts on `View`.** Declarations (name, annotations, preceding
   comment, scope, writes), writes, comments, enriched calls (path, receiver,
   type, trailing lambda), guards/returns, scopes. Implemented in core over the
   existing `SyntaxModel`/CFG.
3. **Emit builders.** `Emit.of`, `Emit.declaration`, automatic evidence, span,
   byte offsets, source tier, and confidence.
4. **Pack skeleton.** `SingleIntentPack`, matcher union, one rule per pack.
5. **Config support.** Schema/defaults-based typed decoding; language defaults
   as data; no per-pack key loops.
6. **Test harness.** Shared parser dispatch, fixture access, and intent/span
   assertions.

## Sequencing

1. Foundation: profile roles + `View` facts + emit builders.
2. Pack skeleton, config decoding, test harness.
3. Pilot: Bookkeeping.
4. Error handling + Validation.
5. Logging (most heuristic-heavy).
6. Remove duplicated traversal/config/emit code and enforce boundaries.
7. Reconcile tests, snapshots, docs, and CI.

## Guardrails

- No detection logic in core.
- No grammar node kinds in rulepacks.
- No duplicated serializers, config loaders, or test fixtures.
- Keep behavior parity tests; widen only deliberately.
- API additions are additive and versioned.
