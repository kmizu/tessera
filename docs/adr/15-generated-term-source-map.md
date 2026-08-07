# ADR 15: Generated-term source map

## Status
- pending

## Context
Parser and elaborator currently track source formatting via pretty printer-like
rendering and synthetic de-sugared terms.

## Decision
Preserve statement-level provenance in parsing trace output before deeper source-map
object model is added.

## Consequences
- `trace-synth` remains useful now.
- Full source span propagation remains future work.
