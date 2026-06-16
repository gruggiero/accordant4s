package io.gruggiero.accordant4s.domain

import io.github.iltotore.iron._
import io.github.iltotore.iron.constraint.all._

/**
 * Pluggable path-selection strategy over an explored state graph (Accordant's
 * `StateCoverage` / `TransitionCoverage` / `RandomWalk`). `StateCoverage` and
 * `TransitionCoverage` are systematic; only `RandomWalk` is random, and then
 * solely as a pure function of its explicit `seed` and `count` (positive by
 * construction, so a non-positive walk length is unrepresentable).
 */
enum CoverageAlgorithm derives CanEqual:
  case StateCoverage
  case TransitionCoverage
  case RandomWalk(seed: Long, count: Int :| Positive)
