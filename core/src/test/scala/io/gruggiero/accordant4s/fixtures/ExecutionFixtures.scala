package io.gruggiero.accordant4s.fixtures

// ═══════════════════════════════════════════════════════════════════════════
//  Fixtures + generators for spec: test-execution (Step 2 test oracle).
//
//  RefSut — the INTRODUCED conformant-by-construction reference SUT. Its
//  responses come from each call's OWN `mock` generator (typed as `call.Res`,
//  so cast-free) and its state advances through `behaviour`'s first matching
//  branch. Because the bank mocks always yield a branch-accepted response,
//  RefSut over conformant cases is the reference implementation the Ring-3
//  soundness property replays generated cases against.
//
//  FAULT INJECTION (cast-free, by construction): a fault is encoded as a TYPED
//  operation with a non-conformant `mock` but the ORIGINAL `behaviour`:
//    - `faultyWithdraw` answers every call with `Success(-1)`. The spec's
//      `withdraw` behaviour rejects it (the success guard needs `b >= 0`; the
//      not-found guard needs `NotFound`), so it deviates AT its own step.
//  The fault lives in the operation's `mock`; the test case CARRIES that
//  operation. RefSut then samples `call.op.mock` (= the faulty mock) and the
//  executor validates via `bankSpec.allows(call.op, …)` (name "Withdraw" is
//  registered; `call.op.behaviour` is the conformant behaviour the faulty op
//  copied). No existential name→type bridge, no `asInstanceOf`: the dependent
//  response type stays statically known because it is the operation's own `Res`.
//  This is the only bank fault that deviates at its own step (a wrong-transition
//  fault deviates on the NEXT step), matching "reported step == faulted op".
//
//  Engine types (`SystemUnderTest`/`ExecutionHooks`/`ExecutionReport`/
//  `TestCaseExecutor`) are promoted to `engine` main sources (Step 3);
//  `RefSut`/`faultyWithdraw`/`SutMode` stay in test sources (they are test
//  fixtures, per the spec's "Concepts Introduced" table).
// ═══════════════════════════════════════════════════════════════════════════

import cats.effect.{IO, Ref}
import hedgehog.{Gen, Range, Size}
import hedgehog.core.Seed
import io.gruggiero.accordant4s.domain.{CoverageAlgorithm, MaxDepth, OutcomeEval, StateOps}
import io.gruggiero.accordant4s.engine.SystemUnderTest
import io.gruggiero.accordant4s.fixtures.GraphFixtures.*
import io.gruggiero.accordant4s.fixtures.InputFixtures.{DepositRequest, deposit}
import io.gruggiero.accordant4s.fixtures.PersistenceFixtures.genAlgorithm
import io.gruggiero.accordant4s.spec.{Operation, OperationCall}

