package io.gruggiero.accordant4s.engine

import cats.data.NonEmptyList
import io.gruggiero.accordant4s.domain.SpecViolation
import io.gruggiero.accordant4s.spec.TestCase

/**
 * The outcome of replaying one [[TestCase]] against a [[SystemUnderTest]].
 *
 * Lives in `engine` (not `domain`, as an implementation-order list suggested)
 * because `DeviatesAt` carries a `TestCase[S]`, which carries a
 * `spec.OperationCall[S]`; `domain` must not import `spec`. The same class of
 * decision as `TestCase`'s placement (spec:test-generation).
 */
enum ExecutionReport[S] derives CanEqual:
  case Passed(stepsRun: Int)

  case DeviatesAt(
      stepIndex: Int,
      violations: NonEmptyList[SpecViolation],
      reproPath: TestCase[S]
  )

  /** `true` only for [[Passed]] — the Ring-3 soundness property's pass test. */
  def isPassed: Boolean = this match
    case Passed(_)           => true
    case DeviatesAt(_, _, _) => false
