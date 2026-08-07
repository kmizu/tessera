# ADR 16: Reducibility

## Status
- pending

## Context
All terms are treated uniformly in MVP normalization and checking.

## Decision
Use current transparent normalization model for all declarations, and defer
opaque/reducibility annotations to later phases.

## Consequences
- Simpler kernel semantics.
- Future work required to support theorem-like opacity rules.
