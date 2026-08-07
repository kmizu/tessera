# ADR 25: Applicative vs monadic field synthesis

## Status
- pending

## Context
`construct` in MVP builds fields through applicative style sequencing with `flatMap`
capability available via `Zip` helper.

## Decision
Prefer applicative composition when slot order is independent; use dependent
sequence only when required.

## Consequences
- Better opportunities for structure-preserving transformations later.
- Matches `zip`-style composition use cases.
