package io.gruggiero.accordant4s.oraclecore

// ═══════════════════════════════════════════════════════════════════════════
//  Test Oracle for spec: oracle-core   (Step 2 — TESTS BEFORE IMPLEMENTATION)
//  Schema: verified-scala3
//
//  Derived from specs/oracle-core/spec.md ONLY (not from any implementation —
//  there is none yet). Compiles against the APPROVED typed contract
//  (io.gruggiero.accordant4s.typecontract.*), whose oracle kernels
//  (Spec.allows, StateProfile.of, eqStateProfile, OutcomeEval/ProfileEval) are
//  `???`. These tests are EXPECTED TO FAIL AT RUNTIME until Step 3 lands the
//  implementation — that is the point. The compile-negative checks already pass
//  (they assert compile errors the contract proved via `typeCheckErrors`).
//
//  Framework: Hedgehog via `hedgehog.munit.HedgehogSuite` (per capability-profile.md).
//  Every block is a property (deterministic scenarios use `Gen.constant(())`),
//  yielding `hedgehog.Result` to avoid the munit/Hedgehog `assert` overload clash.
// ═══════════════════════════════════════════════════════════════════════════

import cats.Eq
import cats.data.{NonEmptyList, ValidatedNel}
import cats.syntax.all._
import hedgehog._
import hedgehog.munit.HedgehogSuite
import io.gruggiero.accordant4s.domain.OutcomeEval.Branch
import io.gruggiero.accordant4s.domain._
import io.gruggiero.accordant4s.fixtures.BankState
import io.gruggiero.accordant4s.spec._
import io.gruggiero.accordant4s.typecontract.OracleCoreTypeContract

// ── Test-side domain model (request/response fixtures for a toy bank) ────────

final case class WithdrawRequest(accountId: String, amount: BigDecimal) derives CanEqual

enum WithdrawResponse derives CanEqual:
  case Success(newBalance: BigDecimal)
  case NotFound
  case BadRequest
  case Timeout

final case class DepositRequest(accountId: String, amount: BigDecimal) derives CanEqual
final case class DepositResponse(newBalance: BigDecimal) derives CanEqual

final case class GetAccountRequest(accountId: String) derives CanEqual

enum GetAccountResponse derives CanEqual:
  case Found(balance: BigDecimal)
  case NotFound

final case class CreateAccountRequest(accountId: String) derives CanEqual

enum CreateAccountResponse derives CanEqual:
  case Created
  case Timeout

