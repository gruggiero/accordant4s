# Capability Profile

Detected by inspecting `build.sbt`, `project/Dependencies.scala`,
`project/plugins.sbt`, `project/build.properties`, and the source tree on
2026-06-12. This file is the stack source of truth for all artifacts and the
apply phase of change `port-accordant-to-scala3`; `openspec/config.yaml`
mirrors it.

**Updated 2026-06-14**: WartRemover is now available and active —
`sbt-wartremover` 3.5.8 was added to `project/plugins.sbt` and wired in
`build.sbt`. The earlier "no `wartremover_3.8.4` build" blocker is resolved.
Ring 1 is no longer degraded. See the Static Analysis table.

NOTE: this change is a greenfield library build. Spec 1's build-restructure
prerequisite (multi-module layout + new dependencies + ring tooling) is DONE
and verified (`sbt <module>/Test/compile`, `scalafmtSbtCheck`,
`scalafixAll --check` all pass on 2026-06-13). The tables below record this
CURRENT state. Spec 1's Step 1–3 (oracle-core typed contract, test oracle,
domain/spec/engine.verified implementation) are still pending the Step 1
human gate.

## Build & Language

| Item | Detected Value | Evidence (file) |
|------|---------------|-----------------|
| Scala version | 3.8.4 | `build.sbt` (`ThisBuild / scalaVersion`) |
| sbt version | 1.12.11 | `project/build.properties` |
| JDK | OpenJDK 26.0.1 (Homebrew) | `java -version` |
| Modules | `core`, `munit`, `http4s`, `smithy4s` (each `dependsOn(core)`), aggregated by `root` (`publish / skip := true`) | `build.sbt` |
| Fatal warnings | ACTIVE: `-Werror`, `-Wunused:all`, `-Wvalue-discard`, `-deprecation`, `-feature`, `-language:strictEquality`, `-language:adhocExtensions` | `build.sbt` scalacOptions |

`-language:strictEquality` is active: all ADTs must `derive CanEqual`; user
state `S` needs a `CanEqual[S, S]` given (bundled in `StateOps[S]`).

## Libraries

