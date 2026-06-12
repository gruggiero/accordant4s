# Spec: Smithy4s Derivation

The capability Accordant doesn't have (report §6.5): derive typed `Operation[Req, Res, S]`
slots directly from a smithy4s `Service`, so the behavioural spec and the implementation
share the Smithy IDL as single source of truth — no drift between contract and oracle.
Lives in the `accordant4s-smithy4s` module.

## Concepts Used (from inventory)

| Concept | Kind | Package |
|---------|------|---------|
| `Operation[Req, Res, S]` | case class | `spec` (introduced by spec:oracle-core) |
| `Spec[S]` | case class | `spec` (introduced by spec:oracle-core) |
| `Outcome[Res, S]` | enum | `domain` (introduced by spec:oracle-core) |
| `OperationName` | opaque type | `domain` (introduced by spec:oracle-core) |
| `HttpBinding[S]` / `Http4sSut` | case class / object | `http` (introduced by spec:http-binding) |
| `BankState` fixture | test case class | (introduced by spec:oracle-core) |

## Concepts Introduced (new)

| Concept | Kind | Description |
|---------|------|-------------|
| `SmithyOps` | object | `forService[Alg, S](service: smithy4s.Service[Alg])` entry point |
| `EndpointSlot[Req, Res, S]` | case class | A derived, not-yet-behaved operation slot: name + schemas from one Smithy endpoint |
| `SpecBuilder[Alg, S]` | builder | Type-safe assignment of `(Req, S) => Outcome[Res, S]` + mock per endpoint; `build` fails listing unbehaved endpoints |
| `SmithyHttpBinding` | object | Derive `HttpRoute`/entity codecs for the slots from the service's HTTP traits (bridges to spec:http-binding) |
| `TestBank.smithy` | test fixture (Smithy IDL) | Small bank service under `smithy4s/src/test/smithy/` exercising the derivation |

## ADDED Requirements

### Requirement: One slot per Smithy endpoint

**Given** a smithy4s `Service[Alg]`
**When** `SmithyOps.forService(service)` is invoked
**Then** it yields exactly one `EndpointSlot` per service endpoint, with `OperationName` equal to the Smithy operation id's name and `Req`/`Res` equal to the smithy4s-generated input/output types

#### Scenario: Happy path

**Given** the `TestBank` service with operations `CreateAccount`, `Deposit`, `Withdraw`, `GetAccount`
**When** slots are derived
**Then** four slots exist; the `Withdraw` slot's request type IS `WithdrawInput` (compile-time evidence: assigning a behaviour with the wrong request type does not compile)

#### Scenario: Edge case — empty service

**Given** a Smithy service with no operations
**When** slots are derived
**Then** the result is an empty slot list and `SpecBuilder.build` succeeds with an empty spec

### Requirement: Complete-or-fail spec assembly

**Given** derived slots and a `SpecBuilder`
**When** behaviours are assigned and `build` is called
**Then** the result is `Right(Spec[S])` iff EVERY endpoint received a behaviour; otherwise `Left` lists the missing operation names

**Rationale**: The whole point of derivation is that the oracle cannot silently drift from the contract — a new Smithy operation breaks the spec build until the rule is written.

#### Scenario: Happy path

**Given** behaviours for all four TestBank endpoints
**When** `build` runs
**Then** the spec's operation-name set equals the service's endpoint-name set

#### Scenario: Error path — missing behaviour

**Given** behaviours for only three endpoints
**When** `build` runs
**Then** the result is `Left(NonEmptyList.one("GetAccount"))`

### Requirement: Derived HTTP binding

**Given** a Smithy service with HTTP traits (`@http(method, uri)`)
**When** `SmithyHttpBinding.forService(service)` is invoked
**Then** an `HttpBinding[S]` for the derived slots is produced (routes from the HTTP traits, entity codecs from the smithy4s schemas), usable directly with `Http4sSut`

#### Scenario: Happy path — end to end from IDL

**Given** TestBank's Smithy model, a behaviour set, and a smithy4s-generated server stub
**When** test cases are generated and executed via the derived binding
**Then** the conformant stub passes; a broken stub deviates — with no hand-written route or codec anywhere

#### Scenario: Edge case — non-HTTP service

**Given** a service without HTTP traits
**When** `SmithyHttpBinding.forService` is invoked
**Then** the result is `Left` naming the operations lacking HTTP bindings (spec derivation still works; only the HTTP bridge is refused)

## Properties (Ring 2)

### Property: Slot set equals endpoint set

**Invariant**: For the TestBank service (and any service fixture), derived slot names are exactly the service's endpoint names — no extras, no omissions, no duplicates.

```
forAll(genServiceFixture) { svc =>
  SmithyOps.forService(svc).map(_.name).toSet == svc.endpoints.map(_.name).toSet &&
  SmithyOps.forService(svc).size == svc.endpoints.size
}
```

### Property: Build completeness

**Invariant**: `build` succeeds iff the assigned-behaviour set covers all endpoints; the `Left` lists exactly the uncovered names.

```
forAll(genBehaviourSubset) { assigned =>
  SpecBuilder(testBank).assignAll(assigned).build match
    case Right(spec) => assigned.names == testBank.endpointNames
    case Left(missing) => missing.toList.toSet == (testBank.endpointNames -- assigned.names)
}
```

### Property: Derived binding transparency

**Invariant**: For all generated test cases, execution through the Smithy-derived HTTP binding over a conformant smithy4s stub equals execution through `RefSut`.

```
forAll(genTestCase) { tc =>
  (run(spec, tc, smithyHttpSut), run(spec, tc, RefSut(spec))).mapN(_ == _)
}
```

## Verification Rings

Ring 0 ✅ · Ring 1 ✅ · Ring 1.5 — (separate module) · Ring 2 ✅ · Ring 3 — · Ring 4 — · Ring 5 — (accordant4s defines no API of its own)
