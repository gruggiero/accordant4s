package io.gruggiero.accordant4s.httpbinding

// ═══════════════════════════════════════════════════════════════════════════
//  Test Oracle for spec: http-binding.
//
//  Covers the spec's 5 scenarios (binding happy/unbound, transport timeout/500
//  deviation, end-to-end conformant) and 3 Ring-3 properties (codec roundtrip,
//  mapper totality, transparency) plus the compile-negative obligation
//  (`MaxRetryCount(0)` must not compile).
//
//  Types come from the http4s module's MAIN sources (`io.gruggiero.accordant4s.http`)
//  and `domain.MaxRetryCount`, promoted at Step 3. The compile-negative obligation
//  evidence stays in `HttpBindingTypeContract.cnZeroRetry`.
//
//  Framework: Hedgehog `HedgehogSuite`. Effects run on `cats.effect.IO`.
// ═══════════════════════════════════════════════════════════════════════════

import scala.compiletime.testing.{Error => TypeCheckError, typeCheckErrors}
import scala.concurrent.duration._

import cats.effect.IO
import hedgehog._
import hedgehog.munit.HedgehogSuite
import io.gruggiero.accordant4s.domain.CallLabel
import io.gruggiero.accordant4s.engine.{ExecutionHooks, ExecutionReport, TestCaseExecutor}
import io.gruggiero.accordant4s.fixtures.BankState
import io.gruggiero.accordant4s.fixtures.GraphFixtures._
import io.gruggiero.accordant4s.fixtures.HttpFixtures._
import io.gruggiero.accordant4s.fixtures.HttpFixtures.given
import io.gruggiero.accordant4s.fixtures.PersistenceFixtures.genTestCase
import io.gruggiero.accordant4s.http.{Http4sSut, HttpBinding}
import io.gruggiero.accordant4s.spec.{TestCase, withInput}
import io.gruggiero.accordant4s.typecontract.HttpBindingTypeContract.cnZeroRetry
import org.http4s.client.Client
import org.http4s.{HttpApp, Response, Status}

