package io.gruggiero.accordant4s.spec

import cats.data.NonEmptyList
import io.gruggiero.accordant4s.domain.CallLabel

/**
 * A concurrent test case: a sequential prefix (graph-valid), a parallel section
 * (2..width distinct calls applicable at the prefix's end state), and an
 * observation suffix. The `initial` state seeds the prefix replay.
 */
final case class ConcurrentTestCase[S](
    name: CallLabel,
    initial: S,
    prefix: List[OperationCall[S]],
    parallel: NonEmptyList[OperationCall[S]],
    suffix: List[OperationCall[S]]
) derives CanEqual
