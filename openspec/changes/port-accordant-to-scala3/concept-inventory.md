# Concept Inventory

<!-- LIVING DOCUMENT — populated by scanning the codebase and updated after each
     spec's implementation during the apply phase.

     SCAN RESULT (2026-06-12): the project is NEW. `src/main/scala/` and
     `src/test/scala/` contain no source files; there are no `.smithy` files.
     Per the verified-scala3 schema rules, the tables below carry headers only
     and will be populated as the specs of change `port-accordant-to-scala3`
     are implemented (each spec's "Concepts Introduced" table is appended here
     at Step 9 of the apply phase).

     MAINTENANCE RULES:
     - APPEND ONLY during apply (never remove or modify existing entries)
     - Each entry records which spec introduced it (traceability)
     - Package paths must be exact (used for import statements)
     - Constraints must be exact (used for Iron type verification) -->

## Opaque Types (Iron Refined)

| Type | Underlying | Iron Constraint | Package | Introduced By |
|------|-----------|-----------------|---------|---------------|
| `OperationName` | `String` | `Not[Blank]` (via `RefinedType`) | `io.gruggiero.accordant4s.domain` | oracle-core |
| `CallLabel` | `String` | `Not[Blank]` (via `RefinedType`) | `io.gruggiero.accordant4s.domain` | oracle-core |
| `StateProfile[S]` | `NonEmptyList[S]` | non-empty + `Eq`-dedup (smart ctors `one`/`of`; no public ctor from a possibly-empty collection) | `io.gruggiero.accordant4s.domain` | oracle-core |

## Sealed Traits and Enums

| Type | Kind | Variants | Package | Introduced By |
|------|------|----------|---------|---------------|
| `SpecViolation` | `enum derives CanEqual` | `CheckFailed(op, detail)`, `UnknownOperation(name)`, `NoBranchMatched(op, branchFailures)`, `ProfileExhausted(op)` | `io.gruggiero.accordant4s.domain` | oracle-core |
| `Outcome[Res, S]` | `enum derives CanEqual` | `Same(check)`, `Next(check, transition)`, `OneOf(branches)` | `io.gruggiero.accordant4s.domain` | oracle-core |
| `Verdict[S]` | `enum derives CanEqual` | `Conformant(StateProfile[S])`, `Deviant(NonEmptyList[SpecViolation])` | `io.gruggiero.accordant4s.domain` | oracle-core |
| `OperationCall[S]` | `sealed trait` (existential `type Req`/`type Res` members; `op`/`req`/`label`; companion `Aux[S,R,Re]` refinement, `apply`, `given canEqual`; private `Impl` case class) | `io.gruggiero.accordant4s.spec` | input-sets |

## Case Classes (Domain Value Objects)

| Type | Fields | Package | Introduced By |
|------|--------|---------|---------------|
| `Operation[Req, Res, S]` | `name: OperationName`, `behaviour: (Req,S)=>Outcome[Res,S]`, `mock: (Req,S)=>hedgehog.Gen[Res]` | `io.gruggiero.accordant4s.spec` | oracle-core |
| `InputSet[S]` | `calls: List[OperationCall[S]]` (private ctor; `labels`/`size`/`++`; companion `empty`, `of` → `Either[NonEmptyList[CallLabel], InputSet[S]]`, `fromGen(op, gen, n: Int, seed: Long)(using Show[R])`) | `io.gruggiero.accordant4s.spec` | input-sets |
| `Spec[S]` | `operations: Map[OperationName, Operation[?,?,S]]` (+ `register`, `allows`; `Spec.empty`) | `io.gruggiero.accordant4s.spec` | oracle-core |
| `OutcomeEval.Branch[Res, S]` | `check: ResponseCheck[Res]`, `transition: (Res,S)=>S` (`matches`/`next`) | `io.gruggiero.accordant4s.domain` | oracle-core |
| `BankState` *(test fixture)* | `accounts: Map[String, BigDecimal]` (+ `Eq`/`Hash`/`Show`) | `io.gruggiero.accordant4s.fixtures` (test sources) | oracle-core |

## Service Traits

| Trait | Type Param | Methods | Package | Introduced By |
|-------|-----------|---------|---------|---------------|
| `StateOps[S]` | `S` | `eqS`, `hashS`, `showS`, `canEqualS` (given `StateOps.derived`) | `io.gruggiero.accordant4s.domain` | oracle-core |

## Type Aliases & Pure Objects

| Concept | Kind | Signature / Members | Package | Introduced By |
|---------|------|---------------------|---------|---------------|
| `ResponseCheck[Res]` | type alias | `Res => ValidatedNel[SpecViolation, Unit]` | `io.gruggiero.accordant4s.domain` | oracle-core |
| `OutcomeEval` | pure object | `flatten`, `survivors`, `Branch` | `io.gruggiero.accordant4s.domain` | oracle-core |
| `ProfileEval` | pure object | `allows(name, behaviour, res, profile)` | `io.gruggiero.accordant4s.domain` | oracle-core |
| `expect` | DSL object | `apply(check)`, `Builder.sameState/.thenState`, `oneOf` | `io.gruggiero.accordant4s.spec` | oracle-core |
| `withInput` | extension method | `Operation[R,Re,S].withInput(req: R, label: CallLabel): OperationCall.Aux[S,R,Re]` (Accordant's `op.With`) | `io.gruggiero.accordant4s.spec` | input-sets |

## Smithy Models

| Model | Kind | Operations/Fields | Location | Introduced By |
|-------|------|-------------------|----------|---------------|

## Hedgehog Generators

| Generator | Type | Location | Introduced By |
|-----------|------|----------|---------------|
| `genOperationCall` | `Gen[OperationCall[BankState]]` | `core` test: `fixtures/InputFixtures.scala` (with `deposit` fixture, `DepositRequest`/`DepositResponse` + `Show`) | input-sets |
| `genInputSet` | `String => Gen[InputSet[BankState]]` (prefix-namespaced labels → label-disjoint by construction) | `core` test: `fixtures/InputFixtures.scala` | input-sets |

## Cats Effect Resources and Middleware

<!-- Shared resources (HTTP clients, executors) and middleware that specs may
     depend on. Empty until spec 5 (executor) / spec 6 (http4s Client) land. -->

| Resource | Type | Purpose | Package | Introduced By |
|----------|------|---------|---------|---------------|
