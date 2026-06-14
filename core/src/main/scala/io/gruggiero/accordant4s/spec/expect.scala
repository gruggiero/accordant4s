package io.gruggiero.accordant4s.spec

import cats.data.NonEmptyList
import io.gruggiero.accordant4s.domain.{Outcome, ResponseCheck}

/**
 * The constructor DSL for `Outcome` (the only public way to build one):
 *   - `expect(check).sameState`        — `Outcome.Same`
 *   - `expect(check).thenState(f)`     — `Outcome.Next`
 *   - `expect.oneOf(branches)`         — `Outcome.OneOf`
 */
object expect:

  final class Builder[Res] private[expect] (check: ResponseCheck[Res]):
    def sameState[S]: Outcome[Res, S]                            = Outcome.Same(check)
    def thenState[S](transition: (Res, S) => S): Outcome[Res, S] = Outcome.Next(check, transition)

  def apply[Res](check: ResponseCheck[Res]): Builder[Res] = new Builder(check)

  def oneOf[Res, S](branches: NonEmptyList[Outcome[Res, S]]): Outcome[Res, S] =
    Outcome.OneOf(branches)
