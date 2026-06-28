# Spec: Linearizability

Port of Accordant's concurrent testing (`GenerateConcurrentTests` + linearizability
checking, tutorial 05): test cases shaped as sequential prefix → parallel section →
sequential suffix; results are conformant iff SOME sequential ordering of the parallel
operations explains the observed responses. The permutation search is a pure Ring 6
kernel; only the parallel execution touches `IO`.

## Concepts Used (from inventory)

| Concept | Kind | Package |
|---------|------|---------|
| `Spec[S]` / `allows` / `Verdict[S]` / `StateProfile[S]` | various | `spec`/`domain` (introduced by spec:oracle-core) |
| `SpecViolation` | enum | `domain` (introduced by spec:oracle-core; gains `NotLinearizable` variant here) |
| `OperationCall[S]` / `InputSet[S]` | sealed trait / case class | `spec` (introduced by spec:input-sets) |
| `StateGraph[S]` / `GraphExplorer` | case class / object | `engine` (introduced by spec:state-graph) |
| `TestCase[S]` / `TestCasePersistence` | case class / object | `spec`/`persist` (introduced by spec:test-generation) |
| `SystemUnderTest[F[_], S]` / `ExecutionHooks` / `RefSut` | trait / case class / fixture | `engine` (introduced by spec:test-execution) |
| `MaxDepth` | opaque type | `domain` (introduced by spec:state-graph) |
| `BankState` fixture | test case class | (introduced by spec:oracle-core) |

## Concepts Introduced (new)

| Concept | Kind | Description |
|---------|------|-------------|
| `ParallelWidth` | opaque type (`Int :| Positive & LessEqual[4]`) | Caps the parallel section (≤ 4! = 24 orderings) |
| `ConcurrentTestCase[S]` | case class | `prefix: List[OperationCall[S]]`, `parallel: NonEmptyList[OperationCall[S]]`, `suffix: List[OperationCall[S]]` |
| `ConcurrentTestCaseGenerator` | object | Derive concurrent cases from a state graph + input set (`generateConcurrent(graph, inputs, width)`) |
| `ObservedResult[S]` | case class | `(call, response)` pair captured from parallel execution |
| `Linearization` | pure object (`engine.verified`) | `findOrdering(spec, profile, observed): Option[(perm, StateProfile[S])]` — ∃-permutation search |
| `ConcurrentExecutor` | object | `IO.parTraverseN` execution of the parallel section + prefix/suffix replay + checker invocation |
| `ConcurrentReport[S]` | enum | `Linearizable(witnessOrdering)` \| `RaceDetected(observed, orderingsTried, reproCase)` |
| `NotLinearizable` | new `SpecViolation` variant | Carries observed results + tried orderings. **Enum-extension impact**: `SpecViolation` is a sealed enum with exhaustive matches (oracle reporting, munit message rendering); adding this variant makes those matches compile errors under `-Werror`, and each must add an explicit `NotLinearizable` case — `case _` fallbacks are forbidden (Ring 1 dangerous-pattern scan + Ring 8) |
| `ConcurrentTestCaseFileRecord` | case class (`persist`) | circe persistence of failing concurrent cases (Accordant parity) |

## ADDED Requirements

### Requirement: Concurrent test case shape and generation

`generateConcurrent` SHALL deterministically produce cases with a graph-valid prefix path, a parallel section of 2..width distinct calls applicable at the prefix's end state, and an observation suffix.

**Given** a state graph, an input set, and a `ParallelWidth`
**When** `generateConcurrent` runs
**Then** each produced case has a graph-valid prefix path, a parallel section of 2..width distinct calls applicable at the prefix's end state, and a suffix of observation calls; generation is deterministic

#### Scenario: Happy path — booking race shape

