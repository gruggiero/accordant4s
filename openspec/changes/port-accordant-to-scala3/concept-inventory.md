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
| `MaxDepth` | `Int` | `Positive` (via `RefinedType`) — mandatory exploration bound | `io.gruggiero.accordant4s.domain` | state-graph |
| `MaxRetryCount` | `Int` | `Positive` (via `RefinedType`) — bound on idempotent connection retries | `io.gruggiero.accordant4s.domain` | http-binding |

## Sealed Traits and Enums

| Type | Kind | Variants | Package | Introduced By |
|------|------|----------|---------|---------------|
| `SpecViolation` | `enum derives CanEqual` | `CheckFailed(op, detail)`, `UnknownOperation(name)`, `NoBranchMatched(op, branchFailures)`, `ProfileExhausted(op)` | `io.gruggiero.accordant4s.domain` | oracle-core |
| `Outcome[Res, S]` | `enum derives CanEqual` | `Same(check)`, `Next(check, transition)`, `OneOf(branches)` | `io.gruggiero.accordant4s.domain` | oracle-core |
| `Verdict[S]` | `enum derives CanEqual` | `Conformant(StateProfile[S])`, `Deviant(NonEmptyList[SpecViolation])` | `io.gruggiero.accordant4s.domain` | oracle-core |
| `OperationCall[S]` | `sealed trait` (existential `type Req`/`type Res` members; `op`/`req`/`label`; companion `Aux[S,R,Re]` refinement, `apply`, `given canEqual`; private `Impl` case class) | `io.gruggiero.accordant4s.spec` | input-sets |
| `CoverageAlgorithm` | `enum derives CanEqual` | `StateCoverage`, `TransitionCoverage`, `RandomWalk(seed: Long, count: Int :| Positive)` | `io.gruggiero.accordant4s.domain` | test-generation |
| `PersistenceError` | `enum derives CanEqual` | `DecodeFailed(io.circe.Error)`, `VersionMismatch(found: Int, expected: Int)` | `io.gruggiero.accordant4s.persist` | test-generation |
| `ExecutionReport[S]` | `enum derives CanEqual` | `Passed(stepsRun: Int)`, `DeviatesAt(stepIndex: Int, violations: NonEmptyList[SpecViolation], reproPath: TestCase[S])` (+ `isPassed`) | `io.gruggiero.accordant4s.engine` | test-execution |
| `TransportOutcome` | `enum derives CanEqual` | `Completed(status: org.http4s.Status, body: String)`, `TimedOut`, `ConnectionFailed(detail: String)` | `io.gruggiero.accordant4s.http` | http-binding |

## Case Classes (Domain Value Objects)

