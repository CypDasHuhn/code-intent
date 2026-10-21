---
name: service delegation and init intents
status: todo
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
