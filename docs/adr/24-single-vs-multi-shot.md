# ADR 24: Single-shot vs multi-shot behavior

## Status
- pending

## Context
Search currently evaluates alternatives deterministically and does not reuse
continuations.

## Decision
Single-shot, left-to-right alternative choice is the MVP behavior.

## Consequences
- Simple predictable semantics.
- Multi-shot replay/safety can be revisited when more control forms are added.
