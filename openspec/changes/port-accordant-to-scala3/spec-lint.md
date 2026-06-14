# Spec Lint Report

Change: `port-accordant-to-scala3` — linted 2026-06-12 after the v2 (renumbered
verified-scala3 schema) retrofit of all 8 specs. Stack assumptions checked against
`capability-profile.md` (munit + Hedgehog + cats-effect; no actor/telemetry
stack).

`✅*` on check 6: the concept inventory is EMPTY (new project, per schema rules).
"Concepts Used" rows in specs 2–8 are forward references to concepts that earlier
specs in THIS change commit to introduce, each annotated "(introduced by spec:N)".
Step 0 of the apply phase re-verifies actual existence in source before each spec
is implemented, so resolution is enforced at the right time.

## Checks

1. Every requirement has concrete Given/When/Then clauses
2. Every `Then` is observable (return value, persisted event, emitted message, error value)
3. Every scenario is testable with the detected stack (capability-profile.md)
4. Every error path is specified
5. Every new public concept appears in "Concepts Introduced"
6. Every reused concept exists in concept-inventory.md
7. Every property has a declared generator strategy
8. Every temporal property has a trigger event and a response event
9. No vague words without a concrete definition
10. Every "unreachable" claim has a type-level proof obligation or explicit runtime check
11. Every enum/GADT extension states how existing pattern matches behave
12. The Proof Obligations table covers every requirement, scenario, invariant, and introduced type constraint

## Results

### Spec: specs/oracle-core/spec.md

| # | Check | Status / Detail |
|---|-------|-----------------|
| 1 | Given/When/Then concrete | ✅ |
| 2 | Then observable | ✅ — verdicts, reports, Left values, graph nodes/edges, counters |
| 3 | Scenarios testable | ✅ — munit + Hedgehog (`HedgehogSuite`) + cats-effect IO per capability-profile.md |
| 4 | Error paths specified | ✅ |
| 5 | New concepts declared | ✅ |
| 6 | Reused concepts resolved | ✅* — inventory is empty (new project); the 1 'used' row is the explicit 'None — new project' marker |
| 7 | Generator strategies | ✅ — every property carries a **Generator strategy** line (constructive Hedgehog gens; no `.ensure`-filtering; no Arbitrary) |
| 8 | Temporal trigger/response | ✅ — vacuous: no Temporal Properties section in any spec (Ring 9 unchecked) |
| 9 | No vague words | ✅ — 'satisfies the declared check' wording; no bare vague words |
| 10 | Unreachable claims proven | ✅ — 'silent override is unrepresentable' → Either-returning register (runtime, specified); StateProfile emptiness → compile-negative stub |
| 11 | Enum extension behavior | ✅ — n/a: introduces `SpecViolation`, extends nothing |
| 12 | Proof obligations complete | ✅ — Proof Obligations table present; every row names its mechanism |

**Verdict: PASS**

### Spec: specs/input-sets/spec.md

| # | Check | Status / Detail |
|---|-------|-----------------|
| 1 | Given/When/Then concrete | ✅ |
| 2 | Then observable | ✅ — verdicts, reports, Left values, graph nodes/edges, counters |
| 3 | Scenarios testable | ✅ — munit + Hedgehog (`HedgehogSuite`) + cats-effect IO per capability-profile.md |
| 4 | Error paths specified | ✅ |
| 5 | New concepts declared | ✅ |
| 6 | Reused concepts resolved | ✅* — references oracle-core concepts (forward refs within this change) |
| 7 | Generator strategies | ✅ — every property carries a **Generator strategy** line (constructive Hedgehog gens; no `.ensure`-filtering; no Arbitrary) |
| 8 | Temporal trigger/response | ✅ — vacuous: no Temporal Properties section in any spec (Ring 9 unchecked) |
| 9 | No vague words | ✅ |
| 10 | Unreachable claims proven | ✅ — existential leak prevention → CN stub + Ring 8 grep |
| 11 | Enum extension behavior | ✅ — n/a: no enum extension |
| 12 | Proof obligations complete | ✅ — Proof Obligations table present; every row names its mechanism |

**Verdict: PASS**

### Spec: specs/state-graph/spec.md

