package io.gruggiero.accordant4s.fixtures

import cats.{Eq, Hash, Show}

/** Reference model (account id -> balance) shared by every spec's test oracle. */
final case class BankState(accounts: Map[String, BigDecimal]) derives CanEqual

object BankState:
  given Eq[BankState]   = Eq.fromUniversalEquals
  given Hash[BankState] = Hash.fromUniversalHashCode
  given Show[BankState] = Show.fromToString
