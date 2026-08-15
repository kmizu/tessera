# Diagnostics

Kernel errors are represented as `KernelError` values and surfaced through
`KernelReport`.

Current error kinds:

- undefined variable
- undefined constant
- expected sort
- lambda inference limitation
- non-function application
- type mismatch
- unresolved hole
- de Bruijn index out of range

Ordered module checking also reports duplicate source declarations, environment
registration failures, and keeps unresolved-hole declarations out of the kernel
environment. Each declaration has one of three statuses:

- `Accepted`: checked or inferred successfully and registered;
- `Unchecked`: an unannotated declaration whose core is an outermost lambda
  with a non-inferable parameter type; a `CannotInferLambda` raised deeper
  inside a term rejects instead, because it may mask a genuine type error.
  Unchecked terms are normalized under a step budget, so a diverging body
  reports `<normalization budget exhausted>` instead of hanging;
- `Rejected`: failed elaboration, hole validation, duplicate validation,
  environment registration, or kernel validation; never registered or
  normalized by checked CLI paths.
