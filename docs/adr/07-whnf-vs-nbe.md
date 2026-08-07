# ADR 07: WHNF vs NbE

## Status
- pending

## Context
Current equality checks are implemented via normalization into WHNF/normal forms
for the minimal term language.

## Decision
Adopt WHNF-based conversion for MVP. Compare with NbE in later phases when
inductive equality proof obligations increase.

## Consequences
- Simpler implementation and easier debugging.
- Potentially less efficient conversion behavior in richer fragments.
