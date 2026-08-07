# Implementation status for Tessera MVP

## Legend

- ✅ implemented
- 🟡 in progress
- ⬜ not yet started

## Current status snapshot

- [x] README command coverage
- [x] Parser/scanner bootstrap
- [x] Kernel vertical slice with closed term checking and basic definitional equality
- [x] Elaborator bootstrap
- [x] Synth combinator API
- [x] `for`/`do`/`param` parser and desugaring (MVP `synth do` + `param` + `yield`; `for` block alias)
- [x] `trace-synth` command output
- [x] CLI wire-up for `show-core`, `show-synth`, `show-desugared`, `trace-synth`
- [x] `holes` command collects and prints unresolved holes in core declarations
- [x] `eval` command evaluates a declaration or a one-shot term and prints normalized core
- [ ] full `eval` structured trace format
- [x] architecture-layer checks (kernel/core/meta import isolation via tests)
- [x] Example corpus under `examples/` (MVP-compatible declarations; advanced forms as annotated placeholders)
- [ ] LSP tooling

## Notes

- [x] `tessera.meta.Synth` now exposes explicit combinators (`fn`, `fnN`, `construct`, `zip`,
  `call`, `inspect`, `search`, etc.) with kernel-rechecking in `derive`.
- [x] `SynthTest` includes coverage for API usage patterns, failure paths, and search ordering.
- [x] `ArchitectureTest` added to assert forbidden cross-layer imports in main code.
- [x] ADR stubs added to cover missing design decision records from `tessera_complete.md`
  (05–26).
- This phase focuses on Phase 0 and a narrow executable kernel subset.
- Current implementation is intentionally minimal and keeps proof search/continuation
  out of scope.
