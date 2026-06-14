# Spec: HTTP Binding

Port of `Accordant.Operations.Http`'s `HttpExecutable`: bind operations to real HTTP
endpoints so a deployed service becomes a `SystemUnderTest[IO]`, using http4s
`Client[IO]` and circe codecs. Lives in the `accordant4s-http4s` module.

## Concepts Used (from inventory)

| Concept | Kind | Package |
|---------|------|---------|
| `SystemUnderTest[F]` | trait | `engine` (introduced by spec:test-execution) |
| `OperationCall[S]` | sealed trait | `spec` (introduced by spec:input-sets) |
| `Operation[Req, Res, S]` | case class | `spec` (introduced by spec:oracle-core) |
| `OperationName` | opaque type | `domain` (introduced by spec:oracle-core) |
| `MaxRetryCount` | opaque type | `domain` (introduced in this spec — see below) |
| `TestCaseExecutor` / `ExecutionReport` | object / enum | `engine` (introduced by spec:test-execution) |
| `BankState` fixture + bank spec | test fixtures | (introduced by spec:oracle-core) |

## Concepts Introduced (new)

| Concept | Kind | Description |
|---------|------|-------------|
| `HttpRoute[Req]` | case class | `(method, uri builder Req => Uri, entity encoding)` for one operation |
| `HttpResponseMapper[Res]` | trait | Total mapping `org.http4s.Response[IO] => IO[Res]` (status + body → user response ADT) |
| `HttpBinding[S]` | case class | `OperationName`-indexed routes + mappers |
| `Http4sSut` | object | `apply[S](client: Client[IO], binding: HttpBinding[S], timeout, retries): SystemUnderTest[IO]` |
| `TransportOutcome` | enum | `Completed(status, body)` \| `TimedOut` \| `ConnectionFailed(detail)` — transport facts as data |
| `MaxRetryCount` | opaque type (`Int :| Positive`) | Bound on idempotent connection retries |

## ADDED Requirements

### Requirement: Operation-to-endpoint binding

`Http4sSut.execute` SHALL encode each request per its route (method, URI, JSON body via circe), send it through the `Client[IO]`, and decode the response via the operation's mapper into the operation's `Res` type; unbound operations MUST fail at construction time, not mid-replay.

**Given** an `HttpBinding` with a route and response mapper for each registered operation
**When** `Http4sSut.execute(call)` runs
**Then** the request is encoded per the route (method, URI, JSON body via circe), sent through the `Client[IO]`, and the response is decoded by the operation's mapper into the operation's `Res` type

#### Scenario: Happy path

**Given** `Withdraw` bound to `POST /accounts/{id}/withdraw` against a stub `Client[IO]` returning 200 with a balance body
**When** the call executes
**Then** the result is `WithdrawResponse.Success(balance)` and the stub observed exactly one request with the encoded JSON body

#### Scenario: Error path — unbound operation

**Given** a binding missing `Withdraw`
**When** an `Http4sSut` is constructed against a spec containing `Withdraw`
**Then** construction fails (`Either.Left`) listing the unbound operation names — failures at wiring time, not mid-replay

### Requirement: Transport outcomes are data, never exceptions

Transport timeouts and connection failures SHALL surface as `Res` values through the mapper's handling of `TransportOutcome`; only mapper-undefined situations MAY raise.

**Given** a timeout or connection failure from the client
**When** `execute` runs
**Then** the outcome surfaces as a `Res` variant via the mapper's handling of `TransportOutcome` (e.g. `r.isTimeout`), so specs can model indefinite failures with `Outcome.OneOf`; only mapper-undefined situations raise

**Rationale**: Accordant's indefinite-failure modeling requires the oracle to SEE timeouts as responses. If transport errors threw, `OneOf(timeout→Same, timeout→Next)` would be untestable over HTTP.

#### Scenario: Happy path — timeout becomes a response

**Given** a stub client that never responds within the configured timeout
**When** `Withdraw` executes
**Then** the result is `WithdrawResponse.Timeout` (mapper applied to `TransportOutcome.TimedOut`) and the executor's oracle validation proceeds normally

#### Scenario: Edge case — unexpected status is a deviation, not a crash

**Given** the server returns 500 where the mapper maps 5xx to `ServerError`
**When** the oracle validates against a spec not allowing `ServerError`
**Then** the executor reports `DeviatesAt` (the bug is caught as a spec deviation, with repro path)

### Requirement: End-to-end pipeline over HTTP

