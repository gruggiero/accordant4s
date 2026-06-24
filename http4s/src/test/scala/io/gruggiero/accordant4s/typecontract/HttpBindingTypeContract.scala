package io.gruggiero.accordant4s.typecontract

// ═══════════════════════════════════════════════════════════════════════════
//  http-binding compile-negative obligation (post-implementation residue).
//
//  At Step 3 the type declarations were PROMOTED out of this file into their
//  real homes:
//    - `domain.MaxRetryCount`        (Iron-refined opaque type)
//    - `http.TransportOutcome`       (enum)
//    - `http.HttpRoute[Req]`         (case class)
//    - `http.HttpResponseMapper[Res]` (trait)
//    - `http.HttpBinding[S]` + `http.Endpoint[S]` (case class + sealed trait)
//    - `http.Http4sSut`              (object → SystemUnderTest[IO, S])
//
//  What remains here is the compile-negative evidence that cannot live in main
//  sources: `MaxRetryCount(0)` does not type-check (Iron `Positive`). The
//  behavioural requirements are exercised by the test oracle
//  (HttpBindingProperties).
// ═══════════════════════════════════════════════════════════════════════════

import scala.compiletime.testing.{Error => TypeCheckError, typeCheckErrors}

object HttpBindingTypeContract:

  /** CN: a non-positive `MaxRetryCount` literal does not type-check (Iron `Positive`). */
  val cnZeroRetry: List[TypeCheckError] =
    typeCheckErrors("""io.gruggiero.accordant4s.domain.MaxRetryCount(0)""")
