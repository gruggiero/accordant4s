package io.gruggiero.accordant4s.fixtures

// ═══════════════════════════════════════════════════════════════════════════
//  HTTP fixtures for spec: http-binding (Step 2 test oracle).
//
//  Provides routes/mappers/codecs for ALL bank operations (Withdraw, Deposit,
//  Create, Fork), a stub `Client[IO]`, and an in-process conformant `HttpApp`
//  (the reference server the transparency property replays through — no
//  network, fully deterministic).
//
//  Reuses core's bank fixtures (withdraw/WithdrawRequest/WithdrawResponse,
//  bankSpec, RefSut) via the `test->test` dependency.
//
//  The route/mapper/binding live in TEST sources; at Step 3 the promoted
//  `HttpRoute`/`HttpResponseMapper`/`HttpBinding`/`Http4sSut`/`TransportOutcome`
//  types come from the http4s module's MAIN sources (`io.gruggiero.accordant4s.http`).
// ═══════════════════════════════════════════════════════════════════════════

import scala.concurrent.duration._
import scala.util.chaining.scalaUtilChainingOps

import cats.effect.IO
import hedgehog.{Gen, Range}
import io.circe.generic.semiauto._
import io.circe.syntax._
import io.circe.{Decoder => CirceDecoder, Encoder => CirceEncoder, HCursor, Json}
import io.gruggiero.accordant4s.domain.{CallLabel, MaxRetryCount}
import io.gruggiero.accordant4s.fixtures.ExecutionFixtures.RefSut
import io.gruggiero.accordant4s.fixtures.GraphFixtures._
import io.gruggiero.accordant4s.fixtures.InputFixtures.{DepositRequest, DepositResponse, deposit}
import io.gruggiero.accordant4s.http.{HttpBinding, HttpResponseMapper, HttpRoute, TransportOutcome}
import io.gruggiero.accordant4s.spec.withInput
import org.http4s.circe._
import org.http4s.client.Client
import org.http4s.headers.`Content-Type`
import org.http4s.{EntityDecoder, HttpApp, MediaType, Response, Status, Uri}

