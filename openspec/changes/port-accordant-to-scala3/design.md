# Design: Port Microsoft Accordant to Scala 3 (accordant4s)

## Accordant → accordant4s Translation Map

The port is concept-faithful but idiom-native. Authoritative mapping (from
`docs/accordant-scala3-report.md` §3.1, verified against microsoft.github.io/accordant
and deepwiki.com/microsoft/accordant):

| .NET Accordant | accordant4s | Notes |
|---|---|---|
| `[State]` + Roslyn `Clone()`/equality codegen | immutable `case class` + `derives Eq, Hash, Show, CanEqual` | no codegen needed; `copy` replaces mutation |
| `Spec<TState>.Operation<TReq,TRes>(name, lambda)` | `Spec[S].register(Operation[Req, Res, S])` | typed handle, no string-keyed casts at use sites |
| `Expect.That(p).SameState()` | `Outcome.Same(check)` | `check: ResponseCheck[Res]` |
| `Expect.That(p).ThenState(mutate)` | `Outcome.Next(check, (res, s) => s.copy(...))` | transition sees the **response** (response-dependent state, tutorial 03) |
| `.ThenState(lambda, mock: ...)` | `Operation.mock: (Req, S) => Gen[Res]` | mock lives on the operation; used only during offline exploration |
| `Expect.OneOf(...)` | `Outcome.OneOf(NonEmptyList[Outcome])` | indefinite failures (how-to: indefinite-failures) |
| state profile (set of candidate states) | `StateProfile[S]` — `Eq`-deduplicated non-empty list | `allows` evaluates all candidates, keeps survivors |
| `spec.Allows(op, req, res, state)` → `(bool, msg, next)` | `spec.allows(op, req, res, profile): Verdict[S]` | `Conformant(profile)` \| `Deviant(NonEmptyList[SpecViolation])` — accumulating, not first-failure |
| `StateGraph` + `TestCaseGenerator` (MaxDepth, StateCoverage/TransitionCoverage/RandomWalk) | `GraphExplorer` (pure BFS, fs2 facade) + `TestCaseGenerator` | same three algorithms, same depth bound |
| `InputSet` + `op.With(req, label)` / `Accordant.Choose` | `InputSet[S]` + `op.withInput(req, label)` + `InputSet.fromGen` | ScalaCheck `Gen` replaces `Choose.Each<T>()` |
| `SystemChecker` / `TestCaseExecutor` (+ `BeforeEachAsync`/`AfterEachAsync`) | `TestCaseExecutor.run(testCase, sut)` with `Resource`/bracket hooks | hooks are bracket-safe by construction |
| `ConcurrentTestCaseFileRecord` JSON persistence | circe codecs for `TestCase`/`ConcurrentTestCase` | roundtrip-property-tested |
| `GenerateConcurrentTests` + linearizability check | `ConcurrentTestCase` (prefix/parallel/suffix) + `LinearizabilityChecker` | ∃-permutation search, pure kernel |
| `HttpExecutable` | `accordant4s-http4s`: `Client[IO]` + circe → `SystemUnderTest[IO]` | |
| — (no .NET equivalent) | `accordant4s-smithy4s`: derive `Operation` slots from a smithy4s `Service` | net gain over the original (report §6.5) |

Deliberately dropped: Roslyn source generator (obsolete under immutability), state
visualization CLI, async step functions (out of scope, see proposal).

## Package Structure

Base package: `io.gruggiero.accordant4s`.

### sbt Modules

The single-module build is restructured into a multi-project build (spec 1 does this),
mirroring Accordant's assembly split so users only depend on what they use:

| Module | Artifact | Depends on | Contents |
|--------|----------|-----------|----------|
| `core` | `accordant4s-core` | cats, cats-effect, fs2-core, iron + iron-cats, scalacheck, circe-core/parser | domain ADTs, Spec/oracle, graph, generation, execution, linearizability, persistence |
| `munit` | `accordant4s-munit` | core, munit, munit-cats-effect (Compile scope) | `AccordantSuite` — emits generated test cases as munit tests |
| `http4s` | `accordant4s-http4s` | core, http4s-client, http4s-circe | HTTP `SystemUnderTest[IO]` binding |
| `smithy4s` | `accordant4s-smithy4s` | core, smithy4s-core | `Operation` derivation from Smithy services |

