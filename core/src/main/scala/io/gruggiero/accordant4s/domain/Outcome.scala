package io.gruggiero.accordant4s.domain

import cats.data.{NonEmptyList, ValidatedNel}

/** An accumulating response predicate: every failed atomic check is retained. */
type ResponseCheck[Res] = Res => ValidatedNel[SpecViolation, Unit]

/**
 * The expected result of an operation against a state.
 *
 *   - `Same`  — the check must pass; the candidate state is unchanged.
 *   - `Next`  — the check must pass; the state advances via a response-dependent
 *     transition (the value comes from the actual response).
 *   - `OneOf` — non-deterministic: the union of every branch that passes (models
 *     Accordant's indefinite-failure / state-profile branching).
 */
enum Outcome[Res, S] derives CanEqual:
  case Same(check: ResponseCheck[Res])
  case Next(check: ResponseCheck[Res], transition: (Res, S) => S)
  case OneOf(branches: NonEmptyList[Outcome[Res, S]])
