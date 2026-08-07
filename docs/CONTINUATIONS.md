# Continuations policy

This MVP uses **direct syntax desugaring** for scoped binders instead of a public
continuation API.

Why:

- `param` changes the answer type (it introduces a `->` lambda), so a plain
  monadic binder is not the right representation.
- We want generated terms to stay directly inspectable and re-checkable by the kernel.
- Avoiding public mutable proof-state / hidden goal-stack behavior keeps the public
  semantics simple and compositional.

Current strategy:

- `param` in `synth do` / `for` expands to nested lambda construction during elaboration.
- no public `call/cc` or mutable `current goal` API in MVP.
- source provenance is preserved through parser + printer style traces.

Planned order in later phases:

1. keep direct desugaring as default
2. optionally introduce a free scoped algebra for richer tooling
3. only if needed, evaluate typed delimited continuation model in an experimental namespace
