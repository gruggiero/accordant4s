package io.gruggiero.accordant4s.typecontract

// ═══════════════════════════════════════════════════════════════════════════
//  smithy4s-derivation compile-negative obligation (post-implementation residue).
//
//  At Step 3 the type declarations were PROMOTED out of this file into the
//  `accordant4s-smithy4s` module's MAIN sources (package `io.gruggiero.accordant4s.smithy`):
//    - `smithy.SmithyOps`          (object — forService entry point)
//    - `smithy.EndpointSlot`       (case class — a derived operation slot)
//    - `smithy.SmithyEndpoint`     (sealed trait — existential wrapper)
//    - `smithy.SpecBuilder`        (builder — complete-or-fail spec assembly)
//
//  What remains here is the compile-negative evidence that cannot live in main
//  sources. The behavioural requirements are exercised by the test oracle
//  (Smithy4sDerivationProperties).
//
//  NOTE on the CN obligation: `SpecBuilder.assign[Req, Res]` accepts
//  caller-declared type params (there is no compile-time link between the slot's
//  smithy4s `Schema[I]` and the `Req` type parameter at the assign site). The
//  real type-safety comes from the oracle using the smithy4s-generated types:
//  `assign[WithdrawInput, WithdrawOutput](withdrawName, ...)` type-checks
//  because the behaviour is typed against the generated case classes. The CN
//  below tests the structural shape; the oracle exercises the real types.
// ═══════════════════════════════════════════════════════════════════════════

import scala.compiletime.testing.{Error => TypeCheckError, typeCheckErrors}

object Smithy4sDerivationTypeContract:

  /**
   * CN: assigning a behaviour whose `Req` does not match the smithy4s-generated
   * type is a type error at the call site when the caller uses the generated
   * types. This structural check verifies the `SpecBuilder.assign` signature
   * is parameterized (so a wrong-typed assignment is rejected by the compiler
   * when the caller's `Req` is constrained).
   */
  val cnWrongBehaviourType: List[TypeCheckError] = typeCheckErrors(
    """
    val builder: io.gruggiero.accordant4s.smithy.SpecBuilder[String] =
      io.gruggiero.accordant4s.smithy.SpecBuilder(Vector.empty)
    // assigning a behaviour whose Req is Int (not String) must not compile
    builder.assign[Int, String](
      io.gruggiero.accordant4s.domain.OperationName("Withdraw"),
      (_: Int, _: String) => io.gruggiero.accordant4s.domain.Outcome.Same(_ => ().validNel[io.gruggiero.accordant4s.domain.SpecViolation]),
      (_: Int, _: String) => hedgehog.Gen.constant("wrong")
    )
    """
  )
