# ADR 02: `for` / `do` / `param` desugaring strategy

## Context

The MVP needs a readable syntax for synthesis blocks while preserving inspectability and
kernel-centered soundness. We currently support

- `synth do { ... }`
- `for { ... }` (alias)
- `param`-based lambda introduction
- mandatory trailing `yield`

## Decision

Adopt **direct syntax desugaring**:

- `synth do` / `for` blocks are parsed into a parsed structure.
- `param` statements translate to nested `Lambda` binders.
- `yield e` translates to the final body term.
- `show-desugared` prints the generated core term.

`trace-synth` now reports the statement-level plan and the resulting desugared term.

## Consequences

- Parser/elaboration surface is lightweight and close to the explicit term model.
- No dedicated mutable proof-state interpreter is introduced in MVP.
- Generated core terms remain ordinary terms and are directly checked by the kernel.
- Scope is lexically clear via nested binders in desugaring.
- Search/continuation behavior is postponed to later phases.
