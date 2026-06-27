package io.gruggiero.accordant4s.smithyderivation

// ═══════════════════════════════════════════════════════════════════════════
//  Test Oracle for spec: smithy4s-derivation (Requirements 1+2; Req 3 deferred).
//
//  Covers the spec's 3 scenarios (derive complete, derive missing, no-collision)
//  and 3 Ring-3 properties (names match, 1-slot-per-endpoint, completeness total)
//  plus the compile-negative obligation (`cnWrongBehaviourType`).
//
//  Types come from the smithy4s module's MAIN sources (`io.gruggiero.accordant4s.smithy`),
//  promoted at Step 3. The CN evidence stays in `Smithy4sDerivationTypeContract`.
//
//  Framework: Hedgehog `HedgehogSuite`.
// ═══════════════════════════════════════════════════════════════════════════

import scala.compiletime.testing.{Error => TypeCheckError}

import hedgehog._
import hedgehog.munit.HedgehogSuite
import io.gruggiero.accordant4s.domain.OperationName
import io.gruggiero.accordant4s.fixtures.SmithyFixtures._
import io.gruggiero.accordant4s.smithy.{SmithyOps, SpecBuilder}
import io.gruggiero.accordant4s.typecontract.Smithy4sDerivationTypeContract.cnWrongBehaviourType

final class Smithy4sDerivationProperties extends HedgehogSuite:

  // ── Requirement: Operation slots derived from the service ───────────────────

  // Scenario: Happy path — forService yields one slot per endpoint, named by the
  // Smithy operation id.
  property("derive — happy path: one slot per endpoint, names match Smithy ids") {
    for _ <- Gen.constant(()).forAll
    yield
      val slots = SmithyOps.forService(accordant4s.testbank.TestBankGen)
      val names = slots.map(_.name).toSet
      Result.assert(slots.length == 4 && names == allNames)
  }

  // Property: Name match — every slot's OperationName equals the Smithy endpoint id.
  property("derive — names match: every slot name is the endpoint's ShapeId name") {
    for _ <- Gen.constant(()).forAll
    yield
      val slots = SmithyOps.forService(accordant4s.testbank.TestBankGen)
      Result.assert(slots.forall(slot => allNames.contains(slot.name)))
  }

  // Property: 1-slot-per-endpoint — no duplicates, no missing.
  property("derive — exactly one slot per endpoint (no dups, no missing)") {
    for _ <- Gen.constant(()).forAll
    yield
      val slots  = SmithyOps.forService(accordant4s.testbank.TestBankGen)
      val names  = slots.map(_.name)
      val noDups = names.distinct.length == names.length
      Result.assert(noDups && slots.length == 4)
  }

  // ── Requirement: Spec assembly — complete-or-fail ───────────────────────────

  // Scenario: Happy path — assigning behaviours to all slots yields Right(Spec).
  property("spec assembly — happy path: all behaviours assigned → Right(Spec)") {
    for _ <- genState.forAll
    yield
      val slots = SmithyOps.forService(accordant4s.testbank.TestBankGen)
      val builder = SpecBuilder[SmithyBankState](slots)
        .assign(createName, createBehaviour, createMock)
        .assign(depositName, depositBehaviour, depositMock)
        .assign(withdrawName, withdrawBehaviour, withdrawMock)
        .assign(getName, getBehaviour, getMock)
      Result.assert(builder.build.isRight)
  }

  // Scenario: Missing behaviour — not assigning all operations → Left(unboundNames).
  property("spec assembly — missing: unassigned operations → Left(unbound)") {
    for _ <- Gen.constant(()).forAll
    yield
      val slots = SmithyOps.forService(accordant4s.testbank.TestBankGen)
      val builder = SpecBuilder[SmithyBankState](slots)
        .assign(createName, createBehaviour, createMock)
      // deliberately skip deposit, withdraw, getAccount
      val result = builder.build
      Result.assert(result match
        case Left(missing) => missing.length == 3
        case Right(_)      => false)
  }

  // Property: Completeness is total — Left lists exactly the unassigned names.
  property("spec assembly — completeness: Left lists exactly the unassigned names") {
    for _ <- Gen.constant(()).forAll
    yield
      val slots = SmithyOps.forService(accordant4s.testbank.TestBankGen)
      val builder = SpecBuilder[SmithyBankState](slots)
        .assign(createName, createBehaviour, createMock)
        .assign(depositName, depositBehaviour, depositMock)
      // skip withdraw, getAccount
      val result   = builder.build
      val expected = Set(withdrawName, getName)
      Result.assert(result match
        case Left(missing) => missing.toList.map(identity).toSet == expected
        case Right(_)      => false)
  }

  // ── Compile-Negative Obligation ─────────────────────────────────────────────

  property("CN: wrong behaviour type does not compile") {
    for _ <- Gen.constant(()).forAll
    yield
      val errors: List[TypeCheckError] = cnWrongBehaviourType
      Result.assert(errors.nonEmpty)
  }
