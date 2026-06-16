package io.gruggiero.accordant4s.testgen

// ═══════════════════════════════════════════════════════════════════════════
//  Test Oracle for spec: test-generation   (Step 2 — TESTS BEFORE IMPL)
//  Schema: verified-scala3
//
//  Derived from specs/test-generation/spec.md ONLY. Compiles against the approved
//  typed contract (TestGenerationTypeContract); behavioural tests FAIL AT RUNTIME
//  until Step 3. Coverage is checked with branch-following set semantics (a
//  (from, call) pair may have several targets via OneOf), so a `TestCase`'s
//  call-only steps are interpreted as the set of states/edges they can traverse.
//  Framework: Hedgehog `HedgehogSuite`. States compared by `Eq[BankState]`.
// ═══════════════════════════════════════════════════════════════════════════

import scala.annotation.tailrec

import cats.syntax.all._
import hedgehog._
import hedgehog.munit.HedgehogSuite
import io.circe.Json
import io.github.iltotore.iron._
import io.github.iltotore.iron.constraint.all._
import io.gruggiero.accordant4s.domain.{CoverageAlgorithm, MaxDepth}
import io.gruggiero.accordant4s.engine.{Edge, GraphExplorer, Node, StateGraph, TestCaseGenerator}
import io.gruggiero.accordant4s.fixtures.BankState
import io.gruggiero.accordant4s.fixtures.GraphFixtures._
import io.gruggiero.accordant4s.fixtures.InputFixtures.{DepositRequest, deposit}
import io.gruggiero.accordant4s.fixtures.PersistenceFixtures._
import io.gruggiero.accordant4s.fixtures.PersistenceFixtures.given
import io.gruggiero.accordant4s.persist.TestCasePersistence.given
import io.gruggiero.accordant4s.persist.{PersistenceError, TestCasePersistence}
import io.gruggiero.accordant4s.spec.{OperationCall, TestCase}

