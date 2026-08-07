# Meta language

Current MVP target:

- Explicit core-level `Synth` combinators are implemented in `tessera.meta` and are now
  directly testable:
  - `use`, `fn`, `depFn`, `fnN`
  - `construct`, `zip`
  - `call`, `inspect`, `lookup`, `choose`, `label`
  - `search` with deterministic first-success policy
  - `map` / `flatMap` composition, and `derive` with kernel recheck
- Surface sugar remains available via declarations like:
  `synth do { ... }` and `for { ... }`
  where `param` introduces lexical binders and `yield` provides the final ordinary term.

Planned shape (next phases):

- richer surface grammar for direct combinator expressions inside `synth do` blocks
- typed quotation/splicing
- richer search diagnostics and multi-shot safety checks with continuation-like control forms
- more structured provenance and trace formats

The implementation keeps generated terms explicit and kernel-checkable by design.
