package io.gruggiero.accordant4s.engine

import io.gruggiero.accordant4s.spec.OperationCall

/**
 * A tagless-final handle to the system under test ("execute then observe"). One
 * concrete binding is the user's real system (e.g. the http4s `Client[IO]`
 * binding of spec:http-binding); another is the reference implementation the
 * test fixtures replay generated cases against (`RefSut`, conformant by
 * construction).
 *
 * `execute(call)` returns `F[call.Res]` — a DEPENDENT method type that ties the
 * SUT's answer to the call's operation. The response's `Res` is the operation's
 * own `Res`, recovered by path (`call.op` / `call.Res` align), so no
 * existential name→type bridge and no `asInstanceOf` is ever needed. A SUT that
 * answered with a different response type does not type-check (compile-negative
 * obligation, `TestExecutionTypeContract`).
 *
 * `reset` returns the SUT to its starting state, so a fresh `TestCase` replays
 * against a clean system. It is the SUT's obligation, not the executor's: the
 * executor does not know what "clean" means for an arbitrary system.
 */
trait SystemUnderTest[F[_], S]:
  def execute(call: OperationCall[S]): F[call.Res]
  def reset: F[Unit]
