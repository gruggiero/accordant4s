import sbt._

object Dependencies {
  // Core
  val CatsEffect = "org.typelevel" %% "cats-effect" % "3.5.7"
  val FS2        = "co.fs2"        %% "fs2-core"    % "3.11.0"

  // Iron refined types
  // NOTE: design.md specifies "iron + iron-cats 2.6.x"; 3.0.2 is the latest
  // available on Maven Central as of 2026-06-13. Using 3.0.2 since this is a
  // new project with no migration cost — flagged for the Step 1 human gate.
  // No iron-hedgehog integration exists; refined-type generators are written by
  // hand (generate a raw value, refine via the smart constructor). iron-scalacheck
  // was dropped with the move to Hedgehog (which has no Arbitrary typeclass).
  val Iron     = "io.github.iltotore" %% "iron"      % "3.0.2"
  val IronCats = "io.github.iltotore" %% "iron-cats" % "3.0.2"

  // circe (test-case persistence)
  val CirceCore    = "io.circe" %% "circe-core"    % "0.14.13"
  val CirceGeneric = "io.circe" %% "circe-generic" % "0.14.13"
  val CirceParser  = "io.circe" %% "circe-parser"  % "0.14.13"

  // http4s (accordant4s-http4s)
  val Http4sClient = "org.http4s" %% "http4s-client" % "0.23.30"
  val Http4sCirce  = "org.http4s" %% "http4s-circe"  % "0.23.30"

  // smithy4s (accordant4s-smithy4s)
  val Smithy4sCore = "com.disneystreaming.smithy4s" %% "smithy4s-core" % "0.18.33"

  // Property testing: Hedgehog (integrated shrinking, no Arbitrary typeclass).
  // hedgehog-core carries the `Gen`/`Property` API and is a Compile dependency of
  // `core` because `Operation.mock` exposes `hedgehog.Gen` in its public type.
  // hedgehog-munit provides `HedgehogSuite` (runs through munit's framework) and
  // pulls in hedgehog-core + hedgehog-runner transitively at Test scope.
  val hedgehogVersion = "0.13.1"
  val HedgehogCore    = "qa.hedgehog" %% "hedgehog-core"  % hedgehogVersion
  val HedgehogMunit   = "qa.hedgehog" %% "hedgehog-munit" % hedgehogVersion

  // Testing
  val Munit           = "org.scalameta" %% "munit"             % "1.0.0"
  val MunitCatsEffect = "org.typelevel" %% "munit-cats-effect" % "2.0.0"
}
