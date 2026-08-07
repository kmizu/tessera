# ADR 08: Equality representation

## Status
- pending

## Context
Current MVP carries equality-like constructors only as names (`Refl`/`Eq`
placeholders in examples).

## Decision
Keep equality as an explicit inductive/data-structure notion in the richer surface
layer; do not overload built-ins for proposition-level equality in core.

## Consequences
- Makes proof-oriented examples explicit.
- Prevents conflating boolean equality with propositional equality.
