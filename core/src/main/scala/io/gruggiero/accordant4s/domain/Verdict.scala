package io.gruggiero.accordant4s.domain

import cats.data.NonEmptyList

/** The result of `Spec.allows`: either a surviving profile, or accumulated violations. */
enum Verdict[S] derives CanEqual:
  case Conformant(profile: StateProfile[S])
  case Deviant(violations: NonEmptyList[SpecViolation])
