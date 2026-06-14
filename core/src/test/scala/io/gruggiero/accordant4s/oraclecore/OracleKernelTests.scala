package io.gruggiero.accordant4s.oraclecore

// ═══════════════════════════════════════════════════════════════════════════
//  Ring-5 hardening — direct unit coverage of the two public kernel functions
//  that `Spec.allows` does not exercise transitively (`OutcomeEval.survivors`
//  and the `eqStateProfile` given). Supplements the approved test oracle; does
//  not modify it.
// ═══════════════════════════════════════════════════════════════════════════

import cats.data.NonEmptyList
import cats.syntax.all._
import hedgehog._
import hedgehog.munit.HedgehogSuite
import io.gruggiero.accordant4s.domain.OutcomeEval.Branch
import io.gruggiero.accordant4s.domain._
import io.gruggiero.accordant4s.fixtures.BankState

final class OracleKernelTests extends HedgehogSuite:

  private val kName: OperationName = OperationName("kernel")

  private val pass: ResponseCheck[Unit] = _ => ().validNel[SpecViolation]

  private val fail: ResponseCheck[Unit] = _ =>
    SpecViolation.CheckFailed(kName, "no match").invalidNel

  // OutcomeEval.survivors must keep only passing branches' next-states, Eq-deduplicated.
  property("OutcomeEval.survivors filters non-matching branches and dedups Eq-equal next-states") {
    for _ <- Gen.constant(()).forAll
    yield
      val sX = BankState(Map("a" -> BigDecimal(1)))
      val sY = BankState(Map("b" -> BigDecimal(2)))
      val branches = List(
        Branch[Unit, BankState](pass, (_, _) => sX), // matches → sX
        Branch[Unit, BankState](pass, (_, _) => sX), // matches → sX (Eq-duplicate)
        Branch[Unit, BankState](fail, (_, _) => sY)  // does not match → excluded
      )
      val out = OutcomeEval.survivors(branches, (), List(BankState(Map.empty)))
      Result.assert(out.size == 1 && out.forall(_ == sX))
  }

  // eqStateProfile: order-insensitive set equality over Eq-deduplicated membership.
  property("eqStateProfile compares deduplicated membership, order-insensitive") {
    for _ <- Gen.constant(()).forAll
    yield
      val s1  = BankState(Map("a" -> BigDecimal(1)))
      val s2  = BankState(Map("b" -> BigDecimal(2)))
      val s3  = BankState(Map("c" -> BigDecimal(3)))
      val p12 = StateProfile.of(NonEmptyList.of(s1, s2))
      val p21 = StateProfile.of(NonEmptyList.of(s2, s1))
      val p13 = StateProfile.of(NonEmptyList.of(s1, s3))
      val p1  = StateProfile.of(NonEmptyList.of(s1))
      Result.assert(
        (p12 === p21) &&    // same set, reversed → equal
          !(p12 === p13) && // same size, one member differs → not equal
          !(p12 === p1)     // different size → not equal
      )
  }
