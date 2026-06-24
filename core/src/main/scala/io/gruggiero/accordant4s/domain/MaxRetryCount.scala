package io.gruggiero.accordant4s.domain

import io.github.iltotore.iron.RefinedType
import io.github.iltotore.iron.constraint.all._

/**
 * Bound on idempotent connection retries; positive by construction (Iron
 * `Positive`), so zero or negative retry counts are unrepresentable. A
 * compile-negative obligation (`MaxRetryCount(0)` does not type-check) is
 * asserted in the http-binding typed contract.
 */
object MaxRetryCount extends RefinedType[Int, Positive]
type MaxRetryCount = MaxRetryCount.T
