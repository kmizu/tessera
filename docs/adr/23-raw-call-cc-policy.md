# ADR 23: Raw `call/cc` exposure policy

## Status
- pending

## Context
No public `call/cc` API is introduced in MVP.

## Decision
Do not expose raw continuation APIs publicly without an explicit ADR and dedicated
safety surface.

## Consequences
- Avoids answer-type/escape semantics pitfalls.
- Keeps delimiter boundary clear.
