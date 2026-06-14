# Spec: Input Sets

Port of Accordant's `InputSet` / `op.With(request, label)` / `Accordant.Choose` to
labeled `OperationCall`s with Hedgehog `Gen` as the enumeration engine (report §3.1:
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
| `InputSet.fromGen` | constructor | Sample `n` calls from a `hedgehog.Gen[Req]` (explicit `Seed` + `Size`) with auto-derived labels (Choose replacement) |
| `genOperationCall` / `genInputSet` | Hedgehog generators | Test generators for downstream specs |

## ADDED Requirements

### Requirement: Labeled operation calls

`op.withInput(req, label)` SHALL produce an `OperationCall[S]` carrying the operation handle, the request, and the label, with `Req`/`Res` recoverable only through the call itself (no casting at use sites).

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

Combining two input sets (`++`) SHALL preserve order and MUST reject duplicate labels with an `Either.Left` listing the colliding labels.

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

`InputSet.fromGen(op, gen, n, seed)` SHALL deterministically produce at most `n` labeled calls from the seed, each labeled `"<opName>(<Show[Req]>)"`, with duplicate requests collapsed before labeling.

**Given** a `Gen[Req]` and a sample size `n`
**When** `InputSet.fromGen(op, gen, n, seed)` is invoked
**Then** `n` calls are produced deterministically from the seed, each labeled `"<opName>(<Show[Req]>)"`, with duplicate requests collapsed before labeling

**Rationale**: Replaces `Choose.Each<T>()` exhaustive enumeration with `Gen`, gaining shrinking and distribution control while keeping exploration deterministic (a seed is required, since the state graph must be reproducible).

#### Scenario: Happy path — deterministic sampling

**Given** `Gen.int(Range.linear(1, 100)).map(DepositRequest("alice", _))`, `n = 10`, `seed = 42`
**When** `fromGen` runs twice
**Then** both runs yield identical input sets

#### Scenario: Edge case — generator collapse

**Given** `Gen.constant(DepositRequest("alice", 50))` and `n = 10`
**When** `fromGen` runs
**Then** the input set contains exactly 1 call (duplicates collapsed, no label collision possible)

## Properties (Ring 3)

### Property: withInput roundtrip

**Generator strategy** (Hedgehog): constructive `genDepositRequest` (`Gen.int(Range.linear(1, 1000000))` amounts × `Gen.element1` account pool) and `genCallLabel` = `Gen.string(Gen.alphaNum, Range.linear(1, 16))` refined through `CallLabel.either` (constructive — the non-blank range guarantees success, no filtering)

**Invariant**: For all operations, requests, and labels, the constructed call returns exactly what was put in.

```
property("withInput roundtrip") {
  for {
    req   <- genDepositRequest.forAll
    label <- genCallLabel.forAll
  } yield {
    val call = deposit.withInput(req, label)
    Result.assert(call.op.name == deposit.name && call.req == req && call.label == label)
  }
}
```

### Property: Composition is associative and label-preserving

**Generator strategy** (Hedgehog): `genInputSet` builds label-disjoint sets by namespacing labels with a per-set prefix — disjointness by construction, not `.ensure`-filtering; classify on set sizes

**Invariant**: For label-disjoint sets, `(a ++ b) ++ c == a ++ (b ++ c)` and the labels of the union equal the concatenation of the labels.

```
property("composition is associative and label-preserving") {
  for {
    a <- genInputSet("a").forAll
    b <- genInputSet("b").forAll
    c <- genInputSet("c").forAll
  } yield Result.assert(
    ((a ++ b).flatMap(_ ++ c)) == (b ++ c).flatMap(a ++ _) &&
    (a ++ b).map(_.labels) == Right(a.labels ::: b.labels)
  )
}
```

### Property: fromGen determinism and bound

**Generator strategy** (Hedgehog): `Gen.long(Range.linearFrom(0, Long.MinValue, Long.MaxValue))` seeds × `Gen.int(Range.linear(1, 20))` for n (refined to `PosInt` via the smart constructor) × inner `Gen.int(Range.linear(1, 100))` request payloads; the duplicate-collapse edge is covered by a `Gen.constant` sub-case via `Gen.frequency1`

**Invariant**: Same `(gen, n, seed)` always yields the same input set, with size ≤ n and unique labels.

```
property("fromGen determinism and bound") {
  for {
    seed <- genSeed.forAll
    n    <- genPosInt.forAll
  } yield {
    val s1 = InputSet.fromGen(deposit, genReq, n, seed)
    val s2 = InputSet.fromGen(deposit, genReq, n, seed)
    Result.assert(s1 == s2 && s1.size <= n.value && s1.labels.distinct == s1.labels)
  }
}
```

## Compile-Negative Obligations

| Must NOT compile | Why | Test |
|---|---|---|
| `CallLabel("")` (literal) | Iron `Not[Blank]`; dynamic labels via `CallLabel.either` | `assertDoesNotCompile` stub |
| `deposit.withInput(WithdrawRequest(...), label)` | `withInput` is typed by the operation's `Req`; cross-operation request reuse is a type error | `assertDoesNotCompile` stub |

## Proof Obligations

| Obligation | Source | Enforcement | Test/Artifact |
|---|---|---|---|
| Call carries (op, req, label) intact, typed | Req: labeled calls / Scenario: happy + Property: roundtrip | property test + type system | "withInput roundtrip" |
| Same op, many inputs coexist distinguishable by label | Scenario: many inputs | scenario test | "labeled calls — many inputs" |
| `++` preserves order, rejects label collisions as `Left` | Req: composition / Scenarios: disjoint, colliding | scenario tests + Property: associativity | "composition" tests |
| Label uniqueness inside any `InputSet` | type constraint | smart constructor (Either) + property (fromGen labels distinct) | "fromGen determinism and bound" |
| `fromGen` deterministic for (gen, n, seed), size ≤ n | Req: gen-backed / Scenario: deterministic + Property | property test | "fromGen determinism and bound" |
| Duplicate generated requests collapse before labeling | Scenario: generator collapse | scenario test | "gen-backed — collapse" |
| Existential `Req`/`Res` never leak as casts | design constraint | type system + compile-negative test + adversarial review (Ring 8 greps for `asInstanceOf`) | typed contract CN stub |

## Requirement ↔ Test Cross-Reference

Implemented and verified (`InputSetsProperties`, `InputFixtures`, `InputSetsTypeContract`):

| Spec heading | Test | Status |
|---|---|---|
| Req: Labeled operation calls / Scenario: Happy path | `labeled calls — happy path` | ✅ |
| Scenario: Edge — same operation, many inputs | `labeled calls — many inputs` | ✅ |
| Req: InputSet composition / Scenario: Happy — disjoint union | `composition — disjoint union` | ✅ |
| Scenario: Error — colliding labels | `composition — colliding labels` | ✅ |
| Req: Gen-backed input sources / Scenario: deterministic sampling | `fromGen — deterministic sampling` | ✅ |
| Scenario: Edge — generator collapse | `fromGen — generator collapse` | ✅ |
| Req: Gen-backed — label format `<opName>(<Show[Req]>)` | `fromGen — label format` | ✅ |
| Req: Gen-backed — "at most n" (n ≤ 0 → empty) | `fromGen — non-positive n yields empty` | ✅ |
| Invariant: label uniqueness inside any InputSet (non-injective Show) | `fromGen — colliding labels collapse to keep labels unique` | ✅ |
| Property: withInput roundtrip | `withInput roundtrip` | ✅ |
| Property: Composition associative and label-preserving | `composition is associative and label-preserving` | ✅ |
| Property: fromGen determinism and bound | `fromGen determinism and bound` | ✅ |
| Compile-Negative: `CallLabel("")` literal | `CN — blank CallLabel literal rejected` | ✅ |
| Compile-Negative: cross-operation request reuse | `CN — cross-operation request is a type error` | ✅ |

## Verification Rings

Ring 0 ✅ · Ring 1 ✅ · Ring 2 ✅ · Ring 3 ✅ · Ring 4 — · Ring 5 ✅ (100%, spec layer) · Ring 6 — · Ring 7 — · Ring 8 ✅ · Ring 9 —
