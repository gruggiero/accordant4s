# Tasks

Depth-first, one spec at a time, in dependency order (see `implementation-order.md`).
For each spec the cycle is: concept check → typed contract (human gate) → test oracle
(human gate) → implementation → rings → concept-delta + inventory update → checkpoint.
R3 (property tests) and R8 (adversarial review) are mandatory for every spec.

## 1. oracle-core (pure oracle kernel + build restructure)

- [x] Multi-module sbt restructure (core/munit/http4s/smithy4s) + add library dependencies (Iron + iron-cats 3.0.2, circe core/generic/parser 0.14.13, http4s client + http4s-circe 0.23.30, smithy4s-core 0.18.33, hedgehog-core Compile + hedgehog-munit 0.13.1 Test — replaces ScalaCheck/munit-scalacheck/iron-scalacheck); verified via `sbt <module>/Test/compile`
- [x] Add sbt plugins to `project/plugins.sbt`: `sbt-stryker4s` 0.21.0 (Ring 5; 0.15.1 could not parse Scala 3.8 indentation), `sbt-wartremover` 3.5.8 (Ring 1), `smithy4s-sbt-codegen` 0.18.33 (spec 7 IDL → Scala)
- [x] Add config files: `.scalafix.conf` (DisableSyntax, RemoveUnused, OrganizeImports), `.scalafmt.conf`, `stryker4s.conf` (thresholds break=90/low=91/high=95, baseline mutate list); WartRemover wired in `build.sbt` as `Warts.unsafe` minus `TripleQuestionMark` (ThisBuild errors; `verified` module exempt)
- [x] Ring 6 (Stainless): dedicated `verified` module (Scala 3.7.2, relaxed flags); PureScala mirror `verified/OracleKernel.scala` verified — 9/9 VCs valid (conformance + termination). Mechanically tied to the shipped kernel by a bridge property (`OracleModelBridgeTests`, `core dependsOn verified % Test`). `stainlessEnabled` off by default; verify with `sbt -J-Xmx6g ring6`
- [x] Step 1 — typed contract: `OperationName`, `CallLabel`, `SpecViolation`, `Outcome`, `StateProfile`, `Verdict`, `StateOps`, `Operation`, `Spec`, `expect` DSL (compiled, human-approved)
- [x] Step 2 — test oracle: 10 scenarios + 4 properties + 3 compile-negative checks (Hedgehog `HedgehogSuite`, human-approved)
- [x] Step 3 — implementation: kernel placed in `domain.{OutcomeEval,ProfileEval}` (Option A, human-approved) + `spec.Spec.allows` delegating to it
- [x] Rings: R0 ✓ R1 ✓ R2 ✓ R3 ✓ (20/20: 10 scenarios + 4 properties + 3 CN + 2 kernel-coverage + 1 model bridge) · R5 ✓ (Stryker 0.21.0, 100% on pure kernel) · R6 ✓ (Stainless 9/9 VCs on the `verified` module + mechanical bridge) · R8 ⚠️ PARTIAL (NoBranchMatched reserved — see checkpoint)
- [x] Concept-delta check + update `concept-inventory.md` + checkpoint

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

- [x] Step 1 — typed contract: `SystemUnderTest`, `ExecutionHooks`, `ExecutionReport`, `TestCaseExecutor`, `RefSut`, `AccordantSuite` (human gate)
- [x] Step 2 — test oracle: scenarios + 4 properties + compile-negative stub (human gate)
- [x] Step 3 — implementation (new `accordant4s-munit` module)
- [x] Rings: R0 R1 R2 R3 R8
- [x] Concept-delta check + inventory update + checkpoint

## 6. http-binding (http4s Client binding, transport outcomes as data)

- [x] Step 1 — typed contract: `HttpRoute`, `HttpResponseMapper`, `HttpBinding`, `Http4sSut`, `TransportOutcome`, `MaxRetryCount` (human gate)
- [x] Step 2 — test oracle: scenarios + 3 properties + compile-negative stub (human gate)
- [x] Step 3 — implementation (new `accordant4s-http4s` module)
- [x] Rings: R0 R1 R3 R4 (HTTP wire round-trip laws) R8
- [x] Concept-delta check + inventory update + checkpoint

## 7. smithy4s-derivation (Operation slots + HTTP binding derived from Smithy IDL) — Req 1+2; Req 3 deferred

- [x] Step 1 — typed contract: `SmithyOps`, `EndpointSlot`, `SpecBuilder` + `TestBank.smithy` fixture (human gate)
- [x] Step 2 — test oracle: scenarios + 3 properties + compile-negative stub (human gate)
- [x] Step 3 — implementation (new `accordant4s-smithy4s` module + codegen)
- [x] Rings: R0 R1 R3 R8
- [x] Concept-delta check + inventory update + checkpoint

## 8. linearizability (concurrent cases, parallel executor, permutation checker)

- [ ] Step 1 — typed contract: `ParallelWidth`, `ConcurrentTestCase`, `ConcurrentTestCaseGenerator`, `ObservedResult`, `Linearization`, `ConcurrentExecutor`, `ConcurrentReport`, `NotLinearizable` variant, `ConcurrentTestCaseFileRecord` (human gate)
- [ ] Step 2 — test oracle: scenarios + 5 properties + compile-negative stubs (human gate)
- [ ] Step 3 — implementation (+ `SpecViolation.NotLinearizable` exhaustiveness updates)
- [ ] Rings: R0 R1 R2 R3 R4 (concurrent fixtures) R5 R6 (best-effort) R8
- [ ] Concept-delta check + inventory update + final summary
