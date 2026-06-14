package io.gruggiero.accordant4s.stategraph

// ═══════════════════════════════════════════════════════════════════════════
//  Test Oracle for spec: state-graph   (Step 2 — TESTS BEFORE IMPLEMENTATION)
//  Schema: verified-scala3
//
//  Derived from specs/state-graph/spec.md ONLY. Compiles against the APPROVED
//  typed contract (StateGraphTypeContract), whose GraphExplorer members are `???`.
//  Behavioural tests FAIL AT RUNTIME until Step 3; the compile-negative check
//  already passes. Framework: Hedgehog via `HedgehogSuite` (capability-profile).
//  States compared by `Eq[BankState]` (`===`), graphs by derived `CanEqual`.
// ═══════════════════════════════════════════════════════════════════════════

import scala.annotation.tailrec

import cats.syntax.all._
import hedgehog._
import hedgehog.munit.HedgehogSuite
import io.gruggiero.accordant4s.domain.{MaxDepth, StateProfile, Verdict}
import io.gruggiero.accordant4s.engine.{Edge, GraphExplorer}
import io.gruggiero.accordant4s.fixtures.BankState
import io.gruggiero.accordant4s.fixtures.GraphFixtures._
import io.gruggiero.accordant4s.fixtures.InputFixtures.{DepositRequest, deposit}
import io.gruggiero.accordant4s.typecontract.StateGraphTypeContract

