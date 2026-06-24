package io.gruggiero.accordant4s.engine

import cats.effect.Async
import cats.syntax.all._
import io.gruggiero.accordant4s.domain.{StateOps, StateProfile, Verdict}
import io.gruggiero.accordant4s.spec.{OperationCall, Spec, TestCase}

/**
 * Step-wise oracle replay: executes a [[TestCase]] against a
 * [[SystemUnderTest]], validating every step's ACTUAL response through the pure
 * `spec.allows` oracle (Accordant's `SystemChecker` / `TestCaseExecutor`).
 *
 * This is the simulate-then-execute closing of the loop: the mocks built the
 * graph (spec:state-graph); the real responses are judged here. For each step in
 * order it executes the call, feeds the ACTUAL response to `spec.allows` with
 * the current profile, threads the surviving profile to the next step, and
 * stops at the first [[Verdict.Deviant]] — no call after a deviation is sent
 * (Scenario: deviation halts execution). On a deviation it returns the executed
 * prefix (including the deviating step) as a `reproPath`, directly persistable
 * via `TestCasePersistence`.
 *
 * Bracket-safe hooks (Req: Bracket-safe hooks): the SUT is reset and
 * `beforeEach` runs before step 1, then the step loop runs inside a `bracket`
 * whose release runs `afterEach` ALWAYS — on `Passed`, on `DeviatesAt`, and on
 * SUT error or cancellation. An SUT error is re-raised (never swallowed into a
 * verdict). The per-case `sut.reset` is what lets a suite replay many cases over
 * one SUT (each case starts from a clean system): the soundness property runs
 * `cases.traverse(run(..., RefSut(spec), noHooks))` and expects every report
 * `Passed`, which holds only because each run resets the SUT to its starting
 * state first.
 *
 * `requires Async[F]` for bracket semantics that survive error/cancellation, and
 * `StateOps[S]` for `spec.allows`'s profile threading.
 */
object TestCaseExecutor:

  def run[F[_], S](
      spec: Spec[S],
      testCase: TestCase[S],
      sut: SystemUnderTest[F, S],
      hooks: ExecutionHooks[F]
  )(using F: Async[F], ops: StateOps[S]): F[ExecutionReport[S]] =
    F.bracket(
      // setup: a clean SUT, then the user's beforeEach — both before step 1
      acquire = sut.reset *> hooks.beforeEach
    )(
      use =
        _ => stepLoop(spec, testCase, sut, testCase.steps, StateProfile.one(testCase.initial), 0)
    )(
      // afterEach ALWAYS: bracket release runs on Passed, DeviatesAt, error, cancellation
      release = _ => hooks.afterEach
    )

  /**
   * Tail-recursive step replay. `remaining` is the not-yet-executed tail of
   * `testCase.steps`; `profile` is the surviving profile threaded from the
   * previous step; `index` is the current step's position. Stops at the first
   * `Deviant`, returning the executed prefix (steps 0..index, inclusive) as the
   * reproducing path; an empty case is `Passed(0)`.
   */
  private def stepLoop[F[_], S](
      spec: Spec[S],
      testCase: TestCase[S],
      sut: SystemUnderTest[F, S],
      remaining: List[OperationCall[S]],
      profile: StateProfile[S],
      index: Int
  )(using F: Async[F], ops: StateOps[S]): F[ExecutionReport[S]] =
    remaining match
      case Nil => F.pure(ExecutionReport.Passed(index))
      case step :: rest =>
        for
          res <- sut.execute(step)
          verdict = spec.allows(step.op, step.req, res, profile)
          report <- verdict match
            // halt: no call after this step; the prefix is directly persistable
            case Verdict.Deviant(violations) =>
              F.pure(
                ExecutionReport.DeviatesAt(
                  index,
                  violations,
                  TestCase(testCase.name, testCase.initial, testCase.steps.take(index + 1))
                )
              )
            // thread the surviving profile + remaining steps to the next step
            case Verdict.Conformant(nextProfile) =>
              stepLoop(spec, testCase, sut, rest, nextProfile, index + 1)
        yield report
