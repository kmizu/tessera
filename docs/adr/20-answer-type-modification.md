# ADR 20: Answer-type modification

## Status
- implemented (direct)

## Context
`param` changes the surrounding synthesis answer type from `B` to `A => B`.

## Decision
Handle this in elaborator through direct lambda expansion, without generic
continuation typing in public meta API.

## Consequences
- Avoids answer-type complexity in public combinator surface.
- Preserves direct inspectability of generated terms.
