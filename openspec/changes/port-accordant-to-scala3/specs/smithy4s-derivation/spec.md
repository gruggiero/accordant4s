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

`SmithyOps.forService` SHALL yield exactly one `EndpointSlot` per service endpoint, with `OperationName` equal to the Smithy operation id's name and `Req`/`Res` equal to the smithy4s-generated input/output types.

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

`SpecBuilder.build` SHALL return `Right(Spec[S])` iff EVERY endpoint received a behaviour, and MUST otherwise return `Left` listing the missing operation names.

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

`SmithyHttpBinding.forService` SHALL derive an `HttpBinding[S]` for the slots (routes from the HTTP traits, entity codecs from the smithy4s schemas) usable directly with `Http4sSut`, and MUST return `Left` naming operations that lack HTTP bindings.

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

## Properties (Ring 3)

### Property: Slot set equals endpoint set

**Generator strategy** (Hedgehog): `genServiceFixture` — `Gen.element1` over a fixed pool of COMPILED test services (TestBank, empty service, non-HTTP service): smithy4s services cannot be synthesized at runtime, so the pool is the constructive domain; classify by endpoint count

**Invariant**: For the TestBank service (and any service fixture), derived slot names are exactly the service's endpoint names — no extras, no omissions, no duplicates.

```
property("slot set equals endpoint set") {
  for {
    svc <- genServiceFixture.forAll
  } yield Result.assert(
    SmithyOps.forService(svc).map(_.name).toSet == svc.endpoints.map(_.name).toSet &&
    SmithyOps.forService(svc).size == svc.endpoints.size
  )
}
```

### Property: Build completeness

**Generator strategy** (Hedgehog): `genBehaviourSubset` — `Gen.subsequence(testBank.endpointBehaviours)` over TestBank's endpoint behaviours (constructive subsets including empty and full)

**Invariant**: `build` succeeds iff the assigned-behaviour set covers all endpoints; the `Left` lists exactly the uncovered names.

```
property("build completeness") {
  for {
    assigned <- genBehaviourSubset.forAll
  } yield SpecBuilder(testBank).assignAll(assigned).build match
    case Right(spec)   => Result.assert(assigned.names == testBank.endpointNames)
    case Left(missing) => Result.assert(missing.toList.toSet == (testBank.endpointNames -- assigned.names))
}
```

### Property: Derived binding transparency

**Generator strategy**: `genTestCase` over the TestBank spec (reuses spec 4 generators)

**Invariant**: For all generated test cases, execution through the Smithy-derived HTTP binding over a conformant smithy4s stub equals execution through `RefSut`.

```
property("derived binding transparency") {
  for {
    tc <- genTestCase.forAll
  } yield Result.assert(
    (run(spec, tc, smithyHttpSut), run(spec, tc, RefSut(spec))).mapN(_ == _).unsafeRunSync()
  )
}
```

## Compile-Negative Obligations

| Must NOT compile | Why | Test |
|---|---|---|
| assigning a behaviour `(WrongInput, S) => Outcome[...]` to the `Withdraw` slot | slot typing comes from the smithy4s endpoint schemas — contract/oracle drift is a type error (this realizes the spec's "compile-time evidence" claim) | `assertDoesNotCompile` stub |

## Proof Obligations

| Obligation | Source | Enforcement | Test/Artifact |
|---|---|---|---|
| Exactly one slot per endpoint, names match operation ids | Req: one slot per endpoint / Scenario: happy + Property: slot set | property test | "slot set equals endpoint set" |
| Slot Req/Res ARE the smithy4s-generated types | Scenario: happy (compile evidence) | type system + compile-negative test | typed contract CN stub |
| Empty service → empty slots, empty spec builds | Scenario: empty service | scenario test | "empty service" |
| `build` = `Right` iff every endpoint behaved; `Left` lists missing | Req: complete-or-fail / Scenarios + Property: completeness | property test | "build completeness" |
| Derived HTTP binding usable with `Http4sSut`, no hand-written codecs | Req: derived binding / Scenario: end-to-end | scenario test (smithy4s server stub) | "derived binding — end to end" |
| Non-HTTP service → `Left` naming unbindable operations | Scenario: non-HTTP | scenario test | "non-HTTP service" |
| Derived binding verdict-equivalent to RefSut | Property: transparency | property test | "derived binding transparency" |
| No oracle/contract drift possible (new endpoint breaks build) | Rationale of complete-or-fail | property (completeness) + adversarial review (Ring 8 checks no default behaviour is injected) | Ring 8 report |

## Verification Rings

Ring 0 ✅ · Ring 1 ✅ · Ring 2 — (separate module) · Ring 3 ✅ · Ring 4 — (codecs are smithy4s-generated; covered by the transparency property) · Ring 5 — · Ring 6 — · Ring 7 — · Ring 8 ✅ · Ring 9 — (accordant4s defines no API of its own)
