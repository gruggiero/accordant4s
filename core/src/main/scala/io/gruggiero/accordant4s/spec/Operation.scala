package io.gruggiero.accordant4s.spec

import hedgehog.Gen
import io.gruggiero.accordant4s.domain.{OperationName, Outcome}

/**
 * A named, typed operation slot.
 *   - `behaviour` is the pure oracle entry: `(Req, S) => Outcome[Res, S]`.
 *   - `mock` supplies realistic responses during offline exploration only; it is
 *     bypassed when executing against a real system (`hedgehog.Gen`).
 */
final case class Operation[Req, Res, S](
    name: OperationName,
    behaviour: (Req, S) => Outcome[Res, S],
    mock: (Req, S) => Gen[Res]
)
