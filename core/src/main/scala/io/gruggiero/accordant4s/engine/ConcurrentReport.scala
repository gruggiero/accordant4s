package io.gruggiero.accordant4s.engine

import cats.data.NonEmptyList
import io.gruggiero.accordant4s.domain.StateProfile
import io.gruggiero.accordant4s.spec.ConcurrentTestCase

/**
 * The outcome of running a [[ConcurrentTestCase]]: `Linearizable` (a conformant
 * sequential ordering was found) or `RaceDetected` (no ordering explains the
 * observed responses — a race was caught). `RaceDetected` carries the observed
 * results, the count of orderings tried, and a persistable repro case.
 */
enum ConcurrentReport[S] derives CanEqual:
  case Linearizable(witnessOrdering: List[ObservedResult[S]], endProfile: StateProfile[S])

  case RaceDetected(
      observed: NonEmptyList[ObservedResult[S]],
      orderingsTried: Int,
      reproCase: ConcurrentTestCase[S]
  )
