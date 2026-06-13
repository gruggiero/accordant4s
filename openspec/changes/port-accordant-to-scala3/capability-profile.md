# Capability Profile

Detected by inspecting `build.sbt`, `project/Dependencies.scala`,
`project/plugins.sbt`, `project/build.properties`, and the source tree on
2026-06-12. This file is the stack source of truth for all artifacts and the
apply phase of change `port-accordant-to-scala3`; `openspec/config.yaml`
mirrors it.

NOTE: this change is a greenfield library build whose spec 1 restructures the
build (multi-module + new dependencies + ring tooling). The tables record the
CURRENT state; the "After spec 1" column records what spec 1 commits to add,
so later specs can rely on it once spec 1's checkpoint is approved.

## Build & Language

| Item | Detected Value | Evidence (file) |
|------|---------------|-----------------|
| Scala version | 3.8.4 | `build.sbt` (`ThisBuild / scalaVersion`) |
| sbt version | 1.12.11 | `project/build.properties` |
| JDK | OpenJDK 26.0.1 (Homebrew) | `java -version` |
| Modules | single root module `accordant4s` (after spec 1: `core`, `munit`, `http4s`, `smithy4s`) | `build.sbt` |
| Fatal warnings | ACTIVE: `-Werror`, `-Wunused:all`, `-Wvalue-discard`, `-deprecation`, `-feature`, `-language:strictEquality`, `-language:adhocExtensions` | `build.sbt` scalacOptions |

`-language:strictEquality` is active: all ADTs must `derive CanEqual`; user
state `S` needs a `CanEqual[S, S]` given (bundled in `StateOps[S]`).

## Libraries

| Concern | Detected Library | Version | Notes |
|---------|-----------------|---------|-------|
| Effect system | cats-effect | 3.5.7 | Compile scope |
| Streaming | fs2-core | 3.11.0 | Compile scope |
| Actors | none | — | no actor specs/test kits apply |
| HTTP | none (spec 6 adds http4s-client/http4s-circe 0.23.x in module `http4s`) | — | |
| Persistence | none (spec 4 adds circe 0.14.x file-record persistence in `core`) | — | Ring 4 applies from spec 4 on |
| Messaging | none | — | |
| JSON | none yet (spec 1 adds circe-core/generic/parser 0.14.x) | — | |
| IDL / codegen | none (spec 7 adds smithy4s-core 0.18.x + sbt plugin for the test fixture, module `smithy4s`) | — | the library consumes user Smithy models; it defines none |
| Refined types | none yet (spec 1 adds Iron + iron-cats 2.6.x, iron-scalacheck % Test) | — | |
| Telemetry | none — no otel4s, no Daut | — | Ring 9: SKIP for this change (impact recorded below) |

## Testing

| Concern | Detected | Consequence |
|---------|----------|-------------|
| Test framework | munit 1.0.0 + munit-cats-effect 2.0.0 (`testFrameworks += munit.Framework`) | ALL generated tests use munit (`munit.FunSuite` / `munit.CatsEffectSuite`) — NOT ScalaTest (the previous config.yaml wrongly claimed ScalaTest; the build is authoritative) |
| Property testing | scalacheck 1.18.1 + munit-scalacheck 1.0.0 | properties use `munit.ScalaCheckSuite` / `ScalaCheckEffectSuite` with `property`/`forAll` |
| Actor test kits | n/a (no actor framework) | no actor scenarios in any spec |
| Mutation tool | NOT present (no sbt-stryker4s plugin, no `stryker4s.conf`) | spec 1 adds the plugin + conf; Ring 5 runs from spec 1 on, with the `mutate` list dynamically set to the spec's changed files (never a fixed list) |
| Formal verification | Stainless NOT on PATH / not a plugin | Ring 6 is best-effort: attempted for `engine.verified` kernels; if Stainless cannot run against Scala 3.8.4 sources, skip with impact noted at the checkpoint (Ring 3/5 properties remain the coverage) |
| Model checking | none (no TLA+/Apalache) | Ring 7: linearizability invariants are covered by the brute-force-comparison property in spec 8; recorded as a test-enforced obligation, not a model-checked one |

## Static Analysis