| # | Check | Status / Detail |
|---|-------|-----------------|
| 1 | Given/When/Then concrete | ✅ |
| 2 | Then observable | ✅ — verdicts, reports, Left values, graph nodes/edges, counters |
| 3 | Scenarios testable | ✅ — munit + Hedgehog (`HedgehogSuite`) + cats-effect IO per capability-profile.md |
| 4 | Error paths specified | ✅ — note: canonicalization & streaming requirements have no failure mode (pure functions); their edge scenarios (diamond collapse, early termination) are recorded as the boundary behaviour |
| 5 | New concepts declared | ✅ |
| 6 | Reused concepts resolved | ✅* — forward refs to specs 1–2 |
| 7 | Generator strategies | ✅ — every property carries a **Generator strategy** line (constructive Hedgehog gens; no `.ensure`-filtering; no Arbitrary) |
| 8 | Temporal trigger/response | ✅ — vacuous: no Temporal Properties section in any spec (Ring 9 unchecked) |
| 9 | No vague words | ✅ |
| 10 | Unreachable claims proven | ✅ — MaxDepth bound → Iron type + CN stub |
| 11 | Enum extension behavior | ✅ — n/a |
| 12 | Proof obligations complete | ✅ — Proof Obligations table present; every row names its mechanism |

**Verdict: PASS**

### Spec: specs/test-generation/spec.md

| # | Check | Status / Detail |
|---|-------|-----------------|
| 1 | Given/When/Then concrete | ✅ |
| 2 | Then observable | ✅ — verdicts, reports, Left values, graph nodes/edges, counters |
| 3 | Scenarios testable | ✅ — munit + Hedgehog (`HedgehogSuite`) + cats-effect IO per capability-profile.md |
| 4 | Error paths specified | ✅ |
| 5 | New concepts declared | ✅ |
| 6 | Reused concepts resolved | ✅* — forward refs to specs 1–3 |
| 7 | Generator strategies | ✅ — every property carries a **Generator strategy** line (constructive Hedgehog gens; no `.ensure`-filtering; no Arbitrary) |
| 8 | Temporal trigger/response | ✅ — vacuous: no Temporal Properties section in any spec (Ring 9 unchecked) |
| 9 | No vague words | ✅ |
| 10 | Unreachable claims proven | ✅ — 'unreachable target impossible by construction' cites the type-level argument (generator consumes only graph.edges) + Ring 8 obligation |
| 11 | Enum extension behavior | ✅ — n/a |
| 12 | Proof obligations complete | ✅ — Proof Obligations table present; every row names its mechanism |

**Verdict: PASS**

### Spec: specs/test-execution/spec.md

| # | Check | Status / Detail |
|---|-------|-----------------|
| 1 | Given/When/Then concrete | ✅ |
| 2 | Then observable | ✅ — verdicts, reports, Left values, graph nodes/edges, counters |
| 3 | Scenarios testable | ✅ — munit + Hedgehog (`HedgehogSuite`) + cats-effect IO per capability-profile.md |
| 4 | Error paths specified | ✅ |
| 5 | New concepts declared | ✅ |
| 6 | Reused concepts resolved | ✅* — forward refs to specs 1–4 |
| 7 | Generator strategies | ✅ — every property carries a **Generator strategy** line (constructive Hedgehog gens; no `.ensure`-filtering; no Arbitrary) |
| 8 | Temporal trigger/response | ✅ — vacuous: no Temporal Properties section in any spec (Ring 9 unchecked) |
| 9 | No vague words | ✅ |
| 10 | Unreachable claims proven | ✅ — wrong-typed SUT response unrepresentable → dependent type + CN stub |
| 11 | Enum extension behavior | ✅ — n/a |
| 12 | Proof obligations complete | ✅ — Proof Obligations table present; every row names its mechanism |

**Verdict: PASS**

### Spec: specs/http-binding/spec.md

