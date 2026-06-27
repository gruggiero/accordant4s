package io.gruggiero.accordant4s.smithy

import cats.data.NonEmptyList
import hedgehog.Gen
import io.gruggiero.accordant4s.domain.OperationName
import io.gruggiero.accordant4s.spec.{Operation, Spec}

/**
 * Type-safe, complete-or-fail assembly of a `Spec[S]` from derived
 * [[EndpointSlot]]s. `assign` attaches a typed behaviour `(Req, S) => Outcome`
 * + mock to a slot, building an `Operation[Req, Res, S]`. `build` returns
 * `Right(Spec[S])` iff every endpoint received a behaviour; otherwise
 * `Left(NonEmptyList[OperationName])` listing the missing names.
 *
 * The whole point of derivation is that the oracle cannot silently drift from
 * the contract — a new Smithy operation breaks the spec build until the rule is
 * written (the "complete-or-fail" requirement). The typed `assign` ensures the
 * behaviour's `Req`/`Res` are the ones the caller declares (which, in practice,
 * are the smithy4s-generated types).
 */
final class SpecBuilder[S] private (
    slots: Vector[EndpointSlot],
    behaviours: Map[OperationName, Operation[?, ?, S]]
):

  /** Attach a typed behaviour + mock to the named slot. */
  def assign[Req, Res](
      name: OperationName,
      behaviour: (Req, S) => io.gruggiero.accordant4s.domain.Outcome[Res, S],
      mock: (Req, S) => Gen[Res]
  ): SpecBuilder[S] =
    val op = Operation(name, behaviour, mock)
    new SpecBuilder(slots, behaviours.updated(name, op))

  /**
   * Build the spec: `Right(Spec[S])` iff every slot received a behaviour;
   * `Left(NonEmptyList[OperationName])` listing the missing names.
   */
  def build: Either[NonEmptyList[OperationName], Spec[S]] =
    val slotNames    = slots.map(_.name).toSet
    val behavedNames = behaviours.keySet
    val missing      = slotNames.diff(behavedNames)
    NonEmptyList.fromList(missing.toList.sorted) match
      case None =>
        // every slot has a behaviour → fold into a Spec
        val spec = behaviours.foldLeft(Spec.empty[S]) { (acc, entry) =>
          val (_, op) = entry
          acc.register(op).getOrElse(acc)
        }
        Right(spec)
      case Some(missingNel) => Left(missingNel)

object SpecBuilder:

  /** Start a builder from a set of derived slots. */
  def apply[S](slots: Vector[EndpointSlot]): SpecBuilder[S] =
    new SpecBuilder(slots, Map.empty)
