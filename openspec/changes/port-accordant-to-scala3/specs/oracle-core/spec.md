# Spec: Oracle Core

Port of Accordant's `Spec<TState>` / `Expect` DSL / `Allows` oracle to a pure Scala 3
kernel. This is the foundational capability: every other spec in this change consumes
the types introduced here.

## Concepts Used (from inventory)

| Concept | Kind | Package |
|---------|------|---------|
| None — new project (inventory is empty) | — | — |

## Concepts Introduced (new)

| Concept | Kind | Description |
|---------|------|-------------|
| `OperationName` | opaque type (`String :| Not[Blank]`) | Registry key for operations |
| `CallLabel` | opaque type (`String :| Not[Blank]`) | Human-readable step identity |
| `SpecViolation` | enum | `CheckFailed`, `UnknownOperation`, `NoBranchMatched`, `ProfileExhausted` — data-carrying deviation reasons |
| `ResponseCheck[Res]` | type alias | `Res => ValidatedNel[SpecViolation, Unit]` |
| `Outcome[Res, S]` | enum | `Same(check)` \| `Next(check, (Res, S) => S)` \| `OneOf(NonEmptyList[Outcome])` |
| `StateProfile[S]` | opaque type | Non-empty `Eq`-deduplicated candidate-state set |
| `Verdict[S]` | enum | `Conformant(StateProfile[S])` \| `Deviant(NonEmptyList[SpecViolation])` |
| `StateOps[S]` | given bundle | `Eq[S]`, `Hash[S]`, `Show[S]`, `CanEqual[S, S]` requirements on user state |
| `Operation[Req, Res, S]` | case class | `name`, `behaviour: (Req, S) => Outcome[Res, S]`, `mock: (Req, S) => Gen[Res]` |
| `Spec[S]` | case class | Operation registry + `allows` oracle + `register` builder |
| `expect` DSL | object | `expect(check).sameState` / `.thenState(f)` / `expect.oneOf(...)` constructors |
| `OutcomeEval` | pure object (`engine.verified`) | Evaluate one outcome tree against one `(res, state)` |
| `ProfileEval` | pure object (`engine.verified`) | Survivor-set computation across a profile |
| `BankState` fixture | test case class | Reference model (accounts: `Map[String, BigDecimal]`) used by all specs' tests |

## ADDED Requirements

### Requirement: Operation registration and lookup

**Given** an empty `Spec[S]`
**When** an `Operation[Req, Res, S]` is registered
**Then** the spec exposes it under its `OperationName`, and `allows` invoked with the typed operation handle dispatches to its behaviour without casts

**Rationale**: Accordant registers operations by name with runtime-typed lambdas; the port keeps the name (for reports/persistence) but dispatches through the typed handle so `Req`/`Res` mismatches are compile errors.

#### Scenario: Happy path — registered operation dispatches

**Given** a spec with the `Withdraw` operation registered
**When** `spec.allows(withdraw, WithdrawRequest("alice", 30), validResponse, profile)` is called
**Then** the verdict is computed from `Withdraw`'s behaviour lambda

#### Scenario: Error path — unknown operation

**Given** a spec where `Withdraw` was never registered
**When** `allows` is invoked with the unregistered `Withdraw` handle
**Then** the verdict is `Deviant(NonEmptyList.one(UnknownOperation("Withdraw")))`

#### Scenario: Edge case — duplicate registration

**Given** a spec with `Withdraw` registered
**When** a second operation with name `Withdraw` is registered
**Then** registration is rejected (`Either.Left`) — silent override is unrepresentable

### Requirement: Outcome evaluation with accumulated violations

**Given** an operation whose behaviour returns `Same(check)` or `Next(check, transition)`
**When** the oracle evaluates an actual response against a candidate state
**Then** if the check validates, the candidate's next state is the unchanged state (`Same`) or `transition(response, state)` (`Next`); if it fails, ALL violations from the check are accumulated (not just the first)

**Rationale**: `ValidatedNel` error accumulation is the designed upgrade over .NET's single boolean+message (report §3.2, §5).

#### Scenario: Happy path — Next transition applies response-dependent state

**Given** state where `alice` has balance 50, and `Deposit`'s outcome is `Next(check, (res, s) => s.copy(accounts = s.accounts.updated("alice", res.newBalance)))`
**When** the oracle evaluates a response with `newBalance = 80`
**Then** the verdict is `Conformant` with the surviving state showing `alice → 80` (the value came from the response, mirroring Accordant tutorial 03)

#### Scenario: Error path — multiple check failures accumulate

**Given** a check asserting both `r.isSuccess` and `r.balance == 20`
**When** the response is a failure with balance 99
**Then** the `Deviant` verdict carries BOTH violations in its `NonEmptyList`

#### Scenario: Edge case — Same outcome leaves profile untouched

**Given** any profile and an outcome `Same(check)` whose check passes
**When** the oracle evaluates
**Then** the surviving profile is `Eq`-equal to the input profile

