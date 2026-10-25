# Service intents

The `service` pack owns `SERVICE_DELEGATION` and `SERVICE_INIT`. It is registered
in bundled discovery and participates in host output alongside the other bundled
packs.

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
constructor injection emit established `SERVICE_INIT`. Kotlin property
parameters (including annotation-qualified `@Inject constructor` forms),
TypeScript constructor parameter properties, classic C# constructors, and C#
primary/record parameters are recognized, including generic parameter lists
that contain commas. Lifecycle method names alone are candidate-only; they are
established only when the function also has strong construction/connection/DI
evidence.

`Factory` is an init suffix, not a delegation suffix. Construction through a
factory (`UserFactory()`/`new UserFactory()`) establishes `SERVICE_INIT` and is
excluded from delegation. A thin non-construction factory call such as
`widgetFactory.create()` requires explicit `delegationCalls`,
`delegationQualifiers`, service-name/type, or oracle evidence before it is
established as `SERVICE_DELEGATION`.

`new`/object creation is detected wherever it occurs (initializer, return,
assignment, or argument), including C# target-typed `new()`, and the emitted span
is the creation node itself. Nested constructions keep the outer configured
creation, and leading file comments do not suppress scanning. Creation evidence
is scoped to the enclosing function so nested helpers do not certify an outer
lifecycle method. Call-based init spans use the call node rather than an outer
chained call site.

Config fields: `initCalls`, `initQualifiers`, `initTypes`, `initSuffixes`,
`initMethods`, `connectionCalls`, `connectionQualifiers`, `diAnnotations`,
`diCalls`, `diQualifiers`.

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

## Registration and host coverage

The pack is declared in the bundled pack resource `packs/bundled-packs.json` as the `service` pack. Bundled discovery and runtime binding are covered by the pack, pipeline, and frozen-corpus parity tests. Classification itself is covered by the direct pack tests and the frozen-corpus parity suite.
