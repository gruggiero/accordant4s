package io.gruggiero.accordant4s.domain

import io.github.iltotore.iron.RefinedType
import io.github.iltotore.iron.constraint.all._

/**
 * Mandatory exploration bound; positive by construction (Iron `Positive`), so
 * unbounded or nonsensical depths are unrepresentable.
 */
object MaxDepth extends RefinedType[Int, Positive]
type MaxDepth = MaxDepth.T
