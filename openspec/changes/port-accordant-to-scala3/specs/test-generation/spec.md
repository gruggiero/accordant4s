# Spec: Test Generation

Port of Accordant's path-selection algorithms over the state graph (`StateCoverage`,
`TransitionCoverage`, `RandomWalk`) producing labeled `TestCase`s, plus circe JSON
persistence (Accordant's test-case file records) for reproducing failures.

## Concepts Used (from inventory)

| Concept | Kind | Package |
|---------|------|---------|
| `StateGraph[S]` / `Node[S]` / `Edge[S]` | case classes | `engine` (introduced by spec:state-graph) |
| `GraphExplorer` | object | `engine` (introduced by spec:state-graph) |
| `OperationCall[S]` / `InputSet[S]` | sealed trait / case class | `spec` (introduced by spec:input-sets) |
| `MaxDepth` | opaque type | `domain` (introduced by spec:state-graph) |
| `CallLabel` / `OperationName` | opaque types | `domain` (introduced by spec:oracle-core) |
| `genStateGraph` | Hedgehog generator | test fixtures (introduced by spec:state-graph) |
| `BankState` fixture | test case class | test fixtures (introduced by spec:oracle-core) |

## Concepts Introduced (new)

| Concept | Kind | Description |
|---------|------|-------------|
| `TestCase[S]` | case class | `name: CallLabel`, `initial: S`, `steps: List[OperationCall[S]]` |
| `CoverageAlgorithm` | enum | `StateCoverage` \| `TransitionCoverage` \| `RandomWalk(seed: Long, count: Int :| Positive)` |
| `TestCaseGenerator` | object | `generate(graph, algorithm): Vector[TestCase[S]]` |
| `TestCaseFileRecord` | case class | Versioned persistence envelope (schema version, spec name, test case) |
| `TestCasePersistence` | object (`persist`) | circe encode/decode of `TestCaseFileRecord` given user codecs for `S`/requests |
| `PersistenceError` | enum | `DecodeFailed(io.circe.Error)` \| `VersionMismatch(found, expected)` |
| `genTestCase` | Hedgehog generator | Test cases from generated graphs, for downstream specs |

## ADDED Requirements

### Requirement: Paths are graph-valid

Every generated `TestCase` SHALL start at the graph's initial state, and each consecutive step MUST follow an existing edge of the graph.

**Given** a state graph and any algorithm
**When** test cases are generated
**Then** every test case starts at the graph's initial state and each consecutive step follows an existing edge

#### Scenario: Happy path

**Given** the bank graph from spec:state-graph
**When** `generate(graph, StateCoverage)` runs
**Then** every produced sequence (e.g. `Create(alice) → Deposit(alice,50) → Withdraw(alice,30)`) corresponds to a connected edge path from the empty state

#### Scenario: Error path — unreachable target is impossible by construction

**Given** a graph whose nodes were produced by BFS (all reachable)
**When** any algorithm runs
**Then** no generated test case can reference a state or call absent from the graph (type-level: generator consumes only `graph.edges`)

### Requirement: Coverage guarantees per algorithm

`StateCoverage` SHALL cover every node, `TransitionCoverage` SHALL cover every edge (including self-loops), and `RandomWalk(seed, count)` SHALL produce exactly `count` cases deterministically from the seed.

**Given** a state graph
**When** `StateCoverage` runs, every node appears in at least one test case; **when** `TransitionCoverage` runs, every edge (including self-loops) appears in at least one test case; **when** `RandomWalk(seed, count)` runs, exactly `count` cases are produced deterministically from the seed

**Rationale**: These are Accordant's pluggable generation algorithms (DeepWiki: StateCoverage, TransitionCoverage, RandomWalk) — systematic, not random, except the explicitly seeded walk.

#### Scenario: Happy path — transition coverage hits error edges

**Given** the bank graph including the self-loop `(∅, Withdraw(alice,30), ∅)` (not-found path)
**When** `TransitionCoverage` runs
**Then** at least one test case exercises that self-loop (error paths are first-class test material)

#### Scenario: Edge case — minimality preference

**Given** a graph where one path covers several yet-uncovered nodes
**When** `StateCoverage` runs
**Then** the case count is ≤ node count (the algorithm extends paths rather than emitting one case per node)

#### Scenario: Edge case — random walk determinism

**Given** the same graph, seed, and count
**When** `RandomWalk` runs twice
**Then** the outputs are identical

### Requirement: Test case persistence

A persisted `TestCase[S]` SHALL round-trip to an equal value; an unknown schema version MUST fail with `VersionMismatch` and malformed JSON MUST fail with `DecodeFailed` — never an exception.

**Given** user-supplied circe codecs for `S` and each request type
**When** a `TestCase[S]` is saved and re-loaded
**Then** the loaded value equals the original; loading a record with an unknown schema version fails with `VersionMismatch`, and malformed JSON fails with `DecodeFailed` — never an exception

**Rationale**: Accordant serializes failing cases (`ConcurrentTestCaseFileRecord`) so non-deterministic failures can be reproduced and isolated; same need here, plus this is what the verified-scala3 pipeline stores as the "reproducing path" artifact for AI feedback.

#### Scenario: Happy path — roundtrip

**Given** a generated bank test case
**When** encoded to JSON and decoded
**Then** the result is `Right` of an equal test case

#### Scenario: Error path — version mismatch

**Given** a record with `version: 999`
**When** decoded
**Then** the result is `Left(VersionMismatch(999, 1))`

## Properties (Ring 3)

### Property: Path validity for all algorithms

**Generator strategy** (Hedgehog): `genStateGraph` (spec:state-graph fixture — graphs of 3–12 nodes built constructively from generated specs) × `genAlgorithm` (`Gen.choice1` of StateCoverage, TransitionCoverage, seeded `RandomWalk`)

**Invariant**: For every generated graph and every algorithm, every test case is an edge-connected path from the initial state.

```
property("path validity for all algorithms") {
  for {
    graph <- genStateGraph.forAll
    algo  <- genAlgorithm.forAll
  } yield Result.assert(
    TestCaseGenerator.generate(graph, algo).forall(tc =>
      tc.initial === graph.initial && isEdgePath(graph, tc.initial, tc.steps))
  )
}
```

### Property: StateCoverage covers all nodes / TransitionCoverage covers all edges

**Generator strategy** (Hedgehog): `genStateGraph` with classification on node/edge counts; self-loop-bearing graphs guaranteed by including a `Same`-outcome operation in the generating spec pool

**Invariant**: The union of states (resp. edges) visited by the generated cases equals the graph's node (resp. edge) set.

```
property("StateCoverage covers all nodes / TransitionCoverage covers all edges") {
  for {
    graph <- genStateGraph.forAll
  } yield Result.assert(
    statesVisited(graph, generate(graph, StateCoverage)).toSet === graph.nodes.map(_.state).toSet &&
    edgesVisited(graph, generate(graph, TransitionCoverage)).toSet === graph.edges.toSet
  )
}
```

### Property: Persistence roundtrip

**Generator strategy** (Hedgehog): `genTestCase` derived from `genStateGraph` + path selection; request payloads cover unicode and empty-string edges via `Gen.frequency1`

**Invariant**: `decode(encode(tc)) == Right(tc)` for all generated test cases.

```
property("persistence roundtrip") {
  for {
    tc <- genTestCase.forAll
  } yield Result.assert(
    TestCasePersistence.fromJson[BankState](TestCasePersistence.toJson(tc)) == Right(tc)
  )
}
```

### Property: RandomWalk is a pure function of (graph, seed, count)

**Generator strategy** (Hedgehog): `genStateGraph` × `Gen.long(Range.linearFrom(0, Long.MinValue, Long.MaxValue))` × `Gen.int(Range.linear(1, 10))` for count (refined to `Positive` via the smart constructor)

**Invariant**: Equal inputs produce equal outputs; `count` is honored exactly.

```
property("RandomWalk is a pure function of (graph, seed, count)") {
  for {
    graph <- genStateGraph.forAll
    seed  <- genSeed.forAll
    count <- genPosSmall.forAll
  } yield {
    val a = generate(graph, RandomWalk(seed, count))
    Result.assert(a == generate(graph, RandomWalk(seed, count)) && a.size == count.value)
  }
}
```

## Compile-Negative Obligations

None — this spec introduces no new refined literals or type-level exclusions;
its constraints (schema `version` check) are explicit runtime checks
(`PersistenceError.VersionMismatch`), specified in the error-path scenario.

## Proof Obligations

| Obligation | Source | Enforcement | Test/Artifact |
|---|---|---|---|
| Every test case is an edge-connected path from initial | Req: graph-valid paths / Scenario: happy + Property: path validity | property test | "path validity for all algorithms" |
| Generator cannot reference states/calls outside the graph | Scenario: unreachable impossible | type system (generator consumes only `graph.edges`) + adversarial review (Ring 8 exercises direct construction) | typed contract + Ring 8 report |
| StateCoverage hits every node | Req: coverage / Property: coverage | property test | "StateCoverage covers all nodes" |
| TransitionCoverage hits every edge incl. self-loops | Scenario: error edges + Property: coverage | scenario + property test | same property |
| Case count ≤ node count (path extension) | Scenario: minimality | scenario test | "minimality preference" |
| RandomWalk deterministic, exact count | Scenario: determinism + Property: purity | property test | "RandomWalk is a pure function" |
| `decode(encode(tc)) == Right(tc)` | Req: persistence / Scenario: roundtrip + Property | property test (round-trip law, Ring 4) | "persistence roundtrip" |
| Unknown schema version → `VersionMismatch`, malformed JSON → `DecodeFailed`, never an exception | Scenario: version mismatch | scenario test + static rule (no-throw) | "version mismatch" |
| Baseline fixtures exist for future compatibility checks | Ring 4 | compatibility fixtures created in this spec (first persisted format — establishes the Ring 4 baseline) | `core/src/test/resources/fixtures/testcase-v1.json` |

## Requirement ↔ Test Cross-Reference

| Requirement / Scenario | Test (`TestGenerationProperties`) | Status |
|---|---|---|
| Paths are graph-valid / Happy path | "StateCoverage cases are edge-connected paths from initial" + "path validity for all algorithms" | ✅ |
| Unreachable target impossible by construction | "no generated step references a call absent from the graph" + Ring 8 direct-construction probe | ✅ |
| Coverage: StateCoverage covers nodes / TransitionCoverage covers edges | "StateCoverage covers all nodes / TransitionCoverage covers all edges" | ✅ |
| Transition coverage hits error self-loop | "TransitionCoverage exercises the not-found self-loop" | ✅ |
| Minimality preference (extend, not one-per-node) | "StateCoverage case count is at most the node count" + "StateCoverage on a linear chain emits one extended path covering all nodes" | ✅ |
| Random walk determinism / exact count | "RandomWalk twice yields identical output" + "RandomWalk is a pure function of (graph, seed, count)" | ✅ |
| RandomWalk walks the graph (bounded, non-empty) | "RandomWalk yields non-empty walks bounded by the node count" | ✅ |
| Persistence roundtrip | "a generated bank test case round-trips through JSON" + "persistence roundtrip" | ✅ |
| Version mismatch → `VersionMismatch`, malformed → `DecodeFailed`, never throws | "unknown version → VersionMismatch; malformed JSON → DecodeFailed" | ✅ |
| Ring 4 baseline fixture decodes | "Ring 4 baseline — committed testcase-v1.json decodes to the baseline case" | ✅ |

## Verification Rings

Ring 0 ✅ · Ring 1 ✅ · Ring 2 ✅ · Ring 3 ✅ · Ring 4 ✅ (first persisted format — fixtures created as baseline) · Ring 5 ✅ (90–95%) · Ring 6 — · Ring 7 — · Ring 8 ✅ · Ring 9 —