ScalaCheck and circe at Compile scope in `core` is deliberate: accordant4s *is* a
testing library — `Gen` powers response mocks and input sets; circe powers test-case
persistence. This is recorded here so Ring 1.5 reviews don't flag it.

### Layers (Ring 1.5 LayerDependencies config)

| Layer | Package | Depends On | Ring 1.5 Rule |
|-------|---------|-----------|---------------|
| Domain | `io.gruggiero.accordant4s.domain` | Nothing (cats/iron data types only) | no outbound project imports |
| Spec (service) | `io.gruggiero.accordant4s.spec` | Domain | `allowed: { from: spec, to: [domain] }` |
| Engine | `io.gruggiero.accordant4s.engine` | Domain, Spec | `allowed: { from: engine, to: [domain, spec] }` |
| Persistence | `io.gruggiero.accordant4s.persist` | Domain | `allowed: { from: persist, to: [domain] }` |
| Integrations | `…accordant4s.munit` / `…http` / `…smithy` | all of the above | separate sbt modules (enforced by build, not Scalafix) |

### New Packages

| Package | Layer | Purpose |
|---------|-------|---------|
| `domain` | Domain | `Outcome`, `Verdict`, `SpecViolation`, `StateProfile`, `OperationName`, `CallLabel`, `MaxDepth`, `TestCase`, `ConcurrentTestCase`, `ExecutionReport`, `CoverageAlgorithm` |
| `spec` | Spec | `Operation`, `Spec`, `OperationCall`, `InputSet`, the `expect` constructor DSL |
| `engine` | Engine | `GraphExplorer`, `StateGraph`, `TestCaseGenerator`, `TestCaseExecutor`, `LinearizabilityChecker`, `SystemUnderTest` |
| `engine.verified` | Engine (pure) | Ring 4 kernels: outcome evaluation, permutation search — Stainless-compatible subset, no fs2/IO imports |
| `persist` | Persistence | circe codecs for `TestCase`/`ConcurrentTestCase` file records |

## Effect Boundaries

**The oracle is pure.** Accordant's `Allows` is synchronous; the report (§5) floats an
effect-aware `IO` oracle as a gain, but a pure oracle is what makes offline BFS
exploration cheap (simulate thousands of transitions without I/O), keeps the kernel
Stainless-eligible (Ring 4), and keeps verdicts deterministic. Decision: **pure oracle
now**; an `F[_]`-oracle variant is a future change (would only affect `Spec.allows`'s
signature via a `SpecF[F, S]` wrapper, not the ADTs).

### Pure Code (Ring 4 candidates)

| Module / Function | Purpose | Ring 4? |
|-------------------|---------|---------|
| `engine.verified.OutcomeEval.evaluate(outcome, res, state)` | match one outcome against one (response, state) | Yes |
| `engine.verified.ProfileEval.allows(branches, res, profile)` | survivor-set computation over a profile | Yes |
| `engine.verified.Linearization.exists(perms, step)` | ∃-permutation search (bounded) | Yes (best-effort) |
| `domain.*` smart constructors | Iron-validated construction | No (trivial, covered by Ring 0) |
| `engine.GraphExplorer.bfs` (pure tail-recursive core) | bounded BFS over `(Spec, InputSet)` | No (uses `Gen` sampling for mocks — not PureScala) |
| `engine.TestCaseGenerator.*` | path selection over a materialized graph | No (RandomWalk uses seeded RNG; Ring 2/3 cover it) |

### Effectful Code (F[_] wrapped)

