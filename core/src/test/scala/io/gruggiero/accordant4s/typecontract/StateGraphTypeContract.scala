package io.gruggiero.accordant4s.typecontract

// ═══════════════════════════════════════════════════════════════════════════
//  State-graph compile-negative obligations (post-implementation residue).
//
//  At Step 3 the type declarations were PROMOTED out of this file into their real
//  homes (`domain.MaxDepth`, `engine.{Node, Edge, StateGraph, GraphExplorer}`).
//  The dependent typing of `sampledResponse` and the explore/stream/compile
//  wiring are exercised by the test oracle (StateGraphProperties); what remains
//  here is the compile-negative evidence that cannot live in main sources.
//
//  `MaxDepth` is fully qualified inside the `typeCheckErrors` strings so no import
//  is needed (an import used only inside a macro string trips `-Wunused`).
// ═══════════════════════════════════════════════════════════════════════════

import scala.compiletime.testing.{Error => TypeCheckError, typeCheckErrors}

object StateGraphTypeContract:

  /** CN: non-positive `MaxDepth` literals do not type-check (Iron `Positive`). */
  val cnZeroDepth: List[TypeCheckError] =
    typeCheckErrors("""io.gruggiero.accordant4s.domain.MaxDepth(0)""")

  val cnNegativeDepth: List[TypeCheckError] =
    typeCheckErrors("""io.gruggiero.accordant4s.domain.MaxDepth(-1)""")
