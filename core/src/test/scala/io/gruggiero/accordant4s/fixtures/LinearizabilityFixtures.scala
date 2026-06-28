package io.gruggiero.accordant4s.fixtures

// ═══════════════════════════════════════════════════════════════════════════
//  Linearizability fixtures for spec: linearizability (Step 2 test oracle).
//
//  Provides the slot-booking spec (tutorial 05's canonical concurrent example):
//    - `CreateSlot` / `BookSlot` / `GetSlot` operations
//    - `BookingState` (the user state S: a map of slot → owner)
//    - `RefSut`-equivalent atomic booking SUT
//    - A deliberately racy booking SUT (read-then-write without atomicity)
//    - Generators for concurrent cases + observed results
//
//  The concurrent types come from the typed contract (LinearizabilityTypeContract)
//  until Step 3 promotes them to engine/spec main sources.
// ═══════════════════════════════════════════════════════════════════════════

import cats.syntax.all._
import cats.{Eq, Hash, Show}
import hedgehog.{Gen, Range}
import io.gruggiero.accordant4s.domain.{
  CallLabel,
  OperationName,
  Outcome,
  ResponseCheck,
  SpecViolation,
  StateOps
}
import io.gruggiero.accordant4s.spec.{Operation, OperationCall, Spec, withInput}

object LinearizabilityFixtures:

  // ── The booking state S: slot name → owner (None = free) ────────────────────

  enum BookingResult derives CanEqual:
    case Created(slot: String)
    case Booked(slot: String, owner: String)
    case Conflict(slot: String)
    case SlotInfo(slot: String, owner: Option[String])

  object BookingResult:
    given Eq[BookingResult]   = Eq.fromUniversalEquals
    given Hash[BookingResult] = Hash.fromUniversalHashCode
    given Show[BookingResult] = Show.fromToString

  final case class BookingState(slots: Map[String, Option[String]]) derives CanEqual

  object BookingState:
    given Eq[BookingState]   = Eq.fromUniversalEquals
    given Hash[BookingState] = Hash.fromUniversalHashCode
    given Show[BookingState] = Show.fromToString

  given StateOps[BookingState] = StateOps.derived

  val emptyBooking: BookingState = BookingState(Map.empty)

  // ── The booking operations ──────────────────────────────────────────────────

  final case class CreateSlotInput(slot: String)
  final case class BookSlotInput(slot: String, owner: String)
  final case class GetSlotInput(slot: String)

  val createSlotName: OperationName = OperationName("CreateSlot")
  val bookSlotName: OperationName   = OperationName("BookSlot")
  val getSlotName: OperationName    = OperationName("GetSlot")

  val createSlot: Operation[CreateSlotInput, BookingResult, BookingState] =
    Operation(
      createSlotName,
      (req, s) =>
        val check: ResponseCheck[BookingResult] =
          case BookingResult.Created(resSlot) if resSlot == req.slot => ().validNel[SpecViolation]
          case _ => SpecViolation.CheckFailed(createSlotName, "slot mismatch").invalidNel
        Outcome.Next(
          check,
          (res, _) =>
            res match
              case BookingResult.Created(slot) => BookingState(s.slots.updated(slot, None))
              case _                           => s
        )
      ,
      (req, _) => Gen.constant(BookingResult.Created(req.slot))
    )

  val bookSlot: Operation[BookSlotInput, BookingResult, BookingState] =
    Operation(
      bookSlotName,
      (req, _) =>
        val check: ResponseCheck[BookingResult] =
          case BookingResult.Booked(resSlot, resOwner)
              if resSlot == req.slot && resOwner == req.owner =>
            ().validNel[SpecViolation]
          case BookingResult.Conflict(resSlot) if resSlot == req.slot =>
            ().validNel[SpecViolation]
          case _ => SpecViolation.CheckFailed(bookSlotName, "slot/owner mismatch").invalidNel
        Outcome.Next(
          check,
          (res, st) =>
            res match
              case BookingResult.Booked(slot, owner) =>
                BookingState(st.slots.updated(slot, Some(owner)))
              case _ => st
        )
      ,
      (req, s) =>
        s.slots.get(req.slot) match
          case Some(None)    => Gen.constant(BookingResult.Booked(req.slot, req.owner))
          case Some(Some(_)) => Gen.constant(BookingResult.Conflict(req.slot))
          case None          => Gen.constant(BookingResult.Conflict(req.slot))
    )

  val getSlot: Operation[GetSlotInput, BookingResult, BookingState] =
    Operation(
      getSlotName,
      (req, _) =>
        val check: ResponseCheck[BookingResult] =
          case BookingResult.SlotInfo(resSlot, _) if resSlot == req.slot =>
            ().validNel[SpecViolation]
          case _ => SpecViolation.CheckFailed(getSlotName, "slot mismatch").invalidNel
        Outcome.Same(check)
      ,
      (req, s) => Gen.constant(BookingResult.SlotInfo(req.slot, s.slots.getOrElse(req.slot, None)))
    )

  val bookingSpec: Spec[BookingState] =
    Spec
      .empty[BookingState]
      .register(createSlot)
      .toOption
      .getOrElse(Spec.empty)
      .register(bookSlot)
      .toOption
      .getOrElse(Spec.empty)
      .register(getSlot)
      .toOption
      .getOrElse(Spec.empty)

  // ── Operation call builders (preserve the Res refinement for ObservedResult) ─

  def createCall(
      req: CreateSlotInput,
      label: String
  ): OperationCall.Aux[BookingState, CreateSlotInput, BookingResult] =
    createSlot.withInput(req, CallLabel.applyUnsafe(label))

  def bookCall(
      req: BookSlotInput,
      label: String
  ): OperationCall.Aux[BookingState, BookSlotInput, BookingResult] =
    bookSlot.withInput(req, CallLabel.applyUnsafe(label))

  def getCall(
      req: GetSlotInput,
      label: String
  ): OperationCall.Aux[BookingState, GetSlotInput, BookingResult] =
    getSlot.withInput(req, CallLabel.applyUnsafe(label))

  // ── Generators ──────────────────────────────────────────────────────────────

  val genSlot: Gen[String]  = Gen.element1("9am", "10am", "11am")
  val genOwner: Gen[String] = Gen.element1("alice", "bob", "carol")

  val genCreateSlotInput: Gen[CreateSlotInput] = genSlot.map(CreateSlotInput.apply)

  val genBookSlotInput: Gen[BookSlotInput] =
    for slot <- genSlot; owner <- genOwner yield BookSlotInput(slot, owner)

  val genGetSlotInput: Gen[GetSlotInput] = genSlot.map(GetSlotInput.apply)

  /** A non-empty input set for graph exploration (CreateSlot + BookSlot). */
  val bookingInputs: io.gruggiero.accordant4s.spec.InputSet[BookingState] =
    val create = io.gruggiero.accordant4s.spec.InputSet
      .fromGen(createSlot, genCreateSlotInput, 2, 0L)(using cats.Show.fromToString)
    val book = io.gruggiero.accordant4s.spec.InputSet
      .fromGen(bookSlot, genBookSlotInput, 2, 0L)(using cats.Show.fromToString)
    (create ++ book).getOrElse(create)

  /** Small booking states for the prefix end-state. */
  val genBookingState: Gen[BookingState] =
    for
      slots <- genSlot.list(Range.linear(0, 3))
      owners = slots.map(_ -> None: (String, Option[String])).toMap
    yield BookingState(owners)
