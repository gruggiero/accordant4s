package io.gruggiero.accordant4s.fixtures

// ═══════════════════════════════════════════════════════════════════════════
//  Persistence + generation fixtures for spec: test-generation (Step 2 oracle).
//
//  Provides the USER-supplied circe codecs the library composes: Codec[BankState],
//  per-request codecs, and the existential Codec[OperationCall[BankState]]
//  (type-test encode + name→operation registry decode — no casts). Plus the
//  generators genAlgorithm / genTestCase and a fixed `baselineTestCase` for the
//  Ring-4 baseline fixture.
//
//  Library types (CoverageAlgorithm, TestCase, TestCaseGenerator, TestCasePersistence,
//  …) are imported from the typed contract pre-implementation; promoted at Step 3.
// ═══════════════════════════════════════════════════════════════════════════

import hedgehog._
import io.circe.generic.semiauto.deriveCodec
import io.circe.syntax._
import io.circe.{Codec, Decoder, DecodingFailure, Json}
import io.github.iltotore.iron._
import io.github.iltotore.iron.constraint.all._
import io.gruggiero.accordant4s.domain.{CallLabel, CoverageAlgorithm}
import io.gruggiero.accordant4s.engine.TestCaseGenerator
import io.gruggiero.accordant4s.fixtures.GraphFixtures.{
  CreateRequest,
  ForkRequest,
  WithdrawRequest,
  call,
  create,
  createName,
  fork,
  forkName,
  genStateGraph,
  withdraw,
  withdrawName
}
import io.gruggiero.accordant4s.fixtures.InputFixtures.{DepositRequest, deposit}
import io.gruggiero.accordant4s.spec.{OperationCall, TestCase, withInput}

object PersistenceFixtures:

  // ── User request/state codecs ───────────────────────────────────────────────

  given bankStateCodec: Codec[BankState]         = deriveCodec
  given depositReqCodec: Codec[DepositRequest]   = deriveCodec
  given createReqCodec: Codec[CreateRequest]     = deriveCodec
  given withdrawReqCodec: Codec[WithdrawRequest] = deriveCodec
  given forkReqCodec: Codec[ForkRequest]         = deriveCodec

  // ── The existential OperationCall codec (the "user request codec") ──────────

  private def encodeCall(c: OperationCall[BankState]): Json =
    val requestJson = c.req match
      case r: DepositRequest  => (r: DepositRequest).asJson
      case r: CreateRequest   => (r: CreateRequest).asJson
      case r: WithdrawRequest => (r: WithdrawRequest).asJson
      case r: ForkRequest     => (r: ForkRequest).asJson
      case _                  => Json.Null // unreachable for the fixture operation set
    Json.obj(
      "op"      -> Json.fromString(c.op.name),
      "label"   -> Json.fromString(c.label),
      "request" -> requestJson
    )

  // name → (label, requestJson) ⇒ rebuilt call against the SAME fixture operation
  private val stepDecoders
      : Map[String, (CallLabel, Json) => Decoder.Result[OperationCall[BankState]]] =
    Map(
      (createName: String) ->
        ((lbl, j) =>
          j.as[CreateRequest].map(r => create.withInput(r, lbl): OperationCall[BankState])
        ),
      (deposit.name: String) ->
        ((lbl, j) =>
          j.as[DepositRequest].map(r => deposit.withInput(r, lbl): OperationCall[BankState])
        ),
      (withdrawName: String) ->
        ((lbl, j) =>
          j.as[WithdrawRequest].map(r => withdraw.withInput(r, lbl): OperationCall[BankState])
        ),
      (forkName: String) ->
        ((lbl, j) => j.as[ForkRequest].map(r => fork.withInput(r, lbl): OperationCall[BankState]))
    )

  given opCallCodec: Codec[OperationCall[BankState]] =
    Codec.from(
      Decoder.instance { cur =>
        for
          opName  <- cur.get[String]("op")
          labelS  <- cur.get[String]("label")
          label   <- CallLabel.either(labelS).left.map(m => DecodingFailure(m, cur.history))
          reqJson <- cur.get[Json]("request")
          step <- stepDecoders
            .get(opName)
            .toRight(DecodingFailure("unknown op: " + opName, cur.history))
          built <- step(label, reqJson)
        yield built
      },
      (c: OperationCall[BankState]) => encodeCall(c)
    )

  // ── Generators ──────────────────────────────────────────────────────────────

  val genSeed: Gen[Long] = Gen.long(Range.linearFrom(0L, Long.MinValue, Long.MaxValue))
  val genPosSmall: Gen[Int :| Positive] = Gen.int(Range.linear(1, 10)).map(_.refineUnsafe[Positive])

  val genAlgorithm: Gen[CoverageAlgorithm] =
    Gen.choice1(
      Gen.constant(CoverageAlgorithm.StateCoverage),
      Gen.constant(CoverageAlgorithm.TransitionCoverage),
      for
        seed  <- genSeed
        count <- genPosSmall
      yield CoverageAlgorithm.RandomWalk(seed, count)
    )

  /** INTRODUCED generator: test cases from generated graphs (StateCoverage path). */
  val genTestCase: Gen[TestCase[BankState]] =
    genStateGraph.map { g =>
      TestCaseGenerator
        .generate(g, CoverageAlgorithm.StateCoverage)
        .headOption
        .getOrElse(TestCase(CallLabel("empty"), g.initial, Nil))
    }

  // ── Fixed case for the Ring-4 baseline fixture (stable across runs) ─────────

  val baselineTestCase: TestCase[BankState] =
    TestCase(
      CallLabel("baseline"),
      BankState(Map.empty),
      List(
        call(create, CreateRequest("alice"), "c"),
        call(deposit, DepositRequest("alice", BigDecimal(50)), "d")
      )
    )
