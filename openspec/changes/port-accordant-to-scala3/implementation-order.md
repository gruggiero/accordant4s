# Implementation Order

Change: `port-accordant-to-scala3` — depth-first, one spec at a time, mandatory human
checkpoint between specs (verified-scala3 schema). Spec 1 also carries the build
restructuring prerequisite (multi-module sbt, new dependencies, Scalafix/WartRemover/
Stryker4s config — design.md §Build Changes).

## Dependency Analysis

| # | Spec | Introduces | Depends On (concepts) | Complexity |
|---|------|-----------|----------------------|------------|
| 1 | `specs/oracle-core/spec.md` | `OperationName`, `CallLabel`, `SpecViolation`, `ResponseCheck`, `Outcome`, `StateProfile`, `Verdict`, `StateOps`, `Operation`, `Spec`, `expect` DSL, `OutcomeEval`, `ProfileEval`, `BankState` fixture | (none — foundational) | high |
| 2 | `specs/input-sets/spec.md` | `OperationCall`, `InputSet`, `withInput`, `InputSet.fromGen`, `genOperationCall`/`genInputSet` | `Operation`, `OperationName`, `CallLabel` | medium |
| 3 | `specs/state-graph/spec.md` | `MaxDepth`, `Node`, `Edge`, `StateGraph`, `GraphExplorer`, `genStateGraph` | `Spec`, `Outcome`, `Operation.mock`, `OperationCall`, `InputSet`, `StateOps` | high |
| 4 | `specs/test-generation/spec.md` | `TestCase`, `CoverageAlgorithm`, `TestCaseGenerator`, `TestCaseFileRecord`, `TestCasePersistence`, `PersistenceError`, `genTestCase` | `StateGraph`, `Node`, `Edge`, `OperationCall`, `MaxDepth` | high |
| 5 | `specs/test-execution/spec.md` | `SystemUnderTest`, `ExecutionHooks`, `ExecutionReport`, `TestCaseExecutor`, `RefSut`, `AccordantSuite` (new sbt module `munit`) | `Spec.allows`, `Verdict`, `StateProfile`, `TestCase`, `TestCasePersistence` | high |
| 6 | `specs/http-binding/spec.md` | `HttpRoute`, `HttpResponseMapper`, `HttpBinding`, `Http4sSut`, `TransportOutcome`, `MaxRetryCount` (new sbt module `http4s`) | `SystemUnderTest`, `OperationCall`, `TestCaseExecutor`, `ExecutionReport` | medium |
| 7 | `specs/smithy4s-derivation/spec.md` | `SmithyOps`, `EndpointSlot`, `SpecBuilder`, `SmithyHttpBinding`, `TestBank.smithy` (new sbt module `smithy4s`) | `Operation`, `Spec`, `Outcome`, `HttpBinding`, `Http4sSut` | high |
| 8 | `specs/linearizability/spec.md` | `ParallelWidth`, `ConcurrentTestCase`, `ConcurrentTestCaseGenerator`, `ObservedResult`, `Linearization`, `ConcurrentExecutor`, `ConcurrentReport`, `NotLinearizable` variant, `ConcurrentTestCaseFileRecord` | `Spec.allows`, `StateProfile`, `StateGraph`, `InputSet`, `SystemUnderTest`, `RefSut`, `TestCasePersistence` | high |