object ExecutionFixtures:

  // ── RefSut: conformant-by-construction reference SUT ──────────────────────

  /**
   * An in-memory `SystemUnderTest[IO, S]` whose responses come from each call's
   * OWN `mock` generator (typed as `call.Res`, so cast-free) and whose state
   * advances through `behaviour`'s first matching branch. Conformant by
   * construction for any spec whose mocks yield branch-accepted responses (the
   * bank fixtures qualify).
   *
   * Mock sampling is keyed by `(state-hash, label)`, matching
   * `GraphExplorer`'s deterministic derivation so a response is a pure function
   * of `(seed, state, call)` — replayable and order-independent.
   */
  final class RefSut[S] private (
      initial: S,
      seed: Long,
      state: Ref[IO, S]
  )(using ops: StateOps[S])
      extends SystemUnderTest[IO, S]:

    def execute(call: OperationCall[S]): IO[call.Res] =
      for
        s   <- state.get
        res <- sampleResponse(call, s)
        _   <- state.set(advance(call, s, res))
      yield res

    def reset: IO[Unit] = state.set(initial)

    private def sampleResponse(call: OperationCall[S], s: S): IO[call.Res] =
      val sub = mixSeed(seed, ops.hashS.hash(s), call.label: String)
      // `Gen.run(...).value._2` is `Option[Res]`; the bank mocks are `Gen.constant`
      // so they always yield `Some`. `None` (a mock that generates nothing) is a
      // reference-implementation defect, surfaced as an SUT error.
      call.op.mock(call.req, s).run(Size(Size.max), Seed.fromLong(sub)).value._2 match
        case Some(r) => IO.pure(r)
        case None    => IO.raiseError(new RuntimeException("RefSut mock generated no response"))

    private def advance(call: OperationCall[S], s: S, res: call.Res): S =
      val branches = OutcomeEval.flatten(call.op.behaviour(call.req, s))
      branches.find(_.matches(res, s)).map(_.next(res, s)).getOrElse(s)

  object RefSut:

    /** Build a conformant reference SUT (state seeded at `initial`). */
    def apply[S](initial: S, seed: Long)(using
        StateOps[S]
    ): IO[RefSut[S]] =
      IO.ref(initial).map(st => new RefSut(initial, seed, st))

  // ── Fault injection (cast-free: a typed operation with a bad mock) ─────────

  /**
   * A `withdraw` whose `mock` always answers `Success(-1)` while its `behaviour`
   * is the original conformant one. Sampling `call.op.mock` (what RefSut does)
   * yields the faulty response; the executor validates it against the original
   * behaviour and rejects it (the success guard needs `b >= 0`). The dependent
   * response type stays `WithdrawResponse` throughout — no existential bridge.
   */
  val faultyWithdraw: Operation[WithdrawRequest, WithdrawResponse, BankState] =
    withdraw.copy(mock = (_, _) => Gen.constant(WithdrawResponse.Success(BigDecimal(-1))))

  /** Name of the faulted operation (matches the spec's `opName` invariant). */
  val faultedOpName: io.gruggiero.accordant4s.domain.OperationName = withdrawName

  /**
   * Generates test cases that are oracle-conformant up to a final faulty
   * `withdraw` step: `[create(id), deposit(id, amt+100), faultyWithdraw(id, amt)]`.
   * The prefix establishes a balance so the conformant expectation is a success;
   * the faulty step's `Success(-1)` therefore deviates at its own step.
   */
  val genFaultyWithdrawCase
      : Gen[io.gruggiero.accordant4s.spec.TestCase[BankState]] =
    for
      id  <- Gen.element1("alice", "bob")
      amt <- Gen.int(Range.linear(1, 100)).map(i => BigDecimal(i))
    yield io.gruggiero.accordant4s.spec.TestCase(
      io.gruggiero.accordant4s.domain.CallLabel.applyUnsafe("faulty-withdraw"),
      empty,
      List(
        call(create, CreateRequest(id), "c"),
        call(deposit, DepositRequest(id, amt + BigDecimal(100)), "d"),
        call(faultyWithdraw, WithdrawRequest(id, amt), "w")
      )
    )

  /** A deterministic faulty case (for scenario tests that need a fixed value). */
  val faultyWithdrawCase: io.gruggiero.accordant4s.spec.TestCase[BankState] =
    io.gruggiero.accordant4s.spec.TestCase(
      io.gruggiero.accordant4s.domain.CallLabel.applyUnsafe("faulty-withdraw"),
      empty,
      List(
        call(create, CreateRequest("alice"), "c"),
        call(deposit, DepositRequest("alice", BigDecimal(150)), "d"),
        call(faultyWithdraw, WithdrawRequest("alice", BigDecimal(30)), "w")
      )
    )

  // ── Recording wrapper (proves deviation halts execution) ──────────────────

  /** A SUT that records each sent call's label into `log`, then delegates. */
  def recording(
      delegate: SystemUnderTest[IO, BankState],
      log: Ref[IO, List[String]]
  ): SystemUnderTest[IO, BankState] =
    new SystemUnderTest[IO, BankState]:
      def execute(call: OperationCall[BankState]): IO[call.Res] =
        log.update(_ :+ (call.label: String)) *> delegate.execute(call)
      def reset: IO[Unit] = delegate.reset

  // ── Raising SUT (for "afterEach on failure" + the hook-invariant raising mode) ──

  /**
   * A SUT that delegates the first `n - 1` calls to a conformant `RefSut` (so
   * those steps succeed) and raises on the `n`-th call. Used to exercise the
   * "2nd call raises" scenario and the hook-invariant raising terminal mode —
   * `afterEach` must still run exactly once after partial progress. Cast-free:
   * early calls return `base.execute(call): IO[call.Res]`.
   */
  def raisingOnCall(
      n: Int,
      initial: BankState,
      seed: Long
  ): IO[SystemUnderTest[IO, BankState]] =
    for
      base <- RefSut(initial, seed)
      cnt  <- IO.ref(0)
    yield new SystemUnderTest[IO, BankState]:
      def execute(call: OperationCall[BankState]): IO[call.Res] =
        cnt.modify(i => (i + 1, i + 1)).flatMap { c =>
          if c >= n then IO.raiseError(new RuntimeException("SUT error"))
          else base.execute(call)
        }
      def reset: IO[Unit] = base.reset *> cnt.set(0)

  // ── Terminal SUT modes (for the hook-invariant property) ──────────────────

  /** Every terminal execution mode constructed explicitly (passing / deviating / raising). */
  enum SutMode derives CanEqual:
    case Passing, Deviating, Raising

  val genSutMode: Gen[SutMode] =
    Gen.element1(SutMode.Passing, SutMode.Deviating, SutMode.Raising)

  /**
   * The `(testCase, sut)` pair realizing a terminal mode. Passing/Raising use a
   * generated conformant case; Deviating uses a faulty case (the fault lives in
   * the case's operation, so the plain conformant RefSut deviates at the faulty
   * step). Raising delegates the first call and raises on the 2nd, requiring a
   * case with at least two steps (so the raise follows a successful step).
   */
  def scenarioForMode(
      mode: SutMode,
      conformantCase: io.gruggiero.accordant4s.spec.TestCase[BankState]
  ): IO[(io.gruggiero.accordant4s.spec.TestCase[BankState], SystemUnderTest[IO, BankState])] =
    mode match
      case SutMode.Passing   => RefSut(conformantCase.initial, 0L).map(sut => (conformantCase, sut))
      case SutMode.Deviating => RefSut(faultyWithdrawCase.initial, 0L).map(sut => (faultyWithdrawCase, sut))
      case SutMode.Raising   =>
        raisingOnCall(2, conformantCase.initial, 0L).map(sut => (conformantCase, sut))

  // ── Composite generator: (spec, inputs, initial, depth, seed, algorithm) ───

  /** Reuses spec:state-graph's spec/input pool + spec:test-generation's algorithm
   *  generator — the soundness property's input space. */
  val genSpecInputsDepthAlgo
      : Gen[(io.gruggiero.accordant4s.spec.Spec[BankState], List[OperationCall[BankState]], BankState, MaxDepth, Long, CoverageAlgorithm)] =
    for
      (spec, inputs, initial, depth, seed) <- GraphFixtures.genSmallSpecAndInputs
      algo                                 <- genAlgorithm
    yield (spec, inputs.calls, initial, depth, seed, algo)

  // ── Internal helpers ──────────────────────────────────────────────────────

  private def mixSeed(seed: Long, stateHash: Int, label: String): Long =
    val mixed =
      (stateHash.toLong * 0x9e3779b97f4a7c15L) ^ (label.hashCode.toLong * 0xc2b2ae3d27d4eb4fL)
    seed ^ mixed
