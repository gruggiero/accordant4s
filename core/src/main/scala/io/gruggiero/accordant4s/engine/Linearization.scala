package io.gruggiero.accordant4s.engine

import cats.data.NonEmptyList
import io.gruggiero.accordant4s.domain.{StateOps, StateProfile, Verdict}
import io.gruggiero.accordant4s.spec.Spec

/**
 * PURE permutation search (no IO/Gen — a Ring 6 Stainless candidate). Returns
 * the FIRST permutation of `observed` under which folding `spec.allows` stays
 * `Conformant` (with the resulting profile), or `None` if every permutation
 * deviates.
 *
 * When multiple permutations are conformant (ambiguity scenario), the resulting
 * profile is the deduplicated union of all witness end-profiles — the suffix's
 * oracle validation runs against that union, avoiding false alarms from picking
 * one winner arbitrarily.
 *
 * The permutation set is bounded by `ParallelWidth ≤ 4` (4! = 24), so
 * brute-force enumeration is tractable. The checker folds `spec.allows` for
 * each permutation, threading the surviving profile through each step. A
 * permutation is a witness iff ALL its steps stay `Conformant`.
 */
object Linearization:

  /**
   * Find a conformant sequential ordering of `observed`, or `None` if none exists.
   * Returns the first witness permutation + the deduplicated union of all witness
   * end-profiles.
   */
  def findOrdering[S](
      spec: Spec[S],
      profile: StateProfile[S],
      observed: NonEmptyList[ObservedResult[S]]
  )(using ops: StateOps[S]): Option[(List[ObservedResult[S]], StateProfile[S])] =
    val allPerms = permutations(observed.toList)
    val witnesses = allPerms.flatMap(perm =>
      foldPermutation(spec, profile, perm).map(endProfile => perm -> endProfile)
    )
    witnesses.headOption.map { (firstPerm, _) =>
      // deduplicate the union of all witness end-profiles
      val unionStates = witnesses.flatMap(_._2.toList).distinct
      val unionProfile = NonEmptyList.fromList(unionStates) match
        case Some(nel) => StateProfile.of(nel)
        case None      => profile
      (firstPerm, unionProfile)
    }

  /**
   * Fold `spec.allows` over one permutation. Returns `Some(endProfile)` if ALL
   * steps stay `Conformant`, `None` if any step deviates. Each step re-invokes
   * `spec.allows` through the observed result's own `call` path — cast-free
   * (the path-dependent types align through `obs.call`).
   */
  private def foldPermutation[S](
      spec: Spec[S],
      initialProfile: StateProfile[S],
      perm: List[ObservedResult[S]]
  )(using ops: StateOps[S]): Option[StateProfile[S]] =
    def loop(
        remaining: List[ObservedResult[S]],
        profile: StateProfile[S]
    ): Option[StateProfile[S]] =
      remaining match
        case Nil         => Some(profile)
        case obs :: rest =>
          // re-invoke spec.allows through obs.call's own path: obs.call.op,
          // obs.call.req, obs.response — the types align (obs.call.Res =:= Res)
          spec.allows(obs.call.op, obs.call.req, obs.response, profile) match
            case Verdict.Conformant(nextProfile) => loop(rest, nextProfile)
            case Verdict.Deviant(_)              => None
    loop(perm, initialProfile)

  /**
   * Enumerate all permutations of a list (bounded by `ParallelWidth ≤ 4` → ≤ 24).
   * Deterministic order (lexicographic by position).
   */
  private def permutations[A](xs: List[A]): List[List[A]] =
    xs match
      case Nil => List(Nil)
      case _ :: _ =>
        xs.zipWithIndex.flatMap { (x, i) =>
          val rest = xs.take(i) ++ xs.drop(i + 1)
          permutations(rest).map(x :: _)
        }
