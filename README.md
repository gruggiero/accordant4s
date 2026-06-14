# accordant4s

**A model-based testing oracle for Scala 3, on the Typelevel stack.**

`accordant4s` lets you describe what a system *should* do as a declarative,
typed state machine — a **`Spec`** — and then use that single description as an
**oracle**: one call,

```scala
spec.allows(operation, request, response, profile): Verdict[S]
```

turns the spec into an executable truth table that says whether an observed
`(request, response)` pair is *conformant* with the model from a given state,
and what the resulting state(s) are. The same `Spec` can drive hand-written
scenarios, property tests, exhaustive state-graph exploration, and replay
against a real running service.

> **Status: early development.** Spec 1 (`oracle-core`) is implemented and fully
> verified (see [Verification](#verification)). Specs 2–8 (input sets, state-graph
> BFS, test generation, execution, HTTP/Smithy bindings, linearizability) are
> designed but not yet implemented — see the [Roadmap](#roadmap).

---

## Table of contents

- [Why this exists](#why-this-exists)
- [Relationship to Microsoft Accordant](#relationship-to-microsoft-accordant)
- [Core concepts](#core-concepts)
- [Quick start](#quick-start)
- [Non-determinism and state profiles](#non-determinism-and-state-profiles)
- [Architecture](#architecture)
- [Verification](#verification)
  - [Ring 6 — Stainless formal verification (in detail)](#ring-6--stainless-formal-verification-in-detail)
  - [Ring 3 — property testing with Hedgehog](#ring-3--property-testing-with-hedgehog)
  - [Ring 5 — mutation testing](#ring-5--mutation-testing)
- [Building and running](#building-and-running)
- [Technology stack](#technology-stack)
- [Roadmap](#roadmap)
- [Development workflow (OpenSpec)](#development-workflow-openspec)
- [References](#references)

---

## Why this exists

Conventional test suites tangle two concerns: *what correct behaviour is* and
*which sequences of operations to try*. Model-based testing separates them:

- the **oracle** — a declarative model of correct behaviour, `(state, request)
  → expected response × next state`;
- the **sequence source** — where the operations to try come from (hand-written
  cases, generators, BFS over reachable states, production replay).

Once the oracle is a value you can call (`spec.allows(...)`), *any* sequence
source can be checked against it. This is especially useful for validating
**AI-generated or third-party implementations** of a service: give the agent the
spec, then check the assembled service against the very same spec. The verdict —
"deviates at step N: expected X, got Y, reproducing path P" — is actionable,
semantic feedback rather than a generic assertion failure.

No existing Scala library provides this combination. The state-machine testing
in ScalaCheck's `Commands` and in Hedgehog is the closest analogue, but neither
offers BFS exploration of reachable states from a finite input set, a structured
expectation DSL with non-deterministic branching, state-profile tracking for
indefinite failures, or a clean `allows` oracle usable outside tests.

## Relationship to Microsoft Accordant

`accordant4s` is a Scala 3 reimagining of **Microsoft Accordant**, a .NET
model-based testing framework built around the same spec-as-oracle idea. (The
analysis this port is based on lives in
[`docs/accordant-scala3-report.md`](docs/accordant-scala3-report.md).)

It is a **targeted port, not a 1:1 translation**: we keep Accordant's best ideas
and express them idiomatically in the Typelevel stack rather than transliterating
its .NET assemblies.

| Microsoft Accordant (.NET) | accordant4s (Scala 3) | Why it changed |
|---|---|---|
| `[State]` class + Roslyn-generated `Clone()`/deep-equality | immutable `case class` + `derives CanEqual` and cats `Eq`/`Hash`/`Show` | no codegen; immutability is free and safer |
| `Spec<TState>.Operation<Req,Res>(name, lambda)` | `Spec[S].register(Operation[Req, Res, S])` | typed handle — `Req`/`Res` mismatches are **compile errors**, not casts |
| `Expect.That(p).SameState()` / `.ThenState(mutate)` | `expect(check).sameState` / `.thenState((res, s) => …)` | transition is a pure function that sees the **response** (response-dependent state) |
| boolean + message result | `ValidatedNel[SpecViolation, Unit]` | **accumulates every** failed check, not just the first |
| `Expect.OneOf(...)` graph branching | `Outcome.OneOf(NonEmptyList[Outcome])` + `StateProfile[S]` | non-determinism as an `Eq`-deduplicated set of candidate states |
| `spec.Allows(op, req, res, state) → (bool, msg, next)` | `spec.allows(op, req, res, profile): Verdict[S]` | `Conformant(profile)` \| `Deviant(NonEmptyList[SpecViolation])` |
| `Accordant.Choose.Each<T>()` exhaustive enumeration | Hedgehog `Gen`-backed input sets *(planned)* | shrinking + distribution control, still deterministic |
| `HttpExecutable` | http4s `Client[IO]` + circe *(planned)* | already in the stack |
| `Accordant.SourceGenerator` (Roslyn) | — (not needed) | immutable case classes replace it |

**Things Scala makes possible that .NET could not:**

- **Invalid states unrepresentable.** Identifiers are Iron-refined opaque types
  (`OperationName`/`CallLabel` are `String :| Not[Blank]`); `StateProfile` cannot
  be constructed empty. These are compile-time guarantees, checked by
  compile-negative tests.
- **Errors as data.** Deviations are a sealed `SpecViolation` enum, not strings
  or exceptions.
- **A formally verified kernel.** The survivor/verdict algorithm is proved with
  Stainless (see [Ring 6](#ring-6--stainless-formal-verification-in-detail)) —
  stronger than Accordant's runtime-only checking.

**Deliberately out of scope** (for now): async/background-job step functions,
state-graph visualization, an effect-aware (`IO`) oracle, and Jepsen/Knossos
interop. The oracle is kept **pure** so offline exploration is cheap and the
kernel qualifies for formal verification.

## Core concepts

All in `io.gruggiero.accordant4s.domain` unless noted.

| Concept | Kind | Meaning |
|---|---|---|
| `OperationName`, `CallLabel` | opaque types `(String :\| Not[Blank])` | non-blank identifiers |
| `ResponseCheck[Res]` | `Res => ValidatedNel[SpecViolation, Unit]` | an accumulating predicate on a response |
| `Outcome[Res, S]` | enum | `Same(check)` \| `Next(check, transition)` \| `OneOf(branches)` |
| `SpecViolation` | enum | `CheckFailed` \| `UnknownOperation` \| `NoBranchMatched` \| `ProfileExhausted` |
| `StateProfile[S]` | opaque type | non-empty, `Eq`-deduplicated set of candidate states |
| `Verdict[S]` | enum | `Conformant(StateProfile[S])` \| `Deviant(NonEmptyList[SpecViolation])` |
| `StateOps[S]` | given bundle | the `Eq`/`Hash`/`Show`/`CanEqual` a user state must provide |
| `Operation[Req, Res, S]` | case class (`…spec`) | `name`, `behaviour: (Req, S) => Outcome[Res, S]`, `mock` |
| `Spec[S]` | case class (`…spec`) | operation registry + the `allows` oracle |
| `expect` | DSL object (`…spec`) | the only public way to build an `Outcome` |

The `behaviour` is the oracle entry point. The `mock` is a Hedgehog `Gen[Res]`
used only during offline exploration (to synthesise plausible server responses)
and is bypassed when replaying against a real system.

## Quick start

```scala
import cats.data.NonEmptyList
import cats.syntax.all.*
import cats.{Eq, Hash, Show}
import hedgehog.Gen
import io.gruggiero.accordant4s.domain.*
import io.gruggiero.accordant4s.spec.*

// 1. Your state — immutable, with the required cats instances + CanEqual.
final case class BankState(accounts: Map[String, BigDecimal]) derives CanEqual
object BankState:
  given Eq[BankState]   = Eq.fromUniversalEquals
  given Hash[BankState] = Hash.fromUniversalHashCode
  given Show[BankState] = Show.fromToString

// 2. Your operation's request/response types.
final case class WithdrawRequest(accountId: String, amount: BigDecimal) derives CanEqual
enum WithdrawResponse derives CanEqual:
  case Success(newBalance: BigDecimal)
  case Rejected

// 3. A response check — accumulates every failure.
val checkWithdraw: ResponseCheck[WithdrawResponse] =
  case WithdrawResponse.Success(b) if b >= BigDecimal(0) => ().validNel
  case other => SpecViolation.CheckFailed(OperationName("withdraw"), s"unexpected: $other").invalidNel

// 4. The operation: name + behaviour (the oracle) + mock (offline exploration only).
val withdraw: Operation[WithdrawRequest, WithdrawResponse, BankState] =
  Operation(
    name = OperationName("withdraw"),
    behaviour = (req, _) =>
      expect(checkWithdraw).thenState { (res, s) =>
        res match
          case WithdrawResponse.Success(b) => s.copy(accounts = s.accounts.updated(req.accountId, b))
          case WithdrawResponse.Rejected   => s
      },
    mock = (req, s) =>
      Gen.constant(WithdrawResponse.Success(s.accounts.getOrElse(req.accountId, BigDecimal(0))))
  )

// 5. Assemble a Spec (duplicate names are rejected as a Left).
val spec: Spec[BankState] =
  Spec.empty[BankState].register(withdraw).getOrElse(Spec.empty[BankState])

// 6. Use the oracle.
val profile = StateProfile.one(BankState(Map("alice" -> BigDecimal(100))))
spec.allows(withdraw, WithdrawRequest("alice", BigDecimal(30)), WithdrawResponse.Success(BigDecimal(70)), profile) match
  case Verdict.Conformant(next) => println(s"ok, surviving states: ${next.toList}")
  case Verdict.Deviant(vs)      => println(s"deviation: ${vs.toList}")
```

`allows` dispatches through the typed `op` handle you pass in (no casts); the
registry is consulted only to confirm the operation was registered — an
unregistered handle yields `Deviant(UnknownOperation(...))` without ever
evaluating its behaviour.

## Non-determinism and state profiles

Some operations are genuinely ambiguous — a timeout may or may not have taken
effect. `Outcome.OneOf` models this, and `allows` returns the **union of the
next-states of every branch that matches**, across **every candidate state** in
the profile, `Eq`-deduplicated:

```scala
// CreateAccount on a timeout: the request was either lost (state unchanged) or
// applied (account now exists). Both worlds survive.
val createAccount: Operation[CreateAccountRequest, CreateAccountResponse, BankState] =
  Operation(
    name = OperationName("createAccount"),
    behaviour = (req, _) =>
      expect.oneOf(NonEmptyList.of(
        expect(succeeded).thenState((_, s) => s.copy(accounts = s.accounts.updated(req.accountId, BigDecimal(0)))),
        expect(timedOut).sameState,                                                   // lost
        expect(timedOut).thenState((_, s) => s.copy(accounts = s.accounts.updated(req.accountId, BigDecimal(0)))) // applied
      )),
    mock = (_, _) => Gen.constant(CreateAccountResponse.Created)
  )
```

A timeout response yields `Conformant` with a **two-state** profile; a later,
unambiguous observation (e.g. `getAccount`) collapses the profile back to one.
The verdict is `Deviant` only when **no** branch matches **any** candidate — and
it then carries the flat list of *every* failed atomic check.

## Architecture

The codebase follows the `verified-scala3` OpenSpec methodology (see
[`openspec/`](openspec/)). Layering is enforced as a one-directional graph
(checked by Ring 2):

```
engine ─▶ spec ─▶ domain        (nothing points "up")
```

| Package / module | Role | May depend on |
|---|---|---|
| `io.gruggiero.accordant4s.domain` | pure ADTs, opaque types, and the oracle **kernel** (`OutcomeEval`, `ProfileEval`) | cats, Iron, stdlib |
| `io.gruggiero.accordant4s.spec` | `Operation`, `Spec`, the `expect` DSL | `domain`, cats, Iron, hedgehog |
| `io.gruggiero.accordant4s.engine` *(planned)* | BFS, generation, execution; `engine.verified` for Ring-6 kernels | `domain`, `spec` |
| modules `munit` / `http4s` / `smithy4s` *(planned)* | integration bindings | core + their integration lib |
| module `verified` | Scala 3.7.2 leaf holding the Stainless model (see below) | nothing project-local |

> **Design note (oracle-core).** The pure kernel lives in `domain`, not
> `engine.verified`, so `spec.Spec.allows` can delegate to it without `spec`
> depending on `engine` (which would break the layer rule and create a cycle).
> `ProfileEval.allows` therefore takes domain-typed arguments
> `(name, behaviour: S => Outcome[Res, S], res, profile)`; `Spec.allows` unwraps
> the `Operation`. Full rationale in
> [`openspec/changes/port-accordant-to-scala3/design.md`](openspec/changes/port-accordant-to-scala3/design.md).

## Verification

accordant4s *is itself a verification oracle* — a bug here silently mis-judges
every system tested with it — so it is developed under a layered set of
verification "rings". Status for the implemented `oracle-core`:

| Ring | What it checks | Status |
|---|---|---|
| 0 Compilation | strict `scalac` (`-Werror -Wunused:all -Wvalue-discard -language:strictEquality …`) + Iron | ✅ |
| 1 Lint | Scalafix (`DisableSyntax`/`RemoveUnused`/`OrganizeImports`), scalafmt, WartRemover | ✅ |
| 2 Architecture | one-directional layer dependencies | ✅ |
| 3 Property tests | Hedgehog invariants + scenarios + compile-negative checks | ✅ 20/20 |
| 4 Wire/persistence compat | circe round-trips | n/a until persistence lands |
| 5 Mutation | Stryker4s on changed files | ✅ 100% on the kernel |
| 6 Formal | Stainless proof of the kernel + a mechanical bridge to production | ✅ 9/9 VCs |
| 7 Model checking | (no TLA+/Apalache; covered by exhaustive-comparison properties later) | n/a |
| 8 Adversarial review | requirement-by-requirement spec-vs-code audit | ⚠️ 1 disclosed item¹ |
| 9 Telemetry | otel4s/Daut runtime monitors | n/a (library, no telemetry) |

¹ `SpecViolation.NoBranchMatched` is in the committed algebra but not yet
constructed: the binding Ring-3 property requires a *flat* atomic violation list,
so per-candidate wrapping is reserved for the later execution/linearizability
specs.

For an interactive walkthrough of the oracle kernel, the Stainless mirror, and
the production-to-model bridge tests, open
[`docs/oracle-kernel-explainer.html`](docs/oracle-kernel-explainer.html).

### Ring 6 — Stainless formal verification (in detail)

[Stainless](https://stainless.epfl.ch/) proves that the oracle's
survivor/verdict algorithm is correct for *all* inputs. Getting it to coexist
with a modern, strict Scala 3 build took care; here is the full setup so it can
be reproduced and extended.

**The problem.** Stainless ships its **own compiler frontend, pinned to Scala
3.7.2**, and can only verify a **PureScala** subset (sealed ADTs, `BigInt`,
`stainless.collection.List` — *no* Iron, cats, opaque types, or function-typed
fields). Two consequences:

1. Enabling the Stainless sbt plugin on a module forces that module to Scala
   3.7.2 and injects Stainless's library *sources* into the compile — those
   sources do not satisfy our `-Werror`/`strictEquality` flags.
2. The real kernel (`domain.OutcomeEval`/`ProfileEval`) uses cats/Iron/opaque
   `StateProfile`/function-typed `ResponseCheck`, so it **cannot be verified
   directly**.

**The solution — a dedicated cross-version leaf module + a mechanical bridge.**

- **`verified/`** is a separate sbt module pinned to `scalaVersion := "3.7.2"`
  with the strict flags relaxed and WartRemover off. Everything else stays on
  **3.8.4**. This is safe because Scala 3 TASTy is **backward-compatible**: a
  3.8.4 module can read a 3.7.2 artifact, never the reverse — so `verified` is a
  *leaf* (nothing project-local depends *up* into it).
- **`verified/.../OracleKernel.scala`** is a hand-written **PureScala mirror** of
  the algorithm: states are `BigInt` identities, a branch is reduced to
  `EvalBranch(passed: Boolean, next: BigInt)`, collections are
  `stainless.collection.List`. Stainless proves its postconditions.
- The mirror and the **shipped** kernel are tied together by a **bridge
  property**,
  [`OracleModelBridgeTests`](core/src/test/scala/io/gruggiero/accordant4s/oraclecore/OracleModelBridgeTests.scala),
  in `core` (which `dependsOn(verified % Test)`). It runs the **real**
  `ProfileEval.allows` *and* the **model** `OracleKernel.survivors` on the same
  generated inputs and asserts they agree on conformance and survivor count. If
  *either* side drifts, this test fails — so the proof is mechanically linked to
  production code, not merely "both aim at the same spec".

**What is proved.** 9/9 verification conditions, including the **conformance
verdict invariant** (`survivors.isEmpty == passingBranches.isEmpty`, i.e.
Conformant ⇔ some branch passed), `distinct` emptiness-preservation, and all
termination/measure obligations.

**What is deliberately *not* proved in Stainless.** Soundness ("every survivor
came from a passing branch") has a `∀s. ∃b. …` shape. z3 is **single-threaded
per query with no default timeout**, and that nested-quantifier goal makes its
instantiation **diverge** (it will run unbounded). So soundness is left to the
Ring-3 property "Conformant iff some branch matches some candidate", and
Stainless is scoped to quantifier-free goals that z3 discharges in milliseconds.

**Operational notes.**

- `verified/stainlessEnabled` defaults to **off**, so `verified/compile` (and
  hence `core/test`, via the bridge dependency) is a fast *plain* compile of the
  model. Verification is the explicit alias:
  ```bash
  sbt -J-Xmx6g ring6
  ```
- The large heap matters: the default 1 GB **OOMs** during Stainless's
  extraction/encoding phase.
- Keep new VCs **quantifier-free**, and add `decreases(...)` for any recursion
  whose argument isn't syntactically structural (e.g. recursion through
  `filter`).

### Ring 3 — property testing with Hedgehog

Property tests use [Hedgehog](https://hedgehogqa.github.io/scala-hedgehog/) via
`hedgehog.munit.HedgehogSuite` — **not** ScalaCheck. Hedgehog has **integrated
shrinking** and **no `Arbitrary` typeclass**: every generator is explicit, with
an explicit `Range`. Refined-type values are generated by hand (produce a raw
value, then refine through the smart constructor). `hedgehog.Gen` is a *compile*
dependency of `core` because `Operation.mock` exposes it.

The oracle-core properties are checked against an **independent reference
oracle** written in the test sources (a separate reimplementation of the
flatten/match/dedup logic), so the production `allows` is cross-checked rather
than compared to itself.

### Ring 5 — mutation testing

[Stryker4s](https://stryker-mutator.io/docs/stryker4s/) 0.21.0 mutates the
changed production files; the suite must *kill* the mutants. The pure kernel sits
at **100%** mutation score. Note: `stryker4s.conf` globs are resolved relative to
the mutated module's base dir, so they use base-agnostic `**/…` patterns.

## Building and running

Requires JDK ≥ 21 (developed on JDK 26), sbt 1.12.x. z3 (and optionally cvc5) on
`PATH` are needed for Ring 6.

```bash
sbt core/Test/compile                 # compile main + tests
sbt core/test                         # run all tests (incl. the model bridge)
sbt scalafmtCheckAll scalafixAll      # Ring 1 (lint)
sbt core/stryker                      # Ring 5 (mutation testing)
sbt -J-Xmx6g ring6                    # Ring 6 (Stainless verification of the model)
```

The `root` aggregate intentionally **excludes** the `verified` module, so normal
builds never trigger Stainless.

## Technology stack

| Concern | Library / tool | Version |
|---|---|---|
| Language | Scala (shipped modules) | 3.8.4 |
| Language | Scala (`verified` / Stainless) | 3.7.2 |
| Effects / streaming | cats-effect / fs2 | 3.5.7 / 3.11.0 |
| Refined types | Iron + iron-cats | 3.0.2 |
| JSON / persistence | circe | 0.14.13 |
| HTTP *(planned)* | http4s-client + http4s-circe | 0.23.30 |
| IDL *(planned)* | smithy4s-core | 0.18.33 |
| Test framework | munit + munit-cats-effect | 1.0.0 / 2.0.0 |
| Property testing | Hedgehog (`hedgehog-core` / `hedgehog-munit`) | 0.13.1 |
| Mutation testing | sbt-stryker4s | 0.21.0 |
| Formal verification | Stainless (stainless-library) | 0.9.9.x |
| Lint | scalafix / scalafmt / WartRemover | 0.14.0 / 2.5.4 / 3.5.8 |

## Roadmap

Eight delta specs, implemented depth-first in dependency order. Each goes through
a typed-contract gate, a test-oracle gate, implementation, and the verification
rings.

1. **oracle-core** — `Outcome`/`Verdict`/`StateProfile`, `Spec`, the `allows`
   oracle. ✅ *implemented & verified*
2. **input-sets** — labelled `OperationCall`s, `InputSet[S]`, Hedgehog-backed
   input sources.
3. **state-graph** — bounded BFS over reachable states (`fs2.Stream`),
   self-loop detection.
4. **test-generation** — coverage algorithms (state/transition/random-walk),
   circe persistence.
5. **test-execution** — `SystemUnderTest[F]`, step-wise replay validated through
   `allows`, munit module.
6. **http-binding** — http4s `Client[IO]` + circe → `SystemUnderTest[IO]`.
7. **smithy4s-derivation** — derive `Operation` slots from Smithy service shapes.
8. **linearizability** — concurrent cases, parallel execution, permutation-based
   linearizability checker.

See [`openspec/changes/port-accordant-to-scala3/`](openspec/changes/port-accordant-to-scala3/)
for the full proposal, design, per-spec specifications, and task tracker.

## Development workflow (OpenSpec)

This project is built with the **verified-scala3** OpenSpec workflow: depth-first,
one spec at a time, each gated by a **typed contract** and a **test oracle**
(both human-reviewed) before any implementation, then driven through the
verification rings to a **checkpoint**. Project context and per-artifact rules
live in [`openspec/config.yaml`](openspec/config.yaml).

```
accordant4s/
├── build.sbt                 # multi-module build (core, munit, http4s, smithy4s, verified)
├── project/                  # sbt plugins (incl. sbt-stainless in project/lib)
├── core/src/{main,test}      # the library + its tests
├── verified/src/main         # Scala 3.7.2 Stainless model (Ring 6)
├── stryker4s.conf            # Ring 5 mutation config
├── openspec/                 # verified-scala3 schema, change artifacts, specs
└── docs/                     # analysis report
```

Agent skills (`/opsx:*`): `explore`, `propose`, `apply`, `next-spec`,
`checkpoint`, `ring <N>`, `scan`, `pseudo`, `archive`.

## References

- **Microsoft Accordant** — the original .NET model-based testing framework this
  project reimagines (assemblies: `Accordant`, `Accordant.Operations`,
  `Accordant.SourceGenerator`, `Accordant.Choose`, `Accordant.Operations.Http`).
- [`docs/accordant-scala3-report.md`](docs/accordant-scala3-report.md) — the
  translation-difficulty and pipeline-fit analysis underpinning this port.
- [Stainless](https://stainless.epfl.ch/) · [Hedgehog for Scala](https://hedgehogqa.github.io/scala-hedgehog/) · [Iron](https://iltotore.github.io/iron/) · [Stryker4s](https://stryker-mutator.io/docs/stryker4s/)

## License

Not yet specified.
