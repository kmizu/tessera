# ADR 16: Reducibility

## Status
- accepted

## Context
The ordered same-file declaration slice needs a deterministic rule for whether an
accepted declaration unfolds during definitional equality and evaluation.

## Decision
Every registered MVP `def` is transparent. `Term.Constant` normalization unfolds
its environment entry, with a cycle guard for defensive totality. Declarations
that fail checking are never registered and therefore cannot participate in
reduction. `opaque def` and theorem-specific opacity remain future work.

## Consequences
- Simpler kernel semantics.
- Same-file aliases participate in definitional equality and evaluation.
- Future work required to support theorem-like opacity rules.