| # | Check | Status / Detail |
|---|-------|-----------------|
| 1 | Given/When/Then concrete | ✅ |
| 2 | Then observable | ✅ — verdicts, reports, Left values, graph nodes/edges, counters |
| 3 | Scenarios testable | ✅ — munit + Hedgehog (`HedgehogSuite`) + cats-effect IO per capability-profile.md |
| 4 | Error paths specified | ✅ |
| 5 | New concepts declared | ✅ |
| 6 | Reused concepts resolved | ✅* — forward refs to specs 1–5 |
| 7 | Generator strategies | ✅ — every property carries a **Generator strategy** line (constructive Hedgehog gens; no `.ensure`-filtering; no Arbitrary) |
| 8 | Temporal trigger/response | ✅ — vacuous: no Temporal Properties section in any spec (Ring 9 unchecked) |
| 9 | No vague words | ✅ |
| 10 | Unreachable claims proven | ✅ — MaxRetryCount → Iron + CN stub |
| 11 | Enum extension behavior | ✅ — n/a |
| 12 | Proof obligations complete | ✅ — Proof Obligations table present; every row names its mechanism |

**Verdict: PASS**

### Spec: specs/smithy4s-derivation/spec.md

| # | Check | Status / Detail |
|---|-------|-----------------|
| 1 | Given/When/Then concrete | ✅ |
| 2 | Then observable | ✅ — verdicts, reports, Left values, graph nodes/edges, counters |
| 3 | Scenarios testable | ✅ — munit + Hedgehog (`HedgehogSuite`) + cats-effect IO per capability-profile.md |
| 4 | Error paths specified | ✅ |
| 5 | New concepts declared | ✅ |
| 6 | Reused concepts resolved | ✅* — forward refs to specs 1–6 |
| 7 | Generator strategies | ✅ — every property carries a **Generator strategy** line (constructive Hedgehog gens; no `.ensure`-filtering; no Arbitrary) |
| 8 | Temporal trigger/response | ✅ — vacuous: no Temporal Properties section in any spec (Ring 9 unchecked) |
| 9 | No vague words | ✅ |
| 10 | Unreachable claims proven | ✅ — contract/oracle drift is a type error → CN stub realizes the compile-evidence claim |
| 11 | Enum extension behavior | ✅ — n/a |
| 12 | Proof obligations complete | ✅ — Proof Obligations table present; every row names its mechanism |

**Verdict: PASS**

### Spec: specs/linearizability/spec.md

| # | Check | Status / Detail |
|---|-------|-----------------|
| 1 | Given/When/Then concrete | ✅ |
| 2 | Then observable | ✅ — verdicts, reports, Left values, graph nodes/edges, counters |
| 3 | Scenarios testable | ✅ — munit + Hedgehog (`HedgehogSuite`) + cats-effect IO per capability-profile.md |
| 4 | Error paths specified | ✅ |
| 5 | New concepts declared | ✅ |
| 6 | Reused concepts resolved | ✅* — forward refs to specs 1–5 |
| 7 | Generator strategies | ✅ — every property carries a **Generator strategy** line (constructive Hedgehog gens; no `.ensure`-filtering; no Arbitrary) |
| 8 | Temporal trigger/response | ✅ — vacuous: no Temporal Properties section in any spec (Ring 9 unchecked) |
| 9 | No vague words | ✅ |
| 10 | Unreachable claims proven | ✅ — ParallelWidth ≤ 4 cap → Iron + CN stubs (type-level, not a runtime guard) |
| 11 | Enum extension behavior | ✅ — `NotLinearizable` added to sealed `SpecViolation`; spec states exhaustive matches become compile errors and must add explicit cases (no `case _`) |
| 12 | Proof obligations complete | ✅ — Proof Obligations table present; every row names its mechanism |

**Verdict: PASS**

## Summary

| Spec | Verdict | Blocking Issues |
|------|---------|-----------------|
| specs/oracle-core/spec.md | PASS | none |
| specs/input-sets/spec.md | PASS | none |
| specs/state-graph/spec.md | PASS | none |
| specs/test-generation/spec.md | PASS | none |
| specs/test-execution/spec.md | PASS | none |
| specs/http-binding/spec.md | PASS | none |
| specs/smithy4s-derivation/spec.md | PASS | none |
| specs/linearizability/spec.md | PASS | none |

All 8 specs PASS — design and implementation-order may proceed.

Fixes applied during this lint pass (for traceability):
- linearizability: added the enum-extension impact statement for `NotLinearizable`
  on sealed `SpecViolation` (check 11); replaced "valid interleaving"/"two valid
  winners" with conformance-defined wording (check 9)
- oracle-core: replaced the undefined `validResponse` placeholder with "a response
  that satisfies `Withdraw`'s declared check" (check 9)