object HttpFixtures:

  // ── circe codecs for request/response types ───────────────────────────────

  given CirceEncoder[WithdrawRequest] = deriveEncoder
  given CirceDecoder[WithdrawRequest] = deriveDecoder

  given CirceEncoder[DepositRequest]  = deriveEncoder
  given CirceDecoder[DepositRequest]  = deriveDecoder
  given CirceEncoder[DepositResponse] = deriveEncoder
  given CirceDecoder[DepositResponse] = deriveDecoder

  given CirceEncoder[CreateRequest] = deriveEncoder
  given CirceDecoder[CreateRequest] = deriveDecoder

  given CirceEncoder[ForkRequest] = deriveEncoder
  given CirceDecoder[ForkRequest] = deriveDecoder

  // WithdrawResponse is a Scala 3 enum (ADT); encode as a tagged object so the
  // mapper can decode it back. Manual codecs (semiauto deriveEncoder doesn't
  // handle enum cases directly).
  given CirceEncoder[WithdrawResponse] =
    case s: WithdrawResponse.Success =>
      Json.obj("Success" := Json.obj("newBalance" := s.newBalance))
    case WithdrawResponse.NotFound    => Json.obj("NotFound" := Json.Null)
    case WithdrawResponse.Timeout     => Json.obj("Timeout" := Json.Null)
    case WithdrawResponse.ServerError => Json.obj("ServerError" := Json.Null)

  given CirceDecoder[WithdrawResponse] = (c: HCursor) =>
    c.keys.flatMap(_.headOption) match
      case Some("Success") =>
        c.downField("Success")
          .downField("newBalance")
          .as[BigDecimal]
          .map(WithdrawResponse.Success(_))
      case Some("NotFound")    => Right(WithdrawResponse.NotFound)
      case Some("Timeout")     => Right(WithdrawResponse.Timeout)
      case Some("ServerError") => Right(WithdrawResponse.ServerError)
      case _                   => Left(io.circe.DecodingFailure("WithdrawResponse", c.history))

  // CreateResponse / ForkResponse are single-case enums — tagged-object codecs.
  given CirceEncoder[CreateResponse] =
    case CreateResponse.Created => Json.obj("Created" := Json.Null)

  given CirceDecoder[CreateResponse] = (c: HCursor) =>
    c.keys.flatMap(_.headOption) match
      case Some("Created") => Right(CreateResponse.Created)
      case _               => Left(io.circe.DecodingFailure("CreateResponse", c.history))

  given CirceEncoder[ForkResponse] =
    case ForkResponse.Ok => Json.obj("Ok" := Json.Null)

  given CirceDecoder[ForkResponse] = (c: HCursor) =>
    c.keys.flatMap(_.headOption) match
      case Some("Ok") => Right(ForkResponse.Ok)
      case _          => Left(io.circe.DecodingFailure("ForkResponse", c.history))

  // ── Entity encoders/decoders for http4s JSON ───────────────────────────────

  given org.http4s.EntityEncoder[IO, WithdrawRequest] = jsonEncoderOf[IO, WithdrawRequest]
  given org.http4s.EntityEncoder[IO, DepositRequest]  = jsonEncoderOf[IO, DepositRequest]
  given org.http4s.EntityEncoder[IO, CreateRequest]   = jsonEncoderOf[IO, CreateRequest]
  given org.http4s.EntityEncoder[IO, ForkRequest]     = jsonEncoderOf[IO, ForkRequest]

  // EntityDecoder for the codec-roundtrip property (decode the HTTP entity back).
  given EntityDecoder[IO, WithdrawRequest] = jsonOf[IO, WithdrawRequest]

  // ── Routes: one per operation (POST /<op>/<encoded-id>) ────────────────────

  private def encodedId(id: String): String =
    java.net.URLEncoder.encode(id, "UTF-8")

  val withdrawRoute: HttpRoute[WithdrawRequest] =
    HttpRoute.jsonPost[WithdrawRequest](req =>
      Uri.unsafeFromString("/withdraw/" + encodedId(req.id))
    )

  val depositRoute: HttpRoute[DepositRequest] =
    HttpRoute.jsonPost[DepositRequest](req =>
      Uri.unsafeFromString("/deposit/" + encodedId(req.accountId))
    )

  val createRoute: HttpRoute[CreateRequest] =
    HttpRoute.jsonPost[CreateRequest](req => Uri.unsafeFromString("/create/" + encodedId(req.id)))

  val forkRoute: HttpRoute[ForkRequest] =
    HttpRoute.jsonPost[ForkRequest](req => Uri.unsafeFromString("/fork/" + encodedId(req.id)))

  // ── Mappers: TransportOutcome → Res (TOTAL) ────────────────────────────────

  /**
   * Maps `TransportOutcome` to `WithdrawResponse`. 200 with a decodable body →
   * `Success`; 404 → `NotFound`; `TimedOut` → `Timeout`; connection failure,
   * 5xx, or undecodable body → `ServerError`. The mapper is TOTAL — it produces
   * a `Res` for every outcome, never raises. `Timeout` is a valid outcome the
   * oracle models (the bank spec's `withdraw` behaviour includes it as a
   * `SameState` branch); `ServerError` is NOT in the behaviour, so a 500
   * deviates at the oracle (Req: transport-as-data / Scenario: 500 deviation).
   */
  val withdrawMapper: HttpResponseMapper[WithdrawResponse] =
    new HttpResponseMapper[WithdrawResponse]:
      def map(outcome: TransportOutcome): IO[WithdrawResponse] =
        outcome match
          case TransportOutcome.Completed(status, body) if status.code == Status.Ok.code =>
            IO.fromEither(
              io.circe.parser
                .decode[WithdrawResponse](body)
                .left
                .map(_ => new Exception("decode error"))
            ).handleError(_ => WithdrawResponse.ServerError)
          case TransportOutcome.Completed(status, _) if status.code == Status.NotFound.code =>
            IO.pure(WithdrawResponse.NotFound)
          case TransportOutcome.TimedOut            => IO.pure(WithdrawResponse.Timeout)
          case TransportOutcome.ConnectionFailed(_) => IO.pure(WithdrawResponse.ServerError)
          case TransportOutcome.Completed(status, _) if status.code == Status.RequestTimeout.code =>
            IO.pure(WithdrawResponse.Timeout)
          case TransportOutcome.Completed(status, _) if status.code >= 500 =>
            IO.pure(WithdrawResponse.ServerError)
          case TransportOutcome.Completed(_, _) => IO.pure(WithdrawResponse.ServerError)

  /** Total mapper for Deposit: 200 → decode body, else → `DepositResponse(-1)`. */
  val depositMapper: HttpResponseMapper[DepositResponse] =
    new HttpResponseMapper[DepositResponse]:
      def map(outcome: TransportOutcome): IO[DepositResponse] =
        outcome match
          case TransportOutcome.Completed(status, body) if status.code == Status.Ok.code =>
            IO.fromEither(
              io.circe.parser
                .decode[DepositResponse](body)
                .left
                .map(_ => new Exception("decode error"))
            ).handleError(_ => DepositResponse(BigDecimal(-1)))
          case _ => IO.pure(DepositResponse(BigDecimal(-1)))

  /** Total mapper for Create: always `Created` (single-case ADT). */
  val createMapper: HttpResponseMapper[CreateResponse] =
    new HttpResponseMapper[CreateResponse]:
      def map(outcome: TransportOutcome): IO[CreateResponse] =
        IO.pure(CreateResponse.Created)

  /** Total mapper for Fork: always `Ok` (single-case ADT). */
  val forkMapper: HttpResponseMapper[ForkResponse] =
    new HttpResponseMapper[ForkResponse]:
      def map(outcome: TransportOutcome): IO[ForkResponse] =
        IO.pure(ForkResponse.Ok)

  // ── The bank HTTP binding: all four operations registered ──────────────────

  val bankBinding: HttpBinding[BankState] =
    HttpBinding
      .empty[BankState]
      .pipe(HttpBinding.register(withdraw, withdrawRoute, withdrawMapper))
      .pipe(HttpBinding.register(deposit, depositRoute, depositMapper))
      .pipe(HttpBinding.register(create, createRoute, createMapper))
      .pipe(HttpBinding.register(fork, forkRoute, forkMapper))

  // ── Stub client: returns a canned (status, body) for every request ──────────

  /**
   * A `Client[IO]` that ignores the request and always returns the given
   * `(status, body)`. Used by the mapper-totality property to exercise every
   * status code without a server.
   */
  def stubClient(status: Status, body: String): Client[IO] =
    Client.fromHttpApp(
      HttpApp(_ =>
        IO.pure(
          Response[IO](status)
            .withEntity(body)
            .withContentType(`Content-Type`(MediaType.application.json))
        )
      )
    )

  // ── In-process conformant server (for the transparency property) ────────────

  /**
   * A `Client[IO]` backed by an in-process conformant server: it actually runs
   * the bank logic (the same `RefSut` state machine) and returns the real HTTP
   * response, so an HTTP round-trip yields the same `ExecutionReport` as the
   * in-memory `RefSut`. No network — `Client.fromHttpApp` wraps an `HttpApp`.
   *
   * Routes all four bank operations by path prefix; each decodes the request
   * body, runs it through `RefSut`, and encodes the response as JSON.
   */
  def conformantClient(initial: BankState, seed: Long): IO[Client[IO]] =
    for refSut <- RefSut(initial, seed)
    yield Client.fromHttpApp(
      HttpApp(req => route(req, refSut))
    )

  /** Route an HTTP request to the matching bank operation via path prefix. */
  private def route(req: org.http4s.Request[IO], refSut: RefSut[BankState]): IO[Response[IO]] =
    val path = req.uri.path.renderString
    if path.startsWith("/withdraw/") then routeWithdraw(req, refSut)
    else if path.startsWith("/deposit/") then routeDeposit(req, refSut)
    else if path.startsWith("/create/") then routeCreate(req, refSut)
    else if path.startsWith("/fork/") then routeFork(req, refSut)
    else IO.pure(Response[IO](Status.NotFound))

  private def routeWithdraw(
      req: org.http4s.Request[IO],
      refSut: RefSut[BankState]
  ): IO[Response[IO]] =
    for
      bodyStr <- req.as[String]
      wreq    <- IO.fromEither(io.circe.parser.decode[WithdrawRequest](bodyStr))
      res <- refSut.execute(
        withdraw.withInput(wreq, CallLabel.applyUnsafe("http-replay"))
      )
    yield res match
      case WithdrawResponse.Success(b) =>
        Response[IO](Status.Ok).withEntity(
          (WithdrawResponse.Success(b): WithdrawResponse).asJson.noSpaces
        )
      case WithdrawResponse.NotFound =>
        Response[IO](Status.NotFound).withEntity(
          (WithdrawResponse.NotFound: WithdrawResponse).asJson.noSpaces
        )
      case WithdrawResponse.Timeout =>
        Response[IO](Status.RequestTimeout).withEntity(
          (WithdrawResponse.Timeout: WithdrawResponse).asJson.noSpaces
        )
      case WithdrawResponse.ServerError =>
        Response[IO](Status.InternalServerError)
          .withEntity((WithdrawResponse.ServerError: WithdrawResponse).asJson.noSpaces)

  private def routeDeposit(
      req: org.http4s.Request[IO],
      refSut: RefSut[BankState]
  ): IO[Response[IO]] =
    for
      bodyStr <- req.as[String]
      dreq    <- IO.fromEither(io.circe.parser.decode[DepositRequest](bodyStr))
      res <- refSut.execute(
        deposit.withInput(dreq, CallLabel.applyUnsafe("http-replay"))
      )
    yield Response[IO](Status.Ok).withEntity((res: DepositResponse).asJson.noSpaces)

  private def routeCreate(
      req: org.http4s.Request[IO],
      refSut: RefSut[BankState]
  ): IO[Response[IO]] =
    for
      bodyStr <- req.as[String]
      creq    <- IO.fromEither(io.circe.parser.decode[CreateRequest](bodyStr))
      res <- refSut.execute(
        create.withInput(creq, CallLabel.applyUnsafe("http-replay"))
      )
    yield Response[IO](Status.Ok).withEntity((res: CreateResponse).asJson.noSpaces)

  private def routeFork(
      req: org.http4s.Request[IO],
      refSut: RefSut[BankState]
  ): IO[Response[IO]] =
    for
      bodyStr <- req.as[String]
      freq    <- IO.fromEither(io.circe.parser.decode[ForkRequest](bodyStr))
      res <- refSut.execute(
        fork.withInput(freq, CallLabel.applyUnsafe("http-replay"))
      )
    yield Response[IO](Status.Ok).withEntity((res: ForkResponse).asJson.noSpaces)

  // ── Broken server (for the "broken server detected" scenario) ───────────────

  /**
   * A `Client[IO]` backed by an in-process server with the insufficient-funds
   * check removed: withdraw always returns `Success(-1)` regardless of balance.
   * Non-withdraw operations delegate to the conformant route. The oracle rejects
   * `Success(-1)` (the success guard needs `b >= 0`) → `DeviatesAt` at the
   * withdraw step (Req: end-to-end / Scenario: broken server).
   */
  def brokenClient(initial: BankState, seed: Long): IO[Client[IO]] =
    for refSut <- RefSut(initial, seed)
    yield Client.fromHttpApp(
      HttpApp(req => brokenRoute(req, refSut))
    )

  /** Like `route` but withdraw always returns `Success(-1)`; other ops delegate. */
  private def brokenRoute(
      req: org.http4s.Request[IO],
      refSut: RefSut[BankState]
  ): IO[Response[IO]] =
    val path = req.uri.path.renderString
    if path.startsWith("/withdraw/") then routeBrokenWithdraw(req)
    else route(req, refSut)

  private def routeBrokenWithdraw(req: org.http4s.Request[IO]): IO[Response[IO]] =
    for _ <- req.as[String]
    yield Response[IO](Status.Ok).withEntity(
      (WithdrawResponse.Success(BigDecimal(-1)): WithdrawResponse).asJson.noSpaces
    )

  // ── Generators ──────────────────────────────────────────────────────────────

  /** Withdraw requests with boundary amounts and varied account ids. */
  val genWithdrawRequest: Gen[WithdrawRequest] =
    for
      id <- Gen.element1("alice", "bob", "carol", "ünicode-ïd")
      amt <- Gen.frequency1[BigDecimal](
        3 -> Gen.int(Range.linear(1, 1000000)).map(i => BigDecimal(i)),
        1 -> Gen.constant(BigDecimal(0)), // boundary: zero
        1 -> Gen.constant(BigDecimal(1))  // boundary: one
      )
    yield WithdrawRequest(id, amt)

  /** HTTP status codes in the full valid range [100, 599]. */
  val genStatus: Gen[Status] =
    Gen
      .int(Range.linear(100, 599))
      .map(code => Status.fromInt(code).fold(_ => Status.Ok, identity))

  /** Response bodies: well-formed JSON, empty, or malformed bytes. */
  val genBody: Gen[String] =
    Gen.choice1(
      Gen.constant("""{"Success":{"newBalance":42}}"""), // well-formed WithdrawResponse
      Gen.constant(""),                                  // empty
      Gen.constant("not json at all")                    // malformed
    )

  // ── Test harness: the HTTP SUT + a direct RefSut, sharing the same seed ─────

  /** A 5s timeout + 1 retry (deterministic; the stub server never fails). */
  val testTimeout: FiniteDuration = 5.seconds
  val testRetries: MaxRetryCount  = MaxRetryCount.applyUnsafe(1)
