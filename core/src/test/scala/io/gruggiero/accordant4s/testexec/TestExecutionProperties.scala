package io.gruggiero.accordant4s.testexec

// ═══════════════════════════════════════════════════════════════════════════
//  Test Oracle for spec: test-execution   (Step 2 — TESTS BEFORE IMPL)
//  Schema: verified-scala3
//
//  Derived from specs/test-execution/spec.md ONLY. Each scenario is traced to
//  its spec requirement/scenario; each property to its Ring-3 block. The
//  compile-negative obligation (`cnWrongSutResponseType`) is asserted non-empty.
//  Framework: Hedgehog `HedgehogSuite`. Effects run on `cats.effect.IO`.
//
//  Engine types (`SystemUnderTest`/`ExecutionHooks`/`ExecutionReport`/
//  `TestCaseExecutor`) were promoted to `engine` main sources at Step 3; the
//  type contract keeps only the compile-negative evidence.
//
//  IMPORTANT: `ExecutionHooks` is a plain record of two `F[Unit]` effects. The
//  BRACKET semantics (`beforeEach` before step 1, `afterEach` ALWAYS) are the
//  EXECUTOR's obligation (Req: Bracket-safe hooks); the test only constructs the
//  hooks and reads BOTH counters AFTER the run.
//
//  Per-case step counts: `Passed(stepsRun)` is compared against EACH case's own
//  `steps.length` (transition coverage yields variable-length paths), not a
//  shared length — see Property/Scenario happy-path below.
// ═══════════════════════════════════════════════════════════════════════════

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.effect.{Ref => IoRef}
import cats.syntax.all._
import hedgehog._
import hedgehog.munit.HedgehogSuite
import io.gruggiero.accordant4s.domain.CoverageAlgorithm.TransitionCoverage
import io.gruggiero.accordant4s.domain.{MaxDepth, OperationName}
import io.gruggiero.accordant4s.engine.ExecutionReport.{DeviatesAt, Passed}
import io.gruggiero.accordant4s.engine.{
  ExecutionHooks,
  ExecutionReport,
  GraphExplorer,
  SystemUnderTest,
  TestCaseExecutor,
  TestCaseGenerator
}
import io.gruggiero.accordant4s.fixtures.BankState
import io.gruggiero.accordant4s.fixtures.ExecutionFixtures.*
import io.gruggiero.accordant4s.fixtures.GraphFixtures.*
import io.gruggiero.accordant4s.fixtures.InputFixtures.{DepositRequest, deposit}
import io.gruggiero.accordant4s.fixtures.PersistenceFixtures.genTestCase
import io.gruggiero.accordant4s.spec.{Spec, TestCase}
import io.gruggiero.accordant4s.typecontract.TestExecutionTypeContract.cnWrongSutResponseType

