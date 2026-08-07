# ADR 18: `do` desugaring

## Status
- implemented

## Context
MVP uses direct syntax desugaring from `synth do`/`for` param blocks to nested
lambdas.

## Decision
Retain direct desugaring for now; no mutable proof-state interpreter.

## Consequences
- Generated term is explicit and inspectable.
- Kernel recheck remains straightforward.
