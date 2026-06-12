import Dependencies._

ThisBuild / organization := "io.gruggiero"
ThisBuild / scalaVersion := "3.8.4"
ThisBuild / version      := "0.1.0-SNAPSHOT"

ThisBuild / scalacOptions ++= Seq(
  "-Werror",
  "-Wunused:all",
  "-Wvalue-discard",
  "-deprecation",
  "-feature",
  "-language:strictEquality",
  "-language:adhocExtensions",
)

ThisBuild / semanticdbEnabled := true
ThisBuild / semanticdbVersion := scalafixSemanticdb.revision

lazy val root = (project in file("."))
  .settings(
    name := "accordant4s",
    libraryDependencies ++= Seq(
      // Core
      CatsEffect,
      FS2,
      // Testing
      Munit,
      MunitCatsEffect,
      MunitScalacheck,
      Scalacheck,
    ),
    testFrameworks += new TestFramework("munit.Framework"),
  )
