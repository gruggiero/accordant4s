package io.gruggiero.accordant4s.munit

// ═══════════════════════════════════════════════════════════════════════════
//  AccordantSuite — munit-cats-effect meta-suite (spec:test-execution, Req: munit integration)
//
//  Configured with a spec, an input set, exploration depth, an algorithm, and a
//  SUT Resource, it explores the graph, generates one TestCase per coverage
//  unit, and registers ONE munit test per case — named by the case's label. A
//  `DeviatesAt` report fails its test with a message carrying the step index,
//  the violations, and the persisted reproducing-path JSON (the agent-actionable
//  artifact: "deviates at step N: expected X, got Y, path P").
//
//  Subclassing contract: a user supplies the spec, the generated cases (or the
//  graph + algorithm to derive them), the SUT Resource, the user request codecs
//  for the repro-path JSON, and the `StateOps[S]` capability. The suite wires
//  the rest: per-case `test(name)(runCase(...))` registration + failure message.
//
//  Effects run on `IO` (the suite is a `CatsEffectSuite`). The SUT is acquired
//  per test via the supplied `Resource[IO, SystemUnderTest[IO, S]]` so a clean
//  system backs every case; the executor additionally resets before step 1.
// ═══════════════════════════════════════════════════════════════════════════

import cats.effect.{IO, Resource}
import io.circe.Encoder
import io.gruggiero.accordant4s.domain.StateOps
import io.gruggiero.accordant4s.engine.{
  ExecutionHooks,
  ExecutionReport,
  SystemUnderTest,
  TestCaseExecutor
}
import io.gruggiero.accordant4s.persist.TestCasePersistence
import io.gruggiero.accordant4s.spec.{Spec, TestCase}
import munit.{CatsEffectSuite, Location}

abstract class AccordantSuite[S](using
    stateOps: StateOps[S],
    testCaseEncoder: Encoder[TestCase[S]]
) extends CatsEffectSuite:

  // ── Abstract configuration (the user supplies these) ────────────────────────

  /** The spec whose oracle validates every step. */
  def spec: Spec[S]

  /** The generated cases; each becomes one named munit test (Req: named by label). */
  def generatedCases: Vector[TestCase[S]]

  /** A fresh SUT per test (acquired and released by munit per case). */
  def sutResource: Resource[IO, SystemUnderTest[IO, S]]

  /** Per-case setup/teardown (defaults to no-op hooks). */
  def hooks: ExecutionHooks[IO] = ExecutionHooks.noop[IO]

  // ── Test registration: one munit test per generated case ────────────────────

  // Register at construction (munit collects tests from the constructor body).
  generatedCases.foreach { tc =>
    test(tc.name: String) {
      sutResource.use { sut =>
        TestCaseExecutor.run(spec, tc, sut, hooks).flatMap {
          case ExecutionReport.Passed(_)               => IO.unit
          case dev @ ExecutionReport.DeviatesAt(_, _, _) =>
            IO.raiseError(failureException(dev))
        }
      }
    }
  }

  // ── Failure-message builder ─────────────────────────────────────────────────

  /**
   * The message for a `DeviatesAt` report: contains the step index, the
   * violations, and the persisted reproducing-path JSON. Pure (no F) so callers
   * can assert on its content directly; the JSON comes from
   * `TestCasePersistence.toJson` (spec:test-generation).
   */
  def failureMessage(report: ExecutionReport.DeviatesAt[S]): String =
    val reproJson = TestCasePersistence.toJson[S](report.reproPath).noSpaces
    val violations = report.violations.toList.iterator.map(_.toString).mkString(", ")
    // plain concatenation (not s"..."): the interpolation's `Any*` varargs trip
    // WartRemover's `Any` wart, while `+` concatenation is allowed.
    "deviates at step " + report.stepIndex.toString + ": " + violations +
      "; repro path: " + reproJson

  /** The munit failure carrying [[failureMessage]]; `Location` is synthesized by
   *  munit's macro at the call site (the suite, since the failure is suite-driven). */
  private def failureException(report: ExecutionReport.DeviatesAt[S])(using loc: Location): Throwable =
    new munit.FailException(failureMessage(report), loc)
end AccordantSuite
