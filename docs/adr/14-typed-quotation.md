# ADR 14: Typed quotation

## Status
- pending

## Context
Quotation/splicing is not part of current MVP.

## Decision
Defer typed quotation after explicit combinator and do/for sugar are stable.

## Consequences
- Avoids premature complexity in parser and meta evaluator.
- Keeps generated-term generation and kernel recheck straightforward.
