package io.gruggiero.accordant4s.fixtures

// ═══════════════════════════════════════════════════════════════════════════
//  Reusable fixtures + generators for spec: input-sets (Step 2 test oracle).
//
//  Houses the `deposit` Operation over BankState and the generators INTRODUCED
//  by this spec — `genOperationCall` / `genInputSet` — so downstream specs
//  (state-graph, spec 3) can import them rather than re-deriving. The remaining
//  `gen*` helpers are test-local strategies named by the spec's Property blocks.
//
//  Types `OperationCall` / `InputSet` / `withInput` live in
//  `io.gruggiero.accordant4s.spec` (promoted from the typed contract at Step 3).
// ═══════════════════════════════════════════════════════════════════════════

import cats.Show
import cats.syntax.all._
import hedgehog._
import io.gruggiero.accordant4s.domain.{CallLabel, OperationName, SpecViolation}
import io.gruggiero.accordant4s.spec.{InputSet, Operation, OperationCall, expect, withInput}

object InputFixtures:

  // ── Domain model for the oracle (toy bank deposit) ──────────────────────────

  final case class DepositRequest(accountId: String, amount: BigDecimal) derives CanEqual
  final case class DepositResponse(newBalance: BigDecimal) derives CanEqual

  given Show[DepositRequest] = Show.show(r => r.accountId + ", " + r.amount.toString)

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

  // ── Test-local generator strategies (named by the spec's Property blocks) ───

  val genDepositRequest: Gen[DepositRequest] =
    for
      acct <- Gen.element1("alice", "bob", "carol")
      amt  <- Gen.int(Range.linear(1, 1000000))
    yield DepositRequest(acct, BigDecimal(amt))

  // Non-blank by the [1, 16] range → `applyUnsafe` never fails (no filtering).
  val genCallLabel: Gen[CallLabel] =
    Gen.string(Gen.alphaNum, Range.linear(1, 16)).map(CallLabel.applyUnsafe)

  val genSeed: Gen[Long] = Gen.long(Range.linearFrom(0L, Long.MinValue, Long.MaxValue))

  // Test-local n: Int in [1, 20] (approved: no public PosInt — see contract).
  val genPosInt: Gen[Int] = Gen.int(Range.linear(1, 20))

  // Mixes varied payloads with a Gen.constant sub-case so the duplicate-collapse
  // path is exercised by the determinism property too.
  val genReq: Gen[DepositRequest] =
    Gen.frequency1(
      3 -> Gen.int(Range.linear(1, 100)).map(i => DepositRequest("alice", BigDecimal(i))),
      1 -> Gen.constant(DepositRequest("alice", BigDecimal(50)))
    )

  // ── Generators INTRODUCED by this spec (reused by downstream specs) ─────────

  val genOperationCall: Gen[OperationCall[BankState]] =
    for
      req   <- genDepositRequest
      label <- genCallLabel
    yield deposit.withInput(req, label): OperationCall[BankState]

  /**
   * Label-disjoint input sets: labels are re-namespaced as `"<prefix>-<index>"`,
   * so disjointness is by construction (not `.ensure`-filtering). Exercises
   * `withInput` on the EXISTENTIAL call (`c.op.withInput(c.req, …)`).
   */
  def genInputSet(prefix: String): Gen[InputSet[BankState]] =
    genOperationCall
      .list(Range.linear(0, 4))
      .map { calls =>
        val relabeled = calls.zipWithIndex.map { case (c, i) =>
          c.op.withInput(c.req, CallLabel.applyUnsafe(prefix + "-" + i.toString)): OperationCall[
            BankState
          ]
        }
        InputSet.of(relabeled).fold(_ => InputSet.empty[BankState], identity)
      }
