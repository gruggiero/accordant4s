# Spec: State Graph

Port of Accordant's state-space exploration: simulate every input against every
discovered state (mocks supply responses — "simulate then execute"), building a
directed graph of reachable states with self-loops for no-change operations, bounded
by `MaxDepth`.

## Concepts Used (from inventory)

| Concept | Kind | Package |
|---------|------|---------|
| `Spec[S]` | case class | `spec` (introduced by spec:oracle-core) |
| `Outcome[Res, S]` | enum | `domain` (introduced by spec:oracle-core) |
| `Operation[Req, Res, S]` (`mock`) | case class | `spec` (introduced by spec:oracle-core) |
| `StateOps[S]` | given bundle | `domain` (introduced by spec:oracle-core) |
| `OperationCall[S]` / `InputSet[S]` | sealed trait / case class | `spec` (introduced by spec:input-sets) |
| `CallLabel` | opaque type | `domain` (introduced by spec:oracle-core) |
| `BankState` fixture | test case class | test fixtures (introduced by spec:oracle-core) |

## Concepts Introduced (new)

| Concept | Kind | Description |
|---------|------|-------------|
| `MaxDepth` | opaque type (`Int :| Positive`) | Mandatory exploration bound |
| `Node[S]` | case class | Canonical state + BFS depth |
| `Edge[S]` | case class | `(from: S, call: OperationCall[S], to: S)` — `from == to` is a self-loop |
| `StateGraph[S]` | case class | `initial: S`, `nodes: Vector[Node[S]]`, `edges: Vector[Edge[S]]` |
| `GraphExplorer` | object | Pure tail-recursive BFS `explore(spec, inputs, initial, depth, seed): StateGraph[S]` + `fs2.Stream` facade |
| `genStateGraph` | ScalaCheck generator | Graphs from arbitrary small specs, for downstream specs' tests |

## ADDED Requirements

### Requirement: Bounded BFS over simulated transitions

`GraphExplorer.explore` SHALL apply every call in the input set to every frontier state, record each distinct resulting state as a depth-tagged node and each `(state, call, next-state)` transition as an edge, and MUST stop expanding at `MaxDepth`.

