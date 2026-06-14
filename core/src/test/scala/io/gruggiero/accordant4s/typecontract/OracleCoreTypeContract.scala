package io.gruggiero.accordant4s.typecontract

// ═══════════════════════════════════════════════════════════════════════════
//  Oracle-core compile-negative obligations (post-implementation residue).
//
//  At Step 3 the type declarations were PROMOTED out of this file into their
//  real homes (`io.gruggiero.accordant4s.domain` / `.spec`). What remains is the
//  test-side evidence that cannot live in main sources: the three
//  compile-negative checks, plus the minimal example they reference.
//
//  Each `typeCheckErrors(...)` MUST return a non-empty list; the test oracle
//  (OracleCoreProperties) asserts `.nonEmpty` on each.
// ═══════════════════════════════════════════════════════════════════════════

import scala.compiletime.testing.{Error => TypeCheckError, typeCheckErrors}

import cats.syntax.all._
import hedgehog.Gen
import io.gruggiero.accordant4s.domain._
import io.gruggiero.accordant4s.fixtures.BankState
import io.gruggiero.accordant4s.spec._

object OracleCoreTypeContract:

  final case class GetBalanceRequest(accountId: String) derives CanEqual
  final case class GetBalanceResponse(balance: BigDecimal) derives CanEqual
  final case class WithdrawRequest(accountId: String, amount: BigDecimal) derives CanEqual

  val getBalanceCheck: ResponseCheck[GetBalanceResponse] =
    res =>
      if res.balance >= BigDecimal(0) then ().validNel
      else
        SpecViolation
          .CheckFailed(OperationName("getBalance"), "balance must be non-negative")
          .invalidNel

  val getBalance: Operation[GetBalanceRequest, GetBalanceResponse, BankState] =
    Operation(
      name = OperationName("getBalance"),
      behaviour = (_, _) => expect(getBalanceCheck).sameState,
      mock = (req, s) =>
        Gen.constant(GetBalanceResponse(s.accounts.getOrElse(req.accountId, BigDecimal(0))))
    )

  val exampleSpec: Either[OperationName, Spec[BankState]] =
    Spec.empty[BankState].register(getBalance)

  /** CN1a: blank Iron literal — `OperationName("")` does not type-check (Not[Blank]). */
  val cnBlankOperationName: List[TypeCheckError] = typeCheckErrors("""OperationName("")""")

  /** CN1b: blank Iron literal — `CallLabel("")` does not type-check (Not[Blank]). */
  val cnBlankCallLabel: List[TypeCheckError] = typeCheckErrors("""CallLabel("")""")

  /** CN2: typed operation handles make Req mismatches a type error — no casts. */
  val cnReqResMismatch: List[TypeCheckError] = typeCheckErrors(
    """exampleSpec.toOption.get.allows(getBalance, WithdrawRequest("alice", BigDecimal(10)), GetBalanceResponse(BigDecimal(0)), StateProfile.one(BankState(Map.empty)))"""
  )

  /** CN3: no public constructor from a possibly-empty collection — emptiness is unrepresentable. */
  val cnEmptyStateProfile: List[TypeCheckError] =
    typeCheckErrors("""StateProfile.of(List.empty[BankState])""")
