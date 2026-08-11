# Structured Eval Trace Design

## Goal

Add an opt-in `eval --trace` mode that exposes the stages between user input and
the value printed by Tessera. The trace makes parsing, elaboration, kernel
validation, and normalization visible without changing the existing concise
`eval` output.

## Command surface

The new command form is:

```text
tessera eval --trace <file.tes> <declaration-or-expression>
```

The existing form remains unchanged:

```text
tessera eval <file.tes> <declaration-or-expression>
```

`--trace` is accepted only immediately after `eval`. Missing file or target
arguments produce a usage line for the selected form.

## Successful declaration trace

For a declaration with an expected type, the output is deterministic and uses
one field per line:

```text
eval trace:
  file: examples/Identity.tes
  target: id
  kind: declaration
  parsed: (lambda A: Type[0] => (lambda x: A => x))
  core: (lambda A: Type[0] => (lambda x: A => x))
  expected: (A: Type[0]) -> (x: A) -> A
  kernel: OK
  normalized: (lambda A: Type[0] => (lambda x: A => x))
```

`parsed` is the readable parsed declaration body. `core` is the elaborated
ordinary term submitted to the kernel. `normalized` is printed only after a
successful check when an expected type is available.

## Inline and unannotated terms

An inline expression has `kind: expression`; an unannotated declaration keeps
`kind: declaration`. Both lack an expected type, so the trace attempts
`Kernel.infer` and reports one of:

```text
  expected: <none>
  inferred: Type[1]
  kernel: inference only
  normalized (unchecked): Type[0]
```

or:

```text
  expected: <none>
  inferred: unavailable: cannot infer type of lambda parameter `x`
  kernel: not checked
  normalized (unchecked): (lambda x: Type[0] => x)
```

The `unchecked` label is required whenever normalization occurs without an
expected-type kernel check. Inference is informative and does not turn an
unannotated term into a checked declaration.

## Failure traces

Each failure reports all stages reached before the failure and omits later
stages.

Module parse failure:

```text
eval trace:
  file: broken.tes
  target: id
  module parse: FAIL: at 4: unexpected end of input
```

Inline expression parse failure:

```text
eval trace:
  file: examples/Identity.tes
  target: (App
  kind: expression
  expression parse: FAIL: at 2: unexpected end of input
```

Elaboration failure includes `kind` and `parsed`, followed by:

```text
  elaboration: FAIL: <message>
```

Kernel rejection includes `parsed`, `core`, and `expected`, followed by:

```text
  kernel: FAIL
    <kernel diagnostic>
```

A rejected typed declaration does not print a normalized term. This keeps the
kernel trust boundary visible.

## Implementation boundary

The CLI owns trace formatting. The parser, elaborator, core AST, kernel, and
normalizer interfaces do not change. A private trace renderer in `TesseraCli`
assembles lines from existing values, so normal `eval` continues through its
current path.

No JSON schema, color protocol, source-span model, or persistent trace object is
introduced in this slice. The text format is stable enough for CLI regression
tests but remains a human-facing diagnostic.

## Tests

CLI tests cover:

- the existing concise `eval` output remaining byte-for-byte unchanged;
- a checked declaration trace with parsed, core, expected, kernel, and normalized
  fields;
- an inline expression with successful inference and unchecked normalization;
- an inline lambda whose type cannot be inferred;
- a typed declaration rejected by the kernel, with no normalized field;
- module and inline-expression parse failures;
- usage output when trace arguments are missing.

The complete suite remains the final compatibility gate.
