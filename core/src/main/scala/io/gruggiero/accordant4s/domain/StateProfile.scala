package io.gruggiero.accordant4s.domain

import cats.Eq
import cats.data.NonEmptyList
import cats.syntax.all._

/**
 * A non-empty, `Eq`-deduplicated set of candidate states. Emptiness is
 * unrepresentable: the only entry points are `one` (single candidate) and `of`
 * (deduplicated from a `NonEmptyList`). Equality is order-insensitive (it is a
 * set), so `eqStateProfile` compares deduplicated membership.
 */
opaque type StateProfile[S] = NonEmptyList[S]

object StateProfile:

  /** A single-candidate profile. */
  def one[S](state: S): StateProfile[S] = NonEmptyList.one(state)

  /** Eq-deduplicated profile, preserving first-occurrence order. */
  def of[S](states: NonEmptyList[S])(using ops: StateOps[S]): StateProfile[S] =
    given Eq[S] = ops.eqS
    states.tail.foldLeft(NonEmptyList.one(states.head)) { (acc, x) =>
      if acc.exists(_ === x) then acc else acc :+ x
    }

  extension [S](p: StateProfile[S])
    def toNonEmptyList: NonEmptyList[S] = p
    def toList: List[S]                 = p.toList

  given eqStateProfile[S](using ops: StateOps[S]): Eq[StateProfile[S]] =
    given Eq[S] = ops.eqS
    Eq.instance { (a, b) =>
      val as = a.toList
      val bs = b.toList
      as.length == bs.length && as.forall(x => bs.exists(_ === x))
    }

  given canEqualStateProfile[S](using CanEqual[S, S]): CanEqual[StateProfile[S], StateProfile[S]] =
    CanEqual.derived
