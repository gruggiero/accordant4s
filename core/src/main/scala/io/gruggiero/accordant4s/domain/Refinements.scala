package io.gruggiero.accordant4s.domain

import io.github.iltotore.iron.RefinedType
import io.github.iltotore.iron.constraint.all._

/** Registry key for operations; non-blank by construction (Iron `Not[Blank]`). */
object OperationName extends RefinedType[String, Not[Blank]]
type OperationName = OperationName.T

/** Human-readable step identity; non-blank by construction. Consumed by spec 2's `OperationCall[S].label`. */
object CallLabel extends RefinedType[String, Not[Blank]]
type CallLabel = CallLabel.T