| Concern | Detected Library | Version | Notes |
|---------|-----------------|---------|-------|
| Effect system | cats-effect | 3.5.7 | Compile scope, `core` module; brings cats-core transitively (no separate explicit cats-core pin) |
| Streaming | fs2-core | 3.11.0 | Compile scope, `core` module |
| Actors | none | — | no actor specs/test kits apply |
| HTTP | http4s-client / http4s-circe | 0.23.30 | Compile scope, `http4s` module (dependency present; binding not yet implemented — spec 6) |
| Persistence | circe-core/generic/parser | 0.14.13 | Compile scope, `core` module (dependency present; codecs not yet implemented — spec 4); Ring 4 applies from spec 4 on |
| Messaging | none | — | |
| JSON | circe-core/generic/parser | 0.14.13 | Compile scope, `core` module |
| IDL / codegen | smithy4s-core 0.18.33 + `smithy4s-sbt-codegen` 0.18.33 plugin | 0.18.33 | Compile scope, `smithy4s` module (dependency + plugin present; codegen not yet enabled via `enablePlugins` — deferred to spec 7's `TestBank.smithy` fixture); the library consumes user Smithy models, it defines none |
| Refined types | Iron / iron-cats | **3.0.2** | Compile scope, `core` module. 3.0.2 is the latest on Maven Central as of 2026-06-13; used directly since this is a new project with no 2.x migration cost (design.md updated to match — was an early "2.6.x" placeholder). Confirmed at the Step 1 human gate. **No iron-scalacheck/iron-hedgehog**: Hedgehog has no `Arbitrary` typeclass, so refined-type generators are hand-written (generate raw value → refine via the smart constructor's `.either`/`applyUnsafe`). |
| Telemetry | none — no otel4s, no Daut | — | Ring 9: SKIP for this change (impact recorded below) |

## Testing

| Concern | Detected | Consequence |
|---------|----------|-------------|
| Test framework | munit 1.0.0 + munit-cats-effect 2.0.0 (`testFrameworks += munit.Framework`) | ALL generated tests use munit (`munit.FunSuite` / `munit.CatsEffectSuite`) — NOT ScalaTest (the previous config.yaml wrongly claimed ScalaTest; the build is authoritative) |
| Property testing | Hedgehog: hedgehog-core (Compile, `core`) + hedgehog-munit 0.13.1 (Test) | properties extend `hedgehog.munit.HedgehogSuite` and use `property("…") { for { x <- gen.forAll } yield <assertion> }`. Integrated shrinking, NO `Arbitrary` typeclass, explicit `Range` sizing. `hedgehog.Gen` is Compile-scoped in `core` (exposed by `Operation.mock`). NOT munit-scalacheck/ScalaCheckSuite |
| Actor test kits | n/a (no actor framework) | no actor scenarios in any spec |
| Mutation tool | sbt-stryker4s **0.21.0** + `stryker4s.conf` (threshold break=90/low=91/high=95 for the pure kernel) | Ring 5 runs from spec 1's Step 3 on; the `mutate` list MUST be retargeted to each spec's changed files (base-agnostic `**/…` globs — Stryker resolves them relative to the mutated module's base dir, NOT the repo root). 0.15.1 could not parse Scala 3.8 indentation; 0.21.0 fixes it. |
| Formal verification | Stainless (sbt plugin, `project/lib/sbt-stainless.jar`) — bundled frontend pinned to **Scala 3.7.2** | Ring 6 runs on a dedicated **`verified`** sbt module pinned to 3.7.2 with relaxed scalac options (shipped modules stay on 3.8.4 strict). It holds a PureScala MIRROR of the oracle kernel; a bridge property (`OracleModelBridgeTests`, `core dependsOn verified % Test`) mechanically ties the real `domain.{OutcomeEval,ProfileEval}` to the model (drift fails CI). `verified/stainlessEnabled` defaults OFF (bridge needs the model compiled, not re-verified → fast `core/test`); verify with `sbt -J-Xmx6g ring6`. Keep VCs quantifier-free (z3 is single-threaded, no default timeout — a `forall/exists` VC hangs unbounded). The `verified` module carries `-Wconf:src=.*stainless-library.*:silent` to mute pattern-match-exhaustivity warnings from the Stainless library sources the plugin injects (also keeps `core/test` quiet, since it compiles `verified`); our own model code still gets full warnings. |
| Model checking | none (no TLA+/Apalache) | Ring 7: linearizability invariants are covered by the brute-force-comparison property in spec 8; recorded as a test-enforced obligation, not a model-checked one |

## Static Analysis

| Tool | Active Rules | Inactive/Missing | Evidence |
|------|-------------|------------------|----------|
| Scalafix | plugin sbt-scalafix 0.14.0 + semanticdb enabled; `.scalafix.conf` ACTIVE: `DisableSyntax` (noVars, noThrows, noNulls, noReturns, noWhileLoops, noAsInstanceOf, noIsInstanceOf, noFinalize; `noUniversalEquality = false` — handled by `-language:strictEquality` instead), `RemoveUnused` (imports/privates/locals/patternvars), `OrganizeImports` (Merge grouping) | — | `.scalafix.conf`, `scalafixAll --check` passes |
| WartRemover | **ACTIVE** (`sbt-wartremover` 3.5.8) — `ThisBuild / wartremoverErrors ++= Warts.unsafe.filterNot(_ == Wart.TripleQuestionMark)`, i.e. as compile ERRORS: `Any`, `AsInstanceOf`, `DefaultArguments`, `EitherProjectionPartial`, `IsInstanceOf`, `IterableOps`, `NonUnitStatements`, `Null`, `OptionPartial`, `Product`, `Return`, `Serializable`, `StringPlusAny`, `Throw`, `TryPartial`, `Var`. `TripleQuestionMark` is excluded so typed contracts may use `???`. | The `verified` module sets `wartremoverErrors := Seq.empty` (the Stainless PureScala mirror is exempt; ships on 3.7.2 with relaxed flags). | `project/plugins.sbt`, `build.sbt` |
| scalafmt | plugin sbt-scalafmt 2.5.4 + `.scalafmt.conf` (`version = 3.9.6`, `runner.dialect = scala3`, `maxColumn = 100`) ACTIVE | — | `scalafmtSbtCheck` / `scalafmtCheckAll` pass after `scalafmtSbt`/`scalafmtAll` |

Ring 1 = Scalafix (DisableSyntax/RemoveUnused/OrganizeImports) + scalafmt +
WartRemover (`Warts.unsafe` minus `TripleQuestionMark`) + compiler
warnings-as-errors. The partial-extraction warts (`OptionPartial` /
`EitherProjectionPartial` / `TryPartial`) are now enforced by WartRemover
(previously Ring 8 review only). Ring 2 (architecture) has no automated rule
yet and is enforced by review against the design's layer table (no Scalafix
`LayerDependencies` rule configured).

## Compile & Test Commands

| Purpose | Command (per module: `core`, `munit`, `http4s`, `smithy4s`) |
|---------|--------------------------------------------------------------|
| Main compile | `sbt <module>/compile` |
| Test compile (typed contracts) | `sbt <module>/Test/compile` |
| Run tests | `sbt <module>/test` |
| Format check / apply | `sbt scalafmtCheckAll scalafmtSbtCheck` / `sbt scalafmtAll scalafmtSbt` |
| Lint | `sbt scalafixAll --check` / `sbt scalafixAll` |
| Mutation | `sbt stryker` with `stryker4s.conf`'s `mutate` list retargeted to the spec's changed files |

## Typed Contract Placement

Typed contracts MUST live in the owning module's test sources so they are
genuinely compiled by sbt (files under `openspec/changes/` are NOT compiled):

- All specs (multi-module layout in place): `<module>/src/test/scala/io/gruggiero/accordant4s/typecontract/<SpecName>TypeContract.scala`, compiled with `sbt <module>/Test/compile`
- Spec 1 (oracle-core): `core/src/test/scala/io/gruggiero/accordant4s/typecontract/OracleCoreTypeContract.scala`

## Domain Purity Rules (feeds Ring 2)

Project-specific layer constraints, derived from the design's layer table.
Until spec 1 installs Scalafix `LayerDependencies`, these are enforced by
review; from spec 1 on they are an automated rule. Integration layers
(`munit`/`http`/`smithy`) are separate sbt modules, so their boundaries are
enforced by the build graph rather than by Scalafix.

| Layer/Package | Must NOT import | May import |
|---------------|-----------------|------------|
| `domain` (pure) | anything project-local; fs2, cats-effect, http4s, smithy4s, circe | scala stdlib, cats core, Iron, hedgehog `Gen` |
| `spec` (service) | fs2, cats-effect, http4s, smithy4s | `domain`, cats core, Iron, hedgehog `Gen` |
| `engine` (effectful) | http4s, smithy4s | `domain`, `spec`, cats-effect, fs2 |
| `engine.verified` (pure, Ring 6) | fs2, cats-effect, hedgehog, anything effectful | `domain`, scala stdlib, cats core (PureScala subset only) |
| `persist` | fs2, cats-effect, http4s, smithy4s | `domain`, `spec`, circe |
| `munit` / `http` / `smithy` (integration modules) | each other | all `core` packages + their own integration lib (munit / http4s / smithy4s) |

## Ring Availability Summary

| Ring | Available? | Basis |
|------|-----------|-------|
| 0 Compilation | ✅ | scalac, fatal warnings active; `sbt <module>/Test/compile` verified across all 4 modules |
| 1 Lint | ✅ | Scalafix DisableSyntax/RemoveUnused/OrganizeImports + scalafmt + WartRemover (`Warts.unsafe` minus `TripleQuestionMark`, sbt-wartremover 3.5.8) active and verified across all modules |
| 2 Architecture | ⚠️ | no automated Scalafix `LayerDependencies` rule configured yet; enforced by review against the design's layer table |
| 3 Property tests | ✅ | munit + hedgehog-core + hedgehog-munit on test classpath (`HedgehogSuite`) |
| 4 Compatibility | ✅ from spec 4 | circe persistence introduced by spec 4 (fixtures created as the baseline) |
| 5 Mutation | ✅ | sbt-stryker4s 0.21.0 + `stryker4s.conf`; runs on the spec's changed files (base-agnostic `**/…` globs); threshold break=90 for the pure kernel |
| 6 Formal | ✅ (best-effort scope) | Stainless on a dedicated `verified` module (Scala 3.7.2, relaxed flags); PureScala mirror of the oracle kernel verified (9/9 VCs). Keep VCs quantifier-free — z3 is single-threaded, no default timeout |
| 7 Model checking | ❌ | no checker; invariants covered by exhaustive-comparison properties (spec 8) |
| 8 Adversarial review | ✅ | procedural — no tooling required |
| 9 Telemetry | ❌ SKIP | no telemetry stack; accordant4s is a library with no API operations. Correctness impact: none for this change — no temporal properties are declared in any spec. otel4s instrumentation of the executor is a future change |
