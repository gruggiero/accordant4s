package io.gruggiero.accordant4s.spec

import cats.data.NonEmptyList
import io.gruggiero.accordant4s.domain._

/**
 * An operation registry plus the `allows` oracle.
 *
 * `allows` dispatches through the typed `op` handle passed in (no casts); the
 * registry is consulted only to confirm `op.name` was registered — if not, the
 * verdict is `Deviant(UnknownOperation)` without evaluating `op.behaviour`.
 * Otherwise it delegates the survivor/verdict computation to the pure
 * `domain.ProfileEval` kernel (Option A: `spec → domain`, never `spec → engine`).
 */
final case class Spec[S](operations: Map[OperationName, Operation[?, ?, S]]):

  /** Register an operation under its name; duplicate names are rejected (`Left` carries the collision). */
  def register[Req, Res](operation: Operation[Req, Res, S]): Either[OperationName, Spec[S]] =
    if operations.contains(operation.name) then Left(operation.name)
    else Right(copy(operations = operations.updated(operation.name, operation)))

  def allows[Req, Res](op: Operation[Req, Res, S], req: Req, res: Res, profile: StateProfile[S])(
      using StateOps[S]
  ): Verdict[S] =
    if !operations.contains(op.name) then
      Verdict.Deviant(NonEmptyList.one(SpecViolation.UnknownOperation(op.name)))
    else ProfileEval.allows(op.name, s => op.behaviour(req, s), res, profile)

object Spec:
  def empty[S]: Spec[S] = Spec(Map.empty)
