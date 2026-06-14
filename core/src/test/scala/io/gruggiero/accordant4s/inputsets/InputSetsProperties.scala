package io.gruggiero.accordant4s.inputsets

// ═══════════════════════════════════════════════════════════════════════════
//  Test Oracle for spec: input-sets   (Step 2 — TESTS BEFORE IMPLEMENTATION)
//  Schema: verified-scala3
//
//  Derived from specs/input-sets/spec.md ONLY. Compiles against the APPROVED
//  typed contract (InputSetsTypeContract), whose new members (withInput,
//  OperationCall.apply, InputSet.{of, ++, fromGen}) are `???`. These tests are
//  EXPECTED TO FAIL AT RUNTIME until Step 3 lands the implementation — that is
//  the point. The two compile-negative checks already pass (they assert compile
//  errors the contract proved via `typeCheckErrors`).
//
//  Framework: Hedgehog via `hedgehog.munit.HedgehogSuite` (per capability-profile).
//  Every block is a property yielding `hedgehog.Result`; deterministic scenarios
//  use `Gen.constant(()).forAll`. Iron opaque types (OperationName/CallLabel) are
//  compared by widening to their String base and using cats `===` (Iron ships no
//  CanEqual; this mirrors the oracle-core oracle's equality style).
// ═══════════════════════════════════════════════════════════════════════════

import cats.Show
import cats.syntax.all._
import hedgehog._
import hedgehog.munit.HedgehogSuite
import io.gruggiero.accordant4s.domain.{CallLabel, StateProfile, Verdict}
import io.gruggiero.accordant4s.fixtures.BankState
import io.gruggiero.accordant4s.fixtures.InputFixtures._
import io.gruggiero.accordant4s.spec.{InputSet, OperationCall, Spec, withInput}
import io.gruggiero.accordant4s.typecontract.InputSetsTypeContract