final class TestGenerationProperties extends HedgehogSuite:

  import CoverageAlgorithm._

  // ── graph-path helpers (branch-following sets) ──────────────────────────────

  private def sameSet[A](a: Set[A], b: Set[A]): Boolean =
    a.size == b.size && a.forall(b.contains)

  private def outEdges(
      graph: StateGraph[BankState],
      current: Set[BankState],
      step: OperationCall[BankState]
  ): Vector[Edge[BankState]] =
    graph.edges.filter(e => current.exists(_ === e.from) && (e.call == step))

  private def isEdgePath(
      graph: StateGraph[BankState],
      initial: BankState,
      steps: List[OperationCall[BankState]]
  ): Boolean =
    @tailrec
    def loop(current: Set[BankState], rest: List[OperationCall[BankState]]): Boolean =
      rest match
        case Nil => true
        case step :: tail =>
          val outs = outEdges(graph, current, step)
          if outs.isEmpty then false else loop(outs.map(_.to).toSet, tail)
    loop(Set(initial), steps)

  private def statesVisited(
      graph: StateGraph[BankState],
      cases: Vector[TestCase[BankState]]
  ): Set[BankState] =
    @tailrec
    def loop(
        current: Set[BankState],
        rest: List[OperationCall[BankState]],
        seen: Set[BankState]
    ): Set[BankState] =
      rest match
        case Nil => seen
        case step :: tail =>
          val nexts = outEdges(graph, current, step).map(_.to).toSet
          loop(nexts, tail, seen ++ nexts)
    cases.foldLeft(Set.empty[BankState])((acc, tc) =>
      acc ++ loop(Set(tc.initial), tc.steps, Set(tc.initial))
    )

  private def edgesVisited(
      graph: StateGraph[BankState],
      cases: Vector[TestCase[BankState]]
  ): Set[Edge[BankState]] =
    @tailrec
    def loop(
        current: Set[BankState],
        rest: List[OperationCall[BankState]],
        used: Set[Edge[BankState]]
    ): Set[Edge[BankState]] =
      rest match
        case Nil => used
        case step :: tail =>
          val outs = outEdges(graph, current, step)
          loop(outs.map(_.to).toSet, tail, used ++ outs.toSet)
    cases.foldLeft(Set.empty[Edge[BankState]])((acc, tc) =>
      acc ++ loop(Set(tc.initial), tc.steps, Set.empty)
    )

  // ── concrete bank graph for scenarios ───────────────────────────────────────

  private val bankInputs = inputSetOf(
    List(
      call(create, CreateRequest("alice"), "c"),
      call(deposit, DepositRequest("alice", BigDecimal(50)), "d"),
      call(withdraw, WithdrawRequest("alice", BigDecimal(30)), "w")
    )
  )

  private val bankGraph: StateGraph[BankState] =
    GraphExplorer.explore(bankSpec, bankInputs, empty, MaxDepth(3), 1L)

  // ═══════════════════════════════════════════════════════════════════════════
  //  Scenario tests
  // ═══════════════════════════════════════════════════════════════════════════

  // spec: test-generation — Scenario: Happy path (graph-valid paths)
  property("StateCoverage cases are edge-connected paths from initial") {
    for _ <- Gen.constant(()).forAll
    yield
      val cases = TestCaseGenerator.generate(bankGraph, StateCoverage)
      Result.assert(
        cases.forall(tc => (tc.initial === empty) && isEdgePath(bankGraph, tc.initial, tc.steps))
      )
  }

  // spec: test-generation — Scenario: Error path — unreachable target impossible
  property("no generated step references a call absent from the graph") {
    for _ <- Gen.constant(()).forAll
    yield
      val cases = TestCaseGenerator.generate(bankGraph, TransitionCoverage)
      Result.assert(
        cases.forall(tc => tc.steps.forall(step => bankGraph.edges.exists(_.call == step)))
      )
  }

  // spec: test-generation — Scenario: Happy path — transition coverage hits error edges
  property("TransitionCoverage exercises the not-found self-loop") {
    for _ <- Gen.constant(()).forAll
    yield bankGraph.edges.find(e => (e.from === empty) && (e.to === empty)) match
      case Some(selfLoop) =>
        val cases = TestCaseGenerator.generate(bankGraph, TransitionCoverage)
        Result.assert(edgesVisited(bankGraph, cases).exists(_ == selfLoop))
      case None => Result.failure
  }

  // spec: test-generation — Scenario: Edge case — minimality preference
  property("StateCoverage case count is at most the node count") {
    for _ <- Gen.constant(()).forAll
    yield Result.assert(
      TestCaseGenerator.generate(bankGraph, StateCoverage).size <= bankGraph.nodes.size
    )
  }

  // spec: test-generation — Scenario: Edge case — random walk determinism
  property("RandomWalk twice yields identical output") {
    for _ <- Gen.constant(()).forAll
    yield
      val rw = RandomWalk(42L, 5)
      val a  = TestCaseGenerator.generate(bankGraph, rw)
      val b  = TestCaseGenerator.generate(bankGraph, rw)
      Result.assert(a.corresponds(b)(_ == _))
  }

  // spec: test-generation — Scenario: Happy path — roundtrip
  property("a generated bank test case round-trips through JSON") {
    for _ <- Gen.constant(()).forAll
    yield
      val tc = TestCaseGenerator
        .generate(bankGraph, StateCoverage)
        .headOption
        .getOrElse(baselineTestCase)
      TestCasePersistence.fromJson[BankState](TestCasePersistence.toJson(tc)) match
        case Right(decoded) => Result.assert(decoded == tc)
        case Left(_)        => Result.failure
  }

  // spec: test-generation — Scenario: Error path — version mismatch (+ malformed)
  property("unknown version → VersionMismatch; malformed JSON → DecodeFailed") {
    for _ <- Gen.constant(()).forAll
    yield
      val good = TestCasePersistence.toJson(baselineTestCase)
      val bad =
        good.hcursor
          .downField("schemaVersion")
          .withFocus(_ => Json.fromInt(999))
          .top
          .getOrElse(good)
      val versionOk = TestCasePersistence.fromJson[BankState](bad) match
        case Left(PersistenceError.VersionMismatch(found, expected)) =>
          found == 999 && expected == 1
        case _ => false
      val malformedOk =
        TestCasePersistence.fromJson[BankState](Json.fromString("not a record")) match
          case Left(PersistenceError.DecodeFailed(_)) => true
          case _                                      => false
      Result.assert(versionOk && malformedOk)
  }

  // spec: test-generation — Proof obligation: Ring 4 baseline fixture decodes
  property("Ring 4 baseline — committed testcase-v1.json decodes to the baseline case") {
    for _ <- Gen.constant(()).forAll
    yield
      val source = scala.io.Source.fromResource("fixtures/testcase-v1.json")
      val text   = source.mkString
      source.close()
      io.circe.parser.parse(text) match
        case Right(json) =>
          TestCasePersistence.fromJson[BankState](json) match
            case Right(decoded) => Result.assert(decoded == baselineTestCase)
            case Left(_)        => Result.failure
        case Left(_) => Result.failure
  }

  // ═══════════════════════════════════════════════════════════════════════════
  //  Properties (Ring 3)
  // ═══════════════════════════════════════════════════════════════════════════

  // spec: test-generation — Property: Path validity for all algorithms
  property("path validity for all algorithms") {
    for
      graph <- genStateGraph.forAll
      algo  <- genAlgorithm.forAll
    yield Result.assert(
      TestCaseGenerator
        .generate(graph, algo)
        .forall(tc => (tc.initial === graph.initial) && isEdgePath(graph, tc.initial, tc.steps))
    )
  }

  // spec: test-generation — Property: StateCoverage covers nodes / TransitionCoverage covers edges
  property("StateCoverage covers all nodes / TransitionCoverage covers all edges") {
    for graph <- genStateGraph.forAll
    yield
      val sc  = TestCaseGenerator.generate(graph, StateCoverage)
      val tcv = TestCaseGenerator.generate(graph, TransitionCoverage)
      Result.assert(
        sameSet(statesVisited(graph, sc), graph.nodes.map(_.state).toSet) &&
          sameSet(edgesVisited(graph, tcv), graph.edges.toSet)
      )
  }

  // spec: test-generation — Property: Persistence roundtrip
  property("persistence roundtrip") {
    for tc <- genTestCase.forAll
    yield TestCasePersistence.fromJson[BankState](TestCasePersistence.toJson(tc)) match
      case Right(decoded) => Result.assert(decoded == tc)
      case Left(_)        => Result.failure
  }

  // spec: test-generation — Property: RandomWalk is a pure function of (graph, seed, count)
  property("RandomWalk is a pure function of (graph, seed, count)") {
    for
      graph <- genStateGraph.forAll
      seed  <- genSeed.forAll
      count <- genPosSmall.forAll
    yield
      val a = TestCaseGenerator.generate(graph, RandomWalk(seed, count))
      val b = TestCaseGenerator.generate(graph, RandomWalk(seed, count))
      Result.assert(a.corresponds(b)(_ == _) && a.size == (count: Int))
  }

  // ═══════════════════════════════════════════════════════════════════════════
  //  Targeted corners (fabricated graphs pin coverage minimality, walk bounding,
  //  and the per-algorithm naming the Ring-3 generators leave under-constrained).
  // ═══════════════════════════════════════════════════════════════════════════

  private val sx0 = empty
  private val sx1 = BankState(Map("x" -> BigDecimal(1)))
  private val sx2 = BankState(Map("x" -> BigDecimal(2)))
  private val sx3 = BankState(Map("x" -> BigDecimal(3)))
  private val e01 = call(deposit, DepositRequest("x", BigDecimal(1)), "e01")
  private val e12 = call(deposit, DepositRequest("x", BigDecimal(2)), "e12")
  private val e23 = call(deposit, DepositRequest("x", BigDecimal(3)), "e23")

  // Linear 4-node chain: a single path reaches every node.
  private val chain: StateGraph[BankState] =
    StateGraph(
      sx0,
      Vector(Node(sx0, 0), Node(sx1, 1), Node(sx2, 2), Node(sx3, 3)),
      Vector(Edge(sx0, e01, sx1), Edge(sx1, e12, sx2), Edge(sx2, e23, sx3))
    )

  // 2-node cycle: every state has exactly one outgoing edge — no dead end, so a
  // walk runs for the full fuel (= node count).
  private val ab = call(deposit, DepositRequest("x", BigDecimal(1)), "ab")
  private val ba = call(withdraw, WithdrawRequest("x", BigDecimal(1)), "ba")

  private val cycle: StateGraph[BankState] =
    StateGraph(
      sx0,
      Vector(Node(sx0, 0), Node(sx1, 1)),
      Vector(Edge(sx0, ab, sx1), Edge(sx1, ba, sx0))
    )

  // spec: test-generation — Scenario: minimality preference (extend paths, not one-per-node)
  property("StateCoverage on a linear chain emits one extended path covering all nodes") {
    for _ <- Gen.constant(()).forAll
    yield
      val cases = TestCaseGenerator.generate(chain, StateCoverage)
      Result.assert(
        cases.size == 1 &&
          cases.size < chain.nodes.size &&
          sameSet(statesVisited(chain, cases), chain.nodes.map(_.state).toSet)
      )
  }

  // spec: test-generation — Property: RandomWalk walks the graph, bounded by its size
  property("RandomWalk yields non-empty walks bounded by the node count") {
    for
      seed  <- genSeed.forAll
      count <- genPosSmall.forAll
    yield
      val cases = TestCaseGenerator.generate(cycle, RandomWalk(seed, count))
      Result.assert(
        cases.size == (count: Int) &&
          cases.forall(c => c.steps.nonEmpty && c.steps.size == cycle.nodes.size)
      )
  }

  // spec: test-generation — cases are named `<algorithm>-<index>` (reproducibility)
  property("generated cases are named per algorithm and index") {
    for _ <- Gen.constant(()).forAll
    yield
      val sc = TestCaseGenerator.generate(chain, StateCoverage)
      val tc = TestCaseGenerator.generate(cycle, TransitionCoverage)
      val rw = TestCaseGenerator.generate(cycle, RandomWalk(1L, 2))
      Result.assert(
        sc.forall(c => (c.name: String).startsWith("state-")) &&
          tc.forall(c => (c.name: String).startsWith("transition-")) &&
          rw.forall(c => (c.name: String).startsWith("walk-")) &&
          sc.headOption.exists(c => (c.name: String) == "state-0")
      )
  }
