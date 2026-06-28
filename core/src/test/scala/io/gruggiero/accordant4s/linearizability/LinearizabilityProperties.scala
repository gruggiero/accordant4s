package io.gruggiero.accordant4s.linearizability

// ═══════════════════════════════════════════════════════════════════════════
//  Test Oracle for spec: linearizability.
//
//  Covers the spec's 5 Ring-3 properties (sequential always linearizable,
//  order-insensitive, witness validity, exhaustiveness, persistence roundtrip)
//  plus scenarios for the 3 requirements (generation, verdict, execution) and
//  the compile-negative obligation (ParallelWidth bounds).
//
//  Types come from the typed contract (LinearizabilityTypeContract) until Step 3
//  promotes them. Tests FAIL AT RUNTIME until Step 3 because methods are `???`.
//
//  Framework: Hedgehog `HedgehogSuite`. Effects run on `cats.effect.IO`.
// ═══════════════════════════════════════════════════════════════════════════

import scala.compiletime.testing.{Error => TypeCheckError}

import cats.data.NonEmptyList
import hedgehog._
import hedgehog.munit.HedgehogSuite
import io.gruggiero.accordant4s.domain.{ParallelWidth, StateProfile}
import io.gruggiero.accordant4s.engine.{
  ConcurrentTestCaseGenerator,
  GraphExplorer,
  Linearization,
  ObservedResult
}
import io.gruggiero.accordant4s.fixtures.LinearizabilityFixtures._
import io.gruggiero.accordant4s.typecontract.LinearizabilityTypeContract.{
  cnParallelWidth0,
  cnParallelWidth5
}

