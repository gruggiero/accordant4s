package io.gruggiero.accordant4s.fixtures

// ═══════════════════════════════════════════════════════════════════════════
//  Fixtures + generators for spec: state-graph (Step 2 test oracle).
//
//  Bank operations covering every Outcome shape: `create` (Next, idempotent),
//  `deposit` (Next, reused from InputFixtures), `withdraw` (OneOf success|notFound,
//  the mock picks per balance), `fork` (OneOf with two always-passing branches →
//  two survivors from one response). Plus the shared `genSmallSpecAndInputs` the
//  Ring-3 properties draw from, and the INTRODUCED `genStateGraph`.
//
//  Graph types live in `domain.MaxDepth` and `engine.{Node, Edge, StateGraph,
//  GraphExplorer}` (promoted from the typed contract at Step 3).
// ═══════════════════════════════════════════════════════════════════════════

import cats.data.NonEmptyList
import cats.syntax.all._
import hedgehog._
import io.gruggiero.accordant4s.domain.{
  CallLabel,
  MaxDepth,
  OperationName,
  Outcome,
  ResponseCheck,
  SpecViolation
}
import io.gruggiero.accordant4s.engine.{GraphExplorer, StateGraph}
import io.gruggiero.accordant4s.fixtures.InputFixtures.{DepositRequest, deposit}
import io.gruggiero.accordant4s.spec.{InputSet, Operation, OperationCall, Spec, expect, withInput}

