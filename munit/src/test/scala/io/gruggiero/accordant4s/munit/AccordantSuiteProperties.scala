package io.gruggiero.accordant4s.munit

// ═══════════════════════════════════════════════════════════════════════════
//  Test Oracle for spec: test-execution — Requirement: munit integration.
//  Lives in the `accordant4s-munit` module because `AccordantSuite` is that
//  module's type (Req: munit integration, spec.md:83-103). The module depends
//  on `core % "test->test"` so it can reuse `RefSut` and the bank fixtures.
//
//  Covers the two munit-integration scenarios the `core` oracle cannot:
//    - Scenario: Happy path — each generated TestCase is ONE named munit test,
//      named by the case's label (asserted via `munitTests().map(_.name)`).
//    - Scenario: Error path — a `DeviatesAt` report's failure message carries
//      the step index, the violations, and the persisted reproducing-path JSON.
//
//  Framework: Hedgehog `HedgehogSuite` (capability-profile). `AccordantSuite`
//  is the promoted munit-module main type (Step 3); it is exercised here via
//  minimal subclasses that supply the spec/cases/sut resource.
// ═══════════════════════════════════════════════════════════════════════════

import cats.effect.{IO, Resource}
import hedgehog._
import hedgehog.munit.HedgehogSuite
import io.gruggiero.accordant4s.domain.CallLabel
import io.gruggiero.accordant4s.engine.{
  ExecutionHooks,
  ExecutionReport,
  SystemUnderTest,
  TestCaseExecutor
}
import io.gruggiero.accordant4s.fixtures.BankState
import io.gruggiero.accordant4s.fixtures.ExecutionFixtures.{RefSut, faultyWithdrawCase}
import io.gruggiero.accordant4s.fixtures.GraphFixtures.*
import io.gruggiero.accordant4s.fixtures.InputFixtures.{DepositRequest, deposit}
import io.gruggiero.accordant4s.fixtures.PersistenceFixtures.given
import io.gruggiero.accordant4s.persist.TestCasePersistence
import io.gruggiero.accordant4s.persist.TestCasePersistence.given
import io.gruggiero.accordant4s.spec.{Spec, TestCase}

final class AccordantSuiteProperties extends HedgehogSuite:

  // ── a small, fixed set of labeled cases for the naming scenario ────────────

  private def namedCase(label: String): TestCase[BankState] =
    TestCase(
      CallLabel.applyUnsafe(label),
      empty,
      List(call(deposit, DepositRequest("alice", BigDecimal(10)), label + "-step"))
    )

  private val namedCases: Vector[TestCase[BankState]] =
    Vector("alpha", "beta", "gamma").map(namedCase)

  /** A minimal suite over `cases`, acquiring a fresh `RefSut` per test. */
  private final class CasesSuite(cases: Vector[TestCase[BankState]]) extends AccordantSuite[BankState]:
    def spec: Spec[BankState]                                      = bankSpec
    def generatedCases: Vector[TestCase[BankState]]                = cases
    def sutResource: Resource[IO, SystemUnderTest[IO, BankState]] =
      Resource.eval(RefSut(empty, 0L))

  // ═══════════════════════════════════════════════════════════════════════════
  //  Requirement: munit integration
  // ═══════════════════════════════════════════════════════════════════════════

  // spec:test-execution — Scenario: Happy path — each generated TestCase is one
  // named munit test, named by the case's label; all generated tests are listed.
  property("AccordantSuite registers one munit test per case, named by the case label") {
    for _ <- Gen.constant(()).forAll
    yield
      val suite = new CasesSuite(namedCases)
      val names = suite.munitTests().map(_.name).toVector
      val expected = namedCases.map(c => c.name: String)
      Result.assert(
        names.length == namedCases.length && names.corresponds(expected)(_ == _)
      )
  }

  // spec:test-execution — Scenario: Error path — a DeviatesAt report's failure
  // message contains the step index, the violations, and the persisted repro JSON.
  property("a DeviatesAt failure message carries step index, violations, and repro JSON") {
    for _ <- Gen.constant(()).forAll
    yield
      val suite = new CasesSuite(Vector(faultyWithdrawCase))
      val program =
        for
          sut    <- RefSut(faultyWithdrawCase.initial, 0L)
          report <- TestCaseExecutor.run(bankSpec, faultyWithdrawCase, sut, ExecutionHooks.noop[IO])
        yield report
      program.unsafeRunSync() match
        case dev: ExecutionReport.DeviatesAt[BankState] =>
          val msg = suite.failureMessage(dev)
          val reproJson = TestCasePersistence.toJson[BankState](dev.reproPath).noSpaces
          // step index (as a decimal substring), a violation mentioning Withdraw,
          // and the persisted repro JSON embedded verbatim
          Result.assert(
            msg.contains(dev.stepIndex.toString) &&
              msg.contains("Withdraw") &&
              msg.contains(reproJson)
          )
        case _ => Result.failure.log("expected a DeviatesAt report for the faulty case")
  }

  // (effects run on the global IO runtime)
  private given canRunIO: cats.effect.unsafe.IORuntime = cats.effect.unsafe.implicits.global
