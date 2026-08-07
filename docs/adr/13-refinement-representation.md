# ADR 13: Refinement representation

## Status
- pending

## Context
Current `Synth` currently models alternatives by executable combinators and
search results instead of explicit named-slot `Refinement` objects.

## Decision
Keep implicit slots represented as ordinary `construct` arguments for now,
while documenting a future named-slot `Refinement` intermediate.

## Consequences
- Reduces surface ceremony.
- Leaves a clear upgrade path for richer named slot introspection.