**Given** a spec, an input set, an initial state, a `MaxDepth`, and a mock seed
**When** `GraphExplorer.explore` runs
**Then** it applies every call in the input set to every frontier state (sampling the operation's `mock` for response-dependent transitions), records each distinct resulting state as a node with its BFS depth, records an edge per (state, call, next-state), and stops expanding at `MaxDepth`

**Rationale**: This is Accordant's `TestCaseGenerator` exploration phase with `MaxDepth` safety bound; purity + seeded mocks make it reproducible.

#### Scenario: Happy path — bank graph

**Given** the BankState spec, inputs {Create(alice), Deposit(alice, 50), Withdraw(alice, 30)}, empty initial state, depth 3
**When** exploration runs
**Then** the graph contains the empty state, `{alice→0}`, `{alice→50}`, `{alice→20}` as nodes, connected by the corresponding edges

#### Scenario: Edge case — no-change operations are self-loops

**Given** the same spec where `Withdraw(alice, 30)` on the empty state yields `Same` (not-found)
**When** exploration runs
**Then** the edge `(∅, Withdraw(alice,30), ∅)` is a self-loop and does NOT create a new node

#### Scenario: Edge case — depth bound respected

**Given** a spec whose `Deposit` always changes state, depth 2
**When** exploration runs
**Then** no node has depth > 2 and exploration terminates even though infinitely many states are reachable in principle

#### Scenario: Error path — OneOf branches all explored

**Given** an operation whose outcome is `OneOf` with two state-changing branches
**When** exploration encounters it
**Then** BOTH next-states appear as nodes with edges from the same (state, call) pair

### Requirement: Canonicalization under Eq

States that are `Eq`-equal SHALL share a single graph node, with identity determined by `Eq[S]`/`Hash[S]` and never by reference or insertion order.

**Given** two transition paths leading to `Eq`-equal states
**When** the graph is built
**Then** they share a single node (state identity is `Eq[S]`/`Hash[S]`, not reference or insertion order)

#### Scenario: Happy path — diamond collapse

**Given** paths `Create(alice) → Deposit(50) → Withdraw(30)` and a direct `Create(alice) → Deposit(20)` that both reach `{alice→20}`
**When** exploration runs
**Then** `{alice→20}` is one node with two inbound edges

### Requirement: Streaming facade

`GraphExplorer.stream` SHALL emit the same nodes as `explore` in BFS order, lazily, performing no expansion beyond the consumed frontier.

**Given** the same parameters
**When** `GraphExplorer.stream(spec, inputs, initial, depth, seed)` is consumed
**Then** it emits the same nodes as `explore` in BFS order, lazily (consuming the first k nodes performs no expansion beyond frontier k)

#### Scenario: Edge case — early termination

**Given** a graph with hundreds of reachable nodes
**When** `.take(5)` is applied to the stream
**Then** exactly 5 nodes are emitted and deeper frontiers are never computed

## Properties (Ring 3)

### Property: Every edge is spec-conformant

**Generator strategy**: shared constructive `genSmallSpecAndInputs`: specs assembled from a pool of 2–4 bank-like operations with behaviours drawn from `Same`/`Next`/`OneOf` combinators, input sets of 2–6 labeled calls, `MaxDepth` via `Gen.chooseNum(1, 4)` (constructive refinement), seeds from `arbitrary[Long]`; `classify` by (operation count, depth); mocked responses re-derived from the seed

**Invariant**: For every edge in any explored graph, replaying the call with the mocked response through the oracle from the edge's source yields `Conformant` containing the edge's target.

```
forAll(genSmallSpecAndInputs) { (spec, inputs, initial, depth, seed) =>
  GraphExplorer.explore(spec, inputs, initial, depth, seed).edges.forall { e =>
    spec.allows(e.call.op, e.call.req, mockedRes(e, seed), StateProfile.one(e.from)) match
      case Conformant(p) => p.containsEq(e.to)
      case Deviant(_)    => false
  }
}
```

### Property: Depth bound and reachability

**Generator strategy**: shared constructive `genSmallSpecAndInputs`: specs assembled from a pool of 2–4 bank-like operations with behaviours drawn from `Same`/`Next`/`OneOf` combinators, input sets of 2–6 labeled calls, `MaxDepth` via `Gen.chooseNum(1, 4)` (constructive refinement), seeds from `arbitrary[Long]`; `classify` by (operation count, depth)

**Invariant**: All nodes have depth ≤ MaxDepth; every node is reachable from the initial state via recorded edges; the initial state is always a node at depth 0.

```
forAll(genSmallSpecAndInputs) { (spec, inputs, initial, depth, seed) =>
  val g = GraphExplorer.explore(spec, inputs, initial, depth, seed)
  g.nodes.forall(_.depth <= depth) &&
  g.nodes.forall(n => reachable(g.initial, n.state, g.edges)) &&
  g.nodes.exists(n => n.state === initial && n.depth == 0)
}
```

### Property: Determinism

**Generator strategy**: shared constructive `genSmallSpecAndInputs`: specs assembled from a pool of 2–4 bank-like operations with behaviours drawn from `Same`/`Next`/`OneOf` combinators, input sets of 2–6 labeled calls, `MaxDepth` via `Gen.chooseNum(1, 4)` (constructive refinement), seeds from `arbitrary[Long]`; `classify` by (operation count, depth); the generated tuple is explored twice

**Invariant**: Same `(spec, inputs, initial, depth, seed)` produces identical graphs.

```
forAll(genSmallSpecAndInputs) { (spec, inputs, initial, depth, seed) =>
  GraphExplorer.explore(spec, inputs, initial, depth, seed) ==
    GraphExplorer.explore(spec, inputs, initial, depth, seed)
}
```

### Property: No duplicate nodes

**Generator strategy**: shared constructive `genSmallSpecAndInputs`: specs assembled from a pool of 2–4 bank-like operations with behaviours drawn from `Same`/`Next`/`OneOf` combinators, input sets of 2–6 labeled calls, `MaxDepth` via `Gen.chooseNum(1, 4)` (constructive refinement), seeds from `arbitrary[Long]`; `classify` by (operation count, depth), with a small state pool to force diamond revisits

**Invariant**: No two nodes hold `Eq`-equal states.

```
forAll(genSmallSpecAndInputs) { (spec, inputs, initial, depth, seed) =>
  val states = GraphExplorer.explore(spec, inputs, initial, depth, seed).nodes.map(_.state)
  states.size == distinctByEq(states).size
}
```

### Property: Stream/explore agreement

**Generator strategy**: shared constructive `genSmallSpecAndInputs`: specs assembled from a pool of 2–4 bank-like operations with behaviours drawn from `Same`/`Next`/`OneOf` combinators, input sets of 2–6 labeled calls, `MaxDepth` via `Gen.chooseNum(1, 4)` (constructive refinement), seeds from `arbitrary[Long]`; `classify` by (operation count, depth); the stream is compiled to a Vector and compared

**Invariant**: The stream facade emits exactly `explore`'s node set, in non-decreasing depth order.

```
forAll(genSmallSpecAndInputs) { (spec, inputs, initial, depth, seed) =>
  val streamed = GraphExplorer.stream(spec, inputs, initial, depth, seed).compile.toVector
  streamed.map(_.state).toSet === explore(...).nodes.map(_.state).toSet &&
  streamed.map(_.depth).isSorted
}
```

## Compile-Negative Obligations

| Must NOT compile | Why | Test |
|---|---|---|
| `MaxDepth(0)` / `MaxDepth(-1)` (literals) | Iron `Positive` — unbounded or nonsensical exploration is unrepresentable; dynamic values via `MaxDepth.either` | `assertDoesNotCompile` stub |

## Proof Obligations

| Obligation | Source | Enforcement | Test/Artifact |
|---|---|---|---|
| BFS applies every call to every frontier state | Req: bounded BFS / Scenario: bank graph | scenario test + Property: edge conformance | "bank graph" + "every edge spec-conformant" |
| No-change ops are self-loops, not new nodes | Scenario: self-loops | scenario test | "self-loop" |
| No node deeper than `MaxDepth`; termination | Scenario: depth bound + Property: depth/reachability | type system (`MaxDepth` Positive) + property test | "depth bound and reachability" |
| All `OneOf` branches explored | Scenario: OneOf branches | scenario test | "OneOf branches explored" |
| `Eq`-equal states share one node | Req: canonicalization / Scenario: diamond + Property: no duplicates | property test | "no duplicate nodes" |
| Every node reachable from initial | Property: depth/reachability | property test | same |
| Exploration deterministic in (spec, inputs, initial, depth, seed) | Property: determinism | property test | "determinism" |
| Stream facade ≡ strict explore, lazy | Req: streaming / Scenario: early termination + Property: agreement | scenario + property test | "stream/explore agreement" |
| Every recorded edge is oracle-conformant | Property: edge conformance | property test + adversarial review (Ring 8 checks no edge is recorded on a failed branch) | "every edge spec-conformant" |

## Verification Rings

Ring 0 ✅ · Ring 1 ✅ · Ring 2 ✅ · Ring 3 ✅ · Ring 4 — · Ring 5 ✅ (90–95%) · Ring 6 — (mock sampling excludes PureScala) · Ring 7 — · Ring 8 ✅ · Ring 9 —
