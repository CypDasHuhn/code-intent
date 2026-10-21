# Service intents

Scaffold for ticket 039. The pack is implemented but intentionally not registered
in `META-INF/services`, so it does not affect bundled host output yet.

## Intents

- `SERVICE_DELEGATION` — a thin method transfers work to another service or
  component without adding domain logic. It is about responsibility transfer,
  not the delegated call's own intent.
- `SERVICE_INIT` — construction, client creation, connection setup, dependency
  wiring, or lifecycle/bootstrap work that prepares a service or resource for
  later use.

## Rules

- `service:delegation` owns `SERVICE_DELEGATION`.
- `service:init` owns `SERVICE_INIT`.

Both rules run independently of other packs. A delegated call may also carry
`SEARCH`, `DATA_MODIFY`, `ERROR_HANDLING`, or another intent. Init/setup calls are
not also emitted as delegation.

## Deterministic delegation

A function establishes delegation only when it has one whole forwarding
expression: the outermost call is a configured service call, contains no nested
calls or trailing lambda, and is not wrapped in local transforms such as
`audit(...)`, `mapper.map(...)`, chained operators, or additional statements. The
body must also have no local writes, guards, or try blocks. Local argument
bindings such as `val key = id.trim()` before forwarding remain candidate-only.

Configured markers live in `ServiceLanguageConfig`:

- `delegationCalls`, `delegationQualifiers`
- `serviceNames`, `serviceTypes`, `serviceSuffixes`
- `controllerSuffixes`, `facadeSuffixes`

Thin methods in controller/facade classes are only candidates unless a call
matches the configured service markers. A body becomes an `aiEligible` candidate
when at least half of its top-level statements contain forwarding evidence; one
candidate is emitted per distinct forwarding site. Nested calls in a single
statement count as one statement, not multiple forwarding units.

## Deterministic init

Configured construction types, factory/init calls, qualified connection setup,
dependency-injection annotations (including annotations with arguments), and
constructor injection emit established `SERVICE_INIT`. Lifecycle method names
alone are candidate-only; they are established only when the function also has
strong construction/connection/DI evidence.

`new`/object creation is detected wherever it occurs (initializer, return,
assignment, or argument), including C# target-typed `new()`, and the emitted span
is the creation node itself. Call-based init spans use the call node rather than
an outer chained call site.

Config fields: `initCalls`, `initQualifiers`, `initTypes`, `initMethods`,
`connectionCalls`, `connectionQualifiers`, `diAnnotations`, `diCalls`,
`diQualifiers`.

Generic connection/DI names (`connect`, `resolve`, `getInstance`, and similar)
require a configured receiver/qualifier/type to be established; unqualified uses
are `aiEligible` candidates. Calls on a configured init type are not init unless
they are construction or configured setup/connection calls, so use and teardown
calls stay untagged. A mostly-init helper that is not already annotated or named
as a lifecycle method becomes an `aiEligible` candidate.

## Tests and fixtures

Direct pack tests cover delegation, init, overlap, configuration, candidate
boundaries, spans, oracle receiver types, and JavaScript/TypeScript/C# creation
positions. `testing-grounds/kotlin/ServiceIntents.kt` is the shared fixture.

## Overlap policy

- Delegation does not replace or suppress the delegate's intent.
- A method that delegates and performs local logic is not forced into
  delegation; it is candidate-only.
- Init is lifecycle construction, not business-data initialization, and not
  every constructor call.
- Init/setup calls are not also established delegation.

## Registration

Registration and host/snapshot coverage are deferred with the scaffold. When the
pack is enabled, add this line to
`analysis-engine/core/rulepacks/src/main/resources/META-INF/services/dev.cint.api.RulePack`:

```text
dev.cint.rulepacks.intents.service.ServiceRulePack
```
