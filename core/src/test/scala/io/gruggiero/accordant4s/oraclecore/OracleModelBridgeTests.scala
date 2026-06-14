package io.gruggiero.accordant4s.oraclecore

// ═══════════════════════════════════════════════════════════════════════════
//  Ring 6 bridge — MECHANICAL link between the shipped kernel and the
//  Stainless-verified model.
//
//  `domain.ProfileEval.allows` (production: cats/Iron/opaque) and
//  `verified.OracleKernel.survivors` (the PureScala model Stainless proves) are
//  run on the SAME generated inputs and asserted to agree on the two invariants
//  Stainless verifies: CONFORMANCE (some state survives ⇔ a branch passed) and
//  survivor CARDINALITY (deduplicated survivor count). If EITHER the production
//  code or the model drifts, this property fails — converting "both satisfy the
//  same spec" into an executable cross-check.
//
//  The model is COMPILED (not re-verified) here — `verified/stainlessEnabled` is
//  off by default, so this adds no Stainless cost to `core/test`. Verification
//  is the separate `sbt ring6` step.
// ═══════════════════════════════════════════════════════════════════════════

import scala.annotation.unused

import cats.data.NonEmptyList
import cats.syntax.all._
import hedgehog._
import hedgehog.munit.HedgehogSuite
import io.gruggiero.accordant4s.domain._
import io.gruggiero.accordant4s.fixtures.BankState
import io.gruggiero.accordant4s.spec._
import io.gruggiero.accordant4s.verified.OracleKernel
import io.gruggiero.accordant4s.verified.OracleKernel.EvalBranch
import stainless.collection.{Cons, List => SList, Nil => SNil}

final class OracleModelBridgeTests extends HedgehogSuite:

  private val name: OperationName = OperationName("bridge")

  private val checkPos: ResponseCheck[Int] =
    r => if r > 0 then ().validNel else SpecViolation.CheckFailed(name, "not positive").invalidNel

  private val checkNeg: ResponseCheck[Int] =
    r => if r < 0 then ().validNel else SpecViolation.CheckFailed(name, "not negative").invalidNel

  private val collapsed: BankState = BankState(Map("collapsed" -> BigDecimal(0)))

  // OneOf exercising every shape: r>0 ⇒ a constant Next (all candidates collapse to one
  // survivor), r<0 ⇒ Same (each candidate survives ⇒ multi-survivor + dedup), r==0 ⇒ no
  // branch matches ⇒ Deviant.
  private def behaviour(@unused s: BankState): Outcome[Int, BankState] =
    expect.oneOf(
      NonEmptyList.of[Outcome[Int, BankState]](
        expect(checkPos).thenState((_, _) => collapsed),
        expect(checkNeg).sameState
      )
    )

  private val genBankState: Gen[BankState] =
    Gen
      .string(Gen.alpha, Range.linear(1, 5))
      .flatMap(id => Gen.int(Range.linear(0, 9)).map(b => BankState(Map(id -> BigDecimal(b)))))

  private val genProfile: Gen[StateProfile[BankState]] =
    genBankState
      .list(Range.linear(1, 4))
      .map(xs => StateProfile.of(NonEmptyList.fromListUnsafe(xs)))

  private val genRes: Gen[Int] = Gen.int(Range.linearFrom(0, -20, 20))

  private def toSList(xs: List[EvalBranch]): SList[EvalBranch] =
    xs.foldRight(SNil[EvalBranch](): SList[EvalBranch])((x, acc) => Cons(x, acc))

  property(
    "real ProfileEval.allows agrees with the Stainless model on conformance and survivor count"
  ) {
    for
      profile <- genProfile.forAll
      res     <- genRes.forAll
    yield
      val real = ProfileEval.allows(name, behaviour, res, profile)

      // Reduce the real per-candidate branch evaluation to the model's abstraction:
      // (did the check pass?, encoded next-state identity).
      val flat: List[(Boolean, BankState)] =
        profile.toList.flatMap(c =>
          OutcomeEval.flatten(behaviour(c)).map(b => (b.matches(res, c), b.next(res, c)))
        )
      val distinctNext: List[BankState] =
        flat
          .map(_._2)
          .foldLeft(List.empty[BankState])((acc, s) => if acc.exists(_ == s) then acc else acc :+ s)
      def enc(s: BankState): BigInt = BigInt(distinctNext.indexWhere(_ == s))
      val modelSurv =
        OracleKernel.survivors(toSList(flat.map((passed, nx) => EvalBranch(passed, enc(nx)))))

      real match
        case Verdict.Conformant(p) =>
          Result.assert(!modelSurv.isEmpty && modelSurv.size.toInt == p.toList.size)
        case Verdict.Deviant(_) => Result.assert(modelSurv.isEmpty)
  }
