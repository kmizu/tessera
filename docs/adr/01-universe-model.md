# ADR 01: Universe model

## Context

MVP uses named terms and explicit `Sort(level: Int)` where `Sort(0)` represents
`Type[0]`.

## Decision

- start with one universe level that can be incremented in checker inference
- keep the representation simple until syntax and elaboration are stable

## Consequences

- good for rapid slicing
- will likely migrate to de Bruijn + explicit level constraints later
