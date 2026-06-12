# Implementation Progress — port-accordant-to-scala3

Tracker for the apply phase (verified-scala3 schema, `tracks` target).
One spec at a time; a checkbox is marked only after the spec's checkpoint
is presented and the human approves.

- [ ] 1. `specs/oracle-core/spec.md` — pure oracle kernel: Outcome ADT, StateProfile, Verdict, Spec.allows (+ multi-module build restructure)
- [ ] 2. `specs/input-sets/spec.md` — labeled OperationCalls, InputSet composition, Gen-backed sources
- [ ] 3. `specs/state-graph/spec.md` — bounded BFS exploration of reachable states with mocked responses
- [ ] 4. `specs/test-generation/spec.md` — state/transition coverage + random walk, circe persistence
- [ ] 5. `specs/test-execution/spec.md` — SystemUnderTest, step-wise oracle replay, hooks, munit module
- [ ] 6. `specs/http-binding/spec.md` — http4s Client binding, transport outcomes as data (http4s module)
- [ ] 7. `specs/smithy4s-derivation/spec.md` — Operation slots + HTTP binding derived from Smithy IDL (smithy4s module)
- [ ] 8. `specs/linearizability/spec.md` — concurrent cases, parallel executor, permutation checker
