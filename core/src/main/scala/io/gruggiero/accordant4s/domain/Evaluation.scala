package io.gruggiero.accordant4s.domain

import scala.annotation.unused

import cats.Eq
import cats.data.NonEmptyList
import io.gruggiero.accordant4s.domain.StateProfile.toList

/**
 * Pure outcome evaluation. Lives in `domain` (Option A) so `spec.Spec.allows`
 * can delegate to it without `spec` depending on `engine` — the layer arrow
 * stays one-directional (`engine → spec → domain`). These are the Ring-6
 * (Stainless, best-effort) targets: List/Option/NonEmptyList only, no
 * Gen/fs2/IO.
 */
object OutcomeEval:

  /** One flattened branch of an outcome tree: a check plus its transition. */
  final case class Branch[Res, S](check: ResponseCheck[Res], transition: (Res, S) => S):
    // `state` is part of the symmetric `(res, state)` API but a check depends on the response only.
    def matches(res: Res, @unused state: S): Boolean = check(res).isValid
    def next(res: Res, state: S): S                  = transition(res, state)

  /** Flatten `OneOf` trees into a flat list of branches (`Same` ⇒ identity transition). */
  def flatten[Res, S](outcome: Outcome[Res, S]): List[Branch[Res, S]] = outcome match
    case Outcome.Same(check)        => List(Branch(check, (_, s) => s))
    case Outcome.Next(check, trans) => List(Branch(check, trans))
    case Outcome.OneOf(branches)    => branches.toList.flatMap(flatten)

  /** Eq-deduplicated next-states for every (candidate × passing branch) pair. */
  def survivors[Res, S](branches: List[Branch[Res, S]], res: Res, candidates: List[S])(using
      ops: StateOps[S]
  ): List[S] =
    given Eq[S] = ops.eqS
    dedupByEq(candidates.flatMap(c => branches.filter(_.matches(res, c)).map(_.next(res, c))))

  private[domain] def dedupByEq[S](xs: List[S])(using eq: Eq[S]): List[S] =
    xs.foldLeft(List.empty[S])((acc, x) => if acc.exists(eq.eqv(_, x)) then acc else acc :+ x)

object ProfileEval:

  /**
   * The survivor-set computation `Spec.allows` delegates to once `op.name` is
   * confirmed registered. `behaviour` is `op.behaviour` partially applied to the
   * request, so this kernel never sees `spec.Operation`.
   *
   *   - Conformant  ⇔ at least one (candidate × branch) check passes; the
   *     surviving profile is the Eq-deduplicated union of their next-states.
   *   - Deviant     ⇔ no branch passes for any candidate; carries the FLAT list
   *     of every failed atomic check (the "accumulate ALL violations"
   *     requirement). `ProfileExhausted` is the unreachable totality fallback.
   */
  def allows[Res, S](
      name: OperationName,
      behaviour: S => Outcome[Res, S],
      res: Res,
      profile: StateProfile[S]
  )(using ops: StateOps[S]): Verdict[S] =
    given Eq[S]   = ops.eqS
    val evaluated = profile.toList.map(c => (c, OutcomeEval.flatten(behaviour(c))))
    val survivors = OutcomeEval.dedupByEq(
      evaluated.flatMap((c, branches) => branches.filter(_.matches(res, c)).map(_.next(res, c)))
    )
    survivors match
      case head :: tail => Verdict.Conformant(StateProfile.of(NonEmptyList(head, tail)))
      case Nil =>
        val atoms = evaluated.flatMap((_, branches) =>
          branches.flatMap(b => b.check(res).fold(_.toList, _ => List.empty))
        )
        Verdict.Deviant(
          NonEmptyList
            .fromList(atoms)
            .getOrElse(NonEmptyList.one(SpecViolation.ProfileExhausted(name)))
        )