final class TestExecutionProperties extends HedgehogSuite:

  // ── shared scenario fixtures (the concrete bank graph) ─────────────────────

  private val bankInputs = inputSetOf(
    List(
      call(create, CreateRequest("alice"), "c"),
      call(deposit, DepositRequest("alice", BigDecimal(50)), "d"),
      call(withdraw, WithdrawRequest("alice", BigDecimal(30)), "w")
    )
  )

  private val bankGraph =
    GraphExplorer.explore(bankSpec, bankInputs, empty, MaxDepth(3), 1L)

  private val transitionCases: Vector[TestCase[BankState]] =
    TestCaseGenerator.generate(bankGraph, TransitionCoverage)

  private def noHooks: ExecutionHooks[IO] = ExecutionHooks.noop[IO]

  private def runAll(
      spec: Spec[BankState],
      cases: Vector[TestCase[BankState]],
      sut: SystemUnderTest[IO, BankState]
  ): IO[Vector[ExecutionReport[BankState]]] =
    cases.traverse(tc => TestCaseExecutor.run(spec, tc, sut, noHooks))

  // ═══════════════════════════════════════════════════════════════════════════
  //  Requirement: Step-by-step oracle validation
  // ═══════════════════════════════════════════════════════════════════════════

  // spec:test-execution — Scenario: Happy path — conformant SUT passes.
  // Passed(stepsRun) is compared against EACH case's own length (transition
  // coverage yields variable-length paths), not a shared length.
  property("a conformant reference SUT passes every transition case") {
    for _ <- Gen.constant(()).forAll
    yield
      if transitionCases.isEmpty then Result.failure.log("no transition cases")
      else
        val sut = RefSut(empty, 0L).unsafeRunSync()
        val reports = runAll(bankSpec, transitionCases, sut).unsafeRunSync()
        Result.assert(reports.size == transitionCases.size && reports.zip(transitionCases).forall {
          case (Passed(stepsRun), tc) => stepsRun == tc.steps.length
          case _                      => false
        })
  }

  // spec:test-execution — Scenario: Error path — deviation reported with reproducing path
  property("a faulty-withdraw SUT deviates with a persistable reproducing path") {
    for _ <- Gen.constant(()).forAll
    yield
      val sut = RefSut(faultyWithdrawCase.initial, 0L).unsafeRunSync()
      val report = TestCaseExecutor.run(bankSpec, faultyWithdrawCase, sut, noHooks).unsafeRunSync()
      Result.assert(report match
        case DeviatesAt(_, violations, reproPath) =>
          violations.exists(_.toString.contains("Withdraw")) &&
          lastOpName(reproPath).contains(faultedOpName)
        case _ => false
      )
  }

  // spec:test-execution — Scenario: Edge case — deviation halts execution
  property("no call is sent to the SUT after a deviation") {
    for _ <- Gen.constant(()).forAll
    yield
      val program =
        for
          log <- IoRef.of[IO, List[String]](Nil)
          sut <- RefSut(faultyWithdrawCase.initial, 0L)
          recorded = recording(sut, log)
          _    <- TestCaseExecutor.run(bankSpec, faultyWithdrawCase, recorded, noHooks)
          sent <- log.get
        yield
          val deviantIndex = faultyWithdrawCase.steps.lastIndexWhere(_.op.name == faultedOpName)
          // at most the prefix up to and including the deviating step was sent
          Result.assert(sent.length <= deviantIndex + 1)
      program.unsafeRunSync()
  }

  // ═══════════════════════════════════════════════════════════════════════════
  //  Requirement: Bracket-safe hooks
  // ═══════════════════════════════════════════════════════════════════════════

  // spec:test-execution — Scenario: Happy path — counters.
  // The spec scenario runs 3 cases over SHARED `Ref` counters and asserts both
  // read 3 — the distinct regression class being an executor that resets hooks
  // per case but fails to re-run them across cases. (The per-case `== 1`
  // invariant is covered separately by the hook-invariant property.)
  property("beforeEach/afterEach each run 3 times across 3 test cases") {
    for _ <- Gen.constant(()).forAll
    yield
      if transitionCases.isEmpty then Result.failure.log("no transition cases")
      else
        val cases = transitionCases.take(3)
        val program =
          for
            ref <- IoRef.of[IO, (Int, Int)]((0, 0))
            sut <- RefSut(empty, 0L)
            _   <- cases.traverse_(tc =>
              TestCaseExecutor.run(bankSpec, tc, sut, countingHooks(ref)).void
            )
            r <- ref.get
          yield Result.assert(r._1 == 3 && r._2 == 3)
        program.unsafeRunSync()
  }

  // spec:test-execution — Scenario: Error path — afterEach on failure.
  // A SUT whose 2ND call raises (the first succeeds) — ensures afterEach runs
  // after partial progress, not only on immediate failure. Uses a fixed
  // ≥2-step conformant case so the raise on call 2 follows a successful step.
  property("afterEach runs exactly once and the error is re-raised on a 2nd-call SUT error") {
    for _ <- Gen.constant(()).forAll
    yield
      val (outcome, b, a) = runWithCountingHooksAttempted(bankSpec, multiStepCase, SutMode.Raising)
        .unsafeRunSync()
      Result.assert(outcome.isLeft && b == 1 && a == 1)
  }

  // ═══════════════════════════════════════════════════════════════════════════
  //  Properties (Ring 3)
  // ═══════════════════════════════════════════════════════════════════════════

  // spec:test-execution — Property: Reference implementation conformance (soundness)
  property("reference implementation conformance") {
    for (spec, inputs, initial, depth, seed, algo) <- genSpecInputsDepthAlgo.forAll
    yield
      val graph = GraphExplorer.explore(spec, inputSetOf(inputs), initial, depth, seed)
      val cases = TestCaseGenerator.generate(graph, algo)
      val sut = RefSut(initial, seed).unsafeRunSync()
      val reports = runAll(spec, cases, sut).unsafeRunSync()
      Result.assert(cases.isEmpty || reports.zip(cases).forall {
        case (Passed(stepsRun), tc) => stepsRun == tc.steps.length
        case _                      => false
      })
  }

  // spec:test-execution — Property: Fault detection (completeness for injected faults)
  property("fault detection") {
    for faultyCase <- genFaultyWithdrawCase.forAll
    yield
      val sut = RefSut(faultyCase.initial, 0L).unsafeRunSync()
      val report = TestCaseExecutor.run(bankSpec, faultyCase, sut, noHooks).unsafeRunSync()
      Result.assert(report match
        case DeviatesAt(_, _, path) => lastOpName(path).contains(faultedOpName)
        case _                      => false
      )
  }

  // spec:test-execution — Property: Deviation index is the first deviation.
  // All steps BEFORE the reported index are oracle-conformant: a constructed
  // conformant prefix plus a faulty final step deviates exactly at the last step.
  // For `genFaultyWithdrawCase` (an always-deviating case) `Passed` is itself a
  // violation of the property's invariant, so it MUST fail — a buggy executor
  // returning Passed for a faulty case cannot slip through here. (The soundness
  // property covers the other direction: conformant cases never Deviate.)
  property("deviation index is the first deviation") {
    for faultyCase <- genFaultyWithdrawCase.forAll
    yield
      val sut = RefSut(faultyCase.initial, 0L).unsafeRunSync()
      val report = TestCaseExecutor.run(bankSpec, faultyCase, sut, noHooks).unsafeRunSync()
      Result.assert(report match
        case DeviatesAt(n, _, _) =>
          // the faulty step is the LAST step; the deviation index is that step
          n == faultyCase.steps.length - 1
        case Passed(_) => false
      )
  }

  // spec:test-execution — Property: Hook invariant.
  // Verifies BOTH counters (before AND after) against the real recorded values —
  // an executor that skips beforeEach cannot pass.
  property("hook invariant") {
    for
      tc   <- genTestCase.forAll
      mode <- genSutMode.forAll
    yield
      val (_, b, a) = runWithCountingHooksAttempted(bankSpec, tc, mode).unsafeRunSync()
      Result.assert(b == 1 && a == 1)
  }

  // ═══════════════════════════════════════════════════════════════════════════
  //  Compile-negative obligation (CN: dependent result type ties the SUT answer)
  // ═══════════════════════════════════════════════════════════════════════════

  property("CN: a wrong-typed SUT response does not type-check") {
    for _ <- Gen.constant(()).forAll
    yield Result.assert(cnWrongSutResponseType.nonEmpty)
  }

  // ═══════════════════════════════════════════════════════════════════════════
  //  Internal helpers — counting hooks; BOTH counters read AFTER the run
  // ═══════════════════════════════════════════════════════════════════════════

  /** The OperationName of a case's final step (None when the case is empty). */
  private def lastOpName(tc: TestCase[BankState]): Option[OperationName] =
    tc.steps.lastOption.map(_.op.name)

  private def countingHooks(ref: IoRef[IO, (Int, Int)]): ExecutionHooks[IO] =
    ExecutionHooks(
      ref.update { case (b, a) => (b + 1, a) },
      ref.update { case (b, a) => (b, a + 1) }
    )

  /** Run one case under a mode's SUT, attempting the run (so an erroring SUT in
   *  Raising mode still observes the always-run `afterEach`). Returns the
   *  outcome (Left = error re-raised) and BOTH hook counts. */
  private def runWithCountingHooksAttempted(
      spec: Spec[BankState],
      tc: TestCase[BankState],
      mode: SutMode
  ): IO[(Either[Throwable, ExecutionReport[BankState]], Int, Int)] =
    for
      ref  <- IoRef.of[IO, (Int, Int)]((0, 0))
      pair <- scenarioForMode(mode, tc)
      (caseForMode, sut) = pair
      out  <- TestCaseExecutor.run(spec, caseForMode, sut, countingHooks(ref)).attempt
      r    <- ref.get
    yield (out, r._1, r._2)

  /** A fixed ≥2-step conformant case for the "2nd call raises" scenario. */
  private val multiStepCase: TestCase[BankState] =
    TestCase(
      io.gruggiero.accordant4s.domain.CallLabel.applyUnsafe("multi"),
      empty,
      List(
        call(create, CreateRequest("alice"), "c"),
        call(deposit, DepositRequest("alice", BigDecimal(50)), "d")
      )
    )
