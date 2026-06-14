package io.gruggiero.accordant4s.engine

import io.gruggiero.accordant4s.spec.OperationCall

/** A canonical state tagged with the BFS depth at which it was first reached. */
final case class Node[S](state: S, depth: Int) derives CanEqual

/** A transition `(from, call, to)`; `from == to` (by `Eq`) is a self-loop. */
final case class Edge[S](from: S, call: OperationCall[S], to: S) derives CanEqual

/** The explored graph: the initial state plus discovered nodes and edges. */
final case class StateGraph[S](
    initial: S,
    nodes: Vector[Node[S]],
    edges: Vector[Edge[S]]
) derives CanEqual
