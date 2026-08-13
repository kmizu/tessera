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

Ordered module checking also reports duplicate source declarations and keeps
unresolved-hole declarations out of the kernel environment. Each declaration has
one of three statuses:

- `Accepted`: checked or inferred successfully and registered;
- `Unchecked`: currently limited to an unannotated lambda whose parameter type
  cannot be inferred; evaluable when selected but not registered;
- `Rejected`: failed elaboration, hole validation, duplicate validation, or kernel
  validation; never registered or normalized by checked CLI paths.
