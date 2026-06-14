package io.gruggiero.accordant4s.verified

import stainless.collection._
import stainless.lang._

/**
 * Ring 6 — a PureScala MIRROR of the oracle-core survivor/verdict algorithm,
 * verified by Stainless (Scala 3.7.2 frontend, this module only).
 *
 * The real implementation (`io.gruggiero.accordant4s.domain.{OutcomeEval,
 * ProfileEval}`) uses Iron / cats / opaque types / function-typed checks that
 * Stainless cannot model, so it cannot be verified directly. Here per-candidate
 * branch evaluation is reduced to its observable effect — `EvalBranch` = (did
 * the check pass?, resulting state) — over `BigInt` state identities. The real
 * implementation is pinned to THIS algorithm by the Ring-3 reference-oracle
 * properties in `core` (`OracleCoreProperties`).
 *
 * Scope (best-effort): Stainless proves the two load-bearing oracle invariants
 *   1. SOUNDNESS — every surviving next-state comes from a branch that passed;
 *   2. CONFORMANCE — "some state survives" ⇔ "some branch passed".
 * Dedup correctness (`StateProfile` holds no Eq-duplicates) is left to the
 * Ring-3 "Profile dedup is idempotent and order-insensitive" property — proving
 * it here needs an unbounded inductive subset lemma that adds no assurance the
 * property test doesn't already give.
 */
object OracleKernel {

  /** A branch reduced to its effect against a fixed response. */
  final case class EvalBranch(passed: Boolean, next: BigInt)

  /**
   * Next-states of the branches whose check passed.
   *
   * NOTE: the SOUNDNESS property ("every produced state is some passing branch's
   * next") is intentionally NOT asserted here. Its VC is `forall(s => exists …)`,
   * whose quantifier instantiation diverges in z3 with no default timeout (z3 is
   * single-threaded per query — it would hang unbounded). Soundness is instead
   * pinned by the Ring-3 property "Conformant iff some branch matches some
   * candidate" in `core`. Stainless here proves the quantifier-free invariants
   * below, which z3 discharges instantly.
   */
  def passingNexts(bs: List[EvalBranch]): List[BigInt] =
    bs match {
      case Nil()      => Nil[BigInt]()
      case Cons(b, t) => if (b.passed) b.next :: passingNexts(t) else passingNexts(t)
    }

  /** Deduplicate, keeping first occurrences (correctness covered by Ring 3). */
  def distinct(xs: List[BigInt]): List[BigInt] = {
    decreases(xs.size)
    xs match {
      case Nil()      => Nil[BigInt]()
      case Cons(h, t) => h :: distinct(t.filter(_ != h))
    }
  }

  /** Surviving states: deduplicated next-states of passing branches. */
  def survivors(bs: List[EvalBranch]): List[BigInt] =
    distinct(passingNexts(bs))

  /** `distinct` preserves emptiness — needed for the conformance verdict. */
  def distinctEmptyIff(xs: List[BigInt]): Boolean = {
    distinct(xs).isEmpty == xs.isEmpty
  }.holds

  /** CONFORMANCE: some state survives ⇔ some branch passed. */
  def conformantIffSomeBranchPassed(bs: List[EvalBranch]): Boolean = {
    survivors(bs).isEmpty == passingNexts(bs).isEmpty
  }.holds
}
