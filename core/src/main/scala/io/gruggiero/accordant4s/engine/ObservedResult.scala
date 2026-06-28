package io.gruggiero.accordant4s.engine

import io.gruggiero.accordant4s.spec.OperationCall

/**
 * A `(call, response)` pair captured from the parallel execution. The response
 * is path-dependent on the call (`response: call.Res`), recovered through the
 * call's own `op` path — cast-free, exactly as `RefSut.execute` /
 * `OperationCall.op.mock` recover `Res`. Sealed trait + abstract member (like
 * `OperationCall` itself) so the existential erasure is handled by the same
 * path-dependent typing mechanism.
 */
sealed trait ObservedResult[S]:
  val call: OperationCall[S]
  def response: call.Res

object ObservedResult:

  /** Refinement preserving the concrete `Res` at construction sites. */
  type Aux[S, Re] = ObservedResult[S] { type Res = Re }
  type Res

  given canEqual[S]: CanEqual[ObservedResult[S], ObservedResult[S]] = CanEqual.derived

  final private case class Impl[S, R, Re](
      call: OperationCall.Aux[S, R, Re],
      response: Re
  ) extends ObservedResult[S]:
    type Res = Re

  def apply[R, Re, S](call: OperationCall.Aux[S, R, Re], response: Re): Aux[S, Re] =
    Impl(call, response)
