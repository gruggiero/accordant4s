# Accordant → Scala 3: Translation Difficulty & Pipeline Fit Report

**Author:** Technical Analysis for Giovanni / AgileLab  
**Date:** June 2026  
**Subject:** Feasibility of translating Microsoft Accordant into Scala 3 using the Typelevel/Cats Effect stack, and its potential value as a ring in the `verified-scala3` OpenSpec pipeline.

---

## 1. What Accordant Actually Is

Accordant is a **model-based testing framework** whose central insight is the separation of two concerns that conventional test suites tangle together:

- **The oracle** — a declarative `Spec` that maps `(State, Request) → ExpectedResponse × NextState`
- **The sequence generator** — anything that produces a stream of operations to run (BFS state-graph exploration, hand-written scenarios, fuzzers, production log replay)

The spec becomes an executable truth table for system behaviour. Given any `(State, Request, ActualResponse)` triple, it returns a verdict: conformant or deviant, and the next state if conformant. This makes the spec reusable across sequential tests, concurrent linearisability checks, chaos/failure-injection scenarios, and — crucially — as a validator for AI-generated implementations.

The five main modules are:

| .NET Assembly | Responsibility |
|---|---|
| `Accordant` | `IState`, `StateGraph`, `SystemChecker` — core engine |
| `Accordant.Operations` | `Spec<TState>`, `Operation<Req,Res,State>`, `Expect` DSL, `TestCaseExecutor` |
| `Accordant.SourceGenerator` | Roslyn codegen for `Clone()` / `Freeze()` / `StringRepresentation()` |
| `Accordant.Choose` | Exhaustive input enumeration (`Choose.Each<T>()`) |
| `Accordant.Operations.Http` | `HttpExecutable` binding operations to real HTTP endpoints |

---

## 2. The Primary Feature: Spec-as-Oracle for AI-Generated Code

The most relevant feature for the `verified-scala3` pipeline is `spec.Allows(operation, request, response, state)` — a single call that returns `(isValid: Boolean, message: String, nextState: S)`. This acts as a deterministic oracle that any test sequence can delegate validation to.

The workflow Accordant enables is:

```
AI generates implementation
        ↓
Hand-craft or auto-generate operation sequences
        ↓
Execute sequences against the real implementation
        ↓
spec.Allows(...) validates every (request, response, state) triple
        ↓
Report: conformant | deviates at step N | reason
```

This is exactly what the `verified-scala3` pipeline needs between the ScalaCheck/Stainless rings and a running service: a **behavioural oracle** that is separate from both the property tests and the implementation.

---

## 3. Translation Difficulty Assessment

### 3.1 Conceptual Difficulty: Low

Every core concept maps directly and often more elegantly into Scala 3:

| Accordant concept | Scala 3 / Typelevel equivalent | Notes |
|---|---|---|
| `IState` | Sealed trait / case class with `Cloneable` / `Copyable` | Scala case classes already give structural equality and copy-by-value semantics |
| `[State]` source generator | Scala 3 `derives` + macro / `Mirror` | No Roslyn; Scala 3 `Mirror` + typeclass derivation handles Clone, Eq, Show |
| `Spec<TState>` | `Spec[S]` — a `Map[OperationName, (Req, S) => IO[Outcome[S]]]` | Cats Effect `IO` makes async first-class |
| `Expect.That(pred).SameState()` | `Outcome.expect(pred).noStateChange` — a simple ADT | Pattern matching replaces the fluent builder |
| `Expect.OneOf(...)` | `NonEmptyList[Outcome[S]]` | Naturally expressed as a list of alternatives |
| `StateGraph` | `Ref[IO, Map[S, List[Edge[S]]]]` or a pure `Map` | Pure functional graph; BFS with `fs2.Stream` |
| `SystemChecker` | A function `(Op, Req, Res, S) => IO[Verdict[S]]` | Trivial |
| `TestCaseExecutor` | `fs2.Stream[IO, OperationCall]` evaluated against the SUT | Composable with `http4s` client |
| `HttpExecutable` | `http4s` `Client[IO]` + `circe` codecs | Already in the stack |
| `Choose.Each<T>()` | `cats.data.NonEmptyList` / `ScalaCheck Gen` | Covered |
| Linearisability checker | Custom permutation check over `IO.both` results | Or integrate Jepsen via its Clojure API (advanced) |

### 3.2 Implementation Difficulty: Medium

The hard parts are not conceptual but engineering:

**State cloning and structural equality.** Accordant uses a Roslyn source generator to emit `Clone()` and deep-equality for mutable `[State]` classes. In Scala, case classes give `==` and `copy` for free if you keep state immutable (which you should in a Cats Effect codebase). The `.ThenState(s => s.accounts(id) = newBalance)` mutation style becomes:

```scala
.thenState(s => s.copy(accounts = s.accounts.updated(id, newBalance)))
```

No code generation needed. Iron refinement types participate naturally.

**The `Expect` DSL.** The fluent `.SameState()` / `.ThenState()` / `.Triggers()` chain can be translated into an ADT:

```scala
enum Outcome[S]:
  case SameState[S](predicate: ResponsePredicate[S])          extends Outcome[S]
  case NextState[S](predicate: ResponsePredicate[S],
                    transition: S => S)                        extends Outcome[S]
  case Ambiguous[S](branches: NonEmptyList[Outcome[S]])        extends Outcome[S]  // Expect.OneOf

type ResponsePredicate[S] = [Res] => (Res, S) => ValidatedNel[String, Unit]
```

Using `ValidatedNel[String, Unit]` (Cats) instead of a boolean gives structured error accumulation for free — a direct gain over .NET.

**Non-determinism / `Expect.OneOf`.** Accordant models this as branching in the state graph. In Scala 3 the equivalent is `NonEmptyList[Outcome[S]]` returned from an operation spec — the verifier tries each branch and reports a violation only if none fits.

**State graph BFS.** The `TestCaseGenerator` performs a breadth-first walk of reachable states. In Scala 3 this is idiomatic with a pure tail-recursive loop or an `fs2.Stream.unfoldEval`:

```scala
def explore[S: Eq](
  spec: Spec[S],
  inputs: InputSet,
  initial: S
): fs2.Stream[IO, TestPath[S]] =
  fs2.Stream.unfoldEval(Queue.of(initial -> List.empty[OperationCall])) { queue =>
    queue.tryDequeue.flatMap { /* BFS expansion */ }
  }
```

**Concurrent / linearisability checking.** Accordant tests `N!` permutations of concurrent responses. This is the most expensive module to implement correctly. In Scala 3 it can be done with `IO.both` / `IO.parSequenceN` for execution, and a pure permutation check using `Cats` or a dedicated library. However, truly rigorous linearisability checking (à la Jepsen / Knossos) is a significant engineering investment.

**HTTP binding.** Accordant's `HttpExecutable` is trivial to replace with an `http4s` `Client[IO]` + `circe` codecs already in the stack.

### 3.3 Effort Estimate

| Module | Effort | Notes |
|---|---|---|
| Core `Spec[S]` + `Outcome[S]` ADT | 2–3 days | Idiomatic Scala 3 ADTs + `ValidatedNel` |
| `IState` / immutable state design | 1 day | Scala case classes + Iron refinements |
| `SystemChecker` oracle function | 1 day | Pure function |
| State-graph BFS + sequential test generation | 3–5 days | `fs2.Stream` BFS; serialisation via `circe` |
| munit / weaver integration | 1–2 days | Test framework wiring |
| `http4s` operation binding | 1–2 days | Reuses existing stack |
| Linearisability checker | 5–8 days | Hardest part; permutation enumeration + `IO.both` |
| ScalaCheck `Gen`-based input sets | 1 day | `Choose` replacement |
| **Total (MVP, no linearisability)** | **~10–14 days** | One focused engineer |
| **Total (full feature parity)** | **~20–28 days** | Including concurrency/linearisability |

This is a realistic estimate for someone who already owns the stack (which you do). The C# codebase is not large; DeepWiki shows ~5 main files for the core engine.

---

## 4. What the Scala 3 Ecosystem Already Provides (and Gaps)

### 4.1 What Already Exists

**ScalaCheck** generates arbitrary operation sequences and validates properties. It can be used as a sequence source feeding into an Accordant-style oracle. You already have this in Ring 5 of the pipeline.

**Stainless** can formally verify pure transition functions — if the state transition `S => S` is pure and bounded, Stainless can statically prove that invariants hold for all reachable states. This is stronger than Accordant's runtime checking.

**Weaver / munit** are composable test frameworks with `cats.effect.IO` integration and structured failure reporting.

**fs2** makes streaming operation sequences and parallel execution natural.

