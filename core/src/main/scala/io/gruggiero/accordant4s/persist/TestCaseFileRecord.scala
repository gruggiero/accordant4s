package io.gruggiero.accordant4s.persist

import io.gruggiero.accordant4s.spec.TestCase

/**
 * Versioned persistence envelope around a [[TestCase]]: the schema version (so
 * future format changes are detectable), the originating spec name (informational),
 * and the test case itself.
 */
final case class TestCaseFileRecord[S](schemaVersion: Int, specName: String, testCase: TestCase[S])
    derives CanEqual