| Module / Trait | Effect Type | Purpose |
|----------------|-------------|---------|
| `engine.SystemUnderTest[F[_]]` | `F: Async` (tagless final) | `execute(call): F[AnyRes]`, `reset: F[Unit]` — the only trait users implement to connect a real system |
| `engine.TestCaseExecutor` | `IO` (via `F: Async`) | replays a `TestCase`, validates each step with the pure oracle, brackets before/after hooks |
| `engine.GraphExplorer.stream` | `fs2.Stream[F, Node[S]]` | lazy facade over the pure BFS for incremental/bounded exploration |
| `engine.ConcurrentExecutor` | `IO` | `parTraverse`/`IO.both` of the parallel section, result collection |
| `http.Http4sSut` | `IO` | `Client[IO]`-backed `SystemUnderTest` |
| `munit.AccordantSuite` | `IO` | munit-cats-effect glue |

## Iron Type Strategy

### New Refined Types

| Type | Underlying | Constraint | Rationale |
|------|-----------|------------|-----------|
| `OperationName` | `String` | `Not[Blank]` | registry key; appears in verdicts, persisted files |
| `CallLabel` | `String` | `Not[Blank]` | human-readable step identity in reports and persisted test cases |
| `MaxDepth` | `Int` | `Positive` | mandatory BFS bound — makes "unbounded exploration" unrepresentable |
| `ParallelWidth` | `Int` | `Positive & LessEqual[4]` | caps `n!` permutation search (4! = 24 orderings worst case) |
| `MaxRetryCount` | `Int` | `Positive` | executor retry bound (HTTP binding) |

### Types Kept as Plain

| Type | Why Not Refined |
|------|----------------|
| `S` (user state) | user-supplied; library requires `Eq[S]`, `Hash[S]`, `Show[S]` givens instead — constraints belong to the user's domain |
| `Req` / `Res` payloads | user-supplied operation types; smithy4s/Iron constraints live in user code |
| violation messages (`String`) | human-readable diagnostics, no structural constraint |
| `seed: Long` (RandomWalk) | any value valid |

## Core Type Sketches (normative for the specs)

```scala
type ResponseCheck[Res] = Res => ValidatedNel[SpecViolation, Unit]

enum Outcome[Res, S]:
  case Same(check: ResponseCheck[Res])
  case Next(check: ResponseCheck[Res], transition: (Res, S) => S)
  case OneOf(branches: NonEmptyList[Outcome[Res, S]])

final case class Operation[Req, Res, S](
  name:      OperationName,
  behaviour: (Req, S) => Outcome[Res, S],   // pure oracle entry
  mock:      (Req, S) => Gen[Res],          // exploration only; bypassed at execution
)

sealed trait OperationCall[S]:               // existential pairing — hides Req/Res
  type Req; type Res
  def op: Operation[Req, Res, S]
  def req: Req
  def label: CallLabel

opaque type StateProfile[S] = NonEmptyList[S]  // Eq-deduplicated candidate states

enum Verdict[S]:
  case Conformant(profile: StateProfile[S])
  case Deviant(violations: NonEmptyList[SpecViolation])

final case class Spec[S](operations: Map[OperationName, OperationCall.Slot[S]]):
  def allows[Req, Res](op: Operation[Req, Res, S], req: Req, res: Res,
                       profile: StateProfile[S]): Verdict[S]
```

