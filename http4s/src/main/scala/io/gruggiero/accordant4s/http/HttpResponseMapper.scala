package io.gruggiero.accordant4s.http

import cats.effect.IO

/**
 * TOTAL mapping `TransportOutcome => IO[Res]`. Totality means: for every
 * HTTP status and body, the mapper produces a `Res` value (Property: mapper
 * totality). Timeouts / connection failures map to a `Res` variant (never
 * raise). Only a body the mapper cannot decode AND has no variant for may
 * raise — and even then it raises a decode-error `Res` if the domain has one.
 *
 * The mapper is the user's responsibility: the library calls it with the
 * `TransportOutcome` it observed (a `Completed` response or a transport
 * failure), and the mapper decides which `Res` variant to return.
 */
trait HttpResponseMapper[Res]:
  def map(outcome: TransportOutcome): IO[Res]
