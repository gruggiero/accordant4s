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
| `genStateGraph` | ScalaCheck generator | test fixtures (introduced by spec:state-graph) |
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
| `genTestCase` | ScalaCheck generator | Test cases from generated graphs, for downstream specs |

## ADDED Requirements

### Requirement: Paths are graph-valid

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

## Properties (Ring 2)

### Property: Path validity for all algorithms

**Invariant**: For every generated graph and every algorithm, every test case is an edge-connected path from the initial state.

```
forAll(genStateGraph, genAlgorithm) { (graph, algo) =>
  TestCaseGenerator.generate(graph, algo).forall(tc =>
    tc.initial === graph.initial && isEdgePath(graph, tc.initial, tc.steps))
}
```

### Property: StateCoverage covers all nodes / TransitionCoverage covers all edges

**Invariant**: The union of states (resp. edges) visited by the generated cases equals the graph's node (resp. edge) set.

```
forAll(genStateGraph) { graph =>
  statesVisited(graph, generate(graph, StateCoverage)).toSet === graph.nodes.map(_.state).toSet &&
  edgesVisited(graph, generate(graph, TransitionCoverage)).toSet === graph.edges.toSet
}
```

### Property: Persistence roundtrip

**Invariant**: `decode(encode(tc)) == Right(tc)` for all generated test cases.

```
forAll(genTestCase) { tc =>
  TestCasePersistence.fromJson[BankState](TestCasePersistence.toJson(tc)) == Right(tc)
}
```

### Property: RandomWalk is a pure function of (graph, seed, count)

**Invariant**: Equal inputs produce equal outputs; `count` is honored exactly.

```
forAll(genStateGraph, arbitrary[Long], genPosSmall) { (graph, seed, count) =>
  val a = generate(graph, RandomWalk(seed, count))
  a == generate(graph, RandomWalk(seed, count)) && a.size == count.value
}
```

## Verification Rings

Ring 0 ✅ · Ring 1 ✅ · Ring 1.5 ✅ · Ring 2 ✅ · Ring 3 ✅ (80%) · Ring 4 — · Ring 5 —
