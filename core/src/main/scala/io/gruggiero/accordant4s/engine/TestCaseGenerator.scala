package io.gruggiero.accordant4s.engine

import scala.annotation.tailrec

import cats.Eq
import cats.syntax.all._
import io.gruggiero.accordant4s.domain.{CallLabel, CoverageAlgorithm, StateOps}
import io.gruggiero.accordant4s.spec.{OperationCall, TestCase}

/**
 * Path-selection over an explored [[StateGraph]] (Accordant's pluggable
 * generation algorithms). Every produced [[TestCase]] starts at the graph's
 * initial state and follows existing edges BY CONSTRUCTION: paths are built only
 * from `graph.edges`, so no generated step can reference a state or call absent
 * from the graph.
 *
 *   - `StateCoverage` greedily extends paths (longest first) until every node is
 *     on some path, so the case count never exceeds the node count.
 *   - `TransitionCoverage` emits, per edge, a shortest path to the edge's source
 *     followed by the edge's call — every edge (self-loops included) is exercised.
 *   - `RandomWalk(seed, count)` is a pure function of `(graph, seed, count)`:
 *     exactly `count` deterministic walks drawn from a splitmix64 stream keyed by
 *     the seed.
 *
 * State equality is supplied by [[StateOps]] (the same capability the explorer
 * requires), so paths are followed without universal equality on `S`.
 */
object TestCaseGenerator:

  def generate[S](graph: StateGraph[S], algorithm: CoverageAlgorithm)(using
      StateOps[S]
  ): Vector[TestCase[S]] =
    algorithm match
      case CoverageAlgorithm.StateCoverage           => stateCoverage(graph)
      case CoverageAlgorithm.TransitionCoverage      => transitionCoverage(graph)
      case CoverageAlgorithm.RandomWalk(seed, count) => randomWalk(graph, seed, count)

  /** A shortest call-path from the initial state to a node, with the states it visits. */
  final private case class Reach[S](calls: List[OperationCall[S]], states: Vector[S])

  /** BFS over edges: one shortest [[Reach]] per state reachable from `initial`. */
  private def reachable[S](graph: StateGraph[S])(using ops: StateOps[S]): Vector[Reach[S]] =
    given Eq[S] = ops.eqS

    def seen(acc: Vector[Reach[S]], s: S): Boolean =
      acc.exists(_.states.lastOption.exists(_ === s))

    @tailrec
    def bfs(frontier: Vector[Reach[S]], acc: Vector[Reach[S]]): Vector[Reach[S]] =
      if frontier.isEmpty then acc
      else
        val level = frontier.foldLeft(Vector.empty[Reach[S]]) { (lvl, r) =>
          r.states.lastOption match
            case None => lvl
            case Some(cur) =>
              graph.edges.foldLeft(lvl) { (lvl2, e) =>
                if (e.from === cur) && !seen(acc, e.to) && !seen(lvl2, e.to) then
                  lvl2 :+ Reach(r.calls :+ e.call, r.states :+ e.to)
                else lvl2
              }
        }
        bfs(level, acc ++ level)

    val start = Reach(List.empty[OperationCall[S]], Vector(graph.initial))
    bfs(Vector(start), Vector(start))

  /**
   * Greedy node coverage: take the longest paths first, extending rather than
   *  emitting one case per node, so the count stays at or below the node count.
   */
  private def stateCoverage[S](graph: StateGraph[S])(using ops: StateOps[S]): Vector[TestCase[S]] =
    given Eq[S] = ops.eqS
    val ordered = reachable(graph).sortBy(r => -r.states.size)
    val selected =
      ordered.foldLeft((Vector.empty[List[OperationCall[S]]], Vector(graph.initial))) {
        case ((acc, covered), r) =>
          val fresh = r.states.filterNot(s => covered.exists(_ === s))
          if fresh.isEmpty then (acc, covered)
          else (acc :+ r.calls, covered ++ fresh)
      }
    val paths = selected._1
    // A single-node graph has no covering path; emit one empty case so the lone
    // initial state is still represented.
    val withInitial = if paths.isEmpty then Vector(List.empty[OperationCall[S]]) else paths
    label("state", withInitial, graph.initial)

  /** Edge coverage: a shortest path to each edge's source, then the edge's call. */
  private def transitionCoverage[S](graph: StateGraph[S])(using
      ops: StateOps[S]
  ): Vector[TestCase[S]] =
    given Eq[S] = ops.eqS
    val reaches = reachable(graph)
    def pathTo(s: S): Option[List[OperationCall[S]]] =
      reaches.find(_.states.lastOption.exists(_ === s)).map(_.calls)
    val paths = graph.edges.flatMap(e => pathTo(e.from).map(_ :+ e.call)).distinct
    label("transition", paths, graph.initial)

  /** `count` deterministic walks from a splitmix64 stream keyed by `seed`. */
  private def randomWalk[S](graph: StateGraph[S], seed: Long, count: Int)(using
      ops: StateOps[S]
  ): Vector[TestCase[S]] =
    given Eq[S] = ops.eqS
    val fuel    = math.max(1, graph.nodes.size)

    @tailrec
    def walk(
        current: S,
        left: Int,
        rng: Long,
        acc: List[OperationCall[S]]
    ): List[OperationCall[S]] =
      if left <= 0 then acc.reverse
      else
        val outs = graph.edges.filter(_.from === current)
        if outs.isEmpty then acc.reverse
        else
          val (rng2, draw) = splitmix(rng)
          val idx          = java.lang.Math.floorMod(draw, outs.size.toLong).toInt
          outs.lift(idx) match
            case None    => acc.reverse
            case Some(e) => walk(e.to, left - 1, rng2, e.call :: acc)

    val paths = (0 until count).toVector.map { i =>
      val (_, sub) = splitmix(seed + i.toLong)
      walk(graph.initial, fuel, sub, List.empty[OperationCall[S]])
    }
    label("walk", paths, graph.initial)

  /** Build named test cases from selected paths (labels are non-blank by construction). */
  private def label[S](
      prefix: String,
      paths: Vector[List[OperationCall[S]]],
      initial: S
  ): Vector[TestCase[S]] =
    paths.zipWithIndex.map { case (steps, i) =>
      TestCase(CallLabel.applyUnsafe(prefix + "-" + i.toString), initial, steps)
    }

  /** splitmix64: `(nextState, output)`, a pure deterministic 64-bit stream. */
  private def splitmix(state: Long): (Long, Long) =
    val s  = state + 0x9e3779b97f4a7c15L
    val z1 = (s ^ (s >>> 30)) * 0xbf58476d1ce4e5b9L
    val z2 = (z1 ^ (z1 >>> 27)) * 0x94d049bb133111ebL
    (s, z2 ^ (z2 >>> 31))
