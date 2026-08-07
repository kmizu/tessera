# ADR 22: Delimited continuation model

## Status
- pending

## Context
Continuation-based designs are not currently used in public API.

## Decision
Defer this design until direct desugaring and explicit combinators reach larger
coverage.

## Consequences
- Keeps model conservative and explainable.
- Prevents continuation-driven proof-state opacity.
