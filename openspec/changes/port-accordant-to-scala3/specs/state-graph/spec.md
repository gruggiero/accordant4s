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

**Given** two transition paths leading to `Eq`-equal states
**When** the graph is built
**Then** they share a single node (state identity is `Eq[S]`/`Hash[S]`, not reference or insertion order)

#### Scenario: Happy path — diamond collapse

**Given** paths `Create(alice) → Deposit(50) → Withdraw(30)` and a direct `Create(alice) → Deposit(20)` that both reach `{alice→20}`
**When** exploration runs
**Then** `{alice→20}` is one node with two inbound edges

### Requirement: Streaming facade

**Given** the same parameters
**When** `GraphExplorer.stream(spec, inputs, initial, depth, seed)` is consumed
**Then** it emits the same nodes as `explore` in BFS order, lazily (consuming the first k nodes performs no expansion beyond frontier k)

#### Scenario: Edge case — early termination

**Given** a graph with hundreds of reachable nodes
**When** `.take(5)` is applied to the stream
**Then** exactly 5 nodes are emitted and deeper frontiers are never computed

## Properties (Ring 2)

### Property: Every edge is spec-conformant

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

**Invariant**: Same `(spec, inputs, initial, depth, seed)` produces identical graphs.

```
forAll(genSmallSpecAndInputs) { (spec, inputs, initial, depth, seed) =>
  GraphExplorer.explore(spec, inputs, initial, depth, seed) ==
    GraphExplorer.explore(spec, inputs, initial, depth, seed)
}
```

### Property: No duplicate nodes

**Invariant**: No two nodes hold `Eq`-equal states.

```
forAll(genSmallSpecAndInputs) { (spec, inputs, initial, depth, seed) =>
  val states = GraphExplorer.explore(spec, inputs, initial, depth, seed).nodes.map(_.state)
  states.size == distinctByEq(states).size
}
```

### Property: Stream/explore agreement

**Invariant**: The stream facade emits exactly `explore`'s node set, in non-decreasing depth order.

```
forAll(genSmallSpecAndInputs) { (spec, inputs, initial, depth, seed) =>
  val streamed = GraphExplorer.stream(spec, inputs, initial, depth, seed).compile.toVector
  streamed.map(_.state).toSet === explore(...).nodes.map(_.state).toSet &&
  streamed.map(_.depth).isSorted
}
```

## Verification Rings

Ring 0 ✅ · Ring 1 ✅ · Ring 1.5 ✅ · Ring 2 ✅ · Ring 3 ✅ (80%) · Ring 4 — (mock sampling excludes PureScala) · Ring 5 —