Ordering rationale: strict topological order on concepts for 1→5. Specs 6, 7, 8 are
mutually independent given 1–5; `http-binding` (medium) precedes `smithy4s-derivation`
(which bridges into it via `SmithyHttpBinding`), and `linearizability` goes last as the
hardest module with the widest concept fan-in (report §3.2: "the most expensive module
to implement correctly").

## Ring Applicability

Rings (v2 numbering): 0 compile · 1 lint · 2 architecture · 3 property tests ·
4 compatibility · 5 mutation · 6 formal · 7 model checking · 8 adversarial review ·
9 telemetry. R3 and R8 are MANDATORY for every spec. Mutation thresholds: 90–95%
(pure kernels). Typed contract is mandatory — decision from the proposal's table.

| # | Spec | R0 | R1 | R2 | R3 | R4 | R5 | R6 | R7 | R8 | R9 | Typed Contract |
|---|------|----|----|----|----|----|----|----|----|----|----|----------------|
| 1 | oracle-core | ✅ | ✅ | ✅ | ✅ | — | ✅ | ✅ | — | ✅ | — | Full (new ADTs + profile semantics) |
| 2 | input-sets | ✅ | ✅ | ✅ | ✅ | — | ✅ | — | — | ✅ | — | Full (existential `OperationCall` encoding) |
| 3 | state-graph | ✅ | ✅ | ✅ | ✅ | — | ✅ | — | — | ✅ | — | Full (BFS + canonicalization + fs2 facade) |
| 4 | test-generation | ✅ | ✅ | ✅ | ✅ | ✅ fixtures | ✅ | — | — | ✅ | — | Full (coverage algorithms + persistence envelope) |
| 5 | test-execution | ✅ | ✅ | ✅ | ✅ | — | — | — | — | ✅ | — | Full (SUT trait + executor signatures + munit glue) |
| 6 | http-binding | ✅ | ✅ | — | ✅ | ✅ wire laws | — | — | — | ✅ | — | Full (binding API + transport error algebra) |
| 7 | smithy4s-derivation | ✅ | ✅ | — | ✅ | — | — | — | — | ✅ | — | Full (Service introspection typing is the risk) |
| 8 | linearizability | ✅ | ✅ | ✅ | ✅ | ✅ fixtures | ✅ | ✅ best-effort | — | ✅ | — | Full (permutation kernel + ambiguity union) |

Ring 9 applies to no spec (library, no API operations, no telemetry stack detected);
Ring 7 applies to no spec (no model checker — spec 8's brute-force exhaustiveness
property is the recorded surrogate). See capability-profile.md.

## Expected Changed Production Files (Ring 5 targeting)

Ring 5 dynamically retargets the Stryker `mutate` list to each spec's changed
production files (never a fixed list). Expected files per spec (post-restructure
paths under `core/src/main/scala/io/gruggiero/accordant4s/` unless noted):

| # | Spec | Expected production files |
|---|------|---------------------------|
| 1 | oracle-core | `domain/{OperationName,CallLabel,SpecViolation,Outcome,StateProfile,Verdict,StateOps}.scala`, `spec/{Operation,Spec,expect}.scala`, `engine/verified/{OutcomeEval,ProfileEval}.scala` + `build.sbt`, `project/Dependencies.scala`, `.scalafix.conf`, `stryker4s.conf` |
| 2 | input-sets | `spec/{OperationCall,InputSet}.scala` |
| 3 | state-graph | `domain/MaxDepth.scala`, `engine/{StateGraph,GraphExplorer}.scala` |
| 4 | test-generation | `domain/{TestCase,CoverageAlgorithm}.scala`, `engine/TestCaseGenerator.scala`, `persist/{TestCaseFileRecord,TestCasePersistence,PersistenceError}.scala` |
| 5 | test-execution | `domain/ExecutionReport.scala`, `engine/{SystemUnderTest,ExecutionHooks,TestCaseExecutor}.scala`, `munit/src/main/scala/.../munit/AccordantSuite.scala` |
| 6 | http-binding | `http4s/src/main/scala/.../http/{HttpRoute,HttpResponseMapper,HttpBinding,Http4sSut,TransportOutcome}.scala`, `domain/MaxRetryCount.scala` |
| 7 | smithy4s-derivation | `smithy4s/src/main/scala/.../smithy/{SmithyOps,EndpointSlot,SpecBuilder,SmithyHttpBinding}.scala` |
| 8 | linearizability | `domain/{ParallelWidth,ConcurrentTestCase,ConcurrentReport}.scala`, `engine/{ConcurrentTestCaseGenerator,ConcurrentExecutor}.scala`, `engine/verified/Linearization.scala`, `persist/ConcurrentTestCaseFileRecord.scala` |

## Effort Calibration (from report §3.3, adjusted to this scope)

| # | Spec | Report basis | Estimate |
|---|------|--------------|----------|
| 1 | oracle-core | "Core Spec + Outcome ADT, 2–3 days" + SystemChecker 1 day | 3–4 days (incl. build restructure) |
| 2 | input-sets | "ScalaCheck Gen input sets, 1 day" | 1 day |
| 3 | state-graph | part of "BFS + sequential generation, 3–5 days" | 2–3 days |
| 4 | test-generation | remainder of the above + circe persistence | 2 days |
| 5 | test-execution | "munit/weaver integration, 1–2 days" + executor | 2 days |
| 6 | http-binding | "http4s operation binding, 1–2 days" | 1–2 days |
| 7 | smithy4s-derivation | report Phase 3, ~1 week | 3–4 days |
| 8 | linearizability | "5–8 days" | 5–8 days |
| | **Total** | report: 10–14 days MVP / 20–28 full | **~19–26 days** |

## Implementation Sequence

Process in this exact order. For each spec: concept check → typed contract
(compiled, human gate) → test oracle (human gate) → implementation → rings →
concept delta + inventory update → checkpoint → WAIT for human approval.

- [ ] 1. `specs/oracle-core/spec.md` — pure oracle kernel: Outcome ADT, StateProfile, Verdict, Spec.allows (+ multi-module build restructure)
- [ ] 2. `specs/input-sets/spec.md` — labeled OperationCalls, InputSet composition, Gen-backed sources
- [ ] 3. `specs/state-graph/spec.md` — bounded BFS exploration of reachable states with mocked responses
- [ ] 4. `specs/test-generation/spec.md` — state/transition coverage + random walk, circe persistence
- [ ] 5. `specs/test-execution/spec.md` — SystemUnderTest, step-wise oracle replay, hooks, munit module
- [ ] 6. `specs/http-binding/spec.md` — http4s Client binding, transport outcomes as data (http4s module)
- [ ] 7. `specs/smithy4s-derivation/spec.md` — Operation slots + HTTP binding derived from Smithy IDL (smithy4s module)
- [ ] 8. `specs/linearizability/spec.md` — concurrent cases, parallel executor, permutation checker