**Given** the slot-booking spec (`CreateSlot`, `BookSlot`) and width 2
**When** generation runs
**Then** a case `CreateSlot("9am") → [BookSlot(alice) ∥ BookSlot(bob)] → GetSlot("9am")` is among the output (tutorial 05's canonical shape)

#### Scenario: Edge case — width bound

**Given** any inputs and width w
**When** generation runs
**Then** no case has a parallel section larger than w

### Requirement: Linearizability verdict

`Linearization.findOrdering` SHALL return the FIRST permutation of the observed (call, response) pairs under which folding `spec.allows` stays `Conformant` (with the resulting profile), or `None` if every permutation deviates.

**Given** observed results of the parallel section and the profile after the prefix
**When** `Linearization.findOrdering` runs
**Then** it returns the FIRST permutation of the observed (call, response) pairs under which folding `spec.allows` stays `Conformant` (with the resulting profile), or `None` if all permutations deviate

**Rationale**: "Results must be explainable by some sequential ordering." Alice-200/Bob-409 is explainable (Alice first); both-200 on one slot is not — that's the race.

#### Scenario: Happy path — conformant interleaving found

**Given** observed results Alice→200, Bob→409 after `CreateSlot("9am")`
**When** the checker runs
**Then** it returns the witness ordering `[Alice, Bob]` and the profile where the slot is Alice's

#### Scenario: Error path — race detected

**Given** observed results Alice→200, Bob→200
**When** the checker runs
**Then** it returns `None`; the executor reports `RaceDetected` with both observations, the orderings tried, and a persistable repro case

#### Scenario: Edge case — ambiguity flows into the profile

**Given** a parallel section whose witness orderings end in different states (e.g. two orderings both conformant under `spec.allows`, with different winners)
**When** more than one permutation is conformant
**Then** the resulting profile is the deduplicated union of all witness end-profiles, and the suffix's oracle validation runs against that union (no false alarms from picking one winner arbitrarily)

### Requirement: Concurrent execution engine

`ConcurrentExecutor.run` SHALL replay the prefix sequentially under oracle validation, launch the parallel calls concurrently (`parTraverseN`, no imposed ordering), capture the `ObservedResult`s, check linearizability, and replay the suffix against the post-parallel profile.

**Given** a `ConcurrentTestCase` and a `SystemUnderTest[IO]`
**When** `ConcurrentExecutor.run` executes
**Then** the prefix replays sequentially (oracle-validated), the parallel calls launch concurrently (`parTraverseN`, no imposed ordering), results are captured as `ObservedResult`s, the checker produces the verdict, and the suffix replays against the post-parallel profile

#### Scenario: Happy path — atomic reference implementation

**Given** `RefSut` with atomic state (`Ref[IO, S]`-backed)
**When** generated concurrent cases run repeatedly
**Then** every report is `Linearizable`

#### Scenario: Error path — racy implementation caught

**Given** a deliberately racy SUT (read-then-write without atomicity on `BookSlot`)
**When** concurrent cases run with enough repetitions
**Then** at least one run reports `RaceDetected`, and the persisted repro record decodes back to the failing case

#### Scenario: Edge case — failure persistence

**Given** a `RaceDetected` report
**When** it is saved via `ConcurrentTestCaseFileRecord`
**Then** loading it reproduces the exact case and observations (Accordant's reproduction-of-nondeterminism workflow)

## Properties (Ring 3)

### Property: Sequential executions are always linearizable

**Generator strategy** (Hedgehog): constructive `genConcurrentCase` (prefix from graph paths, parallel section of `Gen.int(Range.linear(2, width))` distinct calls applicable at the prefix end-state, observation suffix) × `genPermutationOrder` (`Gen.element1` over the index permutations)

**Invariant**: If the parallel section is actually executed one-at-a-time in ANY order against a conformant SUT, the checker finds an ordering — sequential behaviour is never flagged as a race.

```
property("sequential executions are always linearizable") {
  for {
    cc    <- genConcurrentCase.forAll
    order <- genPermutationOrder.forAll
  } yield {
    val observed = runSequentially(RefSut(spec), cc.parallel.reorder(order))
    Result.assert(Linearization.findOrdering(spec, prefixProfile(cc), observed).isDefined)
  }
}
```

### Property: Checker is order-insensitive in its input

**Generator strategy**: `genObservedResults` — (call, response) pairs with responses drawn from each operation's `mock` Gen; width ≤ 4 keeps ≤ 24 permutations, brute-force comparable

**Invariant**: The verdict (Some/None) does not depend on the order in which observed results are presented.

```
property("checker is order-insensitive in its input") {
  for {
    observed <- genObservedResults.forAll
    order    <- genPermutationOrder.forAll
  } yield Result.assert(
    findOrdering(spec, profile, observed).isDefined ==
      findOrdering(spec, profile, observed.reorder(order)).isDefined
  )
}
```

### Property: Witness validity

**Generator strategy**: `genObservedResults` as above

**Invariant**: Whenever the checker returns a witness ordering, folding the oracle over exactly that ordering is `Conformant` and ends in the returned profile.

```
property("witness validity") {
  for {
    observed <- genObservedResults.forAll
  } yield Result.assert(findOrdering(spec, profile, observed).forall { (perm, endProfile) =>
    foldAllows(spec, profile, perm) == Conformant(endProfile)
  })
}
```

### Property: Exhaustiveness on rejection

**Generator strategy** (Hedgehog): `genObservedResults` with `Gen.frequency1` biased toward non-linearizable result sets (e.g. double-success); verdict cross-checked against explicit brute-force enumeration

**Invariant**: When the checker returns `None`, every permutation of the observed results deviates under the oracle (cross-checked against brute-force enumeration for width ≤ 4).

```
property("exhaustiveness on rejection") {
  for {
    observed <- genObservedResults.forAll
  } yield Result.assert(
    !findOrdering(spec, profile, observed).isEmpty ||
      observed.permutations.forall(p => foldAllows(spec, profile, p).isDeviant)
  )
}
```

### Property: Concurrent persistence roundtrip

**Generator strategy**: `genConcurrentRecord` from `genConcurrentCase` + observed results; covers unicode labels and timeout-response payloads

**Invariant**: `decode(encode(record)) == Right(record)` for all concurrent file records.

```
property("concurrent persistence roundtrip") {
  for {
    rec <- genConcurrentRecord.forAll
  } yield Result.assert(ConcurrentPersistence.fromJson(ConcurrentPersistence.toJson(rec)) == Right(rec))
}
```

## Compile-Negative Obligations

| Must NOT compile | Why | Test |
|---|---|---|
| `ParallelWidth(5)` / `ParallelWidth(0)` (literals) | Iron `Positive & LessEqual[4]` — the n! permutation blow-up is capped at the type level (4! = 24), not by a runtime guard | `assertDoesNotCompile` stubs |

## Formal Contracts (Ring 6)

`engine.verified.Linearization` (PureScala subset — List recursion, no IO/Gen):

```
def findOrdering(observed: List[Observed], profile: List[S]): Option[(List[Observed], List[S])] = {
  require(observed.nonEmpty && profile.nonEmpty && observed.size <= 4)
  ...
} ensuring {
  case Some((perm, end)) => isPermutation(perm, observed) && end.nonEmpty && foldConformant(perm, profile, end)
  case None              => true  // exhaustiveness proven separately via permutations bound
}
```

Best-effort: if Stainless rejects the full post-condition, verify `isPermutation` and
profile non-emptiness only; record the downgrade in the checkpoint.

## Proof Obligations

| Obligation | Source | Enforcement | Test/Artifact |
|---|---|---|---|
| Concurrent cases: graph-valid prefix, 2..width parallel, suffix; deterministic | Req: shape / Scenarios: booking shape, width bound | scenario tests | "shape" tests |
| Parallel section size ≤ width | Scenario: width bound | type system (`ParallelWidth`) + scenario test | CN stub + scenario |
| Checker returns first conformant ordering or None | Req: verdict / Scenarios: witness, race + Property: witness validity | property test | "witness validity" |
| `None` ⇒ ALL permutations deviate (no false races) | Property: exhaustiveness | property test — brute-force comparison at width ≤ 4 (serves as the Ring 7 surrogate: no model checker available) | "exhaustiveness on rejection" |
| Sequentially-executed parallel sections never flagged | Property: sequential always linearizable | property test | same |
| Verdict independent of observed-result order | Property: order-insensitive | property test | "order-insensitive" |
| Multiple witnesses ⇒ profile = deduplicated union | Scenario: ambiguity union | scenario test + adversarial review (Ring 8: no arbitrary-winner shortcut) | "ambiguity union" |
| Prefix/suffix oracle-validated, parallel via `parTraverseN` | Req: executor / Scenarios: atomic passes, racy caught | scenario tests (`Ref[IO]`-backed RefSut; racy SUT with read-then-write) | executor tests |
| Failing cases persist and reload exactly | Scenario: persistence + Property: roundtrip | property test (round-trip law, Ring 4) + fixture baseline | "concurrent persistence roundtrip" |
| `findOrdering` permutation/non-emptiness contracts | Formal Contracts | Stainless (Ring 6, best-effort; downgrade recorded) | `engine.verified.Linearization` |

## Verification Rings

Ring 0 ✅ · Ring 1 ✅ · Ring 2 ✅ · Ring 3 ✅ · Ring 4 ✅ (concurrent file-record fixtures) · Ring 5 ✅ (90–95%, checker kernel) · Ring 6 ✅ best-effort (`Linearization`) · Ring 7 — (no checker; exhaustiveness via the brute-force property) · Ring 8 ✅ · Ring 9 —