final class HttpBindingProperties extends HedgehogSuite:

  given cats.effect.unsafe.IORuntime = cats.effect.unsafe.implicits.global

  // ── A conformant test case exercising Withdraw over a pre-funded account ──

  /** The initial state for HTTP test cases: alice funded with 200. */
  private val funded: BankState = BankState(Map("alice" -> BigDecimal(200)))

  private val conformantCase: TestCase[BankState] =
    TestCase(
      CallLabel.applyUnsafe("http-conformant"),
      funded,
      List(
        call(withdraw, WithdrawRequest("alice", BigDecimal(50)), "w")
      )
    )

  // ═══════════════════════════════════════════════════════════════════════════
  //  Requirement: binding — requests encoded per route, responses decoded per mapper
  // ═══════════════════════════════════════════════════════════════════════════

  // Scenario: Happy path — a bound operation's route encodes the request, the
  // mapper decodes the response, execute returns the typed Res.
  property("binding — happy path: a bound operation executes and returns a typed Res") {
    for req <- genWithdrawRequest.forAll
    yield
      val client = stubClient(Status.Ok, """{"Success":{"newBalance":42}}""")
      val sut    = Http4sSut(client, bankBinding, testTimeout, testRetries)
      val res =
        sut.execute(withdraw.withInput(req, CallLabel.applyUnsafe("t"))).attempt.unsafeRunSync()
      Result.assert(res == Right(WithdrawResponse.Success(BigDecimal(42))))
  }

  // Scenario: Unbound operation — an operation without a route fails at wiring
  // time, not mid-replay. HttpBinding.check returns Left(unboundNames).
  property("binding — unbound operation: check rejects it as Left at wiring time") {
    for _ <- Gen.constant(()).forAll
    yield
      // empty binding has no routes → every operation in bankSpec is unbound
      val emptyBinding = HttpBinding.empty[BankState]
      val result       = HttpBinding.check(bankSpec, emptyBinding)
      Result.assert(result.isLeft)
  }

  // ═══════════════════════════════════════════════════════════════════════════
  //  Requirement: transport-as-data — timeouts/connection failures surface as
  //  response data, never raise; unexpected status → spec deviation, not a crash
  // ═══════════════════════════════════════════════════════════════════════════

  // Property: Mapper totality over statuses — for every HTTP status and body,
  // the response mapper produces a Res value; execute never raises for mapped
  // operations.
  property("mapper totality over statuses") {
    for
      status <- genStatus.forAll
      body   <- genBody.forAll
    yield
      val client = stubClient(status, body)
      val sut    = Http4sSut(client, bankBinding, testTimeout, testRetries)
      val call0 =
        withdraw.withInput(WithdrawRequest("alice", BigDecimal(10)), CallLabel.applyUnsafe("t"))
      val res = sut.execute(call0).attempt.unsafeRunSync()
      Result.assert(res.isRight)
  }

  // Scenario: 500 deviation — an unexpected status produces a spec deviation
  // (a DeviatesAt report), not a crash. The mapper maps 500 to `ServerError`,
  // which is NOT in the bank spec's `withdraw` behaviour → the oracle rejects
  // it as a spec deviation.
  property("transport — 500 maps to a response that deviates under the oracle") {
    for _ <- Gen.constant(()).forAll
    yield
      val client = stubClient(Status.InternalServerError, "server error")
      val sut    = Http4sSut(client, bankBinding, testTimeout, testRetries)
      val report = TestCaseExecutor
        .run(bankSpec, conformantCase, sut, ExecutionHooks.noop[cats.effect.IO])
        .unsafeRunSync()
      // 500 → ServerError (not in the behaviour's OneOf) → DeviatesAt
      Result.assert(report match
        case ExecutionReport.Passed(_)           => false
        case ExecutionReport.DeviatesAt(_, _, _) => true)
  }

  // Scenario: Timeout becomes a response — a stub client that never responds
  // within the configured timeout surfaces as `TransportOutcome.TimedOut`, which
  // the mapper maps to `WithdrawResponse.Timeout` (a valid outcome the oracle
  // models with a SameState branch). Execute never raises (Req: transport-as-data
  // / Scenario: timeout).
  property("transport — timeout surfaces as WithdrawResponse.Timeout, never raises") {
    for _ <- Gen.constant(()).forAll
    yield
      val slowClient = Client.fromHttpApp(
        HttpApp[IO](_ => IO.sleep(10.seconds) *> IO.pure(Response[IO](Status.Ok)))
      )
      val shortTimeout: FiniteDuration = 1.millisecond
      val sut = Http4sSut(slowClient, bankBinding, shortTimeout, testRetries)
      val call0 =
        withdraw.withInput(WithdrawRequest("alice", BigDecimal(10)), CallLabel.applyUnsafe("t"))
      val res = sut.execute(call0).attempt.unsafeRunSync()
      Result.assert(res == Right(WithdrawResponse.Timeout))
  }

  // Scenario: Construction-time validation — the Http4sSut.apply overload that
  // takes a Spec[S] enforces the "MUST fail at construction time" requirement
  // (Req: binding / Scenario: unbound) by returning Left(unboundNames).
  property("binding — Http4sSut.apply overload rejects unbound ops at construction time") {
    for _ <- Gen.constant(()).forAll
    yield
      val emptyBinding = HttpBinding.empty[BankState]
      val result =
        Http4sSut(stubClient(Status.Ok, ""), bankSpec, emptyBinding, testTimeout, testRetries)
      Result.assert(result.isLeft)
  }

  // Scenario: Broken server detected — a server with the insufficient-funds
  // check removed (withdraw always returns Success(-1)) yields at least one
  // `DeviatesAt` on a Withdraw step (Req: end-to-end / Scenario: broken server).
  property("end-to-end — broken server yields DeviatesAt on a Withdraw step") {
    for tc <- genTestCase
        .filter(_.steps.exists(s => (s.op.name: String) == (withdrawName: String)))
        .forAll
    yield
      val seed = 0L
      val program =
        for
          client <- brokenClient(tc.initial, seed)
          httpSut = Http4sSut(client, bankBinding, testTimeout, testRetries)
          report <- TestCaseExecutor.run(
            bankSpec,
            tc,
            httpSut,
            ExecutionHooks.noop[cats.effect.IO]
          )
        yield report
      val report = program.unsafeRunSync()
      Result.assert(report match
        case ExecutionReport.DeviatesAt(stepIndex, _, _) =>
          tc.steps.isDefinedAt(stepIndex) &&
          (tc.steps(stepIndex).op.name: String) == (withdrawName: String)
        case _ => false)
  }

  // ═══════════════════════════════════════════════════════════════════════════
  //  Requirement: end-to-end — HTTP SUT verdict-equivalent to direct SUT
  // ═══════════════════════════════════════════════════════════════════════════

  // Property: Transparency — HTTP SUT equals direct SUT. For all generated test
  // cases, executing through Http4sSut over an in-process conformant server
  // yields the same ExecutionReport as executing through the in-memory RefSut.
  // Exercises the full operation set (Withdraw, Deposit, Create, Fork) via
  // genTestCase, not a single hardcoded case.
  property("transparency — HTTP SUT equals direct SUT") {
    for tc <- genTestCase.forAll
    yield
      val seed = 0L
      val program =
        for
          client <- conformantClient(tc.initial, seed)
          refSut <- io.gruggiero.accordant4s.fixtures.ExecutionFixtures.RefSut(tc.initial, seed)
          httpSut = Http4sSut(client, bankBinding, testTimeout, testRetries)
          httpRep <- TestCaseExecutor.run(
            bankSpec,
            tc,
            httpSut,
            ExecutionHooks.noop[cats.effect.IO]
          )
          refRep <- TestCaseExecutor.run(
            bankSpec,
            tc,
            refSut,
            ExecutionHooks.noop[cats.effect.IO]
          )
        yield httpRep == refRep
      Result.assert(program.unsafeRunSync())
  }

  // ═══════════════════════════════════════════════════════════════════════════
  //  Property: Request codec roundtrip (Ring 4 — wire round-trip law)
  // ═══════════════════════════════════════════════════════════════════════════

  property("request codec roundtrip") {
    for req <- genWithdrawRequest.forAll
    yield
      // The route encodes the request into an HTTP Request[IO] (method + uri +
      // JSON entity via the http4s EntityEncoder); the http4s EntityDecoder
      // recovers it exactly from the entity body. This tests the FULL wire
      // round-trip (HttpRoute.encode → entity decode), not just circe.
      val request = withdrawRoute.encode(req)
      val decoded = request.as[WithdrawRequest].attempt.unsafeRunSync()
      Result.assert(decoded == Right(req))
  }

  // ═══════════════════════════════════════════════════════════════════════════
  //  Compile-Negative Obligation: MaxRetryCount(0) must not compile (Iron Positive)
  // ═══════════════════════════════════════════════════════════════════════════

  property("CN: MaxRetryCount(0) does not compile") {
    for _ <- Gen.constant(()).forAll
    yield
      val errors: List[TypeCheckError] = cnZeroRetry
      Result.assert(errors.nonEmpty)
  }
