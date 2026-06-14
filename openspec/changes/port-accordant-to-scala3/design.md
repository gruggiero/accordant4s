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
| `.ThenState(lambda, mock: ...)` | `Operation.mock: (Req, S) => Gen[Res]` (`hedgehog.Gen`) | mock lives on the operation; used only during offline exploration |
| `Expect.OneOf(...)` | `Outcome.OneOf(NonEmptyList[Outcome])` | indefinite failures (how-to: indefinite-failures) |
| state profile (set of candidate states) | `StateProfile[S]` — `Eq`-deduplicated non-empty list | `allows` evaluates all candidates, keeps survivors |
| `spec.Allows(op, req, res, state)` → `(bool, msg, next)` | `spec.allows(op, req, res, profile): Verdict[S]` | `Conformant(profile)` \| `Deviant(NonEmptyList[SpecViolation])` — accumulating, not first-failure |
| `StateGraph` + `TestCaseGenerator` (MaxDepth, StateCoverage/TransitionCoverage/RandomWalk) | `GraphExplorer` (pure BFS, fs2 facade) + `TestCaseGenerator` | same three algorithms, same depth bound |
| `InputSet` + `op.With(req, label)` / `Accordant.Choose` | `InputSet[S]` + `op.withInput(req, label)` + `InputSet.fromGen` | Hedgehog `Gen` replaces `Choose.Each<T>()` |
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
| `core` | `accordant4s-core` | cats, cats-effect, fs2-core, iron + iron-cats, hedgehog-core, circe-core/parser | domain ADTs, Spec/oracle, graph, generation, execution, linearizability, persistence |
| `munit` | `accordant4s-munit` | core, munit, munit-cats-effect (Compile scope) | `AccordantSuite` — emits generated test cases as munit tests |
| `http4s` | `accordant4s-http4s` | core, http4s-client, http4s-circe | HTTP `SystemUnderTest[IO]` binding |
| `smithy4s` | `accordant4s-smithy4s` | core, smithy4s-core | `Operation` derivation from Smithy services |

Hedgehog (hedgehog-core) and circe at Compile scope in `core` is deliberate:
accordant4s *is* a testing library — `hedgehog.Gen` powers response mocks and input
sets; circe powers test-case persistence. This is recorded here so Ring 2
(architecture) reviews don't flag it. Hedgehog has no `Arbitrary` typeclass, so all
generators (including refined-type ones, via raw value → smart constructor) are
written explicitly.

### Layers (Ring 2 LayerDependencies config)

| Layer | Package | Depends On | Ring 2 Rule |
|-------|---------|-----------|---------------|
| Domain | `io.gruggiero.accordant4s.domain` | Nothing (cats/iron data types only) | no outbound project imports |
| Spec (service) | `io.gruggiero.accordant4s.spec` | Domain | `allowed: { from: spec, to: [domain] }` |
| Engine | `io.gruggiero.accordant4s.engine` | Domain, Spec | `allowed: { from: engine, to: [domain, spec] }` |
| Persistence | `io.gruggiero.accordant4s.persist` | Domain | `allowed: { from: persist, to: [domain] }` |
| Integrations | `…accordant4s.munit` / `…http` / `…smithy` | all of the above | separate sbt modules (enforced by build, not Scalafix) |

### New Packages

| Package | Layer | Purpose |
|---------|-------|---------|
| `domain` | Domain | `Outcome`, `Verdict`, `SpecViolation`, `StateProfile`, `StateOps`, `ResponseCheck`, `OperationName`, `CallLabel`, the pure oracle kernel `OutcomeEval`/`ProfileEval` (Option A), `MaxDepth`, `TestCase`, `ConcurrentTestCase`, `ExecutionReport`, `CoverageAlgorithm` |
| `spec` | Spec | `Operation`, `Spec`, `OperationCall`, `InputSet`, the `expect` constructor DSL |
| `engine` | Engine | `GraphExplorer`, `StateGraph`, `TestCaseGenerator`, `TestCaseExecutor`, `LinearizabilityChecker`, `SystemUnderTest` |
| `engine.verified` | Engine (pure) | Ring 6 kernels that legitimately depend on `spec` (e.g. the linearizability permutation search) — Stainless-compatible subset, no fs2/IO. The oracle-core kernel lives in `domain` instead (Option A) so `spec` can use it. |
| `persist` | Persistence | circe codecs for `TestCase`/`ConcurrentTestCase` file records |

