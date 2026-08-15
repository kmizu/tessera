# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Tessera is a Scala-first proof language prototype. The design spec is `tessera_complete.md`; what is actually implemented is tracked in `IMPLEMENTATION_STATUS.md` (keep it honest — invariant 8 forbids overclaiming). Design decisions are recorded as ADRs in `docs/adr/` (01–26), with topic docs in `docs/`.

Scala 3.4.2, sbt 1.10.7, munit for tests. CI runs on Java 17 — keep the build Java 17 compatible.

## Commands

```bash
sbt test                                          # full test suite (includes architecture checks)
sbt "testOnly tessera.kernel.KernelTest"          # single test suite
sbt "runMain tessera.Main check examples/Identity.tes"   # run the CLI
sbt assembly                                      # build executable jar (scripts/build-release.sh for full release)
bash src/test/sh/release-tag-test.sh              # release shell tests (also run in CI)
bash src/test/sh/release-provenance-test.sh
```

CLI subcommands: `check`, `eval [--trace]`, `show-term`, `show-core`, `show-synth`, `show-desugared`, `trace-synth`, `holes`. Sample `.tes` files live in `examples/` (`BadId.tes` is an expected-fail case).

If the sbt launcher fails with `AccessDeniedException: /run/user/1000`, set `XDG_RUNTIME_DIR` to a short writable directory first (`export XDG_RUNTIME_DIR=$(mktemp -d /tmp/tessera-rt.XXXXXX)`); a long path fails too, because sbt places a Unix domain socket there (~108 char limit). `scripts/build-release.sh` applies the same workaround internally.

## Architecture

Pipeline for a `.tes` declaration file:

1. `parser.SimpleSyntaxParser` — surface syntax, including `synth do` / `for` blocks and tuple sugar
2. `elab.NameResolver` + `elab.Elaborator` — desugars surface syntax into core lambda terms (`for`/`do` always lower to explicit combinators; there is no goal-stack interpreter)
3. `compiler.ModuleChecker` — per-declaration orchestration: ordered same-file constants, duplicate/hole/kernel diagnostics, transparent `def` delta-reduction. Declarations may only reference *earlier* accepted declarations; forward references, recursion, imports, and opacity are not implemented.
4. `kernel.Kernel` / `kernel.KernelEnvironment` — type checking and normalization of closed core terms; immutable environments

Shared/auxiliary layers:

- `core` — the `Term` AST used by every layer
- `meta.Synth` — explicit synthesis combinator API (`fn`, `construct`, `zip`, `call`, `search`, …); every synthesized term is re-checked by the kernel via `derive`
- `cli.Commands` + `Main` — command wiring

### Layering rules (enforced by tests)

`src/test/scala/tessera/architecture/ArchitectureTest.scala` fails the build on forbidden imports:

- `kernel` must not reference parser/elab/meta/compiler/cli
- `core` must not import any higher layer
- `meta` must not import parser/elab/compiler/cli
- `parser` and `elab` must not import compiler

## Invariants

`AGENTS.md` lists 20 binding invariants — read it before non-trivial changes. The ones that most often shape a change:

- No unresolved metavariable may reach kernel type checking; a feature is not done until kernel checks of the generated term pass.
- Public meta APIs must not expose mutable global proof state and must not be `List[Goal] => List[Goal]`.
- New `Synth` combinators require type tests and failure-display tests; any binder/substitution/unification bug gets regression coverage.
- `param` is lexically scoped; continuations must not escape `derive`/`synth` delimiters; raw `call/cc` needs an ADR first.
- Automation must be able to show the ordinary terms it generates.