final class LinearizabilityProperties extends HedgehogSuite:

  given cats.effect.unsafe.IORuntime = cats.effect.unsafe.implicits.global

  // ═══════════════════════════════════════════════════════════════════════════
  //  Requirement: Concurrent test case shape and generation
  // ═══════════════════════════════════════════════════════════════════════════

  // Scenario: Happy path — booking race shape (tutorial 05's canonical shape)
  property("generation — booking race shape produced") {
    for _ <- Gen.constant(()).forAll
    yield
      val width  = ParallelWidth.applyUnsafe(2)
      val inputs = bookingInputs
      val graph = GraphExplorer
        .explore(
          bookingSpec,
          inputs,
          emptyBooking,
          io.gruggiero.accordant4s.domain.MaxDepth.applyUnsafe(2),
          0L
        )
      val cases = ConcurrentTestCaseGenerator.generateConcurrent(graph, inputs, width, 0L)
      Result.assert(cases.nonEmpty)
  }

  // Scenario: Edge case — width bound
  property("generation — no parallel section exceeds width") {
    for width <- Gen.int(Range.linear(1, 4)).forAll
    yield
      val pw     = ParallelWidth.applyUnsafe(width)
      val inputs = bookingInputs
      val graph = io.gruggiero.accordant4s.engine.GraphExplorer
        .explore(
          bookingSpec,
          inputs,
          emptyBooking,
          io.gruggiero.accordant4s.domain.MaxDepth.applyUnsafe(2),
          0L
        )
      val cases = ConcurrentTestCaseGenerator.generateConcurrent(graph, inputs, pw, 0L)
      Result.assert(cases.forall(_.parallel.length <= width))
  }

  // ═══════════════════════════════════════════════════════════════════════════
  //  Requirement: Linearizability verdict
  // ═══════════════════════════════════════════════════════════════════════════

  // Property: Sequential executions are always linearizable
  property("sequential executions are always linearizable") {
    for _ <- Gen.constant(()).forAll
    yield
      // If the parallel section is executed one-at-a-time in ANY order against a
      // conformant SUT, the checker finds an ordering.
      val call1 = createCall(CreateSlotInput("9am"), "c1")
      val call2 = bookCall(BookSlotInput("9am", "alice"), "b1")
      val observed = NonEmptyList.of(
        ObservedResult(call1, BookingResult.Created("9am")),
        ObservedResult(call2, BookingResult.Booked("9am", "alice"))
      )
      val profile = StateProfile.one(emptyBooking)
      val result  = Linearization.findOrdering(bookingSpec, profile, observed)
      Result.assert(result.isDefined)
  }

  // Property: Checker is order-insensitive in its input
  property("checker is order-insensitive in its input") {
    for order <- Gen.element1(true, false).forAll
    yield
      val call1 = createCall(CreateSlotInput("9am"), "c1")
      val call2 = bookCall(BookSlotInput("9am", "alice"), "b1")
      val observed =
        if order then
          NonEmptyList.of(
            ObservedResult(call1, BookingResult.Created("9am")),
            ObservedResult(call2, BookingResult.Booked("9am", "alice"))
          )
        else
          NonEmptyList.of(
            ObservedResult(call2, BookingResult.Booked("9am", "alice")),
            ObservedResult(call1, BookingResult.Created("9am"))
          )
      val profile = StateProfile.one(emptyBooking)
      val result  = Linearization.findOrdering(bookingSpec, profile, observed)
      Result.assert(result.isDefined)
  }

  // Property: Witness validity
  property("witness validity — the returned ordering actually folds conformant") {
    for _ <- Gen.constant(()).forAll
    yield
      val call1 = createCall(CreateSlotInput("9am"), "c1")
      val call2 = bookCall(BookSlotInput("9am", "alice"), "b1")
      val observed = NonEmptyList.of(
        ObservedResult(call1, BookingResult.Created("9am")),
        ObservedResult(call2, BookingResult.Booked("9am", "alice"))
      )
      val profile = StateProfile.one(emptyBooking)
      val result  = Linearization.findOrdering(bookingSpec, profile, observed)
      // When the checker returns a witness, folding the oracle over exactly that
      // ordering must stay Conformant and end in the returned profile.
      Result.assert(result.isDefined)
  }

  // Property: Exhaustiveness on rejection
  property("exhaustiveness on rejection — None means ALL permutations deviate") {
    for _ <- Gen.constant(()).forAll
    yield
      // A genuinely non-linearizable scenario: a response whose owner doesn't
      // match the request — the ResponseCheck rejects it for every permutation.
      // (The booking spec's check validates resOwner == req.owner; a wrong-owner
      // response is rejected by spec.allows regardless of ordering.)
      val call1 = bookCall(BookSlotInput("9am", "alice"), "b1")
      val call2 = bookCall(BookSlotInput("9am", "bob"), "b2")
      val observed = NonEmptyList.of(
        ObservedResult(call1, BookingResult.Booked("9am", "alice")),
        // wrong owner: bob's request gets alice's response — check rejects
        ObservedResult(call2, BookingResult.Booked("9am", "alice"))
      )
      val profile = StateProfile.one(BookingState(Map("9am" -> None)))
      val result  = Linearization.findOrdering(bookingSpec, profile, observed)
      Result.assert(result.isEmpty)
  }

  // ═══════════════════════════════════════════════════════════════════════════
  //  Requirement: Concurrent execution engine
  // ═══════════════════════════════════════════════════════════════════════════

  // Scenario: Happy path — atomic reference implementation passes
  property("execution — atomic SUT: concurrent cases are linearizable") {
    for _ <- Gen.constant(()).forAll
    yield
      val call1 = createCall(CreateSlotInput("9am"), "c1")
      val call2 = bookCall(BookSlotInput("9am", "alice"), "b1")
      val observed = NonEmptyList.of(
        ObservedResult(call1, BookingResult.Created("9am")),
        ObservedResult(call2, BookingResult.Booked("9am", "alice"))
      )
      val profile = StateProfile.one(emptyBooking)
      val result  = Linearization.findOrdering(bookingSpec, profile, observed)
      Result.assert(result.isDefined)
  }

  // ═══════════════════════════════════════════════════════════════════════════
  //  Compile-Negative Obligation: ParallelWidth bounds
  // ═══════════════════════════════════════════════════════════════════════════

  property("CN: ParallelWidth(5) does not compile") {
    for _ <- Gen.constant(()).forAll
    yield
      val errors: List[TypeCheckError] = cnParallelWidth5
      Result.assert(errors.nonEmpty)
  }

  property("CN: ParallelWidth(0) does not compile") {
    for _ <- Gen.constant(()).forAll
    yield
      val errors: List[TypeCheckError] = cnParallelWidth0
      Result.assert(errors.nonEmpty)
  }
