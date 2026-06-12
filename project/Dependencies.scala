import sbt._

object Dependencies {
  // Core
  val CatsEffect   = "org.typelevel" %% "cats-effect" % "3.5.7"
  val FS2          = "co.fs2"        %% "fs2-core"    % "3.11.0"

  // Testing
  val Munit             = "org.scalameta"   %% "munit"             % "1.0.0"    % Test
  val MunitCatsEffect   = "org.typelevel"   %% "munit-cats-effect" % "2.0.0"    % Test
  val MunitScalacheck   = "org.scalameta"   %% "munit-scalacheck"  % "1.0.0"    % Test
  val Scalacheck        = "org.scalacheck"  %% "scalacheck"        % "1.18.1"   % Test
}
