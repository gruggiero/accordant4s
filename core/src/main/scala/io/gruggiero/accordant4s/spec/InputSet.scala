package io.gruggiero.accordant4s.spec

import cats.Show
import cats.data.NonEmptyList
import cats.syntax.all._
import hedgehog.core.Seed
import hedgehog.{Gen, Size}
import io.gruggiero.accordant4s.domain.CallLabel

/**
 * An ordered, label-unique collection of [[OperationCall]]s. The private
 * constructor + smart constructors guarantee the invariant: no two calls in any
 * `InputSet` share a label.
 */
final case class InputSet[S] private (calls: List[OperationCall[S]]) derives CanEqual:

  def labels: List[CallLabel] = calls.map(_.label)

  def size: Int = calls.size

  /**
   * Concatenation. Preserves order (this set's calls then `that`'s) and rejects
   * any duplicate labels with a `Left` listing exactly the colliding labels.
   */
  def ++(that: InputSet[S]): Either[NonEmptyList[CallLabel], InputSet[S]] =
    InputSet.of(calls ++ that.calls)

object InputSet:

  def empty[S]: InputSet[S] = InputSet(Nil)

  /**
   * Build an `InputSet` from raw calls, enforcing label uniqueness: a `Left`
   * lists each colliding label once (in first-occurrence order), otherwise a
   * `Right` carrying the calls in their given order.
   */
  def of[S](calls: List[OperationCall[S]]): Either[NonEmptyList[CallLabel], InputSet[S]] =
    NonEmptyList.fromList(duplicateLabels(calls)) match
      case Some(collisions) => Left(collisions)
      case None             => Right(InputSet(calls))

  /**
   * Deterministically sample at most `n` calls from `gen` threading `seed`, each
   * labeled `"<opName>(<Show[Req]>)"`, with duplicate requests collapsed before
   * labeling. Determinism (same `(gen, n, seed)` → same set) makes the state
   * graph reproducible; the label-dedup pass keeps the uniqueness invariant
   * total even for a non-injective `Show`.
   */
  def fromGen[R, Re, S](op: Operation[R, Re, S], gen: Gen[R], n: Int, seed: Long)(using
      Show[R]
  ): InputSet[S] =
    val requests = sample(gen, n, seed).distinct
    val calls    = requests.map(r => OperationCall(op, r, label(op, r)): OperationCall[S])
    InputSet(dedupByLabel(calls))

  private def label[R, Re, S](op: Operation[R, Re, S], req: R)(using Show[R]): CallLabel =
    CallLabel.applyUnsafe((op.name: String) + "(" + req.show + ")")

  private def duplicateLabels[S](calls: List[OperationCall[S]]): List[CallLabel] =
    val labels = calls.map(_.label)
    val counts = labels.groupBy(l => l: String).view.mapValues(_.length).toMap
    labels.distinct.filter(l => counts.getOrElse(l: String, 0) > 1)

  private def dedupByLabel[S](calls: List[OperationCall[S]]): List[OperationCall[S]] =
    calls
      .foldLeft((Set.empty[String], List.empty[OperationCall[S]])) { case ((seen, acc), call) =>
        val key = call.label: String
        if seen.contains(key) then (seen, acc) else (seen + key, call :: acc)
      }
      ._2
      .reverse

  private def sample[A](gen: Gen[A], n: Int, seed: Long): List[A] =
    val size = Size(Size.max)
    val (_, drawn) =
      (0 until math.max(0, n)).foldLeft((Seed.fromLong(seed), List.empty[A])) {
        case ((current, acc), _) =>
          val (next, value) = gen.run(size, current).value
          (next, value.fold(acc)(a => a :: acc))
      }
    drawn.reverse