Running the generated suite through `Http4sSut` SHALL yield all-`Passed` reports against a conformant server stub and at least one `DeviatesAt` report against a deliberately broken one.

**Given** the bank spec, generated test cases, and an http4s server stub implementing the bank API
**When** `TestCaseExecutor` runs the cases through `Http4sSut`
**Then** the conformant stub yields all-`Passed`, and a deliberately broken stub (withdraw ignores balance) yields `DeviatesAt` naming the withdraw step

#### Scenario: Happy path — conformant server

**Given** a correct in-process http4s `HttpApp` wrapped as a `Client[IO]`
**When** the full generated suite runs
**Then** every report is `Passed`

#### Scenario: Error path — broken server detected

**Given** the same app with the insufficient-funds check removed
**When** the suite runs
**Then** at least one report is `DeviatesAt` on a `Withdraw` step

## Properties (Ring 3)

### Property: Request codec roundtrip

**Generator strategy** (Hedgehog): constructive `genWithdrawRequest` with `Gen.frequency1` over boundary amounts and unicode account ids

**Invariant**: For all requests, decoding the encoded HTTP entity yields the original request.

```
property("request codec roundtrip") {
  for {
    req <- genWithdrawRequest.forAll
  } yield Result.assert(decodeEntity[WithdrawRequest](route.encode(req)) == Right(req))
}
```

### Property: Mapper totality over statuses

**Generator strategy** (Hedgehog): `genStatus` = `Gen.int(Range.linear(100, 599))` mapped through `Status.fromInt` (constructive); `genBody` = `Gen.choice1`(well-formed JSON, empty, malformed bytes) — malformed bodies must map to a decode-error response variant

**Invariant**: For every HTTP status code and well-formed body, the response mapper produces a `Res` value — `execute` never raises for mapped operations.

```
property("mapper totality over statuses") {
  for {
    status <- genStatus.forAll
    body   <- genBody.forAll
  } yield Result.assert(
    Http4sSut(stubClient(status, body), binding).execute(withdrawCall).attempt.map(_.isRight).unsafeRunSync()
  )
}
```

### Property: Transparency — HTTP SUT equals direct SUT

**Generator strategy**: `genTestCase` (spec 4 fixtures); the HTTP side wraps an in-process `HttpApp` as `Client[IO]` — no network, fully deterministic

**Invariant**: For all generated test cases, executing through `Http4sSut` over an in-process conformant server yields the same `ExecutionReport` as executing through the in-memory `RefSut`.

```
property("transparency — HTTP SUT equals direct SUT") {
  for {
    tc <- genTestCase.forAll
  } yield Result.assert(
    (TestCaseExecutor.run(spec, tc, httpSut, noHooks),
     TestCaseExecutor.run(spec, tc, RefSut(spec), noHooks)).mapN(_ == _).unsafeRunSync()
  )
}
```

## Compile-Negative Obligations

| Must NOT compile | Why | Test |
|---|---|---|
| `MaxRetryCount(0)` (literal) | Iron `Positive`; dynamic values via `MaxRetryCount.either` | `assertDoesNotCompile` stub |

## Proof Obligations

| Obligation | Source | Enforcement | Test/Artifact |
|---|---|---|---|
| Requests encoded per route, responses decoded per mapper | Req: binding / Scenario: happy | scenario test (stub client observes request) | "binding — happy path" |
| Unbound operations rejected at wiring time as `Left` | Scenario: unbound | scenario test | "binding — unbound operation" |
| Timeouts/connection failures surface as response data, never raise | Req: transport-as-data / Scenario: timeout + Property: totality | property test + static rule (no-throw) + adversarial review | "mapper totality over statuses" |
| Unexpected status → spec deviation, not a crash | Scenario: 500 deviation | scenario test | "transport — 500 is a deviation" |
| `decodeEntity(encode(req)) == Right(req)` | Property: codec roundtrip | property test (wire round-trip law, Ring 4) | "request codec roundtrip" |
| HTTP SUT verdict-equivalent to direct SUT | Req: end-to-end / Scenarios: conformant, broken + Property: transparency | property test | "transparency" |
| `MaxRetryCount` positive | type constraint | type system (Iron) + compile-negative test | typed contract CN stub |

## Verification Rings

Ring 0 ✅ · Ring 1 ✅ · Ring 2 — (separate module; layer rule via build dependsOn) · Ring 3 ✅ · Ring 4 ✅ (HTTP wire round-trip laws — P: codec roundtrip + mapper totality) · Ring 5 — · Ring 6 — · Ring 7 — · Ring 8 ✅ · Ring 9 —
