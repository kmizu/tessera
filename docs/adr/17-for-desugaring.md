# ADR 17: `for` desugaring

## Status
- implemented

## Context
`for` blocks are alias for `synth do`.

## Decision
Keep parser-level alias that desugars identically to `for` + `do` syntax.

## Consequences
- Improves familiarity without introducing new evaluation semantics.
