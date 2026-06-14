package io.gruggiero.accordant4s.domain

import cats.data.NonEmptyList

/**
 * Structured, data-carrying deviation reasons produced by the oracle.
 *
 * Oracle-core constructs `CheckFailed` (the atomic violations a `ResponseCheck`
 * emits — accumulated flatly, see `ProfileEval.allows`) and `UnknownOperation`
 * (dispatch of an unregistered handle). `ProfileExhausted` is the totality
 * witness when a profile yields no survivors yet no atomic violation (a state
 * that cannot arise for the non-empty outcomes oracle-core builds, but keeps
 * `allows` total without a partial `.get`/`throw`).
 *
 * `NoBranchMatched` is part of the committed algebra but is NOT constructed by
 * oracle-core: the binding Ring-3 invariant ("violation count == total failed
 * atomic checks", Property: Deviant accumulates) requires a FLAT list of atoms,
 * so per-candidate wrapping is deferred to the richer reporting in later specs
 * (test-execution / linearizability). Spec 8 additionally adds a
 * `NotLinearizable` variant; every exhaustive match introduced here must be
 * extended then — no catch-all `case _`.
 */
enum SpecViolation derives CanEqual:
  case CheckFailed(op: OperationName, detail: String)
  case UnknownOperation(name: OperationName)
  case NoBranchMatched(op: OperationName, branchFailures: NonEmptyList[SpecViolation])
  case ProfileExhausted(op: OperationName)
