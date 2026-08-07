# ADR 21: Direct desugaring vs free scoped algebra

## Status
- implemented (direct desugaring)

## Context
Scoped binder sugar must map to explicit terms without hidden mutable state.

## Decision
Use direct desugaring as the default MVP strategy.

## Consequences
- Smaller implementation surface.
- Easier kernel boundary and tracing.
