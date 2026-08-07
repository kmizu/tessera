# ADR 11: Positivity checking

## Status
- pending

## Context
No general inductive declarations are checked for positivity in MVP.

## Decision
Introduce positivity checking together with inductive declaration support in a later
phase.

## Consequences
- Keeps current kernel small.
- Positivity remains a required future invariant before general inductive support.