final class StateGraphProperties extends HedgehogSuite:

  // ── helpers ─────────────────────────────────────────────────────────────────

  private def distinctByEq(xs: Vector[BankState]): Vector[BankState] =
    xs.foldLeft(Vector.empty[BankState])((acc, x) => if acc.exists(_ === x) then acc else acc :+ x)

  private def sameStateSet(a: Vector[BankState], b: Vector[BankState]): Boolean =
    val da = distinctByEq(a)
    val db = distinctByEq(b)
    da.length == db.length && da.forall(x => db.exists(_ === x))

  private def reachable(
      initial: BankState,
      target: BankState,
      edges: Vector[Edge[BankState]]
  ): Boolean =
    @tailrec
    def loop(frontier: List[BankState], seen: List[BankState]): Boolean =
      frontier match
        case Nil => false
        case s :: rest =>
          if s === target then true
          else if seen.exists(_ === s) then loop(rest, seen)
          else loop(edges.collect { case e if e.from === s => e.to }.toList ++ rest, s :: seen)
    if initial === target then true else loop(List(initial), Nil)

  // ═══════════════════════════════════════════════════════════════════════════
  //  Scenario tests
  // ═══════════════════════════════════════════════════════════════════════════

  // spec: state-graph — Scenario: Happy path — bank graph
  property("bank graph — expected states present") {
    for _ <- Gen.constant(()).forAll
    yield
      val inputs = inputSetOf(
        List(
          call(create, CreateRequest("alice"), "c"),
          call(deposit, DepositRequest("alice", BigDecimal(50)), "d"),
          call(withdraw, WithdrawRequest("alice", BigDecimal(30)), "w")
        )
      )
      val g = GraphExplorer.explore(bankSpec, inputs, empty, MaxDepth(3), 1L)
      val expected = List(
        empty,
        BankState(Map("alice" -> BigDecimal(0))),
        BankState(Map("alice" -> BigDecimal(50))),
        BankState(Map("alice" -> BigDecimal(20)))
      )
      Result.assert(expected.forall(es => g.nodes.exists(_.state === es)))
  }

  // spec: state-graph — Scenario: Edge case — no-change operations are self-loops
  property("self-loop — withdraw on empty does not add a node") {
    for _ <- Gen.constant(()).forAll
    yield
      val inputs = inputSetOf(List(call(withdraw, WithdrawRequest("alice", BigDecimal(30)), "w")))
      val g      = GraphExplorer.explore(bankSpec, inputs, empty, MaxDepth(1), 1L)
      Result.assert(
        g.nodes.size == 1 &&
          g.nodes.exists(_.state === empty) &&
          g.edges.exists(e => (e.from === empty) && (e.to === empty))
      )
  }

  // spec: state-graph — Scenario: Edge case — depth bound respected
  property("depth bound — no node deeper than MaxDepth, terminates") {
    for _ <- Gen.constant(()).forAll
    yield
      val inputs = inputSetOf(List(call(deposit, DepositRequest("alice", BigDecimal(50)), "d")))
      val g      = GraphExplorer.explore(bankSpec, inputs, empty, MaxDepth(2), 1L)
      Result.assert(
        g.nodes.nonEmpty && g.nodes.forall(_.depth <= 2) && g.nodes.exists(_.depth == 2)
      )
  }

  // spec: state-graph — Scenario: Error path — OneOf branches all explored
  property("OneOf — both branch states become nodes") {
    for _ <- Gen.constant(()).forAll
    yield
      val inputs = inputSetOf(List(call(fork, ForkRequest("x"), "f")))
      val g      = GraphExplorer.explore(bankSpec, inputs, empty, MaxDepth(1), 1L)
      val s1     = BankState(Map("x" -> BigDecimal(1)))
      val s2     = BankState(Map("x" -> BigDecimal(2)))
      Result.assert(
        g.nodes.exists(_.state === s1) &&
          g.nodes.exists(_.state === s2) &&
          g.edges.count(e => (e.from === empty) && ((e.to === s1) || (e.to === s2))) == 2
      )
  }

  // spec: state-graph — Scenario: Happy path — diamond collapse
  property("diamond — Eq-equal target is one node with multiple inbound edges") {
    for _ <- Gen.constant(()).forAll
    yield
      val inputs = inputSetOf(
        List(
          call(create, CreateRequest("alice"), "c"),
          call(deposit, DepositRequest("alice", BigDecimal(50)), "d50"),
          call(deposit, DepositRequest("alice", BigDecimal(20)), "d20"),
          call(withdraw, WithdrawRequest("alice", BigDecimal(30)), "w30")
        )
      )
      val g      = GraphExplorer.explore(bankSpec, inputs, empty, MaxDepth(3), 1L)
      val target = BankState(Map("alice" -> BigDecimal(20)))
      Result.assert(g.nodes.count(_.state === target) == 1 && g.edges.count(_.to === target) >= 2)
  }

  // spec: state-graph — Scenario: Edge case — early termination
  property("stream — take(5) yields exactly the first 5 BFS nodes") {
    for _ <- Gen.constant(()).forAll
    yield
      val inputs = inputSetOf(
        List(
          call(deposit, DepositRequest("alice", BigDecimal(1)), "d1"),
          call(deposit, DepositRequest("alice", BigDecimal(3)), "d3"),
          call(deposit, DepositRequest("alice", BigDecimal(7)), "d7")
        )
      )
      val g = GraphExplorer.explore(bankSpec, inputs, empty, MaxDepth(4), 1L)
      val streamed =
        GraphExplorer.stream(bankSpec, inputs, empty, MaxDepth(4), 1L).take(5).compile.toVector
      Result.assert(
        g.nodes.size > 5 &&
          streamed.size == 5 &&
          streamed.map(_.state).corresponds(g.nodes.take(5).map(_.state))(_ === _)
      )
  }

  // ═══════════════════════════════════════════════════════════════════════════
  //  Properties (Ring 3)
  // ═══════════════════════════════════════════════════════════════════════════

  // spec: state-graph — Property: Every edge is spec-conformant
  property("every edge is oracle-conformant") {
    for (spec, inputs, initial, depth, seed) <- genSmallSpecAndInputs.forAll
    yield Result.assert(
      GraphExplorer.explore(spec, inputs, initial, depth, seed).edges.forall { e =>
        GraphExplorer.sampledResponse(e.call, e.from, seed) match
          case Some(res) =>
            spec.allows(e.call.op, e.call.req, res, StateProfile.one(e.from)) match
              case Verdict.Conformant(p) => p.toList.exists(_ === e.to)
              case Verdict.Deviant(_)    => false
          case None => false
      }
    )
  }

  // spec: state-graph — Property: Depth bound and reachability
  property("depth bound and reachability") {
    for (spec, inputs, initial, depth, seed) <- genSmallSpecAndInputs.forAll
    yield
      val g = GraphExplorer.explore(spec, inputs, initial, depth, seed)
      Result.assert(
        g.nodes.forall(_.depth <= (depth: Int)) &&
          g.nodes.forall(n => reachable(g.initial, n.state, g.edges)) &&
          g.nodes.exists(n => (n.state === initial) && (n.depth == 0))
      )
  }

  // spec: state-graph — Property: Determinism
  property("determinism") {
    for (spec, inputs, initial, depth, seed) <- genSmallSpecAndInputs.forAll
    yield Result.assert(
      GraphExplorer.explore(spec, inputs, initial, depth, seed) ==
        GraphExplorer.explore(spec, inputs, initial, depth, seed)
    )
  }

  // spec: state-graph — Property: No duplicate nodes
  property("no duplicate nodes") {
    for (spec, inputs, initial, depth, seed) <- genSmallSpecAndInputs.forAll
    yield
      val states = GraphExplorer.explore(spec, inputs, initial, depth, seed).nodes.map(_.state)
      Result.assert(states.length == distinctByEq(states).length)
  }

  // spec: state-graph — Property: Stream/explore agreement
  property("stream/explore agreement") {
    for (spec, inputs, initial, depth, seed) <- genSmallSpecAndInputs.forAll
    yield
      val g        = GraphExplorer.explore(spec, inputs, initial, depth, seed)
      val streamed = GraphExplorer.stream(spec, inputs, initial, depth, seed).compile.toVector
      Result.assert(
        sameStateSet(streamed.map(_.state), g.nodes.map(_.state)) &&
          streamed.map(_.depth).zip(streamed.map(_.depth).drop(1)).forall((a, b) => a <= b)
      )
  }

  // ═══════════════════════════════════════════════════════════════════════════
  //  Compile-negative obligation
  // ═══════════════════════════════════════════════════════════════════════════

  // spec: state-graph — Compile-Negative: non-positive MaxDepth literals
  property("CN — non-positive MaxDepth literals rejected") {
    for _ <- Gen.constant(()).forAll
    yield Result.assert(
      StateGraphTypeContract.cnZeroDepth.nonEmpty && StateGraphTypeContract.cnNegativeDepth.nonEmpty
    )
  }