final class OracleCoreProperties extends HedgehogSuite:

  // ── Reference (independent) oracle helpers — derived from the spec ────────
  // These re-express the EXPECTED semantics over the real (non-stubbed) Outcome
  // ADT, so the properties check `Spec.allows` against an independent oracle.

  private def refFlatten[Res, S](o: Outcome[Res, S]): List[Branch[Res, S]] = o match
    case Outcome.Same(check)        => List(Branch(check, (_, s) => s))
    case Outcome.Next(check, trans) => List(Branch(check, trans))
    case Outcome.OneOf(branches)    => branches.toList.flatMap(refFlatten)

  private def matchingBranches[Res, S](o: Outcome[Res, S], res: Res, s: S): List[Branch[Res, S]] =
    refFlatten(o).filter(_.matches(res, s))

  private def countFailedChecks[Req, Res, S](
      op: Operation[Req, Res, S],
      req: Req,
      res: Res,
      profile: StateProfile[S]
  ): Int =
    profile.toList.map { s =>
      refFlatten(op.behaviour(req, s)).map(b => b.check(res).fold(_.length, _ => 0)).sum
    }.sum

  extension [A](xs: List[A])

    private def distinctByEq(using eq: Eq[A]): List[A] =
      xs.foldLeft(List.empty[A])((acc, x) => if acc.exists(eq.eqv(_, x)) then acc else acc :+ x)

    private def sameElementsByEq(ys: List[A])(using Eq[A]): Boolean =
      xs.distinctByEq.forall(x => ys.exists(_ === x))
        && ys.distinctByEq.forall(y => xs.exists(_ === y))

  // ── Operation fixtures (built with the real `expect` DSL) ────────────────

  // Direct references so the concept-coverage table is exercised in code:
  //  - StateOps[BankState] is summoned by every `allows`/`StateProfile.of` call;
  //  - CallLabel is introduced by this spec as a type (consumed by spec 2).
  val bankStateOps: StateOps[BankState] = summon[StateOps[BankState]]
  val sampleLabel: CallLabel            = CallLabel("step-1")

  val withdrawName: OperationName   = OperationName("Withdraw")
  val depositName: OperationName    = OperationName("Deposit")
  val getAccountName: OperationName = OperationName("GetAccount")
  val createName: OperationName     = OperationName("CreateAccount")
  val noopName: OperationName       = OperationName("NoopGet")
  val multiCheckName: OperationName = OperationName("MultiCheck")
  val collapseName: OperationName   = OperationName("Collapse")

  val withdrawCheck: ResponseCheck[WithdrawResponse] =
    case WithdrawResponse.Success(b) if b >= BigDecimal(0) => ().validNel[SpecViolation]
    case WithdrawResponse.Success(_) =>
      SpecViolation.CheckFailed(withdrawName, "negative balance").invalidNel
    case WithdrawResponse.NotFound =>
      SpecViolation.CheckFailed(withdrawName, "not found").invalidNel
    case WithdrawResponse.BadRequest =>
      SpecViolation.CheckFailed(withdrawName, "bad request").invalidNel
    case WithdrawResponse.Timeout => SpecViolation.CheckFailed(withdrawName, "timeout").invalidNel

  // Two atomic checks so failures ACCUMULATE (ValidatedNel applicative).
  val multiCheck: ResponseCheck[WithdrawResponse] = res =>
    val c1: ValidatedNel[SpecViolation, Unit] = res match
      case WithdrawResponse.Success(_) => ().validNel
      case _ => SpecViolation.CheckFailed(multiCheckName, "not success").invalidNel
    val c2: ValidatedNel[SpecViolation, Unit] = res match
      case WithdrawResponse.Success(b) if b === BigDecimal(20) => ().validNel
      case _ => SpecViolation.CheckFailed(multiCheckName, "balance != 20").invalidNel
    (c1, c2).mapN((_, _) => ())

  val createdCheck: ResponseCheck[CreateAccountResponse] =
    case CreateAccountResponse.Created => ().validNel[SpecViolation]
    case CreateAccountResponse.Timeout =>
      SpecViolation.CheckFailed(createName, "expected Created").invalidNel

  val timeoutCheck: ResponseCheck[CreateAccountResponse] =
    case CreateAccountResponse.Timeout => ().validNel[SpecViolation]
    case CreateAccountResponse.Created =>
      SpecViolation.CheckFailed(createName, "expected Timeout").invalidNel

  val anyWithdrawPass: ResponseCheck[WithdrawResponse] = _ => ().validNel[SpecViolation]

  val withdraw: Operation[WithdrawRequest, WithdrawResponse, BankState] = Operation(
    withdrawName,
    (req, _) =>
      expect(withdrawCheck).thenState { (res, s) =>
        res match
          case WithdrawResponse.Success(b) =>
            s.copy(accounts = s.accounts.updated(req.accountId, b))
          case _ => s
      },
    (req, s) =>
      Gen.constant(WithdrawResponse.Success(s.accounts.getOrElse(req.accountId, BigDecimal(0))))
  )

  val deposit: Operation[DepositRequest, DepositResponse, BankState] = Operation(
    depositName,
    (req, _) =>
      expect((_: DepositResponse) => ().validNel[SpecViolation])
        .thenState((res, s) =>
          s.copy(accounts = s.accounts.updated(req.accountId, res.newBalance))
        ),
    (req, s) =>
      Gen.constant(DepositResponse(s.accounts.getOrElse(req.accountId, BigDecimal(0)) + req.amount))
  )

  val getAccount: Operation[GetAccountRequest, GetAccountResponse, BankState] = Operation(
    getAccountName,
    (req, s) =>
      val foundCheck: ResponseCheck[GetAccountResponse] =
        case GetAccountResponse.Found(b) if s.accounts.get(req.accountId).contains(b) =>
          ().validNel[SpecViolation]
        case GetAccountResponse.Found(_) =>
          SpecViolation.CheckFailed(getAccountName, "balance mismatch").invalidNel
        case GetAccountResponse.NotFound =>
          SpecViolation.CheckFailed(getAccountName, "expected found").invalidNel
      val notFoundCheck: ResponseCheck[GetAccountResponse] =
        case GetAccountResponse.NotFound if !s.accounts.contains(req.accountId) =>
          ().validNel[SpecViolation]
        case GetAccountResponse.NotFound =>
          SpecViolation.CheckFailed(getAccountName, "account exists").invalidNel
        case GetAccountResponse.Found(_) =>
          SpecViolation.CheckFailed(getAccountName, "expected notFound").invalidNel
      expect.oneOf(
        NonEmptyList.of[Outcome[GetAccountResponse, BankState]](
          expect(foundCheck).sameState,
          expect(notFoundCheck).sameState
        )
      )
    ,
    (req, s) =>
      Gen.constant(
        s.accounts
          .get(req.accountId)
          .fold[GetAccountResponse](GetAccountResponse.NotFound)(GetAccountResponse.Found(_))
      )
  )

  val createAccount: Operation[CreateAccountRequest, CreateAccountResponse, BankState] = Operation(
    createName,
    (req, _) =>
      expect.oneOf(
        NonEmptyList.of[Outcome[CreateAccountResponse, BankState]](
          expect(createdCheck).thenState((_, s) =>
            s.copy(accounts = s.accounts.updated(req.accountId, BigDecimal(0)))
          ),
          expect(timeoutCheck).sameState,
          expect(timeoutCheck).thenState((_, s) =>
            s.copy(accounts = s.accounts.updated(req.accountId, BigDecimal(0)))
          )
        )
      ),
    (_, _) => Gen.constant(CreateAccountResponse.Created)
  )

  val anyRes: GetAccountResponse = GetAccountResponse.Found(BigDecimal(0))

  val noopGet: Operation[GetAccountRequest, GetAccountResponse, BankState] = Operation(
    noopName,
    (_, _) => expect((_: GetAccountResponse) => ().validNel[SpecViolation]).sameState,
    (_, _) => Gen.constant(anyRes)
  )

  val multiCheckReq: WithdrawRequest = WithdrawRequest("alice", BigDecimal(10))

  val multiCheckOp: Operation[WithdrawRequest, WithdrawResponse, BankState] = Operation(
    multiCheckName,
    (_, _) => expect(multiCheck).sameState,
    (_, _) => Gen.constant(WithdrawResponse.Success(BigDecimal(20)))
  )

  val collapseState: BankState = BankState(Map("x" -> BigDecimal(1)))

  val collapseOp: Operation[WithdrawRequest, WithdrawResponse, BankState] = Operation(
    collapseName,
    (_, _) =>
      expect.oneOf(
        NonEmptyList.of[Outcome[WithdrawResponse, BankState]](
          expect(anyWithdrawPass).thenState((_, _) => collapseState),
          expect(anyWithdrawPass).thenState((_, _) => collapseState)
        )
      ),
    (_, _) => Gen.constant(WithdrawResponse.Success(BigDecimal(0)))
  )

  val fullSpec: Spec[BankState] =
    Spec
      .empty[BankState]
      .register(withdraw)
      .flatMap(_.register(deposit))
      .flatMap(_.register(getAccount))
      .flatMap(_.register(createAccount))
      .flatMap(_.register(noopGet))
      .flatMap(_.register(multiCheckOp))
      .flatMap(_.register(collapseOp))
      .getOrElse(Spec.empty[BankState])

  val emptySpec: Spec[BankState] = Spec.empty[BankState]

  // ── Generators (Hedgehog — constructive, explicit Range, no Arbitrary) ────

  val genBalance: Gen[BigDecimal] = Gen.int(Range.linear(0, 1000)).map(i => BigDecimal(i))
  val genAccountId: Gen[String]   = Gen.string(Gen.alpha, Range.linear(1, 8))

  val genBankState: Gen[BankState] =
    genAccountId
      .flatMap(id => genBalance.map(b => (id, b)))
      .list(Range.linear(0, 4))
      .map(pairs => BankState(pairs.toMap))

  val genStateProfile: Gen[StateProfile[BankState]] =
    genBankState
      .list(Range.linear(1, 4))
      .map(xs => StateProfile.of(NonEmptyList.fromListUnsafe(xs)))

  val genWithdrawRequest: Gen[WithdrawRequest] =
    for
      acct <- Gen.element1("alice", "bob", "carol", "unknown")
      amt  <- genBalance
    yield WithdrawRequest(acct, amt)

  val genWithdrawResponse: Gen[WithdrawResponse] =
    Gen.frequency1(
      3 -> genBalance.map(b => WithdrawResponse.Success(b): WithdrawResponse),
      1 -> Gen.constant[WithdrawResponse](WithdrawResponse.NotFound),
      1 -> Gen.constant[WithdrawResponse](WithdrawResponse.BadRequest),
      1 -> Gen.constant[WithdrawResponse](WithdrawResponse.Timeout)
    )

  val genGetAccountRequest: Gen[GetAccountRequest] =
    Gen.element1("alice", "bob", "carol", "unknown").map(id => GetAccountRequest(id))

  val stateA: BankState = BankState(Map("alice" -> BigDecimal(0)))
  val stateB: BankState = BankState(Map("bob" -> BigDecimal(100)))
  val stateC: BankState = BankState(Map.empty)

  val genNelBankState: Gen[NonEmptyList[BankState]] =
    Gen
      .element1(stateA, stateB, stateC)
      .list(Range.linear(1, 12))
      .map(xs => NonEmptyList.fromListUnsafe(xs))

  // ═══════════════════════════════════════════════════════════════════════
  //  Scenario tests (one per spec Scenario heading)
  // ═══════════════════════════════════════════════════════════════════════

  // spec: oracle-core — Scenario: Happy path — registered operation dispatches
  property("registration — registered operation dispatches") {
    for _ <- Gen.constant(()).forAll
    yield
      val profile = StateProfile.one(BankState(Map("alice" -> BigDecimal(50))))
      fullSpec.allows(
        withdraw,
        WithdrawRequest("alice", BigDecimal(30)),
        WithdrawResponse.Success(BigDecimal(20)),
        profile
      ) match
        case Verdict.Conformant(_) => Result.success
        case Verdict.Deviant(_)    => Result.failure
  }

  // spec: oracle-core — Scenario: Error path — unknown operation
  property("registration — unknown operation") {
    for _ <- Gen.constant(()).forAll
    yield Result.assert(
      emptySpec.allows(
        withdraw,
        WithdrawRequest("alice", BigDecimal(30)),
        WithdrawResponse.Timeout,
        StateProfile.one(BankState(Map.empty))
      ) == Verdict.Deviant(NonEmptyList.one(SpecViolation.UnknownOperation(withdrawName)))
    )
  }

  // spec: oracle-core — Scenario: Edge case — duplicate registration
  // (register is implemented; this scenario PASSES pre-implementation.)
  property("registration — duplicate rejected") {
    for _ <- Gen.constant(()).forAll
    yield
      val dup: Operation[WithdrawRequest, WithdrawResponse, BankState] =
        Operation(
          withdrawName,
          (_, _) => expect(withdrawCheck).sameState,
          (_, _) => Gen.constant(WithdrawResponse.Timeout)
        )
      val result = Spec.empty[BankState].register(withdraw).flatMap(_.register(dup))
      Result.assert(result.swap.toOption.contains(withdrawName))
  }

  // spec: oracle-core — Scenario: Happy path — Next transition applies response-dependent state
  property("evaluation — Next applies response value") {
    for _ <- Gen.constant(()).forAll
    yield
      val profile = StateProfile.one(BankState(Map("alice" -> BigDecimal(50))))
      fullSpec.allows(
        deposit,
        DepositRequest("alice", BigDecimal(30)),
        DepositResponse(BigDecimal(80)),
        profile
      ) match
        case Verdict.Conformant(p) =>
          Result.assert(p.toList.exists(_.accounts.get("alice").contains(BigDecimal(80))))
        case Verdict.Deviant(_) => Result.failure
  }

  // spec: oracle-core — Scenario: Error path — multiple check failures accumulate
  property("evaluation — accumulation") {
    for _ <- Gen.constant(()).forAll
    yield fullSpec.allows(
      multiCheckOp,
      multiCheckReq,
      WithdrawResponse.NotFound,
      StateProfile.one(BankState(Map.empty))
    ) match
      case Verdict.Deviant(vs)   => Result.assert(vs.length == 2)
      case Verdict.Conformant(_) => Result.failure
  }

  // spec: oracle-core — Scenario: Edge case — Same outcome leaves profile untouched
  property("evaluation — Same leaves profile untouched") {
    for _ <- Gen.constant(()).forAll
    yield
      val profile = StateProfile.one(BankState(Map("alice" -> BigDecimal(7))))
      Result.assert(
        fullSpec.allows(noopGet, GetAccountRequest("alice"), anyRes, profile) == Verdict.Conformant(
          profile
        )
      )
  }

  // spec: oracle-core — Scenario: Happy path — timeout forks the profile
  property("branching — timeout forks profile") {
    for _ <- Gen.constant(()).forAll
    yield
      val profile = StateProfile.one(BankState(Map.empty))
      fullSpec.allows(
        createAccount,
        CreateAccountRequest("alice"),
        CreateAccountResponse.Timeout,
        profile
      ) match
        case Verdict.Conformant(p) => Result.assert(p.toList.size == 2)
        case Verdict.Deviant(_)    => Result.failure
  }

  // spec: oracle-core — Scenario: Happy path — later observation collapses the profile
  property("branching — observation collapses") {
    for _ <- Gen.constant(()).forAll
    yield
      val twoState = StateProfile.of(
        NonEmptyList.of(BankState(Map.empty), BankState(Map("alice" -> BigDecimal(0))))
      )
      fullSpec.allows(
        getAccount,
        GetAccountRequest("alice"),
        GetAccountResponse.Found(BigDecimal(0)),
        twoState
      ) match
        case Verdict.Conformant(p) =>
          Result.assert(p.toList.size == 1 && p.toList.exists(_.accounts.contains("alice")))
        case Verdict.Deviant(_) => Result.failure
  }

  // spec: oracle-core — Scenario: Error path — profile exhausted
  property("branching — profile exhausted") {
    for _ <- Gen.constant(()).forAll
    yield
      val twoState = StateProfile.of(
        NonEmptyList.of(BankState(Map.empty), BankState(Map("alice" -> BigDecimal(0))))
      )
      fullSpec.allows(
        getAccount,
        GetAccountRequest("alice"),
        GetAccountResponse.Found(BigDecimal(999)),
        twoState
      ) match
        case Verdict.Deviant(_)    => Result.success
        case Verdict.Conformant(_) => Result.failure
  }

  // spec: oracle-core — Scenario: Edge case — duplicate next-states collapse
  property("profile — duplicate next-states collapse") {
    for _ <- Gen.constant(()).forAll
    yield fullSpec.allows(
      collapseOp,
      WithdrawRequest("x", BigDecimal(1)),
      WithdrawResponse.Success(BigDecimal(0)),
      StateProfile.one(BankState(Map.empty))
    ) match
      case Verdict.Conformant(p) => Result.assert(p.toList.size == 1)
      case Verdict.Deviant(_)    => Result.failure
  }

  // ═══════════════════════════════════════════════════════════════════════
  //  Properties (Ring 3 — one per declared invariant)
  // ═══════════════════════════════════════════════════════════════════════

  // spec: oracle-core — Property: Conformant ⇔ some branch matches some candidate
  property("Conformant iff some branch matches some candidate") {
    for
      profile <- genStateProfile.forAll
      req     <- genWithdrawRequest.forAll
      res     <- genWithdrawResponse.forAll
    yield
      val expected = profile.toList
        .flatMap(s => matchingBranches(withdraw.behaviour(req, s), res, s).map(_.next(res, s)))
        .distinctByEq
      fullSpec.allows(withdraw, req, res, profile) match
        case Verdict.Conformant(p) =>
          Result.assert(expected.nonEmpty && p.toList.sameElementsByEq(expected))
        case Verdict.Deviant(_) => Result.assert(expected.isEmpty)
  }

  // spec: oracle-core — Property: Same preserves the profile
  property("Same preserves the profile") {
    for
      profile <- genStateProfile.forAll
      req     <- genGetAccountRequest.forAll
    yield Result.assert(
      fullSpec.allows(noopGet, req, anyRes, profile) == Verdict.Conformant(profile)
    )
  }

  // spec: oracle-core — Property: Deviant accumulates every failed check
  property("Deviant accumulates every failed check") {
    for
      profile <- genStateProfile.forAll
      res     <- genWithdrawResponse.forAll
    yield fullSpec.allows(multiCheckOp, multiCheckReq, res, profile) match
      case Verdict.Deviant(vs) =>
        Result.assert(vs.length == countFailedChecks(multiCheckOp, multiCheckReq, res, profile))
      case Verdict.Conformant(_) => Result.success
  }

  // spec: oracle-core — Property: Profile dedup is idempotent and order-insensitive
  property("Profile dedup is idempotent and order-insensitive") {
    for states <- genNelBankState.forAll
    yield Result.assert(
      StateProfile.of(states).toList.sameElementsByEq(StateProfile.of(states.reverse).toList) &&
        StateProfile.of(states ::: states).toList.sameElementsByEq(StateProfile.of(states).toList)
    )
  }

  // ═══════════════════════════════════════════════════════════════════════
  //  Compile-Negative Obligations (promoted from the typed contract's
  //  `typeCheckErrors` vals — these PASS pre-implementation).
  // ═══════════════════════════════════════════════════════════════════════

  // spec: oracle-core — CN: blank OperationName/CallLabel literals do not compile
  property("CN — blank Iron literals rejected at compile time") {
    for _ <- Gen.constant(()).forAll
    yield Result.assert(
      OracleCoreTypeContract.cnBlankOperationName.nonEmpty &&
        OracleCoreTypeContract.cnBlankCallLabel.nonEmpty
    )
  }

  // spec: oracle-core — CN: Req/Res mismatch on the typed handle does not compile
  property("CN — Req mismatch on allows is a type error") {
    for _ <- Gen.constant(()).forAll
    yield Result.assert(OracleCoreTypeContract.cnReqResMismatch.nonEmpty)
  }

  // spec: oracle-core — CN: no StateProfile constructor from a possibly-empty collection
  property("CN — StateProfile.of(List.empty) does not compile") {
    for _ <- Gen.constant(()).forAll
    yield Result.assert(OracleCoreTypeContract.cnEmptyStateProfile.nonEmpty)
  }