| Type | Fields | Package | Introduced By |
|------|--------|---------|---------------|
| `Operation[Req, Res, S]` | `name: OperationName`, `behaviour: (Req,S)=>Outcome[Res,S]`, `mock: (Req,S)=>hedgehog.Gen[Res]` | `io.gruggiero.accordant4s.spec` | oracle-core |
| `InputSet[S]` | `calls: List[OperationCall[S]]` (private ctor; `labels`/`size`/`++`; companion `empty`, `of` → `Either[NonEmptyList[CallLabel], InputSet[S]]`, `fromGen(op, gen, n: Int, seed: Long)(using Show[R])`) | `io.gruggiero.accordant4s.spec` | input-sets |
| `Node[S]` | `state: S`, `depth: Int` (canonical state + BFS depth) | `io.gruggiero.accordant4s.engine` | state-graph |
| `Edge[S]` | `from: S`, `call: OperationCall[S]`, `to: S` (`from == to` ⇒ self-loop) | `io.gruggiero.accordant4s.engine` | state-graph |
| `StateGraph[S]` | `initial: S`, `nodes: Vector[Node[S]]`, `edges: Vector[Edge[S]]` | `io.gruggiero.accordant4s.engine` | state-graph |
| `Spec[S]` | `operations: Map[OperationName, Operation[?,?,S]]` (+ `register`, `allows`; `Spec.empty`) | `io.gruggiero.accordant4s.spec` | oracle-core |
| `OutcomeEval.Branch[Res, S]` | `check: ResponseCheck[Res]`, `transition: (Res,S)=>S` (`matches`/`next`) | `io.gruggiero.accordant4s.domain` | oracle-core |
| `BankState` *(test fixture)* | `accounts: Map[String, BigDecimal]` (+ `Eq`/`Hash`/`Show`) | `io.gruggiero.accordant4s.fixtures` (test sources) | oracle-core |
| `RefSut[S]` *(test fixture)* | in-memory `SystemUnderTest[IO, S]` whose responses come from each call's own `mock` and whose state advances through `behaviour`; `apply[S](initial, seed)(using StateOps[S]): IO[RefSut[S]]`; conformant by construction; fault-injection via a typed faulty operation (`faultyWithdraw`) | `io.gruggiero.accordant4s.fixtures.ExecutionFixtures` (test sources) | test-execution |
| `TestCase[S]` | `name: CallLabel`, `initial: S`, `steps: List[OperationCall[S]]` | `io.gruggiero.accordant4s.spec` | test-generation |
| `TestCaseFileRecord[S]` | `schemaVersion: Int`, `specName: String`, `testCase: TestCase[S]` (versioned persistence envelope) | `io.gruggiero.accordant4s.persist` | test-generation |
| `ExecutionHooks[F[_]]` | `beforeEach: F[Unit]`, `afterEach: F[Unit]` (plain record; bracket semantics are the executor's obligation; companion `noop[F]` default) | `io.gruggiero.accordant4s.engine` | test-execution |
| `HttpRoute[Req]` | `uri: Req => org.http4s.Uri`, `encode: Req => org.http4s.Request[IO]` (companion `jsonPost[Req](uri)(using EntityEncoder[IO, Req])`) | `io.gruggiero.accordant4s.http` | http-binding |
| `HttpBinding[S]` | `endpoints: Map[OperationName, Endpoint[S]]` (`endpointFor(call)`; companion `empty`, `register(op, route, mapper): HttpBinding => HttpBinding`, `check(spec, binding): Either[Set[OperationName], HttpBinding]`); `Endpoint[S]` is a sealed trait (existential slot: `encode(call): IO[Request[IO]]`, `decode(outcome): IO[Any]`; built from `Operation[Req, Res, S]`) | `io.gruggiero.accordant4s.http` | http-binding |

## Service Traits

| Trait | Type Param | Methods | Package | Introduced By |
|-------|-----------|---------|---------|---------------|
| `StateOps[S]` | `S` | `eqS`, `hashS`, `showS`, `canEqualS` (given `StateOps.derived`) | `io.gruggiero.accordant4s.domain` | oracle-core |
| `SystemUnderTest[F[_], S]` | `F[_]`, `S` | `execute(call: OperationCall[S]): F[call.Res]` (DEPENDENT result type — the answer is tied to the call's operation, no cast), `reset: F[Unit]` | `io.gruggiero.accordant4s.engine` | test-execution |
| `HttpResponseMapper[Res]` | `Res` | `map(outcome: TransportOutcome): IO[Res]` (TOTAL over all statuses + transport failures; user supplies per-operation) | `io.gruggiero.accordant4s.http` | http-binding |

## Type Aliases & Pure Objects

| Concept | Kind | Signature / Members | Package | Introduced By |
|---------|------|---------------------|---------|---------------|
| `ResponseCheck[Res]` | type alias | `Res => ValidatedNel[SpecViolation, Unit]` | `io.gruggiero.accordant4s.domain` | oracle-core |
| `OutcomeEval` | pure object | `flatten`, `survivors`, `Branch` | `io.gruggiero.accordant4s.domain` | oracle-core |
| `ProfileEval` | pure object | `allows(name, behaviour, res, profile)` | `io.gruggiero.accordant4s.domain` | oracle-core |
| `expect` | DSL object | `apply(check)`, `Builder.sameState/.thenState`, `oneOf` | `io.gruggiero.accordant4s.spec` | oracle-core |
| `withInput` | extension method | `Operation[R,Re,S].withInput(req: R, label: CallLabel): OperationCall.Aux[S,R,Re]` (Accordant's `op.With`) | `io.gruggiero.accordant4s.spec` | input-sets |
| `GraphExplorer` | pure object | `explore(spec, inputs, initial, depth: MaxDepth, seed): StateGraph[S]`, `stream(...): fs2.Stream[Pure, Node[S]]`, `sampledResponse(call, from, seed): Option[call.Res]` (BFS over `spec.allows`; deterministic seed-keyed mock sampling) | `io.gruggiero.accordant4s.engine` | state-graph |
| `TestCaseGenerator` | pure object | `generate[S](graph: StateGraph[S], algorithm: CoverageAlgorithm)(using StateOps[S]): Vector[TestCase[S]]` (StateCoverage greedy path-extension / TransitionCoverage per-edge / deterministic splitmix64 RandomWalk; paths drawn only from `graph.edges`) | `io.gruggiero.accordant4s.engine` | test-generation |
| `TestCasePersistence` | pure object (`persist`) | `schemaVersion: Int`, `given callLabelCodec: Codec[CallLabel]`, `given testCaseCodec[S](using Codec[S], Codec[OperationCall[S]]): Codec[TestCase[S]]`, `toJson[S](tc)(using Encoder[TestCase[S]]): Json`, `fromJson[S](json)(using Decoder[TestCase[S]]): Either[PersistenceError, TestCase[S]]` (versioned envelope; version-gate before decode; never throws) | `io.gruggiero.accordant4s.persist` | test-generation |
| `TestCaseExecutor` | pure object (`engine`) | `run[F[_], S](spec, testCase, sut, hooks)(using Async[F], StateOps[S]): F[ExecutionReport[S]]` (step-wise oracle replay; per step executes the call, validates the ACTUAL response via `spec.allows`, threads the surviving profile, halts at the first `Deviant`; `bracket` runs `sut.reset`+`beforeEach` before step 1 and `afterEach` ALWAYS incl. error/cancellation; errors re-raised, never converted to verdicts) | `io.gruggiero.accordant4s.engine` | test-execution |

## Smithy Models

| Model | Kind | Operations/Fields | Location | Introduced By |
|-------|------|-------------------|----------|---------------|

## Hedgehog Generators

| Generator | Type | Location | Introduced By |
|-----------|------|----------|---------------|
| `genOperationCall` | `Gen[OperationCall[BankState]]` | `core` test: `fixtures/InputFixtures.scala` (with `deposit` fixture, `DepositRequest`/`DepositResponse` + `Show`) | input-sets |
| `genInputSet` | `String => Gen[InputSet[BankState]]` (prefix-namespaced labels → label-disjoint by construction) | `core` test: `fixtures/InputFixtures.scala` | input-sets |
| `genStateGraph` | `Gen[StateGraph[BankState]]` (via shared `genSmallSpecAndInputs`: `bankSpec` of create/deposit/withdraw/fork) | `core` test: `fixtures/GraphFixtures.scala` | state-graph |
| `genTestCase` | `Gen[TestCase[BankState]]` (StateCoverage over `genStateGraph`) | `core` test: `fixtures/PersistenceFixtures.scala` | test-generation |
| `genAlgorithm` | `Gen[CoverageAlgorithm]` (`Gen.choice1` of StateCoverage / TransitionCoverage / seeded RandomWalk); plus `genSeed`, `genPosSmall` | `core` test: `fixtures/PersistenceFixtures.scala` | test-generation |
| `genFaultyWithdrawCase` | `Gen[TestCase[BankState]]` (conformant prefix + faulty final `withdraw`; deviates at its own step) | `core` test: `fixtures/ExecutionFixtures.scala` | test-execution |
| `genSpecInputsDepthAlgo` | `Gen[(Spec, List[OperationCall], BankState, MaxDepth, Long, CoverageAlgorithm)]` (soundness property's input space) | `core` test: `fixtures/ExecutionFixtures.scala` | test-execution |
| `genSutMode` | `Gen[SutMode]` (`Passing`/`Deviating`/`Raising` — every terminal mode constructed explicitly) | `core` test: `fixtures/ExecutionFixtures.scala` | test-execution |

## Integrations (separate sbt modules)

| Concept | Kind | Module | Members | Introduced By |
|---------|------|--------|---------|---------------|
| `AccordantSuite[S]` | abstract class (`munit.CatsEffectSuite`) | `accordant4s-munit` (`io.gruggiero.accordant4s.munit`) | abstract `spec`/`generatedCases`/`sutResource` + `hooks` default; registers one `test(tc.name)` per generated case (fails on `DeviatesAt`); `failureMessage(DeviatesAt)` carries step index, violations, and persisted repro-path JSON | test-execution |
| `Http4sSut` | object (→ `SystemUnderTest[IO, S]`) | `accordant4s-http4s` (`io.gruggiero.accordant4s.http`) | `apply[S](client: Client[IO], binding: HttpBinding[S], timeout: FiniteDuration, retries: MaxRetryCount): SystemUnderTest[IO, S]`; encodes requests per route, sends through the client with timeout, decodes responses via the mapper; transport failures (timeout/connection) surface as `TransportOutcome` → `Res`, never raise | http-binding |

## Cats Effect Resources and Middleware

<!-- Shared resources (HTTP clients, executors) and middleware that specs may
     depend on. Empty until spec 5 (executor) / spec 6 (http4s Client) land. -->

| Resource | Type | Purpose | Package | Introduced By |
|----------|------|---------|---------|---------------|
