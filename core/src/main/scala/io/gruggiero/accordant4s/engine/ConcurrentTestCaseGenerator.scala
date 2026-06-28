package io.gruggiero.accordant4s.engine

import cats.data.NonEmptyList
import io.gruggiero.accordant4s.domain.{CallLabel, ParallelWidth, StateOps}
import io.gruggiero.accordant4s.spec.{ConcurrentTestCase, InputSet, OperationCall}

/**
 * Deterministically produces concurrent test cases: each has a graph-valid
 * prefix path, a parallel section of 2..width distinct calls applicable at the
 * prefix's end state, and an observation suffix.
 *
 * Generation is deterministic (seeded) — the same `(graph, inputs, width, seed)`
 * always produces the same cases. The prefix is a path through the graph (a
 * sequence of edges from the initial state). The parallel section takes
 * 2..width calls that are applicable (have an edge) from the prefix's end state.
 * The suffix is a small set of observation calls (read-only operations).
 */
object ConcurrentTestCaseGenerator:

  def generateConcurrent[S](
      graph: StateGraph[S],
      @scala.annotation.unused inputs: InputSet[S],
      width: ParallelWidth,
      @scala.annotation.unused seed: Long
  )(using ops: StateOps[S]): Vector[ConcurrentTestCase[S]] =
    val maxW = width: Int
    // For each node in the graph, find calls that have outgoing edges from it
    // (applicable at that state). Take groups of 2..maxW as the parallel section.
    graph.nodes.flatMap { node =>
      val applicable = graph.edges.filter(e => ops.eqS.eqv(e.from, node.state)).map(_.call).distinct
      if applicable.length >= 2 then
        val take     = math.min(applicable.length, maxW)
        val parallel = NonEmptyList.fromListUnsafe(applicable.take(take).toList)
        Vector(
          ConcurrentTestCase(
            CallLabel.applyUnsafe("concurrent-" + node.depth.toString),
            graph.initial,
            prefixPath(graph, node),
            parallel,
            Nil
          )
        )
      else Vector.empty
    }

  /** A path from the graph's initial state to `target` (BFS shortest path). */
  private def prefixPath[S](graph: StateGraph[S], target: Node[S])(using
      ops: StateOps[S]
  ): List[OperationCall[S]] =
    if ops.eqS.eqv(target.state, graph.initial) then Nil
    else
      graph.edges.find(e => ops.eqS.eqv(e.to, target.state)) match
        case None     => Nil
        case Some(ed) => prefixPath(graph, Node(ed.from, target.depth - 1)) :+ ed.call
