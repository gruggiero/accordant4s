# Tasks

Depth-first, one spec at a time, in dependency order (see `implementation-order.md`).
For each spec the cycle is: concept check → typed contract (human gate) → test oracle
(human gate) → implementation → rings → concept-delta + inventory update → checkpoint.
R3 (property tests) and R8 (adversarial review) are mandatory for every spec.

## 1. oracle-core (pure oracle kernel + build restructure)

- [ ] Multi-module sbt restructure (core/munit/http4s/smithy4s) + add library dependencies (Iron + iron-cats + iron-scalacheck, circe core/generic/parser, http4s client + http4s-circe, smithy4s-core)
- [ ] Add sbt plugins to `project/plugins.sbt`: `sbt-stryker4s` (Ring 5), `smithy4s-sbt-codegen` (spec 7 IDL → Scala), `sbt-wartremover` (Ring 1)
- [ ] Add config files: `.scalafix.conf` (DisableSyntax, RemoveUnused, OrganizeImports), `.scalafmt.conf`, WartRemover warts, `stryker4s.conf`
- [ ] Note: Stainless (Ring 6) is best-effort/not installed — attempt on `engine.verified` kernels, record downgrade if it cannot run
- [ ] Step 1 — typed contract: `OperationName`, `CallLabel`, `SpecViolation`, `Outcome`, `StateProfile`, `Verdict`, `StateOps`, `Operation`, `Spec`, `expect` DSL (compiles, human gate)
- [ ] Step 2 — test oracle: scenarios + 4 properties + compile-negative stubs (compiles, human gate)
- [ ] Step 3 — implementation: `engine.verified.{OutcomeEval,ProfileEval}` + oracle
- [ ] Rings: R0 R1 R2 R3 R5 (90–95%) R6 (best-effort) R8
- [ ] Concept-delta check + update `concept-inventory.md` + checkpoint

## 2. input-sets (labeled OperationCalls + InputSet composition)

- [ ] Step 1 — typed contract: `OperationCall`, `InputSet`, `withInput`, `InputSet.fromGen` (human gate)
- [ ] Step 2 — test oracle: scenarios + 3 properties + compile-negative stubs (human gate)
- [ ] Step 3 — implementation
- [ ] Rings: R0 R1 R2 R3 R5 R8
- [ ] Concept-delta check + inventory update + checkpoint

## 3. state-graph (bounded BFS over simulated transitions)

- [ ] Step 1 — typed contract: `MaxDepth`, `Node`, `Edge`, `StateGraph`, `GraphExplorer` (+ fs2 facade) (human gate)
- [ ] Step 2 — test oracle: scenarios + 5 properties + compile-negative stub (human gate)
- [ ] Step 3 — implementation
- [ ] Rings: R0 R1 R2 R3 R5 R8
- [ ] Concept-delta check + inventory update + checkpoint

## 4. test-generation (coverage algorithms + circe persistence)

- [ ] Step 1 — typed contract: `TestCase`, `CoverageAlgorithm`, `TestCaseGenerator`, `TestCaseFileRecord`, `TestCasePersistence`, `PersistenceError` (human gate)
- [ ] Step 2 — test oracle: scenarios + 4 properties (human gate)
- [ ] Step 3 — implementation
- [ ] Rings: R0 R1 R2 R3 R4 (persistence fixtures — baseline `testcase-v1.json`) R5 R8
- [ ] Concept-delta check + inventory update + checkpoint

## 5. test-execution (SystemUnderTest, step-wise replay, hooks, munit module)

- [ ] Step 1 — typed contract: `SystemUnderTest`, `ExecutionHooks`, `ExecutionReport`, `TestCaseExecutor`, `RefSut`, `AccordantSuite` (human gate)
- [ ] Step 2 — test oracle: scenarios + 4 properties + compile-negative stub (human gate)
- [ ] Step 3 — implementation (new `accordant4s-munit` module)
- [ ] Rings: R0 R1 R2 R3 R8
- [ ] Concept-delta check + inventory update + checkpoint

## 6. http-binding (http4s Client binding, transport outcomes as data)

- [ ] Step 1 — typed contract: `HttpRoute`, `HttpResponseMapper`, `HttpBinding`, `Http4sSut`, `TransportOutcome`, `MaxRetryCount` (human gate)
- [ ] Step 2 — test oracle: scenarios + 3 properties + compile-negative stub (human gate)
- [ ] Step 3 — implementation (new `accordant4s-http4s` module)
- [ ] Rings: R0 R1 R3 R4 (HTTP wire round-trip laws) R8
- [ ] Concept-delta check + inventory update + checkpoint

## 7. smithy4s-derivation (Operation slots + HTTP binding derived from Smithy IDL)

- [ ] Step 1 — typed contract: `SmithyOps`, `EndpointSlot`, `SpecBuilder`, `SmithyHttpBinding` + `TestBank.smithy` fixture (human gate)
- [ ] Step 2 — test oracle: scenarios + 3 properties + compile-negative stub (human gate)
- [ ] Step 3 — implementation (new `accordant4s-smithy4s` module)
- [ ] Rings: R0 R1 R3 R8
- [ ] Concept-delta check + inventory update + checkpoint

## 8. linearizability (concurrent cases, parallel executor, permutation checker)

- [ ] Step 1 — typed contract: `ParallelWidth`, `ConcurrentTestCase`, `ConcurrentTestCaseGenerator`, `ObservedResult`, `Linearization`, `ConcurrentExecutor`, `ConcurrentReport`, `NotLinearizable` variant, `ConcurrentTestCaseFileRecord` (human gate)
- [ ] Step 2 — test oracle: scenarios + 5 properties + compile-negative stubs (human gate)
- [ ] Step 3 — implementation (+ `SpecViolation.NotLinearizable` exhaustiveness updates)
- [ ] Rings: R0 R1 R2 R3 R4 (concurrent fixtures) R5 R6 (best-effort) R8
- [ ] Concept-delta check + inventory update + final summary
