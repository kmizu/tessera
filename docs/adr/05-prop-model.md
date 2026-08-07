# ADR 05: `Prop` model

## Status
- pending

## Context
The project currently uses a universe hierarchy starting at `Sort(0)` and does not
separate a dedicated proof universe.

## Decision
Keep `Prop` as a future extension after initial MVP stabilizes, while maintaining
`Sort(0)` as the first usable sort in the current kernel.

## Consequences
- Keeps kernel checking tractable for MVP.
- Requires a dedicated follow-up ADR-backed migration once proposition-specific
  reduction/erasure behavior is introduced.