## Effect Boundaries

**The oracle is pure.** Accordant's `Allows` is synchronous; the report (§5) floats an
effect-aware `IO` oracle as a gain, but a pure oracle is what makes offline BFS
exploration cheap (simulate thousands of transitions without I/O), keeps the kernel
Stainless-eligible (Ring 6), and keeps verdicts deterministic. Decision: **pure oracle
now**; an `F[_]`-oracle variant is a future change (would only affect `Spec.allows`'s
signature via a `SpecF[F, S]` wrapper, not the ADTs).

### Pure Code (Ring 6 candidates)

| Module / Function | Purpose | Ring 6? |
|-------------------|---------|---------|
| `domain.OutcomeEval.{flatten,survivors}` | flatten an outcome tree / survivor states for a (response, candidates) | Yes |
| `domain.ProfileEval.allows(name, behaviour, res, profile)` | survivor-set computation over a profile | Yes |
| `engine.verified.Linearization.exists(perms, step)` | ∃-permutation search (bounded) | Yes (best-effort) |

> **oracle-core implementation decision (Option A, human-approved).** The pure
> oracle kernel was placed in **`domain`** (not `engine.verified`) so that
> `spec.Spec.allows` can delegate to it while keeping the layer arrow strictly
> one-directional (`engine → spec → domain`). Putting it in `engine.verified`
> would have forced `spec → engine.verified` (and a `spec ↔ engine.verified`
> cycle, since the kernel needs `Operation`), violating the Ring 2 rule
> `spec → [domain]`. Consequently `ProfileEval.allows` takes domain-typed
> arguments `(name: OperationName, behaviour: S => Outcome[Res,S], res, profile)`
> — the `spec.Operation` is unwrapped inside `Spec.allows` (which calls
> `op.behaviour(req, _)`), so the kernel never imports `spec`. This matches the
> Pure Code table's domain-typed signatures and deviates from the Step-1 typed
> contract only in package (`engine.verified` → `domain`) and in dropping the
> `op:` convenience parameter. Ring 6 (Stainless) therefore targets the
> `domain` kernel.
>
> **Ring 6 mechanics.** Stainless's bundled frontend is pinned to Scala 3.7.2,
> so it runs on a dedicated leaf module `verified` (`scalaVersion := 3.7.2`,
> strict flags relaxed) — the shipped modules stay on 3.8.4 (TASTy is only
> backward-compatible, so a 3.7.2 leaf is safe; nothing depends *up* into it).
> `verified/OracleKernel.scala` is a PureScala MIRROR of the survivor/verdict
> algorithm (states as `BigInt`, `EvalBranch` = (passed?, next), no
> Iron/cats/opaque). Stainless proves the model; the model and the shipped
> `domain` kernel are tied together **mechanically** by a bridge property
> (`OracleModelBridgeTests` in `core`, which `dependsOn(verified % Test)`): it
> runs the real `ProfileEval.allows` and the model `OracleKernel.survivors` on
> the same generated inputs and asserts they agree on conformance + survivor
> count, so drift in either side fails CI. `verified/stainlessEnabled` defaults
> to OFF (the bridge only needs the model COMPILED, not re-verified, so
> `core/test` stays fast); verification is the explicit `sbt ring6` alias. VCs
> must be quantifier-free: z3 is single-threaded with no default timeout, so a
> `forall/exists` goal hangs unbounded — soundness (a `forall/exists` shape) is
> left to Ring 3, and Stainless proves the quantifier-free conformance +
> termination VCs.
>
> **Violation accumulation model.** `Spec.allows` produces a *flat* list of the
> atomic `CheckFailed` violations every failing branch emits, across all
> candidates (the binding Ring-3 invariant "violation count == total failed
> atomic checks"). `UnknownOperation` is produced for an unregistered handle;
> `ProfileExhausted` is the unreachable totality fallback (no survivors yet no
> atomic violation — impossible for the non-empty outcomes built here, but it
> keeps `allows` total without a partial `.get`/`throw`). `NoBranchMatched` is
> part of the committed algebra but is **not constructed by oracle-core**:
> per-candidate structured wrapping would break the flat-count invariant, so it
> is reserved for the richer deviation reports in test-execution /
> linearizability. (Flagged in the Ring 8 review below and at the checkpoint.)
| `domain.*` smart constructors | Iron-validated construction | No (trivial, covered by Ring 0 + compile-negative tests) |
| `engine.GraphExplorer.bfs` (pure tail-recursive core) | bounded BFS over `(Spec, InputSet)` | No (uses `Gen` sampling for mocks — not PureScala) |
| `engine.TestCaseGenerator.*` | path selection over a materialized graph | No (RandomWalk uses seeded RNG; Rings 3/5 cover it) |

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

## Type Strategy — Invalid-State Prevention

Placement of every core invariant on the v2 hierarchy
(impossible > smart constructor > validator > fallback > silent mapping ◄ forbidden):

| Invariant | Placement | Justification |
|-----------|-----------|---------------|
| `StateProfile` never empty | **Impossible** (opaque type, only `NonEmptyList`/`one` constructors) + compile-negative test | emptiness would make `allows` verdicts meaningless |
| `StateProfile` never holds `Eq`-duplicates | **Smart constructor** (dedup on construction) + property test | dedup needs `Eq[S]` at runtime; cannot be type-level for arbitrary `S` |
| `OperationName`/`CallLabel` non-blank | **Impossible for literals** (Iron compile-time) / **smart constructor** for dynamic values | both paths covered; compile-negative stubs prove the literal path |
| `MaxDepth`/`MaxRetryCount` positive, `ParallelWidth` ≤ 4 | **Impossible** (Iron `Positive`, `Positive & LessEqual[4]`) | unbounded exploration / n! blow-up unrepresentable |
| Req/Res match their operation | **Impossible** (typed handles + existential `OperationCall`) + compile-negative tests | no casts anywhere; Ring 8 greps for `asInstanceOf` regressions |
| SUT response type matches the call | **Impossible** (dependent `execute(call): F[call.Res]`) | wrong-typed SUT unrepresentable |
| Duplicate operation registration | **Smart constructor** (`register` returns `Either`) | name set is dynamic |
| Spec/contract drift (smithy4s) | **Smart constructor** (`SpecBuilder.build` fails listing missing endpoints) + completeness property | endpoint set known only at build time |
| Persistence schema version | **Validator** (explicit `VersionMismatch` error) | versions are data from disk; must be checked, never defaulted |
| No silent fallback verdicts | **Forbidden pattern** — no `case _` in outcome matching; enforced by Ring 1 dangerous-pattern scan + Ring 8 | the oracle's entire value is refusing to guess |

## Smithy Model Layout

accordant4s defines **no Smithy models of its own** (Ring 9 not applicable). The
`smithy4s` module *consumes* user services:

| Service | Operations | Smithy File |
|---------|-----------|-------------|
| user-supplied `Service[Alg]` | each smithy4s endpoint → one `Operation[Req, Res, S]` slot (name from the Smithy operation id; Req/Res from the endpoint's input/output schemas) | user project |

Derivation surface: `SmithyOps.operations[Alg, S](service)(behaviours, mocks)` returns
the typed slots; registering them all yields a spec whose operation set provably equals
the service's endpoint set (a Ring 3 property of the smithy4s-derivation spec). The
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

## Compatibility Story (Ring 4)

This change INTRODUCES the project's first persisted/wire formats; no legacy data
exists, so the Ring 4 obligation is to create the baseline that future changes
will be checked against:

| Format | Introduced by | Ring 4 obligation |
|--------|--------------|-------------------|
| `TestCaseFileRecord` JSON (versioned, `version: 1`) | spec:test-generation | round-trip law property + fixture file `core/src/test/resources/fixtures/testcase-v1.json` committed as the decode baseline; unknown version → `VersionMismatch`, malformed → `DecodeFailed` |
| `ConcurrentTestCaseFileRecord` JSON | spec:linearizability | same: round-trip property + `concurrent-testcase-v1.json` fixture baseline |
| HTTP entities (user-defined request/response bodies) | spec:http-binding | wire round-trip law (`decodeEntity ∘ encode == Right`) + mapper totality over all statuses and malformed bodies |
| smithy4s codecs | spec:smithy4s-derivation | generated by smithy4s from the IDL; covered by the derived-binding transparency property, no hand-maintained baseline |

Unknown/missing-field behavior: decoding is strict for the versioned envelope
(unknown `version` fails explicitly); user payload codecs follow the user's circe
configuration and are outside this library's compatibility contract.

## Typed Contract Placement

Per capability-profile.md: contracts compile as real test sources —
`src/test/scala/io/gruggiero/accordant4s/typecontract/<SpecName>TypeContract.scala`
(spec 1, single-module) and `<module>/src/test/scala/.../typecontract/` after the
restructure, verified with `sbt <module>/Test/compile`. Compile-negative obligations
from each spec live in these files as `assertDoesNotCompile` stubs and stay test-side
after implementation promotes the real declarations to main sources.

## Verification Map

Rings: 0 compile · 1 lint · 2 architecture · 3 property tests · 4 compatibility ·
5 mutation · 6 formal · 7 model checking · 8 adversarial review · 9 telemetry.
Ring 8 applies to EVERY module (mandatory per spec); Ring 7 and Ring 9 apply to
none (no model checker / no telemetry stack — see capability-profile.md).

| Module | R0 | R1 | R2 | R3 | R4 | R5 | R6 | R8 |
|--------|----|----|----|----|----|----|----|----|
| `domain` (ADTs, Iron types) | ✅ | ✅ | ✅ | ✅ (constructors, profile dedup) | — | — | — | ✅ |
| `spec` (Operation, Spec, InputSet, DSL) | ✅ | ✅ | ✅ | ✅ | — | ✅ 90–95% | — | ✅ |
| `engine.verified` (oracle kernel) | ✅ | ✅ | ✅ | ✅ | — | ✅ 90–95% | ✅ best-effort | ✅ |
| `engine` graph + generation | ✅ | ✅ | ✅ | ✅ | — | ✅ 90–95% | — | ✅ |
| `engine` executor | ✅ | ✅ | ✅ | ✅ | — | — (effectful; fault properties cover) | — | ✅ |
| `engine.verified` linearization | ✅ | ✅ | ✅ | ✅ | — | ✅ 90–95% | ✅ best-effort | ✅ |
| `persist` | ✅ | ✅ | ✅ | ✅ (roundtrip) | ✅ (fixtures baseline) | — | — | ✅ |
| `munit` module | ✅ | ✅ | — | ✅ | — | — | — | ✅ |
| `http4s` module | ✅ | ✅ | — | ✅ (stubbed client) | ✅ (wire round-trips) | — | — | ✅ |
| `smithy4s` module | ✅ | ✅ | — | ✅ | — | — | — | ✅ |

## Build Changes (spec 1, Ring 0 prerequisite)

- Restructure `build.sbt` into the four modules above (`core`, `munit`, `http4s`, `smithy4s`).
- `project/Dependencies.scala` additions (versions per `openspec/config.yaml`):
  iron + iron-cats 3.0.2, circe-core/generic/parser 0.14.x,
  http4s-client/http4s-circe 0.23.x, smithy4s-core 0.18.x (+ sbt plugin for the test
  fixture), hedgehog-core at Compile scope in `core` (exposed by `Operation.mock`),
  hedgehog-munit % Test (property suites via `HedgehogSuite`), munit/munit-cats-effect
  to Compile scope in the `munit` module. No iron-scalacheck (Hedgehog has no
  `Arbitrary` typeclass — refined-type generators are hand-written).
- Add Scalafix (`.scalafix.conf`: DisableSyntax, RemoveUnused, OrganizeImports),
  WartRemover (`sbt-wartremover` 3.5.8 — `Warts.unsafe` minus `TripleQuestionMark`,
  ThisBuild errors; `verified` module exempt), and Stryker4s config
  (`stryker4s.conf`, thresholds break=90/low=91/high=95) — the rings the schema assumes.
- Keep `-Werror -Wunused:all -Wvalue-discard -language:strictEquality`.
