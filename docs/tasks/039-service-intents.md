---
name: service delegation and init intents
status: done
depends-on: [036]
reviewers: [correctness, detection-fidelity]
---

## Goal

Add two service-boundary intents:

- `SERVICE_DELEGATION` — pass work to another service/component without adding
  domain logic; controller→service, facade, adapter, call-through.
- `SERVICE_INIT` — construct, configure, wire, or bootstrap a service/client/
  resource for later use.

## Scope

- New `service` pack with two rules and per-language config for delegation
  markers, service types, factory/init calls, and DI patterns.
- Deterministic delegation: thin method bodies that only forward/adapt a call,
  configured controller/facade patterns, constructor/factory-injected services.
- Deterministic init: construction, client creation, connection setup,
  dependency wiring, initialization methods.
- Candidate emits for mixed bodies that are mostly delegation or hidden init
  inside helpers (`aiEligible`).
- Overlap tests with `SEARCH`, `DATA_MODIFY`, and `ERROR_HANDLING`.

## Acceptance

- Thin forwarding methods emit `SERVICE_DELEGATION` independently of the
  delegate's intent.
- Service/client construction and wiring emits `SERVICE_INIT`.
- A method that delegates and performs local logic is not silently forced into
  delegation; ambiguous cases become candidates.
- Fixtures, config, docs, and tests are added.

## Out of scope

- Architectural dependency graphs or service topology.
- Treating every constructor call as service init.
- Replacing HTTP/RPC call detection; that is a delegation call shape.

## Notes

- SERVICE_DELEGATION is about responsibility transfer, not the call's business
  intent. It can co-occur with SEARCH or DATA_MODIFY on the inner call.
- SERVICE_INIT is about lifecycle construction, not initializing business data.

## Outcome (2026-10-21)

Implemented `ServiceRulePack` with `service:delegation`
(`SERVICE_DELEGATION`) and `service:init` (`SERVICE_INIT`), plus
`ServiceConfig` for delegation/service markers, controller/facade suffixes,
init/connection/DI calls and qualifiers, and injection annotations. Established
delegation requires a thin whole-body forwarding expression; mixed bodies become
candidates. Init covers construction, factory/connection setup, DI annotations,
and constructor injection. `ServiceDelegationRulePackTest`,
`ServiceInitRulePackTest`, `ServiceOverlapTest`, `ServiceFixtureTest`, and
`ServiceConfigTest` cover the behavior and `testing-grounds/kotlin/ServiceIntents.kt`
is the shared fixture. The pack is registered in
`META-INF/services/dev.cint.api.RulePack`; bundled host registration asserts both
intents, and the current pipeline snapshot includes `service:init` from
`csharp/Sample.cs`. Kotlin `@Inject constructor`, TypeScript parameter-property
injection, and the `Factory` suffix boundary were deferred to ticket 042.
