## Scala 3 — Typelevel ecosystem
## accordant4s

This is a Scala 3 project using the Typelevel ecosystem (Cats Effect, FS2).

### Structure

```
accordant4s/
├── build.sbt                    # Build configuration
├── project/                     # SBT project metadata
├── src/
│   ├── main/scala/              # Source code
│   └── test/scala/              # Tests
├── openspec/                    # OpenSpec verified-scala3 workflow
│   ├── config.yaml              # Project context and rules
│   ├── schemas/                 # Schema definitions and templates
│   ├── specs/                   # Spec files (populated as changes are proposed)
│   └── changes/                 # Active and archived changes
└── .pi/skills/                  # Pi agent skills for openspec workflow
```

### OpenSpec Workflow

This project uses the **verified-scala3** OpenSpec schema for depth-first
verified implementation. See `openspec/config.yaml` for project context.

Available skills:
- `/opsx:explore` — explore ideas before proposing a change
- `/opsx:propose` — create a new change with all artifacts
- `/opsx:apply` — implement specs through verification rings
- `/opsx:next-spec` — implement the next spec in the queue
- `/opsx:checkpoint` — review a completed spec
- `/opsx:ring <N>` — re-run a specific verification ring
- `/opsx:scan` — scan source for concept inventory
- `/opsx:pseudo` — generate pseudocode for a spec
- `/opsx:archive` — archive a completed change
