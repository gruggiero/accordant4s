package io.gruggiero.accordant4s.domain

import io.github.iltotore.iron.RefinedType
import io.github.iltotore.iron.constraint.all._

/**
 * Caps the parallel section. `Positive & LessEqual[4]` — 4! = 24 is the max
 * permutation blow-up for the linearizability search, capped at the type
 * level (CN: `ParallelWidth(5)` / `ParallelWidth(0)` do not compile), not by a
 * runtime guard. Same class of decision as `MaxDepth`/`MaxRetryCount`.
 */
object ParallelWidth extends RefinedType[Int, Positive & LessEqual[4]]
type ParallelWidth = ParallelWidth.T
