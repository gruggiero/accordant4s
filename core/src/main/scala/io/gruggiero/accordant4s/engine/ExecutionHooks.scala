package io.gruggiero.accordant4s.engine

import cats.Applicative

/**
 * Per-test-case setup/teardown effects (Accordant's `BeforeEachAsync`/
 * `AfterEachAsync`). A plain record of two `F[Unit]` effects — the BRACKET
 * semantics (`beforeEach` before step 1, `afterEach` ALWAYS) are the executor's
 * obligation, not the hook's: `TestCaseExecutor.run` runs `beforeEach` then
 * `afterEach` in a `Resource`/`bracket`, so `afterEach` survives `Passed`,
 * `DeviatesAt`, and SUT error or cancellation (Req: Bracket-safe hooks).
 *
 * Both default to no-op; a suite passes `ExecutionHooks(before, after)` to
 * reset a shared environment between cases.
 */
final case class ExecutionHooks[F[_]](beforeEach: F[Unit], afterEach: F[Unit])

object ExecutionHooks:

  /** No-op hooks (the documented defaults). */
  def noop[F[_]](using F: Applicative[F]): ExecutionHooks[F] =
    ExecutionHooks(F.unit, F.unit)
