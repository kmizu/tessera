# ADR 09: Inductive strategy

## Status
- pending

## Context
Core currently models constructors generically (`Constructor(name, fields)`).

## Decision
Keep generic constructor support in core and defer full inductive declaration
elaboration (`enum`-style declarations, recursors) to later phases.

## Consequences
- Maintains MVP speed.
- Requires a migration plan for positivity and recursor generation.
