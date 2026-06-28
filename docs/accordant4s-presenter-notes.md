# Presenter Notes — accordant4s Slide Deck

> **Companion to**: [`accordant4s-presentation.html`](./accordant4s-presentation.html)
> **Audience**: Scala programmers with medium experience, absolute beginners to
> model-based testing. **Estimated talk length**: 25–35 minutes + 10–15 min Q&A.
> **How to use**: open the HTML in a browser (fullscreen with `F`), keep these
> notes on a second screen or printed. Each slide section below has:
> - **Talking points** — what to say (≈ the script, in natural language)
> - **In-depth analysis** — deeper context if an audience member asks for more
> - **Anticipated questions** — what a medium-level Scala dev might ask, and how
>   to answer

---

## Slide 1 — Title

### Talking points

> "This is **accordant4s** — a model-based testing oracle for Scala 3 on the
> Typelevel stack. In the next half hour I'll show you a different way to think
> about testing: instead of writing individual test cases, you describe your
> system as a *spec* — a typed state machine — and that single spec drives
> graph exploration, test-case generation, replay against a real server, and
> even linearizability checking for concurrent systems."

Mention the tech stack badges briefly — the point is "this is all Typelevel
stack, nothing exotic." If the audience is sceptical of Scala 3, note that
the whole library is 3.8.4 with `-Werror` and `strictEquality`.

### In-depth analysis

The library is a port of **Microsoft Accordant**, a .NET model-based testing
framework. It's not a 1:1 translation — it's a "targeted port" that keeps
Accordant's best ideas (spec-as-oracle, state profiles, linearizability) and
expresses them idiomatically in Scala 3 (immutable case classes, ADTs, Iron
refined types, path-dependent typing). The .NET version used Roslyn source
generators for state cloning/equality; Scala 3 gets all of that for free from
`case class` + `derives`.

### Anticipated questions

