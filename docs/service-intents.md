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
`SEARCH`, `DATA_MODIFY`, `ERROR_HANDLING`, or another intent.

## Deterministic delegation

A function body qualifies when it has one forwarding statement, no local writes,
guards, or try blocks, and contains a configured call or a call to a configured
service receiver/type. Controller and facade class suffixes also qualify thin
methods. Configured markers live in `ServiceLanguageConfig`:

- `delegationCalls`, `delegationQualifiers`
- `serviceNames`, `serviceTypes`, `serviceSuffixes`
- `controllerSuffixes`, `facadeSuffixes`

A mixed body with at least half of its statements forwarding configured service
calls becomes an `aiEligible` candidate instead of an established annotation.

## Deterministic init

Configured construction types, factory/init calls, connection setup calls,
dependency-injection annotations, and lifecycle method names emit `SERVICE_INIT`.
Constructor calls are only init when their type or call is configured. A helper
that initializes a service but is not named as a lifecycle method becomes an
`aiEligible` candidate.

Config fields: `initCalls`, `initQualifiers`, `initTypes`, `initMethods`,
`connectionCalls`, `connectionQualifiers`, `diAnnotations`, `diCalls`.

## Overlap policy

- Delegation does not replace or suppress the delegate's intent.
- A method that delegates and performs local logic is not forced into
  delegation; it is candidate-only.
- Init is lifecycle construction, not business-data initialization, and not
  every constructor call.

## Registration

When the pack is enabled, add this line to
`analysis-engine/core/rulepacks/src/main/resources/META-INF/services/dev.cint.api.RulePack`:

```text
dev.cint.rulepacks.intents.service.ServiceRulePack
```
