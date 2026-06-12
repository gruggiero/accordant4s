# Spec: Input Sets

Port of Accordant's `InputSet` / `op.With(request, label)` / `Accordant.Choose` to
labeled `OperationCall`s with ScalaCheck `Gen` as the enumeration engine (report §3.1:
`Choose.Each<T>()` → `Gen`).

## Concepts Used (from inventory)

| Concept | Kind | Package |
|---------|------|---------|
| `Operation[Req, Res, S]` | case class | `spec` (introduced by spec:oracle-core) |
| `OperationName` | opaque type | `domain` (introduced by spec:oracle-core) |
| `CallLabel` | opaque type | `domain` (introduced by spec:oracle-core) |
| `BankState` fixture | test case class | test fixtures (introduced by spec:oracle-core) |

## Concepts Introduced (new)

| Concept | Kind | Description |
|---------|------|-------------|
| `OperationCall[S]` | sealed trait (existential `Req`/`Res` members) | One labeled step: `(op, req, label)` with types hidden |
| `InputSet[S]` | case class | Ordered, label-unique collection of `OperationCall[S]` |
| `withInput` extension | extension method | `op.withInput(req, label): OperationCall[S]` (Accordant's `op.With`) |
| `InputSet.fromGen` | constructor | Sample `n` calls from a `Gen[Req]` with auto-derived labels (Choose replacement) |
| `genOperationCall` / `genInputSet` | ScalaCheck generators | Test generators for downstream specs |

## ADDED Requirements

### Requirement: Labeled operation calls

**Given** a registered operation and a request value
**When** `op.withInput(req, label)` is invoked
**Then** an `OperationCall[S]` is produced that carries the operation handle, the request, and the label, with `Req`/`Res` recoverable only through the call itself (no casting at use sites)

**Rationale**: Sequences mix operations of different `Req`/`Res` types; the existential encoding lets engines treat steps uniformly while the oracle re-associates exact types.

#### Scenario: Happy path

**Given** the `Deposit` operation
**When** `deposit.withInput(DepositRequest("alice", 50), label"Deposit(alice, 50)")` is built
**Then** the call's `op.name` is `Deposit`, its request equals the given value, and feeding it to `spec.allows` type-checks without casts

#### Scenario: Edge case — same operation, many inputs

**Given** the `Deposit` operation
**When** three calls with distinct labels and amounts are built
**Then** all three coexist in one `InputSet` and remain distinguishable by label

### Requirement: InputSet composition with label uniqueness

**Given** two input sets
**When** they are combined (`++`)
**Then** the result preserves order and rejects duplicate labels with an `Either.Left` listing the collisions

#### Scenario: Happy path — disjoint union

**Given** `InputSet` A (2 calls) and B (3 calls) with disjoint labels
**When** `A ++ B`
**Then** the result has 5 calls in A-then-B order

#### Scenario: Error path — colliding labels

**Given** sets that both contain label `Deposit(alice, 50)`
**When** combined
**Then** the result is `Left` naming exactly the colliding label(s)

### Requirement: Gen-backed input sources

**Given** a `Gen[Req]` and a sample size `n`
**When** `InputSet.fromGen(op, gen, n, seed)` is invoked
**Then** `n` calls are produced deterministically from the seed, each labeled `"<opName>(<Show[Req]>)"`, with duplicate requests collapsed before labeling

**Rationale**: Replaces `Choose.Each<T>()` exhaustive enumeration with `Gen`, gaining shrinking and distribution control while keeping exploration deterministic (a seed is required, since the state graph must be reproducible).

#### Scenario: Happy path — deterministic sampling

**Given** `Gen.choose(1, 100).map(DepositRequest("alice", _))`, `n = 10`, `seed = 42`
**When** `fromGen` runs twice
**Then** both runs yield identical input sets

#### Scenario: Edge case — generator collapse

**Given** `Gen.const(DepositRequest("alice", 50))` and `n = 10`
**When** `fromGen` runs
**Then** the input set contains exactly 1 call (duplicates collapsed, no label collision possible)

## Properties (Ring 2)

### Property: withInput roundtrip

**Invariant**: For all operations, requests, and labels, the constructed call returns exactly what was put in.

```
forAll { (req: DepositRequest, label: CallLabel) =>
  val call = deposit.withInput(req, label)
  call.op.name == deposit.name && call.req == req && call.label == label
}
```

### Property: Composition is associative and label-preserving

**Invariant**: For label-disjoint sets, `(a ++ b) ++ c == a ++ (b ++ c)` and the labels of the union equal the concatenation of the labels.

```
forAll { (a: InputSet[BankState], b: InputSet[BankState], c: InputSet[BankState]) =>
  disjoint(a, b, c) ==> {
    ((a ++ b).flatMap(_ ++ c)) == (b ++ c).flatMap(a ++ _) &&
    (a ++ b).map(_.labels) == Right(a.labels ::: b.labels)
  }
}
```

### Property: fromGen determinism and bound

**Invariant**: Same `(gen, n, seed)` always yields the same input set, with size ≤ n and unique labels.

```
forAll { (seed: Long, n: PosInt) =>
  val s1 = InputSet.fromGen(deposit, genReq, n, seed)
  val s2 = InputSet.fromGen(deposit, genReq, n, seed)
  s1 == s2 && s1.size <= n.value && s1.labels.distinct == s1.labels
}
```

## Verification Rings

Ring 0 ✅ · Ring 1 ✅ · Ring 1.5 ✅ · Ring 2 ✅ · Ring 3 ✅ (80%, spec layer) · Ring 4 — · Ring 5 —
