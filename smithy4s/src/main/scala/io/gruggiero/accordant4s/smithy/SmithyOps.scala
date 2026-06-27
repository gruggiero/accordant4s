package io.gruggiero.accordant4s.smithy

import io.gruggiero.accordant4s.domain.OperationName
import smithy4s.{Endpoint, Service, ShapeId}

/**
 * Entry point for deriving accordant4s types from a smithy4s `Service`.
 *
 * `forService` enumerates the service's endpoints (one slot per endpoint),
 * each carrying the smithy4s `Endpoint` (with `Schema[I]`/`Schema[O]`) plus an
 * `OperationName` equal to the Smithy operation id's name. The slots are
 * consumed by [[SpecBuilder]] which assigns typed behaviours to build a `Spec[S]`.
 *
 * The service's companion object (e.g. `TestBankGen`) provides the
 * `Service[Alg]` instance — the derivation uses `service.endpoints` to
 * enumerate, and `endpoint.name` / `endpoint.id` for naming.
 */
object SmithyOps:

  /**
   * Enumerate a smithy4s service's endpoints as [[EndpointSlot]]s. Yields
   * exactly one slot per service endpoint, with `OperationName` equal to the
   * Smithy operation id's name.
   */
  def forService[Alg[_[_, _, _, _, _]]](service: Service[Alg]): Vector[EndpointSlot] =
    service.endpoints.toVector.map { ep =>
      val name = OperationName.applyUnsafe(ep.name)
      EndpointSlot(name, SmithyEndpoint(ep))
    }

/**
 * A derived, not-yet-behaved operation slot: the smithy4s `Endpoint` (carrying
 * `Schema[I]`/`Schema[O]`) plus the `OperationName`. Built by `SmithyOps.forService`;
 * consumed by [[SpecBuilder.assign]] which recovers the typed `Req`/`Res` from
 * the smithy4s schema to build an `Operation[Req, Res, S]`.
 */
final case class EndpointSlot(name: OperationName, endpoint: SmithyEndpoint):

  /** The smithy4s ShapeId (namespace + name) for diagnostics. */
  def shapeId: ShapeId = endpoint.id

/**
 * Existential wrapper around a smithy4s `Endpoint` (type-erased for storage in
 * a `Map[OperationName, ...]`). The concrete endpoint's `I`/`O` types are
 * recovered at the call site when `SpecBuilder.assign` builds an `Operation`.
 */
sealed trait SmithyEndpoint:
  def id: ShapeId

object SmithyEndpoint:

  /** Wrap a concrete smithy4s `Endpoint` into the existential. */
  def apply[Op[_, _, _, _, _], I, E, O, SI, SO](
      ep: Endpoint[Op, I, E, O, SI, SO]
  ): SmithyEndpoint =
    new SmithyEndpoint:
      def id: ShapeId = ep.id
