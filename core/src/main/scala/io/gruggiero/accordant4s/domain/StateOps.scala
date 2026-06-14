package io.gruggiero.accordant4s.domain

import cats.{Eq, Hash, Show}

/**
 * The capabilities the library requires of a user state `S`: equality (for
 * profile dedup and survivor comparison), hashing and show (diagnostics), and
 * a `CanEqual` witness (mandatory under `-language:strictEquality`).
 */
trait StateOps[S]:
  def eqS: Eq[S]
  def hashS: Hash[S]
  def showS: Show[S]
  def canEqualS: CanEqual[S, S]

object StateOps:

  given derived[S](using e: Eq[S], h: Hash[S], sh: Show[S], ce: CanEqual[S, S]): StateOps[S] =
    new StateOps[S]:
      def eqS: Eq[S]                = e
      def hashS: Hash[S]            = h
      def showS: Show[S]            = sh
      def canEqualS: CanEqual[S, S] = ce
