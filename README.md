# Tessera

Tessera is a Scala-first proof language prototype guided by `tessera_complete.md`.

This repository currently contains an executable MVP slice:

- a closed-term kernel slice (`src/main/scala/tessera/kernel`)
- a tiny term AST (`src/main/scala/tessera/core`)
- a toy parser for declaration files (`src/main/scala/tessera/parser`)
- a CLI that can check and pretty-print parsed declarations (`src/main/scala/tessera/cli`)
- an explicit synthesis combinator API (`src/main/scala/tessera/meta`) and `derive`-based kernel recheck

## Quick start

```bash
sbt test
```

Run a sample declaration file:

```bash
sbt "runMain tessera.Main check examples/Identity.tes"
sbt "runMain tessera.Main eval examples/Identity.tes id"
```

You can also try the new `synth do` sugar example:

```bash
sbt "runMain tessera.Main show-desugared examples/AndIntro.tes andIntro"
```

## Current CLI

```bash
sbt "runMain tessera.Main check examples/Identity.tes"
sbt "runMain tessera.Main eval examples/Identity.tes id"
sbt "runMain tessera.Main show-term examples/Identity.tes id"
sbt "runMain tessera.Main show-core examples/Identity.tes"
sbt "runMain tessera.Main show-synth examples/Identity.tes id"
sbt "runMain tessera.Main show-desugared examples/Identity.tes id"
sbt "runMain tessera.Main trace-synth examples/AndIntro.tes andIntro"
sbt "runMain tessera.Main holes examples/Identity.tes"
```

Example corpus:

- `examples/Identity.tes`
- `examples/BadId.tes` (expected-fail case for type checking)
- `examples/AndIntro.tes`
- `examples/Functions.tes`
- `examples/AndOr.tes`
- `examples/Nat.tes`
- `examples/Equality.tes`
- `examples/Holes.tes`
- `examples/SynthesisExplicit.tes`
- `examples/SynthesisFor.tes`
- `examples/SynthesisDo.tes`
- `examples/SynthesisParam.tes`
- `examples/SynthesisFailure.tes`
- `examples/SynthesisBranching.tes`

Implemented in this slice:

- `sbt test`
- `sbt "runMain tessera.Main check <file.tes>"`
- `sbt "runMain tessera.Main eval <file.tes> <decl or expression>"`
- `sbt "runMain tessera.Main show-term <file.tes> [decl]"`
- `sbt "runMain tessera.Main show-core <file.tes> [decl]"`
- `sbt "runMain tessera.Main show-synth <file.tes> [decl]"`
- `sbt "runMain tessera.Main show-desugared <file.tes> [decl]"`
- `sbt "runMain tessera.Main trace-synth <file.tes> [decl]"`
- `sbt "runMain tessera.Main holes <file.tes> [decl]"`

Current MVP `synth do` / `for` support:

- `synth do { ... }` and `for { ... }` blocks with `param` and final `yield`
- `sbt "runMain tessera.Main show-synth <file.tes> [decl]"` prints the parsed synthesis block shape
- `sbt "runMain tessera.Main show-desugared <file.tes> [decl]"` prints the elaborated lambda core
- `sbt "runMain tessera.Main trace-synth <file.tes> [decl]"` prints statement trace + desugared core

## Current status

See `IMPLEMENTATION_STATUS.md` for the latest implemented/in-progress items.
