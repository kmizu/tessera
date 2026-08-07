# ADR 12: Implicit arguments

## Status
- pending

## Context
Current parser uses explicit parameters in core syntax.

## Decision
Introduce implicit argument elaboration only after the elaborator and meta
combinators settle.

## Consequences
- Delays complexity in parser grammar.
- Leaves room for scoped `param` and explicit examples in MVP.