final class InputSetsProperties extends HedgehogSuite:

  // ── helpers ─────────────────────────────────────────────────────────────────

  private def mkSet(calls: List[OperationCall[BankState]]): InputSet[BankState] =
    InputSet.of(calls).fold(_ => InputSet.empty[BankState], identity)

  extension (is: InputSet[BankState])
    private def labelStrings: List[String] = is.labels.map(l => l: String)

  // ═══════════════════════════════════════════════════════════════════════════
  //  Scenario tests (one per spec Scenario heading)
  // ═══════════════════════════════════════════════════════════════════════════

  // spec: input-sets — Scenario: Happy path (Labeled operation calls)
  property("labeled calls — happy path") {
    for _ <- Gen.constant(()).forAll
    yield
      val req   = DepositRequest("alice", BigDecimal(50))
      val label = CallLabel("Deposit(alice, 50)")
      val call  = deposit.withInput(req, label)
      val spec  = Spec.empty[BankState].register(deposit).fold(_ => Spec.empty[BankState], identity)
      val verdict =
        spec.allows(
          call.op,
          call.req,
          DepositResponse(BigDecimal(50)),
          StateProfile.one(BankState(Map.empty))
        )
      val feedsOk = verdict match
        case Verdict.Conformant(_) => true
        case Verdict.Deviant(_)    => false
      Result.assert(((call.op.name: String) === "Deposit") && (call.req == req) && feedsOk)
  }

  // spec: input-sets — Scenario: Edge case — same operation, many inputs
  property("labeled calls — many inputs") {
    for _ <- Gen.constant(()).forAll
    yield
      val c1 =
        deposit.withInput(DepositRequest("alice", BigDecimal(10)), CallLabel("Deposit(alice, 10)"))
      val c2 =
        deposit.withInput(DepositRequest("alice", BigDecimal(20)), CallLabel("Deposit(alice, 20)"))
      val c3 =
        deposit.withInput(DepositRequest("alice", BigDecimal(30)), CallLabel("Deposit(alice, 30)"))
      InputSet.of(List(c1, c2, c3)) match
        case Right(is) => Result.assert(is.size == 3 && is.labelStrings.distinct.length == 3)
        case Left(_)   => Result.failure
  }

  // spec: input-sets — Scenario: Happy path — disjoint union
  property("composition — disjoint union") {
    for _ <- Gen.constant(()).forAll
    yield
      val a = mkSet(
        List(
          deposit.withInput(DepositRequest("alice", BigDecimal(10)), CallLabel("a1")),
          deposit.withInput(DepositRequest("alice", BigDecimal(20)), CallLabel("a2"))
        )
      )
      val b = mkSet(
        List(
          deposit.withInput(DepositRequest("bob", BigDecimal(1)), CallLabel("b1")),
          deposit.withInput(DepositRequest("bob", BigDecimal(2)), CallLabel("b2")),
          deposit.withInput(DepositRequest("bob", BigDecimal(3)), CallLabel("b3"))
        )
      )
      (a ++ b) match
        case Right(ab) =>
          Result.assert(ab.size == 5 && (ab.labelStrings === (a.labelStrings ++ b.labelStrings)))
        case Left(_) => Result.failure
  }

  // spec: input-sets — Scenario: Error path — colliding labels
  property("composition — colliding labels") {
    for _ <- Gen.constant(()).forAll
    yield
      val shared = CallLabel("Deposit(alice, 50)")
      val a      = mkSet(List(deposit.withInput(DepositRequest("alice", BigDecimal(50)), shared)))
      val b      = mkSet(List(deposit.withInput(DepositRequest("alice", BigDecimal(50)), shared)))
      (a ++ b) match
        case Left(collisions) =>
          Result.assert(collisions.toList.map(l => l: String) === List("Deposit(alice, 50)"))
        case Right(_) => Result.failure
  }

  // spec: input-sets — Scenario: Happy path — deterministic sampling
  property("fromGen — deterministic sampling") {
    for _ <- Gen.constant(()).forAll
    yield
      val g  = Gen.int(Range.linear(1, 100)).map(i => DepositRequest("alice", BigDecimal(i)))
      val s1 = InputSet.fromGen(deposit, g, 10, 42L)
      val s2 = InputSet.fromGen(deposit, g, 10, 42L)
      Result.assert(s1 == s2)
  }

  // spec: input-sets — Scenario: Edge case — generator collapse
  property("fromGen — generator collapse") {
    for _ <- Gen.constant(()).forAll
    yield
      val g = Gen.constant(DepositRequest("alice", BigDecimal(50)))
      val s = InputSet.fromGen(deposit, g, 10, 42L)
      Result.assert(s.size == 1)
  }

  // spec: input-sets — Requirement: Gen-backed input sources
  // (each call labeled exactly "<opName>(<Show[Req]>)")
  property("fromGen — label format") {
    for _ <- Gen.constant(()).forAll
    yield
      val g = Gen.constant(DepositRequest("alice", BigDecimal(50)))
      val s = InputSet.fromGen(deposit, g, 1, 1L)
      Result.assert(s.labelStrings === List("Deposit(alice, 50)"))
  }

  // spec: input-sets — Requirement: Gen-backed input sources ("at most n": n <= 0 → empty)
  property("fromGen — non-positive n yields empty") {
    for _ <- Gen.constant(()).forAll
    yield
      val g = Gen.constant(DepositRequest("alice", BigDecimal(50)))
      Result.assert(InputSet.fromGen(deposit, g, 0, 1L).size == 0)
  }

  // spec: input-sets — Invariant: label uniqueness inside any InputSet
  // A non-injective Show maps distinct requests to one label; fromGen MUST still
  // yield unique labels (the label-dedup totality guard).
  property("fromGen — colliding labels collapse to keep labels unique") {
    for _ <- Gen.constant(()).forAll
    yield
      val constShow: Show[DepositRequest] = Show.show(_ => "X")
      val g = Gen.int(Range.linear(1, 100)).map(i => DepositRequest("alice", BigDecimal(i)))
      val s = InputSet.fromGen(deposit, g, 20, 7L)(using constShow)
      Result.assert(s.size == 1 && (s.labelStrings === List("Deposit(X)")))
  }

  // ═══════════════════════════════════════════════════════════════════════════
  //  Properties (Ring 3)
  // ═══════════════════════════════════════════════════════════════════════════

  // spec: input-sets — Property: withInput roundtrip
  property("withInput roundtrip") {
    for
      req   <- genDepositRequest.forAll
      label <- genCallLabel.forAll
    yield
      val call = deposit.withInput(req, label)
      Result.assert(
        ((call.op.name: String) === (deposit.name: String)) &&
          (call.req == req) &&
          ((call.label: String) === (label: String))
      )
  }

  // spec: input-sets — Property: Composition is associative and label-preserving
  property("composition is associative and label-preserving") {
    for
      a <- genInputSet("a").forAll
      b <- genInputSet("b").forAll
      c <- genInputSet("c").forAll
    yield
      val lhs = (a ++ b).flatMap(_ ++ c)
      val rhs = (b ++ c).flatMap(a ++ _)
      val assocOk = (lhs, rhs) match
        case (Right(l), Right(r)) => l == r
        case _                    => false
      val labelOk = (a ++ b) match
        case Right(ab) => ab.labelStrings === (a.labelStrings ++ b.labelStrings)
        case Left(_)   => false
      Result.assert(assocOk && labelOk)
  }

  // spec: input-sets — Property: fromGen determinism and bound
  property("fromGen determinism and bound") {
    for
      seed <- genSeed.forAll
      n    <- genPosInt.forAll
    yield
      val s1 = InputSet.fromGen(deposit, genReq, n, seed)
      val s2 = InputSet.fromGen(deposit, genReq, n, seed)
      val ls = s1.labelStrings
      Result.assert((s1 == s2) && (s1.size <= n) && (ls.distinct.length == ls.length))
  }

  // ═══════════════════════════════════════════════════════════════════════════
  //  Compile-negative obligations (asserted via the contract's typeCheckErrors)
  // ═══════════════════════════════════════════════════════════════════════════

  // spec: input-sets — Compile-Negative: CallLabel("") literal
  property("CN — blank CallLabel literal rejected") {
    for _ <- Gen.constant(()).forAll
    yield Result.assert(InputSetsTypeContract.cnBlankCallLabel.nonEmpty)
  }

  // spec: input-sets — Compile-Negative: cross-operation request reuse
  property("CN — cross-operation request is a type error") {
    for _ <- Gen.constant(()).forAll
    yield Result.assert(InputSetsTypeContract.cnCrossOpRequest.nonEmpty)
  }
