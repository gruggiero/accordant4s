package io.gruggiero.accordant4s.typecontract

// ═══════════════════════════════════════════════════════════════════════════
//  Test-execution compile-negative obligation (post-implementation residue).
//
//  At Step 3 the type declarations were PROMOTED out of this file into their
//  real homes:
//    - `engine.SystemUnderTest[F, S]`
//    - `engine.ExecutionHooks[F]`
//    - `engine.ExecutionReport[S]`
//    - `engine.TestCaseExecutor`
//    - `munit.AccordantSuite[S]` (the `accordant4s-munit` module's main sources)
//
//  What remains here is the compile-negative evidence that cannot live in main
//  sources: a `SystemUnderTest` whose `execute(call)` answers with a type other
//  than `call.Res` does not type-check — the dependent result type ties the
//  SUT's answer to the call's operation, so wrong-typed SUT responses are
//  unrepresentable. The behavioural requirements are exercised by the test
//  oracles (TestExecutionProperties in `core`, AccordantSuiteProperties in
//  `munit`).
//
//  Types are fully qualified inside the `typeCheckErrors` strings so no import
//  is needed (an import used only inside a macro string trips `-Wunused`).
// ═══════════════════════════════════════════════════════════════════════════

import scala.compiletime.testing.{Error => TypeCheckError, typeCheckErrors}

object TestExecutionTypeContract:

  /**
   * CN: a `SystemUnderTest` whose `execute` answers with a type other than
   * `call.Res` does not type-check — wrong-typed SUT responses are
   * unrepresentable (the dependent result type ties the answer to the call).
   */
  val cnWrongSutResponseType: List[TypeCheckError] = typeCheckErrors(
    """
    new io.gruggiero.accordant4s.engine.SystemUnderTest[
      cats.effect.IO,
      io.gruggiero.accordant4s.fixtures.BankState
    ]:
      def execute(
          call: io.gruggiero.accordant4s.spec.OperationCall[io.gruggiero.accordant4s.fixtures.BankState]
      ): cats.effect.IO[call.Res] = cats.effect.IO.pure("wrong-typed response")
      def reset: cats.effect.IO[Unit] = cats.effect.IO.unit
    """
  )