**circe** handles test case serialisation/deserialisation (Accordant's `TestCasePersistence` module).

**Iron** refinement types make invalid states unrepresentable at the type level — something Accordant cannot do because .NET lacks this expressive type system.

### 4.2 What Is Missing or Weak

**The oracle pattern itself is absent as a library.** ScalaCheck tests *properties*, not *behavioural contracts expressed as state machines*. There is no Scala library that gives you the Accordant `spec.Allows(op, req, res, state)` oracle idiom combined with BFS state-graph exploration and auto-generated test sequences from a finite input set.

**Model-based testing as a first-class citizen.** The closest existing tool is [scala-stm](https://nbronson.github.io/scala-stm/) (STM, different concept) or hand-rolling a state machine in ScalaCheck's `Commands` API. ScalaCheck's `Commands` is the most direct analogue:

```scala
// ScalaCheck Commands — the existing closest thing
object BankSpec extends Commands:
  type State = Map[AccountId, Balance]
  type Sut   = BankClient[IO]
  // define commands, nextState, postCondition ...
```

ScalaCheck's `Commands` covers the oracle pattern, but lacks: auto BFS exploration of reachable states from a finite input set, structured `Expect` DSL, step functions for async workflows, and the clean `Allows` API for use outside tests.

**Linearisability checking.** Jepsen exists but is a Clojure ecosystem tool; integrating it from Scala requires the Clojure/Java interop layer and is operational overhead. No pure Scala linearisability library exists with the ergonomics Accordant provides.

---

## 5. Gains of a Scala 3 Translation

A Scala 3 port would not be a mere translation — it would be a *strict upgrade* in several dimensions:

**Type-safe state.** States expressed as `case class BankState(accounts: Map[AccountId PosLong, Balance NonNegBigDecimal])` make invalid states unrepresentable. Accordant's `[State]` attribute + mutation-based `ThenState` can corrupt state silently at runtime if the mutation is wrong; Scala's immutable `copy` + Iron cannot.

**Effect-aware oracle.** The oracle function can be `(Op, Req, Res, S) => IO[Verdict[S]]`, meaning the spec itself can call `IO` — useful when the transition depends on external state (e.g., a Kafka offset) that must be queried. The .NET version is synchronous.

**Structured error accumulation.** `ValidatedNel[SpecViolation, Unit]` accumulates *all* property violations in one oracle call rather than short-circuiting at the first failure.

**Integration with Smithy4s specs.** The most powerful gain: operation schemas can be derived directly from Smithy IDL. A `SmithyOperation` becomes a typed `Operation[Req, Res, S]` automatically. The spec is no longer a separate artifact — it is derived from the same Smithy source of truth that generates your HTTP clients and server stubs.

**ScalaCheck `Gen` as input sets.** Instead of a fixed `InputSet`, the Scala version can draw from `Gen[Req]` — giving shrinking, arbitrary coverage, and compatibility with the property-test ring already in the pipeline.

---

## 6. Fit and Value in the verified-scala3 OpenSpec Pipeline

### 6.1 Current Pipeline Rings (Recap)

```
Ring 1: scalac (type checking)
Ring 2: Iron (refinement types)
Ring 3: Scalafix + WartRemover (lint)
Ring 4: ScalaCheck (property tests)
Ring 5: Stryker4s (mutation testing)
Ring 6: Stainless (formal verification of pure functions)
Ring 7: otel4s / Daut (runtime observability assertions)
```

### 6.2 Where Accordant-Scala3 Would Slot In

An Accordant-style library would fit as a **Ring 4.5** — sitting between ScalaCheck property tests and Stainless formal verification:

```
Ring 4:   ScalaCheck      — arbitrary property tests on isolated units
Ring 4.5: behavioural-spec — spec-as-oracle against the running service (NEW)
Ring 5:   Stryker4s       — mutation testing of unit code
Ring 6:   Stainless       — formal verification of pure transition functions
```

It fills a gap that nothing else in the pipeline fills: **end-to-end behavioural conformance testing of the assembled service**, where the oracle is the same model the AI agent was given to implement.

### 6.3 Specific Value for AI-Generated Code Validation

This is the highest-value intersection. The current pipeline uses Rings 1–6 to validate the *code artefact* produced by Claude Code / Devin. But none of those rings tests *whether the running service behaves as specified*.

An Accordant-style oracle closes this loop:

```
Hermes skill: verified-scala3-review-loop
        ↓
AI agent generates implementation from OpenSpec
        ↓
Rings 1–6: validate the code artefact
        ↓
Ring 4.5: deploy to test container
          run spec.Allows() over BFS-generated sequences  ← NEW
          report: conformant or "deviates at step N: expected X, got Y"
        ↓
Ring 7: observability / Daut runtime assertions
```

The oracle message is high-signal feedback for the AI agent: it is not a compilation error (Ring 1) or a property counterexample (Ring 4), but a **behavioural deviation with a path that reproduces it** — exactly the kind of feedback that allows an agent to correct a semantic mistake rather than a syntactic one.

For your LoyaltyConnect context (Banca d'Italia / PagoPA / SPID compliance), the spec also becomes a **regulatory artefact**: a machine-executable statement of the business rules that can be reviewed by a compliance officer, kept in version control, and re-run on every deployment. This is something Accordant's own documentation highlights as a "reviewable contract" — 60 lines of rules instead of 600 scattered assertions.

### 6.4 Integration with ADK4S

The oracle pattern maps naturally onto the ADK4S agentic framework:

- The `Spec[S]` becomes a skill input: the agent is given the spec and generates an implementation
- The `spec.Allows` call becomes a post-execution tool call the supervisor agent uses to score the implementation
- The state graph BFS becomes a planning step: the supervisor enumerates reachable states and constructs the most discriminating test sequences to give to the implementation agent

### 6.5 Integration with Smithy4s

Since the stack uses Smithy4s, the most natural design would derive `Operation[Req, Res, S]` from Smithy service shapes. Each Smithy operation becomes a typed slot in the spec. The developer writes only the `(Req, S) => Outcome[S]` lambda — the request/response types are already known. This means the spec and the implementation share a single source of truth, eliminating the drift that occurs when hand-maintaining test stubs.

---

## 7. Recommended Approach

Given the analysis, the recommended path is **not a full port of Accordant** but a targeted Scala 3 library that takes its best ideas natively:

**Phase 1 (MVP, ~2 weeks):** Implement the oracle core.

```scala
// Core types
sealed trait Outcome[+S]
object Outcome:
  case class SameState[S](check: ResponseCheck)                   extends Outcome[S]
  case class NextState[S](check: ResponseCheck, next: S => S)     extends Outcome[S]
  case class Ambiguous[S](branches: NonEmptyList[Outcome[S]])      extends Outcome[S]

type OperationSpec[Req, Res, S] = (Req, S) => IO[Outcome[S]]

trait Spec[S]:
  def register[Req, Res](name: String)(f: OperationSpec[Req, Res, S]): Spec[S]
  def allows[Req, Res](
    op: String, req: Req, res: Res, state: S
  ): IO[Verdict[S]]  // Verdict = Conformant(nextState) | Deviant(reason, path)
```

Integrate with `munit-cats-effect` so that any test can call `spec.allows(...)` and get a structured failure.

**Phase 2 (~1 week):** BFS state-graph explorer using `fs2.Stream` + `ScalaCheck Gen` for input sets. Auto-generate test sequences and emit them as munit test cases.

**Phase 3 (~1 week):** Smithy4s binding — derive `OperationSpec` stubs from Smithy shapes, reducing spec authoring to writing only the `(Req, S) => Outcome[S]` body.

**Phase 4 (optional, ~1 week):** Linearisability checker using `IO.both` / `IO.parSequenceN` for concurrent execution and a pure permutation validator. Integrate with Pekko cluster tests for LoyaltyConnect's consumer tripling scenarios.

**Phase 5 (optional):** Hermes skill wrapping the oracle — `behavioural-spec-oracle` — so the AI agent in the verified-scala3 loop receives structured oracle feedback and can self-correct.

---

## 8. Summary

| Dimension | Assessment |
|---|---|
| Conceptual translation difficulty | **Low** — every Accordant concept has a cleaner Scala 3 equivalent |
| Engineering effort (MVP oracle) | **~2 weeks** |
| Engineering effort (full feature parity) | **~4–5 weeks** |
| Ecosystem gap filled | **High** — no existing Scala library combines spec-as-oracle + BFS state-graph exploration |
| Gain over .NET version | **Significant** — immutable state, Iron types, effect-aware oracle, Smithy4s integration, ScalaCheck `Gen` input sets |
| Value in verified-scala3 pipeline | **High** — fills Ring 4.5, the missing behavioural conformance layer between ScalaCheck and Stainless |
| Value for AI-generated code validation | **Very high** — the oracle produces agent-actionable feedback on semantic deviations, not just type or lint errors |
| Regulatory value (LoyaltyConnect) | **High** — spec is a reviewable, version-controlled statement of business rules executable on every deployment |

The single most valuable piece to implement first is the **oracle function** (`spec.Allows`) and its integration into the Hermes `verified-scala3-review-loop` skill. The state-graph exploration and linearisability checker are additive, but the oracle alone already closes the most important gap in the pipeline.
