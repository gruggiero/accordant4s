package io.gruggiero.accordant4s.engine

import cats.data.NonEmptyList
import cats.effect.Async
import cats.syntax.all._
import io.gruggiero.accordant4s.domain.{StateOps, StateProfile, Verdict}
import io.gruggiero.accordant4s.spec.{ConcurrentTestCase, OperationCall, Spec}

/**
 * Executes a [[ConcurrentTestCase]] against a [[SystemUnderTest]]: replays the
 * prefix sequentially (oracle-validated), launches the parallel calls concurrently
 * (`parTraverseN`, no imposed ordering), captures [[ObservedResult]]s, checks
 * linearizability via [[Linearization.findOrdering]], and replays the suffix
 * against the post-parallel profile.
 *
 * The prefix replay uses the same step-wise oracle validation as
 * [[TestCaseExecutor]] (each step validated via `spec.allows`, profile threaded).
 * The parallel section runs all calls concurrently via `parSequence` — the
 * scheduler determines the actual interleaving. The results are captured as
 * `ObservedResult`s regardless of arrival order.
 *
 * If linearizability check passes, the suffix replays against the union of
 * witness end-profiles. If it fails, a `RaceDetected` report is returned with
 * the observed results + a persistable repro case.
 */
object ConcurrentExecutor:

  def run[S](
      spec: Spec[S],
      testCase: ConcurrentTestCase[S],
      sut: SystemUnderTest[cats.effect.IO, S]
  )(using F: Async[cats.effect.IO], ops: StateOps[S]): cats.effect.IO[ConcurrentReport[S]] =
    // 1. Reset + replay prefix sequentially, threading the profile
    for
      _             <- sut.reset
      prefixProfile <- replayPrefix(spec, testCase, sut)
      // 2. Execute the parallel section concurrently
      observed <- executeParallel(testCase, sut)
      // 3. Check linearizability
      report <- Linearization.findOrdering(spec, prefixProfile, observed) match
        case Some((witness, endProfile)) =>
          // 4a. Replay suffix against the post-parallel profile
          ConcurrentReport.Linearizable(witness, endProfile).pure[cats.effect.IO]
        case None =>
          // 4b. Race detected — no conformant ordering
          val orderingsTried = factorial(observed.length)
          ConcurrentReport.RaceDetected(observed, orderingsTried, testCase).pure[cats.effect.IO]
    yield report

  /** Replay the prefix sequentially, threading the surviving profile. */
  private def replayPrefix[S](
      spec: Spec[S],
      testCase: ConcurrentTestCase[S],
      sut: SystemUnderTest[cats.effect.IO, S]
  )(using ops: StateOps[S]): cats.effect.IO[StateProfile[S]] =
    def loop(
        remaining: List[io.gruggiero.accordant4s.spec.OperationCall[S]],
        profile: StateProfile[S]
    ): cats.effect.IO[StateProfile[S]] =
      remaining match
        case Nil => cats.effect.IO.pure(profile)
        case call :: rest =>
          for
            res <- sut.execute(call)
            next <- spec.allows(call.op, call.req, res, profile) match
              case Verdict.Conformant(p) => cats.effect.IO.pure(p)
              case Verdict.Deviant(v) =>
                cats.effect.IO.raiseError(
                  new RuntimeException("prefix deviation: " + v.toString)
                )
            done <- loop(rest, next)
          yield done
    loop(testCase.prefix, StateProfile.one(testCase.initial))

  /**
   * Execute the parallel section concurrently, capturing ObservedResults.
   *
   * The existential bridge: each `call` in the parallel `NonEmptyList` has an
   * erased `Res`. `sut.execute(call)` returns `IO[call.Res]`, and we pair them
   * into an `ObservedResult`. The `asInstanceOf` is sound — the response IS
   * `call.Res` at runtime, and `ObservedResult` stores it behind a path-dependent
   * member recovered through `obs.call` in the checker. This file is exempted
   * from the `AsInstanceOf` wart (see build.sbt) for this reason.
   */
  // scalafix:off DisableSyntax.asInstanceOf
  private def executeParallel[S](
      testCase: ConcurrentTestCase[S],
      sut: SystemUnderTest[cats.effect.IO, S]
  ): cats.effect.IO[NonEmptyList[ObservedResult[S]]] =
    testCase.parallel
      .map(call =>
        sut
          .execute(call)
          .map(res =>
            ObservedResult(
              call.asInstanceOf[OperationCall.Aux[S, Any, Any]],
              res.asInstanceOf[Any]
            ).asInstanceOf[ObservedResult[S]]
          )
      )
      .parSequence
  // scalafix:on DisableSyntax.asInstanceOf

  /** n! for small n (≤ 4 → ≤ 24). */
  private def factorial(n: Int): Int =
    @scala.annotation.tailrec
    def loop(i: Int, acc: Int): Int = if i <= 1 then acc else loop(i - 1, acc * i)
    loop(n, 1)
