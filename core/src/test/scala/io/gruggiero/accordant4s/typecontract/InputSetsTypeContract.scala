package io.gruggiero.accordant4s.typecontract

// ═══════════════════════════════════════════════════════════════════════════
//  Input-sets compile-negative obligations (post-implementation residue).
//
//  At Step 3 the type declarations were PROMOTED out of this file into their
//  real homes (io.gruggiero.accordant4s.spec.{OperationCall, InputSet, withInput}).
//  What remains is the test-side evidence that cannot live in main sources: the
//  two compile-negative checks and the minimal fixtures they reference, plus the
//  existential re-association proof (`depositCall` / `feeds`) the spec's
//  no-casts obligation rests on.
//
//  Each `typeCheckErrors(...)` MUST return a non-empty list; the test oracle
//  (InputSetsProperties) asserts `.nonEmpty` on each.
// ═══════════════════════════════════════════════════════════════════════════

import scala.compiletime.testing.{Error => TypeCheckError, typeCheckErrors}

import cats.syntax.all._
import hedgehog.Gen
import io.gruggiero.accordant4s.domain._
import io.gruggiero.accordant4s.fixtures.BankState
import io.gruggiero.accordant4s.spec.{Operation, OperationCall, Spec, expect, withInput}

object InputSetsTypeContract:

  // ── Reused fixtures (request/response model for the CN obligations) ─────────

  final case class DepositRequest(accountId: String, amount: BigDecimal) derives CanEqual
  final case class DepositResponse(newBalance: BigDecimal) derives CanEqual
  final case class WithdrawRequest(accountId: String, amount: BigDecimal) derives CanEqual

  val deposit: Operation[DepositRequest, DepositResponse, BankState] =
    Operation(
      name = OperationName("Deposit"),
      behaviour = (req, _) =>
        expect((_: DepositResponse) => ().validNel[SpecViolation])
          .thenState((res, s) =>
            s.copy(accounts = s.accounts.updated(req.accountId, res.newBalance))
          ),
      mock = (req, s) =>
        Gen.constant(
          DepositResponse(s.accounts.getOrElse(req.accountId, BigDecimal(0)) + req.amount)
        )
    )

  val sampleLabel: CallLabel = CallLabel("Deposit(alice, 50)")

  // ── Existential re-association proof (no casts) ─────────────────────────────

  /** At a construction site the concrete types survive: `call.req` is typed. */
  val depositCall: OperationCall.Aux[BankState, DepositRequest, DepositResponse] =
    deposit.withInput(DepositRequest("alice", BigDecimal(50)), sampleLabel)

  /**
   * Feeding a STORED (existential) call to `spec.allows` type-checks: the
   * path-dependent `call.op` / `call.req` / `call.Res` align, no casts.
   */
  def feeds(
      spec: Spec[BankState],
      call: OperationCall[BankState],
      res: call.Res,
      profile: StateProfile[BankState]
  )(using StateOps[BankState]): Verdict[BankState] =
    spec.allows(call.op, call.req, res, profile)

  // ── Compile-negative obligations (asserted `.nonEmpty` by the test oracle) ──

  /** CN1: blank Iron literal — `CallLabel("")` does not type-check (`Not[Blank]`). */
  val cnBlankCallLabel: List[TypeCheckError] = typeCheckErrors("""CallLabel("")""")

  /** CN2: `withInput` is typed by the operation's `Req`; cross-op reuse is a type error. */
  val cnCrossOpRequest: List[TypeCheckError] =
    typeCheckErrors("""deposit.withInput(WithdrawRequest("alice", BigDecimal(1)), sampleLabel)""")