object GraphFixtures:

  // ── Create: idempotent account creation at balance 0 (Next) ─────────────────

  final case class CreateRequest(id: String) derives CanEqual

  enum CreateResponse derives CanEqual:
    case Created

  val createName: OperationName = OperationName("Create")

  val create: Operation[CreateRequest, CreateResponse, BankState] = Operation(
    createName,
    (req, _) =>
      expect((_: CreateResponse) => ().validNel[SpecViolation])
        .thenState((_, s) =>
          if s.accounts.contains(req.id) then s
          else s.copy(accounts = s.accounts.updated(req.id, BigDecimal(0)))
        ),
    (_, _) => Gen.constant(CreateResponse.Created)
  )

  // ── Withdraw: OneOf(success | notFound); the mock decides per balance ───────

  final case class WithdrawRequest(id: String, amount: BigDecimal) derives CanEqual

  enum WithdrawResponse derives CanEqual:
    case Success(newBalance: BigDecimal)
    case NotFound
    case Timeout
    case ServerError

  val withdrawName: OperationName = OperationName("Withdraw")

  private val wSuccess: ResponseCheck[WithdrawResponse] =
    case WithdrawResponse.Success(b) if b >= BigDecimal(0) => ().validNel[SpecViolation]
    case _ => SpecViolation.CheckFailed(withdrawName, "expected success").invalidNel

  private val wNotFound: ResponseCheck[WithdrawResponse] =
    case WithdrawResponse.NotFound => ().validNel[SpecViolation]
    case _ => SpecViolation.CheckFailed(withdrawName, "expected not-found").invalidNel

  private val wTimeout: ResponseCheck[WithdrawResponse] =
    case WithdrawResponse.Timeout => ().validNel[SpecViolation]
    case _ => SpecViolation.CheckFailed(withdrawName, "expected timeout").invalidNel

  val withdraw: Operation[WithdrawRequest, WithdrawResponse, BankState] = Operation(
    withdrawName,
    (req, _) =>
      expect.oneOf(
        NonEmptyList.of[Outcome[WithdrawResponse, BankState]](
          expect(wSuccess).thenState((res, s) =>
            res match
              case WithdrawResponse.Success(b) => s.copy(accounts = s.accounts.updated(req.id, b))
              case _                           => s
          ),
          expect(wNotFound).sameState,
          expect(wTimeout).sameState
        )
      ),
    (req, s) =>
      s.accounts.get(req.id) match
        case Some(bal) if bal >= req.amount =>
          Gen.constant(WithdrawResponse.Success(bal - req.amount))
        case _ => Gen.constant(WithdrawResponse.NotFound)
  )

  // ── Fork: OneOf with two always-passing branches → two survivors ────────────

  final case class ForkRequest(id: String) derives CanEqual

  enum ForkResponse derives CanEqual:
    case Ok

  val forkName: OperationName = OperationName("Fork")

  val fork: Operation[ForkRequest, ForkResponse, BankState] = Operation(
    forkName,
    (req, _) =>
      expect.oneOf(
        NonEmptyList.of[Outcome[ForkResponse, BankState]](
          expect((_: ForkResponse) => ().validNel[SpecViolation])
            .thenState((_, s) => s.copy(accounts = s.accounts.updated(req.id, BigDecimal(1)))),
          expect((_: ForkResponse) => ().validNel[SpecViolation])
            .thenState((_, s) => s.copy(accounts = s.accounts.updated(req.id, BigDecimal(2))))
        )
      ),
    (_, _) => Gen.constant(ForkResponse.Ok)
  )

  // ── Spec assembly (the pool of all four ops registered) ─────────────────────

  private def registerAll(ops: List[Operation[?, ?, BankState]]): Spec[BankState] =
    ops.foldLeft(Spec.empty[BankState])((sp, op) => sp.register(op).getOrElse(sp))

  val bankSpec: Spec[BankState] = registerAll(List(create, deposit, withdraw, fork))

  // ── Convenience builders for the scenario tests ─────────────────────────────

  def call[Req, Res](
      op: Operation[Req, Res, BankState],
      req: Req,
      label: String
  ): OperationCall[BankState] =
    op.withInput(req, CallLabel.applyUnsafe(label))

  def inputSetOf(calls: List[OperationCall[BankState]]): InputSet[BankState] =
    InputSet.of(calls).fold(_ => InputSet.empty[BankState], identity)

  val empty: BankState = BankState(Map.empty)

  // ── Shared property generator: (spec, inputs, initial, depth, seed) ─────────

  private val genId: Gen[String]         = Gen.element1("alice", "bob")
  private val genAmount: Gen[BigDecimal] = Gen.int(Range.linear(1, 100)).map(i => BigDecimal(i))
  private val tmp: CallLabel             = CallLabel("tmp")

  private val genAnyCall: Gen[OperationCall[BankState]] =
    Gen.frequency1(
      1 -> genId.map(id => create.withInput(CreateRequest(id), tmp): OperationCall[BankState]),
      2 -> (for id <- genId; a <- genAmount
      yield deposit.withInput(DepositRequest(id, a), tmp): OperationCall[BankState]),
      2 -> (for id <- genId; a <- genAmount
      yield withdraw.withInput(WithdrawRequest(id, a), tmp): OperationCall[BankState]),
      1 -> genId.map(id => fork.withInput(ForkRequest(id), tmp): OperationCall[BankState])
    )

  private val genInitialState: Gen[BankState] =
    Gen.frequency1(
      2 -> Gen.constant(empty),
      1 -> (for id <- genId; a <- genAmount yield BankState(Map(id -> a)))
    )

  val genSmallSpecAndInputs
      : Gen[(Spec[BankState], InputSet[BankState], BankState, MaxDepth, Long)] =
    for
      calls   <- genAnyCall.list(Range.linear(2, 6))
      depthN  <- Gen.int(Range.linear(1, 4))
      seed    <- Gen.long(Range.linearFrom(0L, Long.MinValue, Long.MaxValue))
      initial <- genInitialState
    yield
      val relabeled = calls.zipWithIndex.map { case (c, i) =>
        c.op.withInput(c.req, CallLabel.applyUnsafe("c-" + i.toString)): OperationCall[BankState]
      }
      (bankSpec, inputSetOf(relabeled), initial, MaxDepth.applyUnsafe(depthN), seed)

  /** INTRODUCED generator: graphs from arbitrary small specs, for downstream specs. */
  val genStateGraph: Gen[StateGraph[BankState]] =
    genSmallSpecAndInputs.map((spec, inputs, initial, depth, seed) =>
      GraphExplorer.explore(spec, inputs, initial, depth, seed)
    )
