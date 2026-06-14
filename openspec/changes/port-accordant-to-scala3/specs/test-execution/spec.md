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
| `TestCase[S]` | case class | `domain` (introduced by spec:test-generation) |
| `TestCasePersistence` | object | `persist` (introduced by spec:test-generation) |
| `genTestCase` | Hedgehog generator | test fixtures (introduced by spec:test-generation) |
| `BankState` fixture | test case class | test fixtures (introduced by spec:oracle-core) |

## Concepts Introduced (new)

| Concept | Kind | Description |
|---------|------|-------------|
| `SystemUnderTest[F[_]]` | trait (tagless final, `F: Async`) | `execute(call: OperationCall[S]): F[call.Res]`, `reset: F[Unit]` |
| `ExecutionHooks[F]` | case class | `beforeEach: F[Unit]`, `afterEach: F[Unit]` (defaults no-op) |
| `ExecutionReport[S]` | enum | `Passed(stepsRun)` \| `DeviatesAt(stepIndex, violations, reproPath: TestCase[S])` |
| `TestCaseExecutor` | object | `run[F, S](spec, testCase, sut, hooks): F[ExecutionReport[S]]` |
| `RefSut` | test fixture | In-memory `SystemUnderTest[IO]` driven by the spec itself (conformant by construction) + fault-injection wrapper |
| `AccordantSuite` | abstract class (`accordant4s-munit`) | munit-cats-effect suite emitting one test per generated `TestCase` |

## ADDED Requirements

### Requirement: Step-by-step oracle validation

The executor SHALL, for each step in order, execute the call against the SUT, validate the ACTUAL response via `spec.allows` against the current profile, thread the surviving profile to the next step, and MUST stop at the first `Deviant` verdict.

**Given** a spec, a test case, and a `SystemUnderTest[F]`
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

**Given** an `AccordantSuite` configured with a spec, input set, exploration depth, algorithm, and a SUT `Resource`
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

**Generator strategy**: composite constructive `genSpecInputsDepthAlgo` reusing spec:state-graph's spec/input pool plus `genAlgorithm`; the SUT is `RefSut(spec)` — deterministic, derived from the spec itself

**Invariant**: A SUT that implements exactly the spec's transitions passes every generated test case, for all explored graphs and algorithms.

```
property("reference implementation conformance") {
  for {
    (spec, inputs, depth, algo) <- genSpecInputsDepthAlgo.forAll
  } yield {
    val cases = generate(explore(spec, inputs, init, depth, seed), algo)
    val passed = cases.traverse(tc => TestCaseExecutor.run(spec, tc, RefSut(spec), noHooks))
                      .map(_.forall(_.isPassed))
                      .unsafeRunSync()
    Result.assert(passed)
  }
}
```

### Property: Fault detection (completeness for injected faults)

**Generator strategy** (Hedgehog): `genFault` — `Gen.choice1` over single-operation behavioural mutations (drop-check, wrong-transition, swallow-error) applied to `RefSut`; classify by fault kind

**Invariant**: For any single-operation fault injected into `RefSut`, executing a `TransitionCoverage` case set reports at least one `DeviatesAt`, and the reported step's call is the faulted operation.

```
property("fault detection") {
  for {
    fault <- genFault.forAll
  } yield {
    val reports = runAll(spec, transitionCases, RefSut(spec).withFault(fault))
    Result.assert(reports.exists {
      case DeviatesAt(_, _, path) => path.steps.last.op.name == fault.opName
      case _                      => false
    })
  }
}
```

### Property: Deviation index is the first deviation

**Generator strategy**: same `genFault`; the executed prefix is replayed through the pure oracle with the recorded responses

**Invariant**: All steps before the reported index validate as `Conformant` when replayed through the oracle with the recorded responses.

```
property("deviation index is the first deviation") {
  for {
    fault <- genFault.forAll
  } yield Result.assert(runAll(...).forall {
    case DeviatesAt(n, _, path) => replayOracle(spec, path.steps.take(n)).isConformant
    case Passed(_)              => true
  })
}
```

### Property: Hook invariant

**Generator strategy** (Hedgehog): `genTestCase` (spec 4) × `genSutBehaviour` = `Gen.choice1(passing, deviating, raising)` — every terminal mode constructed explicitly, counters in `Ref[IO, Int]`

**Invariant**: For every execution — passing, deviating, or erroring — `#beforeEach == #afterEach == 1` per test case.

```
property("hook invariant") {
  for {
    tc        <- genTestCase.forAll
    behaviour <- genSutBehaviour.forAll
  } yield {
    val (b, a) = countersAfter(TestCaseExecutor.run(spec, tc, behaviour.sut, countingHooks)).unsafeRunSync()
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