- **"Is this like ScalaCheck's Commands?"** — Yes, it's the closest analogue.
  The key differences: accordant4s offers BFS state-graph exploration from a
  finite input set (ScalaCheck doesn't), structured `Outcome` branching with
  non-determinism (ScalaCheck has flat post-conditions), state-profile tracking
  for indefinite failures, and a formally verified kernel. ScalaCheck's Commands
  is property-based; accordant4s is model-based (the oracle is the model).

- **"Why not just use Hedgehog's state testing?"** — Hedgehog has `hedgehog.state`
  with `Command`/`Var`/`State`. It's similar in spirit but doesn't have the
  oracle/sequence-source separation: you can't take the same model and replay it
  against an HTTP server, or check linearizability. accordant4s makes the oracle
  a *value* (`Spec[S]`) usable outside tests.

---

## Slide 2 — The Problem

### Talking points

> "Every conventional test welds together two questions: *what is correct
> behaviour?* and *which sequence of operations should I try?* The assertion is
> the oracle; the test body is the sequence source. They're inseparable.
>
> Want to try a different path? Rewrite the test. Want to change what 'correct'
> means? Rewrite *every* test. The result is coverage holes you can't see, and
> brittle suites that test implementation details."
>
> "Think of a restaurant critic who can only review one fixed meal. If you
> separate the standard — what good food is — from the sampling — which dishes
> to try — suddenly you can review *any* meal against the same standard."

Pause on the analogy. It's the mental model for the whole talk: the oracle is
the standard, the sequence source is the sampling.

### In-depth analysis

This is a well-known problem in the formal-methods and model-based-testing
literature. The separation was articulated by the TTT (Testing from Timed
Automata) community and popularized in practice by frameworks like
Spec Explorer (Microsoft, Accordant's predecessor), QuickCheck for Erlang, and
Jepsen. The insight is that the oracle is the *expensive* part to get right
(it encodes the spec), while sequence generation is *cheap* to vary once you
have the oracle.

### Anticipated questions

- **"Isn't this just property-based testing?"** — Property-based testing (PBT)
  generates inputs and checks a property. Model-based testing (MBT) generates
  *sequences of operations* and checks them against a *model*. PBT is "for all
  x, f(x) holds"; MBT is "for all sequences of operations, the system behaves
  like the model." accordant4s uses Hedgehog (a PBT library) for *generation*,
  but the oracle is the model — the two are decoupled.

- **"How does this compare to integration testing?"** — Integration tests
  check specific scenarios. accordant4s checks *all reachable states* from a
  finite input set (via BFS), plus randomly generated paths. It finds edge
  cases integration tests miss (e.g. withdraw → deposit → withdraw on the
  same account — a path you'd never hand-write).

---

## Slide 3 — The Idea: Separate the Oracle

### Talking points

> "Here's the core idea: make the oracle a *value you can call*.
> `spec.allows(op, req, res, profile)` takes an operation, a request, the
> observed response, and the current state — and returns a verdict: either
> Conformant (with the surviving state) or Deviant (with the violations).
>
> Once the oracle is callable, *any* sequence source can use it. BFS exploration
> generates sequences. Test-case generation picks coverage paths. The executor
> replays them against a real system. The linearizability checker searches
> orderings. All consumers of one method."

Point to the flow diagram: `Spec[S]` ← `allows` → `(req, res)` → `Verdict[S]`.

Emphasize: "The verdict — 'deviates at step N: expected X, got Y, path P' — is
*actionable semantic feedback*, not a generic assertion failure. This is
especially useful for validating AI-generated implementations: give the agent
the spec, then check the assembled service against the same spec."

### In-depth analysis

The "actionable feedback" point is the AI-validation use case. In a CI pipeline
where an AI agent generates an implementation from a spec, accordant4s runs the
generated cases against the implementation and reports *exactly* where it
deviates — with a reproducing path that can be fed back to the agent. This is
the "Ring 4.5" the porting report calls out: behavioural conformance as the
missing layer between unit tests and formal verification.

### Anticipated questions

- **"What does 'profile' mean in the allows signature?"** — That's the state
  profile — a non-empty set of *candidate* states. It handles non-determinism.
  We'll get to it on slide 7. For now, think of it as "the current state, but
  possibly a set of them if we're unsure."

- **"How is this different from a state machine in Akka/Pekko?"** — Akka/Pekko
  state machines are *runtime* behaviour controllers. accordant4s specs are
  *test oracles* — they describe what correct behaviour looks like, so you can
  check whether a system (Akka-based or not) conforms. You can use an Akka
  actor as the SUT and check it against an accordant4s spec.

---

## Slide 4 — What is a Spec?

### Talking points

> "A Spec is a registry of typed operations. Each operation has a name, a
> *behaviour* — the pure oracle rule — and a *mock* that supplies realistic
> responses for offline exploration."

Walk through the `deposit` code on the slide:
- `OperationName("Deposit")` — the registry key (Iron-refined, non-blank)
- `behaviour` — a pure function `(Req, S) => Outcome`. No side effects. This is
  what the oracle uses to judge correctness.
- `mock` — a Hedgehog `Gen[Res]` that supplies responses during BFS exploration.
  It's *bypassed* when replaying against a real system — the real system's
  response is used instead.

Key emphasis: "**behaviour is pure.** No IO, no exceptions, no side effects.
This is what makes offline exploration cheap (thousands of transitions without
I/O) and what makes the kernel eligible for formal verification."

### In-depth analysis

The `expect` DSL: `expect(check).thenState((res, s) => ...)` is Accordant's
`Expect.That(p).ThenState(mutate)` translated to Scala 3. The `check` is a
`ResponseCheck[Res]` — an accumulating predicate `Res => ValidatedNel[SpecViolation, Unit]`.
The `ValidatedNel` means *every* failed check is accumulated, not just the
first — this is important for debugging: you see all the reasons a response was
rejected, not just one.

The `thenState` transition is **response-dependent**: `(Res, S) => S`. The
transition *sees the response*, so `Withdraw` can transition to a different
state depending on whether the response was `Success(newBalance)` or `NotFound`.
This is Accordant tutorial 03's key feature, preserved faithfully.

The `mock` is a Hedgehog `Gen`, not ScalaCheck's `Arbitrary`. Hedgehog has
integrated shrinking and no typeclass — you always write generators explicitly.
`hedgehog.Gen` is a Compile-scope dependency of `core` because `Operation.mock`
exposes it in its public type. This is deliberate: accordant4s *is* a testing
library.

### Anticipated questions

- **"Why is the mock on the operation, not on the spec?"** — Because the mock is
  operation-specific (each operation generates different responses). Putting it
  on the `Operation` case class means a single value carries everything the
  explorer needs: the name, the behaviour, and the response generator. The spec
  just registers operations.

- **"Could I use a different generator library?"** — In principle, but Hedgehog
  is wired into the public API (`Operation.mock` returns `hedgehog.Gen`). It was
  chosen over ScalaCheck for integrated shrinking and the absence of an
  `Arbitrary` typeclass. Changing it would be a breaking API change.

- **"What's `ValidatedNel`?"** — It's cats' `Validated[NonEmptyList[E], A]` — an
  applicative functor that accumulates errors instead of short-circuiting like
  `Either`. So if three checks fail, you get all three in the `NonEmptyList`,
  not just the first.

---

## Slide 5 — The Outcome ADT

### Talking points

> "An `Outcome` describes what a correct response looks like and what happens to
> the state. There are three variants:"

1. **`Same(check)`** — the check passes; the state doesn't change. Example: a
   read-only `GetAccount`.
2. **`Next(check, transition)`** — the check passes; the state advances via a
   *response-dependent* transition. Example: `Deposit` updates the balance.
3. **`OneOf(branches)`** — non-deterministic: the union of every passing branch.
   Example: `Withdraw` might succeed OR return not-found.

Click the toggle button to reveal the `OneOf` branch-tree card:
> "A `Withdraw` might succeed — the balance decreases — or the account might not
> exist, returning not-found. The oracle doesn't know which until it sees the
> actual response. So it keeps *both* branches alive as candidate states. This
> is the key to modelling non-determinism: instead of guessing, the oracle
> *forks* and carries all possibilities forward."

### In-depth analysis

`Outcome` is a GADT-like sealed enum (Scala 3 `enum`). The `OneOf` variant is
recursively nested — you can have `OneOf(Next, OneOf(Same, Next))` for complex
non-determinism trees. The `OutcomeEval.flatten` function turns any tree into
a flat list of `Branch(check, transition)` pairs, which `ProfileEval.allows`
then evaluates.

The `ResponseCheck[Res]` is `Res => ValidatedNel[SpecViolation, Unit]`. It's
just a predicate that accumulates failures. The `expect` DSL constructs these:
`expect(res => res.newBalance >= 0)` makes a check that passes when the new
balance is non-negative, and fails (accumulating a `CheckFailed` violation)
otherwise.

### Anticipated questions

- **"Can OneOf be nested arbitrarily deep?"** — Yes. `Outcome.OneOf` takes a
  `NonEmptyList[Outcome]`, and each branch can itself be a `OneOf`. The
  `OutcomeEval.flatten` function handles arbitrary depth recursively.

- **"How does the check work with pattern matching?"** — In Scala 3, the
  `ResponseCheck` can be a pattern-matching lambda:
  ```scala
  case WithdrawResponse.Success(b) if b >= 0 => ().validNel
  case _ => SpecViolation.CheckFailed(...).invalidNel
  ```
  This is how the bank fixtures work — it reads like a switch on the response
  variant.

- **"Why not just use a Boolean for the check?"** — Because a Boolean only tells
  you *that* it failed, not *why*. `ValidatedNel[SpecViolation, Unit]` carries
  structured, data-rich failure reasons (`CheckFailed(op, detail)`,
  `UnknownOperation(name)`, etc.) that are useful for debugging and for the
  munit failure message.

---

## Slide 6 — spec.allows: The Heart

### Talking points

> "This is it. The entire oracle. Six lines of code."

Walk through line by line:
1. Check if the operation is registered. If not → `Deviant(UnknownOperation)`.
2. Otherwise, delegate to `ProfileEval.allows` — the pure kernel.
3. The kernel flattens the outcome tree, filters branches that match the
   response, maps to next-states, deduplicates.
4. If any branch matches → `Conformant(survivingProfile)`.
5. If none match → `Deviant(every failed check, accumulated)`.

> "Two things to notice: First, `Conformant` returns the *surviving profile* —
> the set of next-states — which threads forward to the next step. Second,
> `Deviant` returns *every* failed atomic check, not just the first. This is
> the accumulation semantics — you see all the reasons at once."

> "Everything else in the library — exploration, generation, execution,
> linearizability — is a *consumer* of this one method."

### In-depth analysis

The `ProfileEval.allows` kernel (in `domain/Evaluation.scala`) is the formally
verified piece. It works as follows:
1. For each candidate state in the profile, evaluate `behaviour(candidate)` →
   flatten to a list of `Branch(check, transition)`.
2. Filter branches where `check(res).isValid` (the response passes the check).
3. Map surviving branches to next-states via `transition(res, candidate)`.
4. Eq-deduplicate the next-states.
5. If non-empty → `Conformant(StateProfile.of(survivors))`.
6. If empty → `Deviant(flat list of all failed atomic checks)`.

This algorithm is mirrored in `verified/OracleKernel.scala` (PureScala subset)
and proved correct with Stainless (9/9 VCs). A property test (`OracleModelBridgeTests`)
runs the real kernel and the Stainless mirror on identical inputs and asserts
they agree — drift fails CI.

### Anticipated questions

- **"What happens with multiple candidate states?"** — Each candidate is
  evaluated independently. If candidate A's branches pass for the response but
  candidate B's don't, the survivor set is just A's next-states. B is "pruned."
  If both pass, the union of their next-states is the new profile.

- **"Is this O(n) in the profile size?"** — Yes. For each candidate, flatten the
  outcome tree (O(branches)), filter (O(branches × check cost)), map (O(survivors)).
  Dedup is O(survivors²) in the worst case (it's a list-based dedup), but
  profiles are small in practice (usually 1–3 candidates).

- **"How are the violations structured?"** — `SpecViolation` is a sealed enum:
  `CheckFailed(op, detail)`, `UnknownOperation(name)`, `NoBranchMatched(op, branchFailures)`,
  `ProfileExhausted(op)`, and (from linearizability) `NotLinearizable(op, observedCount, orderingsTried)`.
  They're data, not strings — the munit integration renders them into the
  failure message.

---

## Slide 7 — State Profiles & Indefinite Failures

### Talking points

> "What happens when a timeout *may or may not have taken effect*? You sent the
> request, but you don't know if the server processed it before the timeout.
> The oracle can't know either — so it keeps *both* possibilities alive."

Point to the flow diagram: timeout → `StateProfile { balance=100, balance=0 }`
→ later observation collapses to one.

> "A `StateProfile` is a non-empty, deduplicated set of candidate states. The
> oracle evaluates *all* candidates, keeps the survivors, and carries them
> forward. A later observation — say, a successful read showing balance=0 —
> collapses the profile to the surviving branch."

> "Accordant calls this *indefinite failure*: a timeout you can't immediately
> resolve. Instead of guessing, the oracle forks the state and carries both
> possibilities. No false alarms, no missed races."

### In-depth analysis

`StateProfile[S]` is an opaque type wrapping `NonEmptyList[S]`. Emptiness is
unrepresentable — the only constructors are `one(state)` (single candidate) and
`of(NonEmptyList)` (deduplicated from a NEL). Equality is order-insensitive
(it's a set): `[A, B] == [B, A]`.

The deduplication uses `Eq[S]` (from `StateOps[S]`), not universal equality.
This is important because `BankState(Map(...))` uses `Eq.fromUniversalEquals`,
but a user could supply a custom `Eq` that considers semantically-equal states
equal even if their representation differs.

The profile-widening cost: each `OneOf` branch that passes *multiplies* the
profile by the number of distinct next-states. In practice, this stays small
(1–3 candidates) because most operations have deterministic outcomes. The
worst case is `OneOf` with many branches all passing on many candidates — but
the mock supplies realistic responses, and the BFS depth bound limits total
exploration.

### Anticipated questions

- **"Could the profile grow unboundedly?"** — In theory, yes — if every step
  doubles the profile. In practice, the BFS depth bound (`MaxDepth`, Iron
  `Positive`) caps total exploration, and most operations collapse the profile
  quickly (a deterministic response prunes all but one branch). The library
  hasn't needed a profile-size limit.

- **"How is this different from a probability distribution?"** — It's not
  probabilistic — it's *epistemic*. The oracle doesn't assign probabilities;
  it carries *all* possibilities that are consistent with the observations.
  It's more like a belief set than a distribution. This is the same idea as
  "possible worlds" in model checking.

- **"Can I inspect the profile?"** — Yes — `StateProfile` exposes `toList` and
  `toNonEmptyList`. The `ExecutionReport.DeviatesAt` carries the profile at the
  deviation point for diagnostics.

---

## Slide 8 — From Spec to Tests: The Pipeline

### Talking points

> "One Spec drives the entire pipeline. No hand-written tests, no separate
> models."

Walk through the vertical pipeline diagram top-to-bottom:
1. **Spec + InputSet** → the model + the operations to try
2. **GraphExplorer** → BFS over mock responses, taking transitions through
   `spec.allows` → every edge is oracle-conformant *by construction*
3. **StateGraph** → the discovered nodes (canonical states) and edges
4. **TestCaseGenerator** → picks paths (state coverage, transition coverage,
   random walk) from the graph
5. **TestCaseExecutor** → replays those paths against the *real* SUT, validating
   every response through the same oracle
6. **ExecutionReport** → `Passed(stepsRun)` or `DeviatesAt(step, violations, reproPath)`

> "Notice: the graph is built with *mocks*, but the replay uses *real* responses.
> The mocks built the graph; the oracle judges the real responses. This is
> simulate-then-execute: simulate cheaply, execute against reality."

### In-depth analysis

`GraphExplorer.explore` is a strict BFS that applies every call in the input
set to every frontier state, with the operation's `mock` supplying the response.
Transitions are taken *through* `spec.allows` — so a `Deviant` verdict records
no edge (the mock generated a non-conformant response, which can happen with
`OneOf` branching). Every recorded edge's target is an oracle survivor.

The `stream` variant is a lazy `fs2.Stream[Pure, Node]` for memory-efficient
exploration of large graphs. It shares the same `expandLevel` step as `explore`,
so they discover identical nodes in the same order.

`TestCaseGenerator` has three algorithms:
- **StateCoverage** — greedy path extension until every node is on some path
- **TransitionCoverage** — per-edge: shortest path to the edge's source + the edge
- **RandomWalk(seed, count)** — deterministic splitmix64-keyed walks

All paths are drawn only from `graph.edges` — no generated step can reference
a state or call absent from the graph.

### Anticipated questions

- **"How deep does the BFS go?"** — Bounded by `MaxDepth` (Iron `Positive`).
  You set it explicitly. The default in fixtures is 3–4. Deeper exploration
  finds more states but grows exponentially.

- **"What if the mock generates a non-conformant response?"** — Then `spec.allows`
  returns `Deviant`, and no edge is recorded. This is fine — it means that
  particular response isn't a valid transition from this state. The explorer
  just doesn't record it. The graph contains only oracle-conformant edges.

- **"Can I bring my own test cases?"** — Yes. `TestCaseExecutor.run` takes any
  `TestCase[S]`. You can hand-write cases, load them from JSON (via
  `TestCasePersistence`), or generate them. The executor doesn't care where
  the case came from.

---

## Slide 9 — The Bank Example: Withdraw with Branching

### Talking points

> "Let's make this concrete. Here's the `withdraw` operation — it uses `OneOf`
> because the outcome depends on whether the account exists and has sufficient
> funds."

Walk through the code:
- `expect.oneOf(...)` — two branches
- Branch 1 (success): `expect(res => res.newBalance >= 0).thenState(...)` — the
  new balance must be non-negative, and the state updates to the new balance
- Branch 2 (not-found): `expect(_ => WithdrawResponse.NotFound).sameState` —
  the response is `NotFound`, state unchanged

> "At runtime: during *offline exploration*, the mock decides the response based
> on the balance — if there's enough, it returns `Success`; otherwise `NotFound`.
> During *real replay*, the SUT returns the actual response, and `spec.allows`
> validates it against both branches."

> "If the SUT returns `Success(-1)` on an empty account, the success-check fails
> (newBalance < 0) AND the notFound-check fails (wrong variant). The verdict is
> `Deviant` with two accumulated violations — you see exactly why it's wrong."

### In-depth analysis

The `mock` for `withdraw` is state-dependent:
```scala
mock = (req, s) => s.accounts.get(req.id) match
  case Some(bal) if bal >= req.amount => Gen.constant(Success(bal - req.amount))
  case _ => Gen.constant(NotFound)
```
This means the mock always generates a *conformant* response for the current
state — which is why BFS exploration only records conformant edges. A faulty
SUT, by contrast, returns non-conformant responses, which the executor catches.

The `ResponseCheck` for the success branch is:
```scala
case WithdrawResponse.Success(b) if b >= BigDecimal(0) => ().validNel
case _ => SpecViolation.CheckFailed(withdrawName, "expected success").invalidNel
```
This is a pattern-matching partial function. The `if b >= 0` guard means a
`Success(-1)` response *fails* this check — but it also fails the not-found
check (it's not `NotFound`). So the `Deviant` verdict carries both failures.

### Anticipated questions

- **"What if the SUT returns a completely unexpected response type?"** — The
  `ResponseCheck` is total (it's a `Res => ValidatedNel`). If the response
  doesn't match any branch's check, all checks fail, and the verdict is `Deviant`
  with the accumulated failures. There's no "unhandled response" — every
  response is either conformant (some branch passes) or deviant (all fail).

- **"Could the two branches both pass for the same response?"** — In principle,
  yes — if both checks accept the same response. In that case, the profile
  widens to the union of both branches' next-states. The oracle carries both
  possibilities forward. This is correct: if the response is ambiguous, the
  oracle shouldn't arbitrarily pick one.

---

## Slide 10 — HTTP Binding: Transport as Data

### Talking points

> "A deployed service becomes a `SystemUnderTest[IO, S]` via http4s. But here's
> the design decision that makes the oracle work over HTTP: timeouts and
> connection failures don't throw — they surface as *response values*."

Point to the `TransportOutcome` enum:
- `Completed(status, body)` — a normal HTTP response
- `TimedOut` — the request timed out
- `ConnectionFailed(detail)` — connection refused, DNS failure, etc.

> "The mapper turns transport facts into domain responses. If the server times
> out, the mapper maps `TimedOut` to a domain response variant — like
> `WithdrawResponse.Timeout`. The oracle sees it as a normal response and can
> model it with `Outcome.OneOf`."

> "Why does this matter? Because Accordant's indefinite-failure modeling
> requires the oracle to *see* timeouts as responses. If transport errors threw,
> `OneOf(timeout→Same, timeout→Next)` would be untestable over HTTP."

### In-depth analysis

The `Http4sSut.send` method wraps the http4s `Client.run(request).use(...)` with
a `.timeoutTo(timeout, IO.pure(TimedOut)).handleError(e => ConnectionFailed(...))`.
This means:
- A normal response → `Completed(status, body)` → mapper decodes → domain `Res`
- A timeout → `TimedOut` → mapper maps to a domain response variant
- A connection error → `ConnectionFailed(detail)` → mapper maps to a domain variant

The mapper is the user's responsibility: the library calls it with the
`TransportOutcome` and the mapper decides which `Res` variant to return. This
is the `HttpResponseMapper[Res]` trait — a total mapping
`TransportOutcome => IO[Res]`.

The `HttpBinding.check` is a total constructor-time validator: against a
`Spec[S]`, it returns `Left(unboundNames)` if any registered operation lacks a
route. Unbound operations fail at *wiring time*, never mid-replay.

### Anticipated questions

- **"How do you handle the existential type across operations?"** — The
  `Endpoint[S]` is an existential slot (`type Req; type Res`) built from a
  concrete `Operation[Req, Res, S]`. The one `asInstanceOf` at the existential
  boundary is sound by the name-keyed lookup invariant (the call was looked up
  by `op.name`, so `call.Req =:= Req`). The http4s module has a targeted
  WartRemover exemption for this.

- **"What about retries?"** — `MaxRetryCount` (Iron `Positive`) bounds idempotent
  retries. The current implementation uses a single attempt; the parameter is a
  future hook for retry logic.

- **"Does it support non-HTTP protocols?"** — The `SystemUnderTest[F, S]` trait
  is transport-agnostic. The http4s module is one binding; you could write a
  gRPC or WebSocket binding the same way. The spec's `SmithyHttpBinding`
  (deferred) would derive the HTTP binding from Smithy HTTP traits.

---

## Slide 11 — Smithy4s: The Contract IS the Oracle

### Talking points

> "Here's something Accordant couldn't do: derive the spec directly from the
> Smithy IDL. With smithy4s, your service definition generates typed Scala types.
> accordant4s derives `Operation` slots from those types — so the behavioural
> spec and the implementation share the Smithy IDL as a single source of truth.
> No drift."

Point to the horizontal flow: `TestBank.smithy` → smithy4s codegen →
`SmithyOps.forService` → `SpecBuilder.build`.

> "The key feature is *complete-or-fail*: a new Smithy operation breaks the spec
> build until you write the rule. The oracle cannot silently drift from the
> contract."

### In-depth analysis

`SmithyOps.forService` takes a smithy4s `Service[Alg]` (the generated companion
object, e.g. `TestBankGen`) and enumerates its `endpoints` — yielding one
`EndpointSlot` per endpoint, named by the ShapeId.

`SpecBuilder` is the typed entry: `assign[Req, Res](name, behaviour, mock)`
attaches a behaviour to a slot. The `build` method is complete-or-fail:
`Right(Spec[S])` iff every endpoint received a behaviour; otherwise
`Left(NonEmptyList[OperationName])` listing the missing names.

The deferred Requirement 3 (`SmithyHttpBinding`) would derive `HttpRoute`/entity
codecs from the Smithy HTTP traits (`@http(method, uri)`), bridging directly to
`Http4sSut`. It was split to a follow-up because it builds on the http4s
existential-Endpoint bridge (the riskiest type-level piece).

### Anticipated questions

- **"Does this mean I write my spec in Smithy?"** — No. You write your *service
  contract* in Smithy (which you'd do anyway for API definitions). accordant4s
  derives the *operation slots* (names + input/output types) from the contract.
  You still write the *behaviours* (the oracle rules) in Scala. The derivation
  ensures every Smithy operation has a corresponding oracle rule — no silent
  gaps.

- **"What if I don't use Smithy?"** — The smithy4s module is optional. The core
  library works fine without it — you register operations manually via
  `Spec.register`. The smithy4s module is for teams that already use Smithy for
  API definitions and want the contract/oracle alignment.

- **"What about the compile-time type safety?"** — The `SpecBuilder.assign`
  method is parameterized by `[Req, Res]`. When you assign a behaviour typed
  against the smithy4s-generated `WithdrawInput`, the types align at the call
  site. A wrong-typed assignment is a compile error — the spec's
  "compile-time evidence" claim.

---

## Slide 12 — Linearizability

### Talking points

> "Now the hardest problem: concurrent testing. When operations run in parallel,
> can we still check correctness?"

Point to the prefix→parallel→suffix shape:
> "A concurrent test case has a sequential prefix (set up state), a parallel
> section (race operations concurrently), and a suffix (observe the result)."

> "The question is: does *some* sequential ordering of the parallel operations
> explain the observed responses?"

Walk through the two verdicts:
- **Linearizable**: Alice→200, Bob→409. Explained by: Alice booked first
  (success), Bob got a conflict. The checker finds the witness `[Alice, Bob]`.
- **Race Detected**: Alice→200, Bob→200. No ordering explains both succeeding
  on the same slot. The checker returns `None` → `RaceDetected`.

> "The permutation search is a *pure function* — no IO, no randomness. It folds
> `spec.allows` over each candidate ordering, threading the state profile.
> Bounded by `ParallelWidth ≤ 4` — that's an Iron type constraint, not a runtime
> guard. 4! = 24 permutations max."

### In-depth analysis

`Linearization.findOrdering` enumerates all permutations of the observed
`(call, response)` pairs. For each permutation, it folds `spec.allows` —
re-invoking the oracle for each step, threading the surviving profile. A
permutation is a "witness" iff all steps stay `Conformant`.

When multiple permutations are conformant (ambiguity), the resulting profile is
the *deduplicated union* of all witness end-profiles. The suffix's oracle
validation runs against this union — no false alarms from picking one winner
arbitrarily. This is the "ambiguity flows into the profile" scenario.

`ConcurrentExecutor.run` replays the prefix sequentially (oracle-validated),
launches the parallel calls via `parSequence` (cats-effect concurrent
execution — no imposed ordering), captures `ObservedResult`s, checks
linearizability, then replays the suffix.

The `ParallelWidth ≤ 4` bound is enforced at the type level: `ParallelWidth`
is `Int :| Positive & LessEqual[4]`. `ParallelWidth(5)` and `ParallelWidth(0)`
don't compile (compile-negative test). The n! permutation blow-up is capped at
24 — manageable for brute force.

### Anticipated questions

- **"4! = 24 is very small. What if I need wider parallelism?"** — The bound
  exists because brute-force permutation search is O(n!). At n=5 it's 120, at
  n=10 it's 3.6M. The bound keeps the checker tractable. For wider parallelism,
  you'd need a smarter search (e.g. partial-order reduction or a SAT solver).
  That's future work. In practice, the most interesting races are between 2–4
  operations.

- **"How does this compare to Jepsen/Knossos?"** — Jepsen uses external checkers
  (Knossos/WGL) that model the system's consistency as a register. accordant4s
  uses the *same spec oracle* that drives sequential testing — no separate
  model. The trade-off: accordant4s checks against *your* spec (which may be
  weaker or stronger than linearizability), while Jepsen checks against a fixed
  consistency model. They're complementary: Jepsen is a black-box system-level
  checker; accordant4s is a white-box spec-level checker.

- **"Can the checker have false positives?"** — No. If the checker returns
  `Linearizable(witness)`, the witness is a real permutation that folds
  conformant. The "witness validity" property test verifies this. If the checker
  returns `None`, the "exhaustiveness on rejection" property verifies every
  permutation deviates.

- **"What's `ObservedResult`?"** — It's a `(call, response)` pair where the
  response is path-dependent (`response: call.Res`). It uses the same sealed-trait
  + abstract-member pattern as `OperationCall` — the existential `Res` is
  recovered through the call's own `op` path when the checker re-invokes
  `spec.allows`. Cast-free.

---

## Slide 13 — Formally Verified

### Talking points

> "The oracle kernel isn't just tested — it's *proved correct* with Stainless,
> a formal verifier for Scala."

Two pieces:
1. **The Stainless mirror** (`OracleKernel.scala`, 70 lines, PureScala subset):
   mirrors the survivor/verdict algorithm over `BigInt`. Stainless proves 9/9
   VCs, including "conformant iff some branch passed" and "the survivor set is
   deduplicated."

2. **The bridge test** (`OracleModelBridgeTests`): runs the real
   `ProfileEval.allows` and the Stainless `OracleKernel.survivors` on identical
   generated inputs and asserts they agree. Drift fails CI.

> "Why is this a big deal? Accordant (.NET) has runtime-only checking. accordant4s
> has a formally proved kernel — stronger than any test suite. The oracle that
> judges your system is itself verified."

> "And the oracle is *pure* — no IO, no side effects — which is what makes it
> verification-eligible."

### In-depth analysis

Stainless works on a PureScala subset — no `IO`, no `Gen`, no `fs2`, no
higher-kinded types. The oracle kernel was placed in `domain` (the pure layer)
specifically so it could be mirrored in PureScala. The `verified` module is
pinned to Scala 3.7.2 (Stainless's bundled frontend supports 3.7.2, not 3.8.x)
with relaxed scalac options and no WartRemover.

The 9/9 VCs include:
- `distinctEmptyIff`: the dedup function returns `Nil` iff the input has no
  unique elements
- `conformantIffSomeBranchPassed`: the verdict is `Conformant` iff at least one
  branch's check passes for at least one candidate
- `survivorsNonEmptyIffConformant`: the survivor set is non-empty iff the verdict
  is `Conformant`

The bridge test is the critical link: without it, the Stainless model could
drift from production. The property test runs on Hedgehog-generated inputs
(varied states, outcomes, responses) and asserts the real kernel and the model
agree on both the conformance verdict and the survivor cardinality. If anyone
changes the production algorithm without updating the model, CI fails.

### Anticipated questions

- **"Why not verify the production code directly?"** — Stainless can't handle
  cats-effect, fs2, Hedgehog, Iron, or Scala 3.8.x. The production kernel uses
  `NonEmptyList`, `ValidatedNel`, `Eq` — none of which are PureScala. So we
  mirror the *algorithm* in PureScala (over `BigInt` and `List`) and prove the
  mirror. The bridge test ties the mirror to production mechanically.

- **"What does 'best-effort' mean for Ring 6?"** — Stainless may reject a VC
  if it's too complex (quantifiers, non-linear arithmetic). We keep VCs
  quantifier-free (z3 is single-threaded with no default timeout — a
  `forall/exists` VC hangs unbounded). If a VC fails, we record the downgrade
  and rely on the Ring 3 property tests + Ring 8 adversarial review.

- **"Is the whole library verified?"** — No. Only the oracle kernel
  (`OutcomeEval`/`ProfileEval`) is mirrored and proved. The engine (exploration,
  generation, execution) and integration modules (http4s, smithy4s) are tested
  via properties and adversarial review. The kernel is the highest-risk piece
  (a bug there silently mis-verdicts every system tested with it), so it gets
  the strongest verification.

---

## Slide 14 — Recap

### Talking points

> "To recap: accordant4s is 8 specs, 99 tests, 4 sbt modules — all passing."

Briefly mention the modules:
- **core** — the oracle kernel (`domain`), the `Spec`/`Operation` API (`spec`),
  and the engine (exploration, generation, execution, linearizability)
- **munit** — `AccordantSuite`: one munit test per generated `TestCase`
- **http4s** — http4s `Client[IO]` SUT binding
- **smithy4s** — derive `Spec` from Smithy IDL

> "The oracle doesn't just *test* your system — it *judges* it. Every response,
  every transition, every concurrent interleaving is checked against the same
  pure, formally verified model."

### Closing line

> "If you want to try it, the repo is at [your GitHub link]. The README has a
> quick start. Thanks."

### Anticipated questions

- **"Is this production-ready?"** — It's a library prototype (0.1.0-SNAPSHOT).
  The core oracle and engine are stable and well-tested. The integration
  modules (http4s, smithy4s) are functional but have deferred features (HTTP
  trait derivation from Smithy). It's suitable for evaluation and pilot projects.

- **"What's the performance like?"** — Offline BFS exploration is fast (thousands
  of transitions per second — pure functions, no I/O). Real replay depends on
  the SUT's latency. The linearizability checker is bounded at 24 permutations,
  so it's instant. The main cost is the SUT itself, not the oracle.

- **"How do I contribute?"** — The project uses an OpenSpec workflow: specs are
  the source of truth, implementation is spec-by-spec through a verification-ring
  pipeline (typed contract → test oracle → implementation → rings → adversarial
  review → checkpoint). See `AGENTS.md` and `openspec/changes/` for the workflow.

- **"What's next?"** — Deferred features: the smithy4s HTTP binding derivation
  (Req 3), async/background-job step functions, state-graph visualization,
  an effect-aware `IO` oracle, Jepsen/Knossos interop. These are documented as
  follow-up changes.

---

## General Q&A Preparation

### If asked "why should I care?"

> "Because the spec is the single source of truth. You write it once, and it
> drives exploration, generation, replay, and linearizability — without writing
> a single test case by hand. And the oracle is formally verified, so the thing
> judging your code is itself proved correct."

### If asked about real-world usage

> "The primary use case is validating services against a behavioural spec —
> especially AI-generated implementations. You give an agent the spec, it
> generates the code, and accordant4s checks every response against the spec.
> The 'deviates at step N' feedback is exactly what the agent needs to fix its
> output."

### If asked about limitations

> "The oracle is pure — it can't model side effects beyond the state machine
> (no database queries, no external API calls in the behaviour). The linearizability
> checker is bounded at 4-way parallelism. The HTTP binding is functional but
> the Smithy HTTP-trait derivation is deferred. And the whole thing is a
> prototype — use it to evaluate the approach, not in production tomorrow."

### Timing guide

| Section | Slides | Time |
|---------|--------|------|
| Intro + problem + idea | 1–3 | 5 min |
| Core concepts (Spec, Outcome, allows, profiles) | 4–7 | 10 min |
| Pipeline + bank example | 8–9 | 5 min |
| Integrations (HTTP, Smithy, concurrency) | 10–12 | 7 min |
| Formal verification + recap | 13–14 | 3 min |
| Q&A | — | 10–15 min |
| **Total** | | **30–45 min** |
