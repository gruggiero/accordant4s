# Spec: Test Execution

Port of Accordant's `SystemChecker` + `TestCaseExecutor`: replay generated (or
hand-written) sequences against the real system, validating every step with the pure
oracle, with bracket-safe setup/teardown hooks (`BeforeEachAsync`/`AfterEachAsync`)
and a munit integration module that turns generated cases into test suite entries.

## Concepts Used (from inventory)

| Concept | Kind | Package |
|---------|------|---------|
| `Spec[S]` / `allows` | case class / method | `spec` (introduced by spec:oracle-core) |
| `Verdict[S]` / `StateProfile[S]` / `SpecViolation` | enum / opaque / enum | `domain` (introduced by spec:oracle-core) |
| `OperationCall[S]` | sealed trait | `spec` (introduced by spec:input-sets) |
| `TestCase[S]` | case class | `spec` (introduced by spec:test-generation) |
| `TestCasePersistence` | object | `persist` (introduced by spec:test-generation) |
| `genTestCase` | Hedgehog generator | test fixtures (introduced by spec:test-generation) |
| `BankState` fixture | test case class | test fixtures (introduced by spec:oracle-core) |

## Concepts Introduced (new)

| Concept | Kind | Description |
|---------|------|-------------|
| `SystemUnderTest[F[_], S]` | trait (tagless final, `F: Async`) | `execute(call: OperationCall[S]): F[call.Res]` (DEPENDENT result type — the answer is tied to the call's operation), `reset: F[Unit]`. `S` is needed because `execute` references both the call's `Res` and `S` |
| `ExecutionHooks[F]` | case class | `beforeEach: F[Unit]`, `afterEach: F[Unit]`; companion `noop[F]` factory for no-op defaults |
| `ExecutionReport[S]` | enum (in `engine`, not `domain` — `DeviatesAt` carries `TestCase[S]` which carries `spec.OperationCall[S]`, so `domain` must not own it) | `Passed(stepsRun)` \| `DeviatesAt(stepIndex, violations, reproPath: TestCase[S])`; `isPassed: Boolean` accessor |
| `TestCaseExecutor` | object | `run[F[_], S](spec, testCase, sut, hooks)(using Async[F], StateOps[S]): F[ExecutionReport[S]]` |
| `RefSut` | test fixture | In-memory `SystemUnderTest[IO, S]` driven by the spec itself (conformant by construction); `apply[S](initial, seed)(using StateOps[S]): IO[RefSut[S]]` |
| `AccordantSuite[S]` | abstract class (`accordant4s-munit`, extends `munit.CatsEffectSuite`) | abstract `spec: Spec[S]`, `generatedCases: Vector[TestCase[S]]`, `sutResource: Resource[IO, SystemUnderTest[IO, S]]`, `hooks: ExecutionHooks[IO]` (default no-op); registers one munit test per generated case and fails it on `DeviatesAt`; `failureMessage(DeviatesAt[S])` carries step index, violations, and persisted repro-path JSON |

## ADDED Requirements

### Requirement: Step-by-step oracle validation

The executor SHALL, for each step in order, execute the call against the SUT, validate the ACTUAL response via `spec.allows` against the current profile, thread the surviving profile to the next step, and MUST stop at the first `Deviant` verdict.

**Given** a spec, a test case, and a `SystemUnderTest[F, S]`
**When** the executor runs
**Then** for each step in order it executes the call against the SUT, feeds the ACTUAL response to `spec.allows` with the current profile, threads the surviving profile to the next step, and stops at the first `Deviant` verdict

**Rationale**: This is the simulate-then-execute closing of the loop: mocks built the graph, real responses are judged here. Profile threading preserves indefinite-failure semantics end to end.

#### Scenario: Happy path — conformant SUT passes

**Given** the bank spec and `RefSut` (reference implementation derived from the spec)
**When** any generated test case is executed
**Then** the report is `Passed(steps.length)`

#### Scenario: Error path — deviation reported with reproducing path

**Given** `RefSut` wrapped with a fault that makes `Withdraw` ignore the insufficient-funds rule
**When** a test case covering that edge runs
**Then** the report is `DeviatesAt(n, violations, reproPath)` where step `n` is the faulty withdraw, violations name the failed check, and `reproPath` is the executed prefix including step `n` — directly persistable via `TestCasePersistence`

#### Scenario: Edge case — deviation halts execution

**Given** the faulty SUT above
**When** the executor deviates at step `n`
**Then** no call after step `n` is sent to the SUT

### Requirement: Bracket-safe hooks

`beforeEach` SHALL run before the first step and `afterEach` MUST always run — on `Passed`, on `DeviatesAt`, and on SUT error or cancellation (`Resource`/`bracket` semantics).

**Given** `ExecutionHooks` with `beforeEach`/`afterEach`
**When** a test case runs
**Then** `beforeEach` runs before step 1 and `afterEach` ALWAYS runs — on `Passed`, on `DeviatesAt`, and on SUT error/cancellation (`Resource`/`bracket` semantics)

**Rationale**: Accordant's `BeforeEachAsync`/`AfterEachAsync` reset shared environments; in Cats Effect this must survive errors and cancellation or test pollution follows.

#### Scenario: Happy path

**Given** hooks that increment `Ref` counters
**When** 3 test cases run
**Then** both counters read 3

#### Scenario: Error path — afterEach on failure

**Given** a SUT whose 2nd call raises in `F`
**When** the executor runs
**Then** `afterEach` still executes exactly once and the error is re-raised (not swallowed into a verdict)

### Requirement: munit integration

Each generated `TestCase` SHALL appear as exactly one named munit test, and a `DeviatesAt` report MUST fail its test with a message carrying the step index, the violations, and the reproducing-path JSON.

**Given** an `AccordantSuite[S]` subclass providing a `spec`, the `generatedCases` (a `Vector[TestCase[S]]`, e.g. from `GraphExplorer.explore` + `TestCaseGenerator.generate`), and a `sutResource: Resource[IO, SystemUnderTest[IO, S]]`
**When** munit collects the suite
**Then** each generated `TestCase` appears as one named munit test (name = test-case label); a `DeviatesAt` report fails the test with a message containing the step index, the violations, and the reproducing path JSON

**Rationale**: This is the behavioural-conformance surface for the verified-scala3 pipeline (the report's "Ring 4.5" in its own pipeline numbering — not this schema's rings): the failure message is the agent-actionable artifact ("deviates at step N: expected X, got Y, path P").

#### Scenario: Happy path

**Given** the bank spec suite over `RefSut`
**When** `sbt test` runs it
**Then** all generated tests pass and are individually listed

#### Scenario: Error path — failure message content

**Given** the faulty-withdraw SUT
**When** the suite runs
**Then** the failing test's message matches `deviates at step <n>.*Withdraw.*` and embeds the persisted repro JSON

## Properties (Ring 3)

### Property: Reference implementation conformance (soundness)

**Generator strategy**: composite constructive `genSpecInputsDepthAlgo` reusing spec:state-graph's spec/input pool plus `genAlgorithm`; the SUT is `RefSut(initial, seed)` — deterministic, derived from the spec itself (a fresh SUT per run; the executor resets it before step 1)

**Invariant**: A SUT that implements exactly the spec's transitions passes every generated test case, for all explored graphs and algorithms.

```
property("reference implementation conformance") {
  for {
    (spec, inputs, initial, depth, seed, algo) <- genSpecInputsDepthAlgo.forAll
  } yield {
    val graph = explore(spec, inputSetOf(inputs), initial, depth, seed)
    val cases = TestCaseGenerator.generate(graph, algo)
    val sut   = RefSut(initial, seed).unsafeRunSync()
    val passed = cases.traverse(tc => TestCaseExecutor.run(spec, tc, sut, noHooks))
                      .map(_.forall(_.isPassed))
                      .unsafeRunSync()
    Result.assert(cases.isEmpty || passed)
  }
}
```

### Property: Fault detection (completeness for injected faults)

**Generator strategy** (Hedgehog): `genFaultyWithdrawCase` — a `RefSut` fault encoded cast-free as a typed operation (`faultyWithdraw`) with a non-conformant `mock` (always answers `Success(-1)`) but the ORIGINAL conformant `behaviour`; the executor samples `call.op.mock` and validates via the original `behaviour`, deviating at its own step

**Invariant**: For a faulty operation injected into `RefSut` via the test case, execution reports `DeviatesAt`, and the reported step's call is the faulted operation.

```
property("fault detection") {
  for {
    faultyCase <- genFaultyWithdrawCase.forAll
  } yield {
    val sut    = RefSut(faultyCase.initial, 0L).unsafeRunSync()
    val report = TestCaseExecutor.run(bankSpec, faultyCase, sut, noHooks).unsafeRunSync()
    Result.assert(report match {
      case DeviatesAt(_, _, path) => path.steps.last.op.name == faultedOpName
      case _                      => false
    })
  }
}
```

### Property: Deviation index is the first deviation

**Generator strategy**: same `genFaultyWithdrawCase`; the constructed conformant prefix plus a faulty final step deviates exactly at the last step — all steps before the reported index are oracle-conformant

**Invariant**: All steps before the reported index validate as `Conformant` when replayed through the oracle with the recorded responses.

```
property("deviation index is the first deviation") {
  for {
    faultyCase <- genFaultyWithdrawCase.forAll
  } yield {
    val sut    = RefSut(faultyCase.initial, 0L).unsafeRunSync()
    val report = TestCaseExecutor.run(bankSpec, faultyCase, sut, noHooks).unsafeRunSync()
    Result.assert(report match {
      case DeviatesAt(n, _, _) => n == faultyCase.steps.length - 1  // faulty step is last
      case Passed(_)           => false                            // a faulty case MUST deviate
    })
  }
}
```

### Property: Hook invariant

**Generator strategy** (Hedgehog): `genTestCase` (spec 4) × `genSutMode` = `Gen.element1(Passing, Deviating, Raising)` — every terminal mode constructed explicitly, counters in `Ref[IO, (Int, Int)]`

**Invariant**: For every execution — passing, deviating, or erroring — `#beforeEach == #afterEach == 1` per test case.

```
property("hook invariant") {
  for {
    tc   <- genTestCase.forAll
    mode <- genSutMode.forAll
  } yield {
    // run is `.attempt`ed so an erroring (Raising) SUT still observes the always-run afterEach
    val (_, b, a) = runWithCountingHooksAttempted(spec, tc, mode).unsafeRunSync()
    Result.assert(b == 1 && a == 1)
  }
}
```

## Compile-Negative Obligations

| Must NOT compile | Why | Test |
|---|---|---|
| a `SystemUnderTest` whose `execute(call)` returns a response type other than `call.Res` | the dependent result type ties the SUT's answer to the call's operation — wrong-typed SUT responses are unrepresentable | `assertDoesNotCompile` stub in the typed contract |

## Proof Obligations

| Obligation | Source | Enforcement | Test/Artifact |
|---|---|---|---|
| Each step validated via `allows`; surviving profile threaded | Req: step-by-step / Scenario: conformant pass + Property: soundness | property test | "reference implementation conformance" |
| Deviation reported with step index, violations, repro path | Scenario: deviation report + Property: fault detection | property test | "fault detection" |
| No call sent after a deviation | Scenario: halts + Property: first deviation | property test + adversarial review (Ring 8 checks no eager prefetch) | "deviation index is the first deviation" |
| `beforeEach`/`afterEach` exactly once, incl. error/cancellation | Req: hooks / Scenarios: counters, afterEach on failure + Property: hook invariant | type discipline (`Resource`/`bracket`) + property test | "hook invariant" |
| One munit test per generated case, named by label | Req: munit integration / Scenario: all pass listed | scenario test (meta-suite) | "munit integration — listing" |
| Failure message contains step index, violations, repro JSON | Scenario: failure message content | scenario test | "munit integration — failure message" |
| Errors re-raised, never converted to verdicts | Scenario: afterEach on failure | scenario test + adversarial review | Ring 8 report |
| Repro path persistable | Req: step-by-step | reuses spec:test-generation round-trip law (Ring 4 there) | "persistence roundtrip" (spec 4) |

## Verification Rings

Ring 0 ✅ · Ring 1 ✅ · Ring 2 ✅ · Ring 3 ✅ · Ring 4 — (reuses spec 4's persistence; no new wire format) · Ring 5 — (effectful orchestration; mutants covered by the Ring 3 fault properties) · Ring 6 — · Ring 7 — · Ring 8 ✅ · Ring 9 — (otel4s spans: future change)