### Requirement: Non-deterministic outcomes and state-profile branching

**Given** an operation returning `OneOf(branches)` (e.g. timeout: request lost / request applied but response lost)
**When** `allows` evaluates a response against a profile
**Then** the resulting profile is the `Eq`-deduplicated union of next-states of every branch whose check passes, across every candidate state; the verdict is `Deviant` only if NO branch passes for ANY candidate

**Rationale**: This is Accordant's indefinite-failure model (how-to: indefinite-failures): a timeout on `CreateAccount("alice")` leaves a profile of two worlds; a later successful `GetAccount("alice")` collapses it to one.

#### Scenario: Happy path — timeout forks the profile

**Given** a single-state profile and `CreateAccount` whose outcome is `OneOf(success→Next, timeout→Same, timeout→Next)`
**When** the actual response is a timeout
**Then** the verdict is `Conformant` with a two-state profile: one without `alice`, one with `alice → 0`

#### Scenario: Happy path — later observation collapses the profile

**Given** the two-state profile above and `GetAccount("alice")` specced as `OneOf(found if alice ∈ accounts → Same, notFound if alice ∉ accounts → Same)`
**When** the actual response is `found(balance = 0)`
**Then** the verdict is `Conformant` with exactly the single state where `alice` exists

#### Scenario: Error path — profile exhausted

**Given** the two-state profile and a `GetAccount("alice")` response of `found(balance = 999)`
**When** `allows` evaluates
**Then** the verdict is `Deviant` and includes `NoBranchMatched`/`ProfileExhausted` details for both candidate worlds

### Requirement: StateProfile invariants

**Given** any sequence of oracle evaluations
**When** profiles are constructed, forked, and collapsed
**Then** a profile is never empty and never contains two `Eq`-equal states

#### Scenario: Edge case — duplicate next-states collapse

**Given** two `OneOf` branches whose transitions produce `Eq`-equal states
**When** both branches match
**Then** the surviving profile contains that state exactly once

## Properties (Ring 2)

### Property: Conformant ⇔ some branch matches some candidate

**Invariant**: `allows` returns `Conformant` iff at least one (candidate state × outcome branch) pair has a passing check; the surviving profile equals the deduplicated set of corresponding next-states.

```
forAll { (profile: StateProfile[BankState], req: WithdrawRequest, res: WithdrawResponse) =>
  val expected = profile.toList
    .flatMap(s => matchingBranches(withdraw.behaviour(req, s), res, s).map(_.nextState(res, s)))
    .distinctByEq
  spec.allows(withdraw, req, res, profile) match
    case Conformant(p) => expected.nonEmpty && p.toList.sameElementsByEq(expected)
    case Deviant(_)    => expected.isEmpty
}
```

### Property: Same preserves the profile

**Invariant**: For operations whose outcome is `Same` with an always-passing check, the output profile is `Eq`-equal to the input profile, for all profiles and requests.

```
forAll { (profile: StateProfile[BankState], req: GetAccountRequest) =>
  spec.allows(noopGet, req, anyRes, profile) == Conformant(profile)
}
```

### Property: Deviant accumulates every failed check

**Invariant**: When no branch matches, the violation list size equals the total number of failed atomic checks across all branches and candidates — never 1 unless there was exactly one check.

```
forAll { (profile: StateProfile[BankState], res: WithdrawResponse) =>
  spec.allows(multiCheckOp, req, res, profile) match
    case Deviant(vs)   => vs.length == countFailedChecks(multiCheckOp, res, profile)
    case Conformant(_) => true
}
```

### Property: Profile dedup is idempotent and order-insensitive

**Invariant**: `StateProfile.of(xs) == StateProfile.of(xs.reverse)` and constructing from a list with duplicates equals constructing from its distinct elements.

```
forAll { (states: NonEmptyList[BankState]) =>
  StateProfile.of(states) === StateProfile.of(states.reverse) &&
  StateProfile.of(states ::: states) === StateProfile.of(states)
}
```

## Formal Contracts (Ring 4)

`engine.verified.OutcomeEval` and `engine.verified.ProfileEval` are PureScala-subset
(no Gen, no fs2, only List/Option/Either):

```
def survivors(branches: List[Branch], res: Res, candidates: List[S]): List[S] = {
  require(candidates.nonEmpty)
  ...
} ensuring (out =>
  out.forall(s => candidates.exists(c => branches.exists(b => b.matches(res, c) && b.next(res, c) == s)))
  && noDuplicates(out)
)

def verdict(survivors: List[S], violations: List[SpecViolation]): Verdict = {
  ...
} ensuring (v => (survivors.nonEmpty == v.isConformant) && (survivors.isEmpty ==> violations.nonEmpty))
```

## Verification Rings

Ring 0 ✅ · Ring 1 ✅ · Ring 1.5 ✅ · Ring 2 ✅ · Ring 3 ✅ (80%) · Ring 4 ✅ (`engine.verified` kernels) · Ring 5 —
