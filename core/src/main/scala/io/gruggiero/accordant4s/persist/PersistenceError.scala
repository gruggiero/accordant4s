package io.gruggiero.accordant4s.persist

/**
 * Why loading a persisted test-case record failed — as data, never an exception.
 * `DecodeFailed` wraps a circe error (malformed or structurally invalid JSON);
 * `VersionMismatch` reports a record whose schema version this build does not read.
 */
enum PersistenceError derives CanEqual:
  case DecodeFailed(error: io.circe.Error)
  case VersionMismatch(found: Int, expected: Int)
