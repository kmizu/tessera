# ADR 03: Synth runtime representation

## Status

- Implemented: **partial** (MVP)
- Date: 2026-08-07

## Context

The meta layer needs a runtime representation for automation scripts that:

- does not depend on mutable global proof state,
- can be composed with ordinary `map` / `flatMap`,
- and can be rechecked by the kernel before acceptance.

## Decision

Use an explicit `Synth` type:

- `type Goal[A] = Term` (explicit goal term in MVP),
- `type Search[A] = SearchResult[A]` where search is pure and deterministic,
- `class Synth` stores an evaluator function `Context => SearchResult[Term]`.

Combinators (`fn`, `call`, `construct`, `lookup`, `choose`, `search`, etc.) are pure
and return a `Synth` value, which only becomes an ordinary `Term` after `derive`.

`derive` performs kernel re-checking against an optional expected type.

## Consequences

- Public meta API is a value language, not a stateful `ProofState` API.
- Generated terms remain inspectable and can be shown through existing CLI commands.
- Failures and traces are carried as part of `SearchResult` and can be presented in
  structured diagnostics.
