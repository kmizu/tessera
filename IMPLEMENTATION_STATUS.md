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
- [x] `for`/`do` parser and desugaring (MVP `param`/`let`/bind/`yield`; `for` block alias)
- [x] `trace-synth` command output
- [x] CLI wire-up for `show-core`, `show-synth`, `show-desugared`, `trace-synth`
- [x] `holes` command collects and prints unresolved holes in core declarations
- [x] `eval` command evaluates a declaration or a one-shot term and prints normalized core
- [x] structured `eval --trace` output for parse, elaboration, kernel, and normalization stages
- [x] ordered same-file constants with immutable kernel environments and transparent delta reduction
- [x] Java 17 executable JAR packaging with checksum and tag-driven GitHub Release workflow
- [x] GitHub Actions CI for pull requests and main
- [x] architecture-layer checks (kernel/core/meta import isolation via tests)
- [x] Example corpus under `examples/` (MVP-compatible declarations; advanced forms as annotated placeholders)
- [x] Kernel soundness hardening: variable checking compares types (not sorts),
  capture-avoiding substitution, alpha-insensitive definitional equality,
  kernel-level hole rejection, acyclic-by-construction environments, and
  budget-bounded normalization for unchecked terms
- [x] Compiler warnings promoted to errors (`-Wunused:all -Xfatal-warnings`)
- [ ] LSP tooling

## Notes

- [x] `tessera.meta.Synth` now exposes explicit combinators (`fn`, `fnN`, `construct`, `zip`,
  `call`, `inspect`, `search`, etc.) with kernel-rechecking in `derive`.
- [x] `SynthTest` includes coverage for API usage patterns, failure paths, and search ordering.
- [x] `ArchitectureTest` added to assert forbidden cross-layer imports in main code.
- [x] ADR stubs added to cover missing design decision records from `tessera_complete.md`
  (05–26).
- This phase focuses on Phase 0 and a narrow executable kernel subset.
- Same-file constants cover only earlier accepted declarations; this is not a general module,
  import, recursion, or opacity system.
- Universe levels are not yet constrained (ADR-01): any sort checks against any
  sort, so `def a : (Sort 0) = (Sort 5)` is accepted and pinned by tests.
- `synth do` `let`/`<-` values are elaborated with a hardcoded `Sort 0` value
  type, so only `Sort(0)`-typed values (e.g. constructors) check; this limit is
  pinned by a `ModuleCheckerTest` case.
- Current implementation is intentionally minimal and keeps proof search/continuation
  out of scope.
- Release automation targets a portable JVM JAR only; native binaries,
  installers, package-manager publication, and Maven Central remain out of scope.
- Repository-side release protection is active: a `release-tags` ruleset on
  `v*` tags (required signatures, no deletion, no force-push) and a `release`
  deployment environment whose reviewer approval gates the publish job.
  Release tags must be signed (`tag.gpgsign` is enabled in-repo; the SSH
  signing key must be registered on GitHub as a signing key).
