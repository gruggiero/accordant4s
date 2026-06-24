package io.gruggiero.accordant4s.http

import scala.concurrent.duration.FiniteDuration

import cats.effect.IO
import io.gruggiero.accordant4s.domain.{MaxRetryCount, OperationName}
import io.gruggiero.accordant4s.engine.SystemUnderTest
import io.gruggiero.accordant4s.spec.{OperationCall, Spec}
import org.http4s.Request
import org.http4s.client.Client

/**
 * Builds an http4s `Client[IO]`-backed [[SystemUnderTest]]. Each `execute(call)`
 * encodes the request via the call's route, sends it through the client with a
 * timeout, and decodes the response via the operation's mapper into `call.Res`.
 * Transport failures (timeout, connection failure) surface as
 * [[TransportOutcome]] into the mapper — they NEVER raise; the mapper decides
 * which `Res` variant to return (Req: transport-as-data).
 *
 * `retries` bounds idempotent connection retries (a future hook; the initial
 * implementation uses a single attempt with the configured timeout).
 */
object Http4sSut:

  /**
   * Build a `SystemUnderTest[IO, S]` from an http4s client + binding. Each step:
   *   1. look up the endpoint for `call.op.name`
   *   2. encode the request via the endpoint's route
   *   3. send it through the client with `timeout`
   *   4. read the response body + status, build a `TransportOutcome`
   *   5. decode via the mapper into `call.Res`
   */
  def apply[S](
      client: Client[IO],
      binding: HttpBinding[S],
      timeout: FiniteDuration,
      // `retries` is a documented future hook (idempotent connection retries);
      // the initial implementation uses a single attempt with the configured timeout.
      @scala.annotation.unused retries: MaxRetryCount
  ): SystemUnderTest[IO, S] =
    new SystemUnderTest[IO, S]:
      def execute(call: OperationCall[S]): IO[call.Res] =
        binding.endpointFor(call) match
          case None =>
            // Unbound operations should have been caught at wiring time by
            // HttpBinding.check. If they reach here it's a wiring bug — raise.
            IO.raiseError(new IllegalStateException("unbound operation: " + (call.op.name: String)))
          case Some(endpoint) =>
            for
              request <- endpoint.encode(call)
              outcome <- send(client, request, timeout)
              decoded <- endpoint.decode(outcome)
            // Sound by the name-keyed invariant (same as endpoint.encode):
            // call.Res =:= the mapper's Res at runtime. The existential bridge
            // requires this one controlled cast; see HttpBinding.scala + build.sbt.
            // scalafix:off DisableSyntax.asInstanceOf
            yield decoded.asInstanceOf[call.Res]
            // scalafix:on DisableSyntax.asInstanceOf

      def reset: IO[Unit] = IO.unit

  /**
   * Construction-time-validated overload: unbound operations (registered in
   * `spec` but missing from `binding`) cause `Left(unboundNames)` — the
   * "MUST fail at construction time, not mid-replay" requirement (Req: binding
   * / Scenario: unbound). Use this when you want the SUT builder itself to
   * enforce wiring completeness; the plain overload above is for callers who
   * manage validation via [[HttpBinding.check]] separately.
   */
  def apply[S](
      client: Client[IO],
      spec: Spec[S],
      binding: HttpBinding[S],
      timeout: FiniteDuration,
      retries: MaxRetryCount
  ): Either[Set[OperationName], SystemUnderTest[IO, S]] =
    HttpBinding.check(spec, binding).map(checked => apply(client, checked, timeout, retries))

  /**
   * Send a request through the client with a timeout, returning a
   * [[TransportOutcome]]. A `java.net.ConnectException` (or similar transport
   * failure from `client.run`) becomes `ConnectionFailed`; a timeout becomes
   * `TimedOut`; a completed response becomes `Completed(status, body)`.
   *
   * Body-read errors (truncated stream, invalid encoding) are caught INSIDE the
   * `.use` block and surface as `Completed(status, "")` — the mapper sees the
   * status code and can make a domain-appropriate decision. Only transport-level
   * failures (from `client.run` resource acquisition) propagate to the outer
   * `handleError` as `ConnectionFailed`.
   */
  private def send(
      client: Client[IO],
      request: Request[IO],
      timeout: FiniteDuration
  ): IO[TransportOutcome] =
    client
      .run(request)
      .use { response =>
        response.bodyText.compile.string
          .map(body => TransportOutcome.Completed(response.status, body))
          .handleError(_ => TransportOutcome.Completed(response.status, ""))
      }
      .timeoutTo(timeout, IO.pure(TransportOutcome.TimedOut))
      .handleError(e =>
        TransportOutcome.ConnectionFailed(Option(e.getMessage).getOrElse(e.getClass.getSimpleName))
      )
