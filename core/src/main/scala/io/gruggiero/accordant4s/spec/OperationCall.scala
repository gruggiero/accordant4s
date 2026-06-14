package io.gruggiero.accordant4s.spec

import io.gruggiero.accordant4s.domain.CallLabel

/**
 * One labeled step of a test sequence: a typed operation handle, its request,
 * and a human-readable label, with `Req`/`Res` hidden behind abstract type
 * members (the existential encoding). Sequences mix operations of different
 * `Req`/`Res` types; engines treat steps uniformly while the oracle
 * re-associates the exact types THROUGH the call (`call.op` / `call.req` /
 * `call.Res` align by path), never via a cast.
 */
sealed trait OperationCall[S]:
  type Req
  type Res
  def op: Operation[Req, Res, S]
  def req: Req
  def label: CallLabel

object OperationCall:

  /** Refinement preserving the concrete `Req`/`Res` at construction sites. */
  type Aux[S, R, Re] = OperationCall[S] { type Req = R; type Res = Re }

  /** Structural equality over the existential (used by `InputSet` equality). */
  given canEqual[S]: CanEqual[OperationCall[S], OperationCall[S]] = CanEqual.derived

  final private case class Impl[R, Re, S](
      op: Operation[R, Re, S],
      req: R,
      label: CallLabel
  ) extends OperationCall[S]:
    type Req = R
    type Res = Re

  def apply[R, Re, S](op: Operation[R, Re, S], req: R, label: CallLabel): Aux[S, R, Re] =
    Impl(op, req, label)

/** Accordant's `op.With(request, label)`. Typed by the operation's `Req`. */
extension [R, Re, S](self: Operation[R, Re, S])

  def withInput(request: R, lbl: CallLabel): OperationCall.Aux[S, R, Re] =
    OperationCall(self, request, lbl)
