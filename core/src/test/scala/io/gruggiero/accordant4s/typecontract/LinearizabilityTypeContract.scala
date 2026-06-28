package io.gruggiero.accordant4s.typecontract

// ═══════════════════════════════════════════════════════════════════════════
//  linearizability compile-negative obligation (post-implementation residue).
//
//  At Step 3 the type declarations were PROMOTED out of this file into their
//  real homes:
//    - `domain.ParallelWidth`                        (Iron-refined opaque type)
//    - `domain.SpecViolation.NotLinearizable`        (new enum variant)
//    - `spec.ConcurrentTestCase[S]`                  (case class)
//    - `engine.ObservedResult[S]`                    (sealed trait)
//    - `engine.ConcurrentTestCaseGenerator`          (object)
//    - `engine.Linearization`                        (pure object — permutation search)
//    - `engine.ConcurrentExecutor`                   (object — parallel IO execution)
//    - `engine.ConcurrentReport[S]`                  (enum)
//
//  What remains here is the compile-negative evidence that cannot live in main
//  sources: `ParallelWidth(5)` and `ParallelWidth(0)` do not type-check (Iron
//  `Positive & LessEqual[4]`). The behavioural requirements are exercised by
//  the test oracle (LinearizabilityProperties).
// ═══════════════════════════════════════════════════════════════════════════

import scala.compiletime.testing.{Error => TypeCheckError, typeCheckErrors}

object LinearizabilityTypeContract:

  /** CN: ParallelWidth(5) (above the ≤4 bound) does not type-check. */
  val cnParallelWidth5: List[TypeCheckError] =
    typeCheckErrors("""io.gruggiero.accordant4s.domain.ParallelWidth(5)""")

  /** CN: ParallelWidth(0) (non-positive) does not type-check. */
  val cnParallelWidth0: List[TypeCheckError] =
    typeCheckErrors("""io.gruggiero.accordant4s.domain.ParallelWidth(0)""")
