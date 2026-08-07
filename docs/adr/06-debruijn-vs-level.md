# ADR 06: de Bruijn index vs level

## Status
- pending

## Context
Terms currently use named variables, with de Bruijn helpers implemented in the kernel.

## Decision
Keep named representation as the parser-level and elaboration-facing form,
and retain de Bruijn operations as a future kernel-normalization helper.

## Consequences
- Easier source/error reporting during MVP.
- Migration path to de Bruijn can be deferred and documented.
