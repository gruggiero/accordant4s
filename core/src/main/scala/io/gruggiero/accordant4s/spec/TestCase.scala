package io.gruggiero.accordant4s.spec

import io.gruggiero.accordant4s.domain.CallLabel

/**
 * A labeled, replayable sequence of operation calls. By construction it starts
 * at `initial` and every consecutive step follows an existing edge of the graph
 * it was generated from (the generator draws steps only from `graph.edges`).
 */
final case class TestCase[S](name: CallLabel, initial: S, steps: List[OperationCall[S]])
    derives CanEqual