`allows` semantics (normative, mirrors Accordant's state-profile behaviour): for each
candidate state in the profile, evaluate the operation's outcome tree; collect every
next-state from branches whose check validates; dedup with `Eq[S]`. Non-empty survivor
set → `Conformant`; empty → `Deviant` carrying the **accumulated** violations of all
failed branches (this is the `ValidatedNel` upgrade over .NET's single message).

**strictEquality note**: the build enables `-language:strictEquality`. All domain ADTs
`derive CanEqual`; user state `S` requires `CanEqual[S, S]` via the `Eq[S]`-adjacent
given bundle `StateOps[S]` (one implicit bundle: `Eq[S], Hash[S], Show[S], CanEqual`).

## Smithy Model Layout

accordant4s defines **no Smithy models of its own** (Ring 5 not applicable). The
`smithy4s` module *consumes* user services:

| Service | Operations | Smithy File |
|---------|-----------|-------------|
| user-supplied `Service[Alg]` | each smithy4s endpoint → one `Operation[Req, Res, S]` slot (name from the Smithy operation id; Req/Res from the endpoint's input/output schemas) | user project |

Derivation surface: `SmithyOps.operations[Alg, S](service)(behaviours, mocks)` returns
the typed slots; registering them all yields a spec whose operation set provably equals
the service's endpoint set (a Ring 2 property of the smithy4s-derivation spec). The
test fixture defines a small `TestBank` Smithy service under `smithy4s/src/test/smithy/`.

## Error Strategy

### Error Modeling

| Error Enum | Variants | Used By |
|------------|----------|---------|
| `SpecViolation` | `CheckFailed(op, label, detail)`, `UnknownOperation(name)`, `NoBranchMatched(op, branchFailures)`, `ProfileExhausted(op)`, `NotLinearizable(triedOrderings)` | oracle, executor, checker |
| `ExecutionReport[S]` | `Passed(steps)`, `DeviatesAt(stepIndex, violations, reproPath)` | executor, munit suite |
| `PersistenceError` | `DecodeFailed(circeError)`, `VersionMismatch(found, expected)` | persist |

All variants are data-carrying. `Verdict.Deviant` is **not** an exception: the oracle
never throws (DisableSyntax enforces this anyway).

### Error Propagation

| Boundary | Pattern | Example |
|----------|---------|---------|
| Pure → Pure | `ValidatedNel[SpecViolation, *]` inside outcome eval; `Verdict` at oracle surface | `allows(...): Verdict[S]` |
| Pure → Effect | executor folds verdicts; deviation short-circuits the replay via `Either` in `tailRecM` | `TestCaseExecutor.run: F[ExecutionReport[S]]` |
| SUT failures | transport errors surface as a `Res` variant the user's check can match (timeouts are *data* — required for `OneOf` indefinite-failure modeling), never as `F.raiseError` mid-protocol | `Http4sSut` maps status/timeout to response ADT |
| Persistence | `Either[PersistenceError, TestCase[S]]` | `TestCasePersistence.load` |

## Verification Map

| Module | Ring 0 | Ring 1 | Ring 1.5 | Ring 2 | Ring 3 | Ring 4 | Ring 5 |
|--------|--------|--------|----------|--------|--------|--------|--------|
| `domain` (ADTs, Iron types) | ✅ | ✅ | ✅ | ✅ (constructors, profile dedup) | — | — | — |
| `spec` (Operation, Spec, InputSet, DSL) | ✅ | ✅ | ✅ | ✅ | ✅ 80% | — | — |
| `engine.verified` (oracle kernel) | ✅ | ✅ | ✅ | ✅ | ✅ 80% | ✅ | — |
| `engine` graph + generation | ✅ | ✅ | ✅ | ✅ | ✅ 80% | — | — |
| `engine` executor | ✅ | ✅ | ✅ | ✅ | — | — | — (otel4s: future) |
| `engine.verified` linearization | ✅ | ✅ | ✅ | ✅ | ✅ 80% | ✅ best-effort | — |
| `persist` | ✅ | ✅ | ✅ | ✅ (roundtrip) | — | — | — |
| `munit` module | ✅ | ✅ | — | ✅ | — | — | — |
| `http4s` module | ✅ | ✅ | — | ✅ (stubbed client) | — | — | — |
| `smithy4s` module | ✅ | ✅ | — | ✅ | — | — | — |

## Build Changes (spec 1, Ring 0 prerequisite)

- Restructure `build.sbt` into the four modules above (`core`, `munit`, `http4s`, `smithy4s`).
- `project/Dependencies.scala` additions (versions per `openspec/config.yaml`):
  iron + iron-cats 2.6.x (+ iron-scalacheck % Test), circe-core/generic/parser 0.14.x,
  http4s-client/http4s-circe 0.23.x, smithy4s-core 0.18.x (+ sbt plugin for the test
  fixture), scalacheck moved to Compile scope in `core`, munit/munit-cats-effect to
  Compile scope in the `munit` module.
- Add Scalafix (`.scalafix.conf`: DisableSyntax, RemoveUnused, OrganizeImports) and
  WartRemover settings, and Stryker4s config (`stryker4s.conf`, threshold 80%) — the
  rings the schema assumes.
- Keep `-Werror -Wunused:all -Wvalue-discard -language:strictEquality`.
