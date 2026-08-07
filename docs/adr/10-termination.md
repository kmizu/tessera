# ADR 10: Termination strategy

## Status
- pending

## Context
The MVP kernel does not implement structural recursion checking or general
termination analysis.

## Decision
Defer termination checking until recursive datatypes and recursors are
introduced.

## Consequences
- Safer to stage language features before enforcing totality.
- Explicitly tracks future soundness work as a hardening step.
