addSbtPlugin("org.scalameta"                % "sbt-scalafmt"         % "2.5.4")
addSbtPlugin("ch.epfl.scala"                % "sbt-scalafix"         % "0.14.0")
addSbtPlugin("io.stryker-mutator"           % "sbt-stryker4s"        % "0.21.0")
addSbtPlugin("com.disneystreaming.smithy4s" % "smithy4s-sbt-codegen" % "0.18.33")
addSbtPlugin("org.wartremover"              % "sbt-wartremover"      % "3.5.8")

// sbt-wartremover 3.2.3 (latest, Oct 2024) requests org.wartremover:wartremover_3.8.4,
// which does not exist on Maven Central — wartremover has no Scala 3.8.x build yet.
// Ring 1 falls back to Scalafix DisableSyntax (.scalafix.conf) until a compatible
// wartremover release is published; re-add then. See capability-profile.md.
