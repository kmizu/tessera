# ADR 26: Error accumulation

## Status
- pending

## Context
Search/result handling currently carries first-failure/ordered-failure details.

## Decision
Keep linear, deterministic failure traces in MVP; revisit richer accumulation once
meta control and continuation-like behavior are added.

## Consequences
- Easier failure output and tests.
- Limits parallel diagnostics in some search branches.
