package io.gruggiero.accordant4s.http

import cats.effect.IO
import io.gruggiero.accordant4s.domain.OperationName
import io.gruggiero.accordant4s.spec.{Operation, OperationCall, Spec}
import org.http4s.Request

/**
 * A registry of per-operation HTTP endpoints. Each [[Endpoint]] carries a typed
 * route + mapper captured at construction time (when `Req`/`Res` are concrete).
 * Looked up by `call.op.name`.
 *
 * The existential bridge: `Endpoint[S]` erases `Req`/`Res` for name-keyed
 * lookup, but the functions were captured from a concrete `Operation[Req, Res, S]`.
 * The one `asInstanceOf` in `Endpoint.encode` is sound by the name-keyed
 * invariant (the call was looked up by `op.name`, so `call.Req =:= Req`).
 * The `AsInstanceOf` wart is exempted for the http4s module for this reason.
 */
final case class HttpBinding[S](endpoints: Map[OperationName, Endpoint[S]]):

  /** Look up the endpoint for `call.op.name`; None ⇒ unbound (a wiring bug). */
  def endpointFor(call: OperationCall[S]): Option[Endpoint[S]] =
    endpoints.get(call.op.name)

object HttpBinding:

  /** Empty binding (no routes). */
  def empty[S]: HttpBinding[S] = HttpBinding(Map.empty)

  /**
   * Register one operation's route + mapper. The endpoint's typed functions
   * are captured here, so downstream `encode`/`decode` need no further cast.
   */
  def register[Req, Res, S](
      op: Operation[Req, Res, S],
      route: HttpRoute[Req],
      mapper: HttpResponseMapper[Res]
  ): HttpBinding[S] => HttpBinding[S] =
    binding =>
      binding.copy(endpoints = binding.endpoints.updated(op.name, Endpoint(op, route, mapper)))

  /**
   * TOTAL constructor-time validation against a `Spec[S]`: returns
   * `Left(unboundOperationNames)` if any registered operation lacks a route.
   * Unbound operations therefore fail at WIRING time, never mid-replay
   * (Req: binding / Scenario: unbound).
   */
  def check[S](spec: Spec[S], binding: HttpBinding[S]): Either[Set[OperationName], HttpBinding[S]] =
    val bound      = binding.endpoints.keySet
    val registered = spec.operations.keySet
    val unbound    = registered.diff(bound)
    if unbound.isEmpty then Right(binding) else Left(unbound)

/**
 * One operation's HTTP endpoint. Built from a concrete
 * `Operation[Req, Res, S]` + `HttpRoute[Req]` + `HttpResponseMapper[Res]`; the
 * typed functions are captured at construction. The existential `Req`/`Res`
 * are erased for name-keyed lookup; `encode`/`decode` recover the typed values
 * through the call's own path (sound by the name-keyed invariant).
 */
sealed trait Endpoint[S]:
  /** Encode the call's request into an HTTP request via the stored route. */
  def encode(call: OperationCall[S]): IO[Request[IO]]

  /** Decode a transport outcome into the operation's `Res` via the stored mapper. */
  def decode(outcome: TransportOutcome): IO[Any]

object Endpoint:

  /** Build an endpoint FROM an operation + route + mapper. */
  def apply[Req, Res, S](
      // `op` fixes the `Req`/`Res` type parameters so the route and mapper
      // align with the operation's types. It isn't read at runtime — the
      // endpoint's identity is the operation's name (used by HttpBinding.register).
      @scala.annotation.unused op: Operation[Req, Res, S],
      route: HttpRoute[Req],
      mapper: HttpResponseMapper[Res]
  ): Endpoint[S] =
    new Endpoint[S]:
      def encode(call: OperationCall[S]): IO[Request[IO]] =
        // Sound by the name-keyed invariant: the caller looked up this
        // endpoint by `call.op.name`, and this endpoint was built from `op`
        // whose `name` is the same key. So `call.Req =:= Req` at runtime.
        // scalafix:off DisableSyntax.asInstanceOf
        IO.pure(route.encode(call.req.asInstanceOf[Req]))
        // scalafix:on DisableSyntax.asInstanceOf
      def decode(outcome: TransportOutcome): IO[Any] =
        mapper.map(outcome)
