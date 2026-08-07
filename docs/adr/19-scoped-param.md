# ADR 19: Scoped `param`

## Status
- implemented

## Context
`param` introduces lexical binders and answer-type modification (`A => B`).

## Decision
Lower `param` directly to nested lambdas in elaboration.

## Consequences
- No mutable goal queue; scope remains lexical.
- Supports clear equivalence with explicit `fn`/nested `fn` forms.
