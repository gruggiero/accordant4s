# Capability Profile

<!-- DETECTED project capabilities. Populated by inspecting build.sbt,
     project/plugins.sbt, source code, and tool configs — NEVER assumed.
     All later artifacts (specs, design, apply phase) must generate code
     and tests for THIS stack. If this file disagrees with openspec/config.yaml,
     this file wins — update config.yaml. -->

## Build & Language

| Item | Detected Value | Evidence (file) |
|------|---------------|-----------------|
| Scala version | <!-- e.g. 3.8.3 --> | <!-- build.sbt --> |
| sbt version | <!-- e.g. 1.12.x --> | <!-- project/build.properties --> |
| Modules | <!-- e.g. platform-loyalty, core, api --> | <!-- build.sbt --> |
| Fatal warnings | <!-- -Werror active? per module? --> | <!-- build.sbt scalacOptions --> |

## Libraries

| Concern | Detected Library | Version | Notes |
|---------|-----------------|---------|-------|
| Effect system | <!-- cats-effect / ZIO / Future / none --> | | |
| Actors | <!-- Pekko typed / Akka / none --> | | <!-- cluster? sharding? persistence? --> |
| HTTP | <!-- Pekko HTTP / http4s / tapir / none --> | | |
| Persistence | <!-- DynamoDB / Postgres (skunk/doobie) / journal --> | | |
| Messaging | <!-- Kafka client / none --> | | |
| Streaming | <!-- fs2 / Pekko Streams / none --> | | |
| JSON | <!-- circe / ... --> | | |
| IDL / codegen | <!-- Smithy/smithy4s / protobuf/scalapb / none --> | | |
| Refined types | <!-- Iron / refined / none --> | | |
| Telemetry | <!-- otel4s / raw OpenTelemetry / none --> | | <!-- if none: Ring 9 skip or setup task --> |

## Testing

| Concern | Detected | Consequence |
|---------|----------|-------------|
| Test framework | <!-- ScalaTest / munit / weaver --> | <!-- generated tests use exactly this --> |
| Property testing | <!-- ScalaCheck via scalatestplus / munit-scalacheck --> | <!-- base trait/suite to use --> |
| Actor test kits | <!-- ActorTestKit, BehaviorTestKit, PersistenceTestKit available? --> | <!-- actor scenarios use these, never deferred --> |
| Mutation tool | <!-- sbt-stryker4s? stryker4s.conf? fixed mutate list? --> | <!-- Ring 5 retargets mutate list per spec --> |
| Formal verification | <!-- Stainless available? --> | <!-- Ring 6 applicability --> |
| Model checking | <!-- TLA+/Apalache available? --> | <!-- Ring 7 applicability --> |

## Static Analysis

| Tool | Active Rules | Inactive/Commented | Evidence |
|------|-------------|--------------------|----------|
| Scalafix | <!-- DisableSyntax flags, OrganizeImports, custom arch rules --> | | <!-- .scalafix.conf --> |
| WartRemover | <!-- active warts --> | <!-- commented-out warts --> | <!-- build.sbt --> |
| scalafmt | <!-- config present? check enforced? --> | | |

## Compile & Test Commands

<!-- The EXACT commands that genuinely compile/run code in this project.
     Typed contracts and tests are only trustworthy if compiled with these. -->

| Purpose | Command |
|---------|---------|
| Main compile | <!-- e.g. sbt platformLoyalty/compile --> |
| Test compile (typed contracts) | <!-- e.g. sbt platformLoyalty/Test/compile --> |
| Run tests | <!-- e.g. sbt platformLoyalty/test --> |
| Lint | <!-- e.g. sbt "scalafix --check" --> |
| Mutation | <!-- e.g. sbt stryker --> |

## Typed Contract Placement

<!-- Where typed-contract files must live so sbt genuinely compiles them.
     Files under openspec/changes/... are NOT compiled — never place them there. -->

- Contract location pattern: `<module>/src/test/scala/<pkg>/typecontract/<SpecName>TypeContract.scala`
- Compile command: `sbt <module>/Test/compile`

## Domain Purity Rules (feeds Ring 2)

<!-- Project-specific layer constraints derived from the detected stack. -->

| Layer/Package | Must NOT import | May import |
|---------------|-----------------|------------|
| <!-- pure domain --> | <!-- the detected actor/protobuf-runtime/HTTP/messaging/DB libs --> | <!-- stdlib, cats core, refined-type lib --> |
| <!-- actor/runtime --> | <!-- — --> | <!-- actor framework, persistence, messaging --> |
| <!-- endpoint --> | <!-- — --> | <!-- HTTP framework, generated API models --> |
| <!-- generated code --> | <!-- excluded from checks: list paths --> | |

## Ring Availability Summary

| Ring | Available? | If unavailable: impact / setup task |
|------|-----------|--------------------------------------|
| 0 Compile | ✅ | |
| 1 Lint | <!-- ✅/partial --> | |
| 2 Architecture | <!-- rules installed? --> | |
| 3 Property tests | ✅ | |
| 4 Compatibility | <!-- fixtures exist? --> | |
| 5 Mutation | <!-- ✅/❌ --> | |
| 6 Formal | <!-- ✅/❌ --> | |
| 7 Model checking | <!-- ✅/❌ --> | |
| 8 Adversarial review | ✅ (manual — always available) | |
| 9 Telemetry | <!-- ✅/❌ --> | <!-- skip with stated impact, or setup task --> |
