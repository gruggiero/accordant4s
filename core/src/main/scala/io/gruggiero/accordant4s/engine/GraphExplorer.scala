package io.gruggiero.accordant4s.engine

import scala.annotation.tailrec

import cats.Eq
import cats.syntax.all._
import fs2.{Chunk, Pure, Stream}
import hedgehog.Size
import hedgehog.core.Seed
import io.gruggiero.accordant4s.domain.{MaxDepth, StateOps, StateProfile, Verdict}
import io.gruggiero.accordant4s.spec.{InputSet, OperationCall, Spec}

/**
 * Bounded BFS over simulated transitions ("simulate then execute"): every call
 * in the input set is applied to every frontier state, with the operation's
 * `mock` supplying the response. Transitions are taken THROUGH `spec.allows`, so
 * every recorded edge's target is an oracle survivor (a `Deviant` verdict records
 * nothing) — "every edge is oracle-conformant" holds by construction.
 *
 * Mock sampling is a pure function of `(seed, from, call)` (order-independent),
 * which makes exploration deterministic and lets callers re-derive an edge's
 * response via [[sampledResponse]]. `explore` (strict) and `stream` (lazy fs2)
 * share one `expandLevel` step, so they emit the same nodes in the same order.
 */
object GraphExplorer:

  private val sampleSize: Size = Size(Size.max)

  def explore[S](spec: Spec[S], inputs: InputSet[S], initial: S, depth: MaxDepth, seed: Long)(using
      ops: StateOps[S]
  ): StateGraph[S] =
    val maxD = depth: Int

    @tailrec
    def loop(
        d: Int,
        frontier: Vector[S],
        nodes: Vector[Node[S]],
        edges: Vector[Edge[S]]
    ): (Vector[Node[S]], Vector[Edge[S]]) =
      if d >= maxD || frontier.isEmpty then (nodes, edges)
      else
        val (newNodes, newEdges) = expandLevel(spec, inputs.calls, frontier, d, seed, nodes)
        loop(d + 1, newNodes.map(_.state), nodes ++ newNodes, edges ++ newEdges)

    val (nodes, edges) = loop(0, Vector(initial), Vector(Node(initial, 0)), Vector.empty)
    StateGraph(initial, nodes, edges)

  def stream[S](spec: Spec[S], inputs: InputSet[S], initial: S, depth: MaxDepth, seed: Long)(using
      ops: StateOps[S]
  ): Stream[Pure, Node[S]] =
    val maxD = depth: Int
    Stream.emit(Node(initial, 0)) ++
      Stream.unfoldChunk((0, Vector(initial), Vector(Node(initial, 0)))) {
        case (d, frontier, visited) =>
          if d >= maxD || frontier.isEmpty then None
          else
            val (newNodes, _) = expandLevel(spec, inputs.calls, frontier, d, seed, visited)
            Some((Chunk.from(newNodes), (d + 1, newNodes.map(_.state), visited ++ newNodes)))
      }

  /**
   * The response the mock yields for `call` from `from` under `seed`,
   * deterministically and independent of traversal order; `None` when the mock
   * generates nothing (then no edge is recorded).
   */
  def sampledResponse[S](call: OperationCall[S], from: S, seed: Long)(using
      ops: StateOps[S]
  ): Option[call.Res] =
    val sub = edgeSeed(seed, ops.hashS.hash(from), call.label: String)
    call.op.mock(call.req, from).run(sampleSize, Seed.fromLong(sub)).value._2

  /** Deterministic, order-independent seed mix for one `(from, call)` sample. */
  private def edgeSeed(seed: Long, stateHash: Int, label: String): Long =
    val mixed =
      (stateHash.toLong * 0x9e3779b97f4a7c15L) ^ (label.hashCode.toLong * 0xc2b2ae3d27d4eb4fL)
    seed ^ mixed

  /**
   * Expand one BFS frontier level: apply every call to every frontier state,
   * record an edge per surviving transition, and tag each NOT-yet-seen target
   * (against `visited` and within this level) as a node at depth `d + 1`. Shared
   * by `explore` and `stream` so they discover identical nodes.
   */
  private def expandLevel[S](
      spec: Spec[S],
      calls: List[OperationCall[S]],
      frontier: Vector[S],
      d: Int,
      seed: Long,
      visited: Vector[Node[S]]
  )(using ops: StateOps[S]): (Vector[Node[S]], Vector[Edge[S]]) =
    given Eq[S] = ops.eqS
    frontier.foldLeft((Vector.empty[Node[S]], Vector.empty[Edge[S]])) {
      case ((levelNodes, edges), s) =>
        calls.foldLeft((levelNodes, edges)) { case ((ns, es), c) =>
          sampledResponse(c, s, seed) match
            case None => (ns, es)
            case Some(res) =>
              spec.allows(c.op, c.req, res, StateProfile.one(s)) match
                case Verdict.Deviant(_) => (ns, es)
                case Verdict.Conformant(profile) =>
                  profile.toList.foldLeft((ns, es)) { case ((ns2, es2), t) =>
                    val es3   = es2 :+ Edge(s, c, t)
                    val known = visited.exists(_.state === t) || ns2.exists(_.state === t)
                    if known then (ns2, es3) else (ns2 :+ Node(t, d + 1), es3)
                  }
        }
    }
