# Spec: Linearizability

Port of Accordant's concurrent testing (`GenerateConcurrentTests` + linearizability
checking, tutorial 05): test cases shaped as sequential prefix → parallel section →
sequential suffix; results are conformant iff SOME sequential ordering of the parallel
operations explains the observed responses. The permutation search is a pure Ring 4
kernel; only the parallel execution touches `IO`.

## Concepts Used (from inventory)

| Concept | Kind | Package |
|---------|------|---------|
| `Spec[S]` / `allows` / `Verdict[S]` / `StateProfile[S]` | various | `spec`/`domain` (introduced by spec:oracle-core) |
| `SpecViolation` | enum | `domain` (introduced by spec:oracle-core; gains `NotLinearizable` variant here) |
| `OperationCall[S]` / `InputSet[S]` | sealed trait / case class | `spec` (introduced by spec:input-sets) |
| `StateGraph[S]` / `GraphExplorer` | case class / object | `engine` (introduced by spec:state-graph) |
| `TestCase[S]` / `TestCasePersistence` | case class / object | `domain`/`persist` (introduced by spec:test-generation) |
| `SystemUnderTest[F]` / `ExecutionHooks` / `RefSut` | trait / case class / fixture | `engine` (introduced by spec:test-execution) |
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
| `NotLinearizable` | new `SpecViolation` variant | Carries observed results + tried orderings |
| `ConcurrentTestCaseFileRecord` | case class (`persist`) | circe persistence of failing concurrent cases (Accordant parity) |

## ADDED Requirements

### Requirement: Concurrent test case shape and generation

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

**Given** observed results of the parallel section and the profile after the prefix
**When** `Linearization.findOrdering` runs
**Then** it returns the FIRST permutation of the observed (call, response) pairs under which folding `spec.allows` stays `Conformant` (with the resulting profile), or `None` if all permutations deviate

**Rationale**: "Results must be explainable by some sequential ordering." Alice-200/Bob-409 is explainable (Alice first); both-200 on one slot is not — that's the race.

#### Scenario: Happy path — valid interleaving found

**Given** observed results Alice→200, Bob→409 after `CreateSlot("9am")`
**When** the checker runs
**Then** it returns the witness ordering `[Alice, Bob]` and the profile where the slot is Alice's

#### Scenario: Error path — race detected

**Given** observed results Alice→200, Bob→200
**When** the checker runs
**Then** it returns `None`; the executor reports `RaceDetected` with both observations, the orderings tried, and a persistable repro case

#### Scenario: Edge case — ambiguity flows into the profile

**Given** a parallel section whose witness orderings end in different states (e.g. two valid winners)
**When** more than one permutation is conformant
**Then** the resulting profile is the deduplicated union of all witness end-profiles, and the suffix's oracle validation runs against that union (no false alarms from picking one winner arbitrarily)

### Requirement: Concurrent execution engine

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

## Properties (Ring 2)

### Property: Sequential executions are always linearizable

**Invariant**: If the parallel section is actually executed one-at-a-time in ANY order against a conformant SUT, the checker finds an ordering — sequential behaviour is never flagged as a race.

```
forAll(genConcurrentCase, genPermutationOrder) { (cc, order) =>
  val observed = runSequentially(RefSut(spec), cc.parallel.reorder(order))
  Linearization.findOrdering(spec, prefixProfile(cc), observed).isDefined
}
```

### Property: Checker is order-insensitive in its input

**Invariant**: The verdict (Some/None) does not depend on the order in which observed results are presented.

```
forAll(genObservedResults, genPermutationOrder) { (observed, order) =>
  findOrdering(spec, profile, observed).isDefined ==
    findOrdering(spec, profile, observed.reorder(order)).isDefined
}
```

### Property: Witness validity

**Invariant**: Whenever the checker returns a witness ordering, folding the oracle over exactly that ordering is `Conformant` and ends in the returned profile.

```
forAll(genObservedResults) { observed =>
  findOrdering(spec, profile, observed).forall { (perm, endProfile) =>
    foldAllows(spec, profile, perm) == Conformant(endProfile)
  }
}
```

### Property: Exhaustiveness on rejection

**Invariant**: When the checker returns `None`, every permutation of the observed results deviates under the oracle (cross-checked against brute-force enumeration for width ≤ 4).

```
forAll(genObservedResults) { observed =>
  findOrdering(spec, profile, observed).isEmpty ==>
    observed.permutations.forall(p => foldAllows(spec, profile, p).isDeviant)
}
```

### Property: Concurrent persistence roundtrip

**Invariant**: `decode(encode(record)) == Right(record)` for all concurrent file records.

```
forAll(genConcurrentRecord) { rec =>
  ConcurrentPersistence.fromJson(ConcurrentPersistence.toJson(rec)) == Right(rec)
}
```

## Formal Contracts (Ring 4)

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

## Verification Rings

Ring 0 ✅ · Ring 1 ✅ · Ring 1.5 ✅ · Ring 2 ✅ · Ring 3 ✅ (80%, checker kernel) · Ring 4 ✅ best-effort (`Linearization`) · Ring 5 —
