# Proposal: Port Microsoft Accordant to Scala 3 (accordant4s)

## Why

Microsoft Accordant is a .NET model-based testing framework whose central idea is the
separation of the **oracle** (a declarative `Spec` mapping `(State, Request) →
ExpectedResponse × NextState`) from the **sequence generator** (BFS state-graph
exploration, hand-written scenarios, fuzzers, production replay). The single call
`spec.Allows(operation, request, response, state)` turns the spec into an executable
truth table usable by any test sequence.

No Scala library provides this combination. ScalaCheck's `Commands` is the closest
analogue but lacks BFS exploration of reachable states from a finite input set, a
structured `Expect` DSL with non-deterministic branching (`Expect.OneOf`), state-profile
tracking for indefinite failures, and a clean `allows` oracle API usable outside tests
(see `docs/accordant-scala3-report.md` §4.2).

The `verified-scala3` pipeline needs this as its missing behavioural-conformance
layer (the report calls it "Ring 4.5" in its own pipeline numbering — unrelated to
this schema's verification rings): end-to-end conformance testing of an assembled
service, where the oracle is the same model an AI agent was given to implement. The oracle's verdict ("deviates at step N:
expected X, got Y, reproducing path P") is agent-actionable semantic feedback that no
existing ring produces.

## What Changes

This is a **targeted library**, not a 1:1 port (per report §7). We take Accordant's
best ideas and express them natively in the Typelevel stack: immutable state with Iron
refinements instead of Roslyn-generated `Clone()`, an `Outcome` ADT with
`ValidatedNel` error accumulation instead of the fluent `Expect` builder, fs2-based BFS
instead of imperative graph walking, and http4s/smithy4s instead of `HttpExecutable`.

### Affected Capabilities

- `specs/oracle-core/spec.md` — `Outcome[Res, S]` ADT, `ResponseCheck`, `Verdict[S]`, `StateProfile[S]`, `Operation[Req, Res, S]`, `Spec[S]` registry, the `allows` oracle, response-dependent transitions with mocks
- `specs/input-sets/spec.md` — labeled `OperationCall` values, `InputSet[S]` composition, ScalaCheck `Gen`-backed input sources (replaces `Accordant.Choose`)
- `specs/state-graph/spec.md` — BFS exploration of reachable states from a spec + input set (`fs2.Stream`), depth bounding, self-loop detection
- `specs/test-generation/spec.md` — path-selection algorithms (state coverage, transition coverage, random walk), `TestCase` values, circe JSON persistence
- `specs/test-execution/spec.md` — `SystemUnderTest[F]` abstraction, `TestCaseExecutor` validating every step through `allows`, bracket-safe before/after hooks, munit integration
- `specs/http-binding/spec.md` — http4s `Client[IO]` + circe binding of operations to real HTTP endpoints (replaces `Accordant.Operations.Http`)
- `specs/smithy4s-derivation/spec.md` — derive `Operation[Req, Res, S]` slots from smithy4s service shapes so spec and implementation share one source of truth
- `specs/linearizability/spec.md` — concurrent test cases (sequential prefix → parallel section → suffix), parallel execution via `IO`, permutation-based linearizability verdict

### Out of Scope

- **Async step functions** (`Triggers`, `AsyncOperation.Create`, `PollingSetup`,
  `TerminatingStepFunction`): background-job state evolution layers cleanly on top of
  the oracle and is deferred to a future change.
- **State visualization** (Accordant's graph rendering/CLI tooling).
- **Effect-aware oracle** (`(Req, S) => IO[Outcome]`): the oracle is kept pure in this
  change so it can drive offline BFS exploration and qualify for Ring 6; an `F[_]`
  variant is a documented future extension (design.md §Effect Boundaries).
- **Hermes skill wrapping** (report Phase 5) — pipeline integration is a separate change.
- **Jepsen/Knossos integration** — the permutation checker is pure Scala; external
  checker interop is future work.

## Approach

Eight delta specs implemented depth-first in dependency order. The foundation is a pure
oracle kernel (`Outcome` evaluation over a `StateProfile`) — pure functions over
immutable case-class state, which makes BFS exploration trivial (no I/O during
simulation, mocks supply response-dependent values) and the kernel a Ring 6 candidate.
Effects appear only at the engine boundary: fs2 streams for exploration/generation and
`IO` for executing against a real SUT. Integration modules (munit, http4s, smithy4s)
sit on top as separate sbt modules so library users only pay for what they use.

Key semantic carry-overs from Accordant, verified against the official docs:

1. **State profiles**: `Expect.OneOf` ambiguity (e.g. a timeout that may or may not
   have taken effect) is modeled by `allows` evaluating every candidate state in a
   profile and returning the union of surviving next-states; later observations
   collapse the profile.
2. **Response-dependent transitions**: `ThenState((res, s) => …)` captures
   server-generated values; a `mock` generator supplies realistic values during
   offline exploration and is bypassed during real execution.
3. **Simulate-then-execute**: graph exploration runs purely against the spec;
   execution replays generated sequences against the real SUT with the spec as oracle.
4. **Concurrent tests**: prefix establishes state, parallel section races operations,
   the checker searches for any conformant sequential ordering (linearizability).

## Correctness Risk Level

**HIGH** — accordant4s is itself a verification oracle: a bug here silently
mis-verdicts every system tested with it. Specific high-risk traits: oracle/evaluator
logic (`allows`, outcome matching), a permutation checker whose false positives mask
races, and persistence schema for repro records. Fallback/default paths in branch
matching are exactly the bug class Ring 8 exists for.

## Verification Strategy

Ring availability per `capability-profile.md` — Stryker4s/Scalafix/WartRemover
configs do not exist yet and are added by spec 1.

- [x] Ring 0: Compilation — strict scalac flags (`-Werror`, `strictEquality`), Iron refined types
- [x] Ring 1: Lint — Scalafix DisableSyntax + RemoveUnused, WartRemover, dangerous-pattern scan (config added by spec 1)
- [x] Ring 2: Architecture — layer dependencies (domain → nothing, spec → domain, engine → domain+spec), sealed domain types, effect discipline (rules added by spec 1)
- [x] Ring 3: Property-based tests — MANDATORY for all 8 specs (munit-scalacheck per detected stack); no waivers claimed
- [x] Ring 4: Wire/persistence compatibility — applies to `test-generation` and `linearizability` (circe file records); fixtures created as the compatibility baseline since none exist yet
- [x] Ring 5: Mutation testing — Stryker4s on the spec's changed files (dynamic targeting), thresholds 90–95% for pure kernels (`domain`, `spec`, `engine.verified`, generation), 80–90% for effectful engine code
- [x] Ring 6: Formal verification — Stainless on the pure kernels only: outcome evaluation (`oracle-core`) and the permutation checker (`linearizability`); best-effort — Stainless is not currently installed (see capability-profile.md)
- [ ] Ring 7: Model checking — no TLA+/Apalache available; the linearizability exhaustiveness invariant is instead enforced by a brute-force-comparison property (spec 8) and recorded as a test-enforced obligation
- [x] Ring 8: Adversarial spec-compliance review — MANDATORY for all 8 specs
- [ ] Ring 9: Telemetry — NOT applicable: this change introduces a library, not API operations or event sequences, and no telemetry stack is detected. No spec declares temporal properties. otel4s instrumentation of the executor is future work.

## Typed Contract Decision

The typed contract phase is MANDATORY for every code-changing spec. Decision per spec:

| Spec | Contract | Justification |
|------|----------|---------------|
| oracle-core | **Full** | new domain ADTs, opaque types, error algebra, `Spec[S]` public API |
| input-sets | **Full** | existential `OperationCall` encoding — the riskiest type-level design in the change |
| state-graph | **Full** | new types + BFS signatures + fs2 facade boundary |
| test-generation | **Full** | new types, coverage-algorithm API, persistence envelope (wire format) |
| test-execution | **Full** | public `SystemUnderTest[F]` trait, executor signatures, munit module surface |
| http-binding | **Full** | public binding API + transport-outcome error algebra |
| smithy4s-derivation | **Full** | `Service`-introspection typing is the principal risk; compile evidence is the point |
| linearizability | **Full** | new types + pure checker kernel signatures |

No minimal contracts or waivers: every spec introduces new public types.
Contracts live in the owning module's test sources (placement in
capability-profile.md) and must compile via `sbt <module>/Test/compile`.

## Existing Concepts to Reuse

None — new project. The concept inventory is empty; `src/main/scala/` and
`src/test/scala/` contain no sources. Later specs reuse concepts that earlier specs in
this change introduce (tracked spec-by-spec in their Concepts Used tables).

## New Concepts to Introduce

Directional preview — refined in the specs and design:

| Concept | Kind | Purpose |
|---------|------|---------|
| `Outcome[Res, S]` | enum | Expected response + transition: `Same`, `Next`, `OneOf` (replaces `Expect` DSL) |
| `ResponseCheck[Res]` | type alias | `Res => ValidatedNel[SpecViolation, Unit]` — accumulating response predicate |
| `SpecViolation` | enum | Structured deviation reasons (failed check, unknown operation, empty profile…) |
| `Verdict[S]` | enum | `Conformant(profile)` \| `Deviant(violations)` — result of `allows` |
| `StateProfile[S]` | opaque type | Non-empty, `Eq`-deduplicated set of candidate states (indefinite-failure tracking) |
| `Operation[Req, Res, S]` | case class | Named, typed operation slot: behaviour `(Req, S) => Outcome[Res, S]` + response mock |
| `Spec[S]` | case class / trait | Operation registry + `allows(op, req, res, profile): Verdict[S]` oracle |
| `OperationCall[S]` | sealed trait | Existential (Operation, Req, label) triple — one step of a sequence |
| `InputSet[S]` | opaque/case class | Composable collection of labeled calls; `Gen`-backed sources |
| `StateGraph[S]` | case class | Nodes (canonical states) + labeled edges discovered by BFS |
| `GraphExplorer` | object (pure + fs2) | Bounded BFS: `(Spec[S], InputSet[S], S, MaxDepth) => StateGraph[S]` |
| `TestCase[S]` | case class | Labeled sequence of `OperationCall`s with expected verdict trail |
| `CoverageAlgorithm` | enum | `StateCoverage` \| `TransitionCoverage` \| `RandomWalk(seed)` |
| `TestCaseGenerator` | object | Path selection over `StateGraph` → `TestCase`s |
| `SystemUnderTest[F]` | trait (tagless final) | Executes an `OperationCall` against the real system; reset hook |
| `TestCaseExecutor` | object | Replays a `TestCase` against a SUT, validating each step via `allows` |
| `ExecutionReport[S]` | enum | `Passed` \| `DeviatesAt(step, violations, reproPath)` |
| `HttpBinding` | module | http4s `Client[IO]` + circe codecs → `SystemUnderTest[IO]` |
| `SmithyOps` | module | Derive `Operation` slots from a smithy4s `Service` |
| `ConcurrentTestCase[S]` | case class | prefix / parallel (NonEmptyList) / suffix sections |
| `LinearizabilityChecker` | object (pure kernel) | ∃-permutation search over parallel results against the spec |
| `MaxDepth`, `OperationName`, `CallLabel` | opaque types (Iron) | `Int :| Positive`, `String :| Not[Blank]` constrained identifiers |

## Risks and Mitigations

| Risk | Mitigation |
|------|------------|
| Existential `OperationCall` typing fights `-language:strictEquality` and type inference | The mandatory typed contract compiles the type skeleton first (human-gated); fall back to a sealed-trait-with-type-members encoding if match types get hairy |
| State-space explosion in BFS | `MaxDepth` is mandatory (Iron `Positive`), exploration is a lazy `fs2.Stream`, canonicalization via `cats.Eq` + `Hash` collapses revisits |
| `n!` permutation blow-up in linearizability checker | Parallel sections bounded (Accordant uses pairs; we cap at a small `ParallelWidth`), short-circuit on first conformant ordering |
| Stainless (Ring 6) may not accept the kernel as written (PureScala subset, Scala-version support; not currently installed) | Ring 6 scoped to two small pure functions in a `verified/`-style package; failure downgrades to Ring 3/5 coverage, recorded in the checkpoint |
| Stryker4s not yet configured in build | Spec 1 adds the sbt plugin + config; if it proves incompatible with Scala 3.8.4, Ring 5 is marked ⏭️ per checkpoint with rationale |
| smithy4s `Service` introspection API churn (0.18.x) | Derivation kept in an isolated module; pinned version; compile-time-only dependency surface |
| Dependency creep in core (circe, scalacheck at Compile scope) | Deliberate, documented in design.md: accordant4s is itself a testing library, so ScalaCheck `Gen` for mocks and circe for persistence are legitimate core dependencies |
