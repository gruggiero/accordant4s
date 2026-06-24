package io.gruggiero.accordant4s.http

import org.http4s.Status

/**
 * Transport facts as DATA (never exceptions). The response mapper consumes
 * this, so a timeout becomes a `Res` variant the oracle can model with
 * `Outcome.OneOf` (Req: transport-as-data). `Completed` carries the raw
 * response for the mapper to read status + body; `TimedOut` /
 * `ConnectionFailed` are transport-level failures the mapper maps to a
 * domain response.
 */
enum TransportOutcome derives CanEqual:
  case Completed(status: Status, body: String)
  case TimedOut
  case ConnectionFailed(detail: String)
