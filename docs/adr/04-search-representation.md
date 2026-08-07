# ADR 04: Search representation

## Status

- Implemented: **partial** (MVP)
- Date: 2026-08-07

## Context

Search is needed for composition patterns like `choose`/`search` without importing
an external nondeterminism runtime.

## Decision

Represent search outcome as:

- `SearchResult.Success(value, trace)`
- `SearchResult.Failure(message, trace)`

and keep `choose` and `search` deterministic:

- `choose` returns first success, second only when first fails,
- `search(config)` evaluates alternatives in order and returns first success, otherwise
  all failure messages.

## Consequences

- Predictable behavior for MVP and easy regression testing.
- Failure traces remain inspectable and can be surfaced as diagnostics.
- Multi-shot / continuation replay is not introduced at MVP stage.
