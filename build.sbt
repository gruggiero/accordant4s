import Dependencies._

ThisBuild / organization := "io.gruggiero"
ThisBuild / scalaVersion := "3.8.4"
ThisBuild / version      := "0.1.0-SNAPSHOT"

// Strict options for the SHIPPED modules. The `verified` module overrides these
// (see below): Stainless pins its own 3.7.2 frontend and injects library sources
// that cannot satisfy -Werror/strictEquality, so verification code is exempt.
val strictScalacOptions = Seq(
  "-Werror",
  "-Wunused:all",
  "-Wvalue-discard",
  "-deprecation",
  "-feature",
  "-language:strictEquality",
  "-language:adhocExtensions"
)

ThisBuild / scalacOptions ++= strictScalacOptions
ThisBuild / wartremoverErrors ++= Warts.unsafe.filterNot(_ == Wart.TripleQuestionMark)

ThisBuild / semanticdbEnabled := true
ThisBuild / semanticdbVersion := scalafixSemanticdb.revision

lazy val munitTestDeps = Seq(
  Munit           % Test,
  MunitCatsEffect % Test,
  HedgehogMunit   % Test
)

lazy val core = (project in file("core"))
  .dependsOn(
    verified % Test
  ) // bridge test: real ProfileEval vs the Stainless model (model compiled, not re-verified, on core/test)
  .settings(
    name := "accordant4s-core",
    libraryDependencies ++= Seq(
      CatsEffect,
      FS2,
      Iron,
      IronCats,
      CirceCore,
      CirceGeneric,
      CirceParser,
      HedgehogCore
    ) ++ munitTestDeps,
    testFrameworks += new TestFramework("munit.Framework")
  )

// ── Ring 6 — Stainless formal verification (Option A) ──────────────────────
// A dedicated LEAF module pinned to Scala 3.7.2 (the version Stainless's bundled
// frontend supports) with the strict flags relaxed, so the rest of the build can
// stay on 3.8.4. It depends on nothing project-local (TASTy is only
// backward-compatible: a 3.8.4 module could read this 3.7.2 artifact, never the
// reverse), and contains a PureScala mirror of the oracle algorithm. The real
// `domain.{OutcomeEval,ProfileEval}` is pinned to the SAME algorithm by the
// Ring-3 reference-oracle properties in `core`. Not aggregated by `root`, so
// normal builds skip Stainless; run Ring 6 with `sbt verified/compile`.
lazy val verified = (project in file("verified"))
  .enablePlugins(StainlessPlugin)
  .settings(
    name         := "accordant4s-verified",
    scalaVersion := "3.7.2",
    // Stainless injects its library sources into this module's compile; they emit
    // pattern-match-exhaustivity warnings we don't own (and can't fix). Silence
    // warnings from that source path only — our model (`OracleKernel.scala`) keeps
    // full warnings. This also keeps `core/test` quiet (it compiles `verified`).
    scalacOptions := Seq(
      "-deprecation",
      "-feature",
      "-Wconf:src=.*stainless-library.*:silent"
    ),
    wartremoverErrors := Seq.empty,
    semanticdbEnabled := false,
    // Default OFF: `verified/compile` (incl. as `core`'s bridge-test dependency) is then a
    // plain, fast compile of the model. Ring 6 turns verification ON explicitly via the
    // `ring6` alias below, so it never slows down `core/test`.
    stainlessEnabled := false,
    publish / skip   := true
  )

// Ring 6 — run Stainless verification on the model (needs a big heap; z3 single-threaded).
addCommandAlias("ring6", "; set verified / stainlessEnabled := true ; verified / compile")

lazy val munit = (project in file("munit"))
  .dependsOn(core)
  // `test->test` so this module's meta-suite scenario tests can reuse `core`'s
  // test fixtures (RefSut, bank spec/graph fixtures) — the same reuse spec 5's
  // typed contract flagged as a Step-3 build change (commitment #4).
  .dependsOn(core % "test->test")
  .settings(
    name := "accordant4s-munit",
    libraryDependencies ++= Seq(
      Munit,
      MunitCatsEffect,
      HedgehogMunit % Test
    ),
    testFrameworks += new TestFramework("munit.Framework")
  )

lazy val http4s = (project in file("http4s"))
  .dependsOn(core)
  .dependsOn(core % "test->test")
  .settings(
    name := "accordant4s-http4s",
    // The existential-Endpoint bridge (call.req / call.Res recovered from a
    // name-keyed lookup) needs one controlled `asInstanceOf` and exposes `Any`
    // at the erased boundary. Both are sound by the name-keyed invariant.
    // Exempt those two warts from this module only (the `verified` module
    // exempts all warts for the same kind of reason).
    wartremoverErrors := Warts.unsafe
      .filterNot(w => w == Wart.TripleQuestionMark || w == Wart.AsInstanceOf || w == Wart.Any),
    libraryDependencies ++= Seq(
      Http4sClient,
      Http4sCirce
    ) ++ munitTestDeps,
    testFrameworks += new TestFramework("munit.Framework")
  )

lazy val smithy4s = (project in file("smithy4s"))
  .dependsOn(core)
  .enablePlugins(Smithy4sCodegenPlugin)
  .settings(
    name := "accordant4s-smithy4s",
    // The derivation intropyespects smithy4s `Service[Alg]`; `asInstanceOf`
    // warts are exempted for the same reason as http4s (existential bridge).
    wartremoverErrors := Warts.unsafe
      .filterNot(w => w == Wart.TripleQuestionMark || w == Wart.AsInstanceOf || w == Wart.Any),
    libraryDependencies ++= Seq(
      Smithy4sCore
    ) ++ munitTestDeps,
    testFrameworks += new TestFramework("munit.Framework")
  )

lazy val root = (project in file("."))
  .aggregate(core, munit, http4s, smithy4s)
  .settings(
    name           := "accordant4s",
    publish / skip := true
  )