| Tool | Active Rules | Inactive/Missing | Evidence |
|------|-------------|------------------|----------|
| Scalafix | plugin sbt-scalafix 0.14.0 + semanticdb enabled | NO `.scalafix.conf` — no rules active yet; spec 1 adds DisableSyntax (var/null/throw/return/while), RemoveUnused, OrganizeImports | `project/plugins.sbt`, `build.sbt` |
| WartRemover | not installed | spec 1 adds it (Wart.AsInstanceOf, IsInstanceOf, Null, Return, Var) | — |
| scalafmt | plugin sbt-scalafmt 2.5.4 | NO `.scalafmt.conf` — `scalafmtCheck` not enforceable yet; spec 1 adds the conf | `project/plugins.sbt` |

Until spec 1 lands, Ring 1 = compiler warnings-as-errors only; Ring 2
(architecture) has no automated rule and is enforced by review against the
design's layer table.

## Compile & Test Commands

| Purpose | Command (current single-module) | After spec 1 (per module) |
|---------|--------------------------------|---------------------------|
| Main compile | `sbt compile` | `sbt core/compile` (and `munit/`, `http4s/`, `smithy4s/`) |
| Test compile (typed contracts) | `sbt Test/compile` | `sbt core/Test/compile` etc. |
| Run tests | `sbt test` | `sbt core/test` etc. |
| Lint | `sbt "scalafix --check"` (no-op until `.scalafix.conf` exists) | same, per module |
| Mutation | n/a | `sbt "stryker"` with retargeted mutate list |

## Typed Contract Placement

Typed contracts MUST live in the owning module's test sources so they are
genuinely compiled by sbt (files under `openspec/changes/` are NOT compiled):

- Spec 1 (before restructure): `src/test/scala/io/gruggiero/accordant4s/typecontract/<SpecName>TypeContract.scala`, compiled with `sbt Test/compile`
- Specs 2–8 (after restructure): `<module>/src/test/scala/io/gruggiero/accordant4s/typecontract/<SpecName>TypeContract.scala`, compiled with `sbt <module>/Test/compile`

## Domain Purity Rules (feeds Ring 2)

Project-specific layer constraints, derived from the design's layer table.
Until spec 1 installs Scalafix `LayerDependencies`, these are enforced by
review; from spec 1 on they are an automated rule. Integration layers
(`munit`/`http`/`smithy`) are separate sbt modules, so their boundaries are
enforced by the build graph rather than by Scalafix.

| Layer/Package | Must NOT import | May import |
|---------------|-----------------|------------|
| `domain` (pure) | anything project-local; fs2, cats-effect, http4s, smithy4s, circe | scala stdlib, cats core, Iron, scalacheck `Gen` |
| `spec` (service) | fs2, cats-effect, http4s, smithy4s | `domain`, cats core, Iron, scalacheck `Gen` |
| `engine` (effectful) | http4s, smithy4s | `domain`, `spec`, cats-effect, fs2 |
| `engine.verified` (pure, Ring 6) | fs2, cats-effect, scalacheck, anything effectful | `domain`, scala stdlib, cats core (PureScala subset only) |
| `persist` | fs2, cats-effect, http4s, smithy4s | `domain`, circe |
| `munit` / `http` / `smithy` (integration modules) | each other | all `core` packages + their own integration lib (munit / http4s / smithy4s) |

## Ring Availability Summary

| Ring | Available? | Basis |
|------|-----------|-------|
| 0 Compilation | ✅ | scalac, fatal warnings active |
| 1 Lint | ⚠️ from spec 1 | scalafix plugin present, conf added by spec 1 |
| 2 Architecture | ⚠️ from spec 1 | layer rules configured with the Scalafix setup |
| 3 Property tests | ✅ | munit + scalacheck + munit-scalacheck on test classpath |
| 4 Compatibility | ✅ from spec 4 | circe persistence introduced by spec 4 (fixtures created as the baseline) |
| 5 Mutation | ⚠️ from spec 1 | Stryker4s added by spec 1; dynamic mutate targeting mandatory |
| 6 Formal | ⚠️ best-effort | Stainless not installed; attempt on `engine.verified`, degrade with stated impact |
| 7 Model checking | ❌ | no checker; invariants covered by exhaustive-comparison properties (spec 8) |
| 8 Adversarial review | ✅ | procedural — no tooling required |
| 9 Telemetry | ❌ SKIP | no telemetry stack; accordant4s is a library with no API operations. Correctness impact: none for this change — no temporal properties are declared in any spec. otel4s instrumentation of the executor is a future change |
