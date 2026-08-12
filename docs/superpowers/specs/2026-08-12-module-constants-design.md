# Module Constants and Declaration References Design

## Goal

Make a Tessera source file behave as an ordered module. A declaration may use
accepted declarations that occur before it, and evaluation may unfold those
transparent definitions. This closes the largest remaining Phase 1 gap:
constants, environment lookup, declaration validation, and delta reduction.

The first runnable example is:

```text
def id : (Pi A (Sort 0) (Pi x (Var A) (Var A))) =
  (Lam A (Sort 0) (Lam x (Var A) (Var x)))

def alias : (Pi A (Sort 0) (Pi x (Var A) (Var A))) = id
```

Both declarations pass `check`, while `eval ... alias` unfolds `alias` and
`id` to the identity lambda.

## Scope

This slice adds:

- a core distinction between local variables and global constants;
- an immutable kernel environment containing accepted definitions;
- binder-aware resolution of source names;
- ordered module checking and registration;
- transparent delta reduction during normalization and definitional equality;
- module-aware `check`, `eval`, and `eval --trace` behavior;
- module constants in inline `eval` expressions.

This slice does not add imports, namespaces, mutual recursion, recursive
definitions, `opaque def`, `theorem`, separate compilation, or persistent
compiled environments. ADR 16's all-transparent MVP policy remains in force.

## Core Representation

Add a global reference node distinct from local variables:

```scala
Term.Constant(name: String)
```

`Term.Var` continues to represent a lexically bound local variable. A free
source identifier resolves to `Constant`, even if the name is absent from the
current environment. That lets the kernel report an undefined global without
receiving an accidentally open local term.

`Printer.render(Constant("id"))` renders `id`. The readable ordinary term does
not expose an implementation-only sigil; resolver tests distinguish `Constant`
from `Var` structurally.

All existing structural operations must handle constants:

- name substitution leaves `Constant` unchanged;
- de Bruijn shift and substitution leave `Constant` unchanged;
- hole collection treats `Constant` as a leaf;
- architecture tests continue to keep `core` independent of parser,
  elaboration, compiler orchestration, and CLI packages.

## Immutable Kernel Environment

Introduce these kernel-owned concepts:

```scala
final case class KernelDeclaration(
  name: String,
  declaredType: Term,
  value: Term
)

final class KernelEnvironment private (...):
  def lookup(name: String): Option[KernelDeclaration]
  def contains(name: String): Boolean
  def define(declaration: KernelDeclaration): Either[KernelEnvironmentError, KernelEnvironment]
  def names: Vector[String]

object KernelEnvironment:
  val empty: KernelEnvironment
```

The internal map is immutable. `define` rejects a duplicate name and returns a
new environment; it never overwrites an accepted declaration. `names` preserves
declaration order for deterministic diagnostics and tests, not for proof
semantics.

`Kernel` receives an environment through construction, defaulting to
`KernelEnvironment.empty` so existing closed-term callers remain source
compatible:

```scala
Kernel(environment: KernelEnvironment = KernelEnvironment.empty)
```

`define` is a structural immutable update and enforces uniqueness, not type
correctness. The production module-checking path calls it only for validated,
hole-free declarations. Keeping the low-level operation structural permits
isolated kernel tests, including defensive normalization tests for environments
that source checking could never construct. The kernel does not import parser,
elaborator, module checker, or CLI types.

## Name Resolution

Add a resolver in the elaboration layer. It traverses both declaration types
and desugared declaration bodies while carrying a lexical set of local names.

Resolution follows these rules:

1. A `Var(name)` whose name is lexically bound remains `Var(name)`.
2. Any other `Var(name)` becomes `Constant(name)`.
3. A `Pi` binder is in scope only in its codomain.
4. A `Lambda` binder is in scope only in its body.
5. A `Let` binder is in scope only in its body, not its type or value.
6. Constructor fields, applications, hole annotations, and nested terms are
   traversed recursively.
7. Existing `Constant` nodes remain constants.

Local bindings therefore shadow global declarations. Given a global `A`, the
`A` in `(Lam A (Sort 0) (Var A))` remains local, while an unbound `id` becomes
`Constant("id")`.

The resolver does not decide whether a constant exists. Environment lookup and
the resulting `UndefinedConstant` diagnostic belong to the kernel.

## Ordered Module Checking

Add `tessera.compiler.ModuleChecker` as the orchestration boundary. It may
depend on parser values, elaboration, core, and kernel; none of those lower
layers may depend on it.

The checker folds source declarations from top to bottom with an immutable
environment and an immutable set of every source name already encountered:

```text
parsed declaration + environment before declaration
  -> reject duplicate name if already encountered in this source file
  -> desugar and resolve names
  -> validate that every Constant in type and body exists in the prior environment
  -> reject unresolved holes for registration
  -> check against annotation, or infer when no annotation exists
  -> on success, define(name, type, value)
  -> on failure, record rejection and keep the previous environment
```

The current declaration is not added before its body is checked. Consequently:

- references to earlier accepted declarations succeed;
- forward references fail with `undefined constant`;
- direct self-reference fails with `undefined constant`;
- duplicate declarations fail even if the first declaration was rejected, and
  never replace the first source declaration;
- a rejected declaration never becomes visible to later declarations;
- checking continues after a rejection so independent later declarations can
  still be diagnosed and accepted.

### Typed declarations

For a declaration with an annotation, resolve both the annotation and body,
then run `Kernel(environment).check(body, annotation)`. Register the annotation
as its type only when the report is successful and neither annotation nor body
contains a hole.

### Unannotated declarations

For a declaration without an annotation, run `Kernel(environment).infer(body)`.
Register it with the inferred type only when inference succeeds and the body
contains no hole. An unannotated lambda whose only blocker is the kernel's
`CannotInferLambda` limitation remains evaluable as an unchecked selected source
declaration but is not registered. Later declarations cannot refer to it. Other
inference errors, including undefined constants, reject the declaration.

This distinction must be visible in the module result rather than calling an
unregistered declaration accepted.

### Result model

The checker returns every declaration outcome plus the final environment. The
outcome status is exactly one of:

- `Accepted`: kernel-checked or successfully inferred, hole-free, and registered;
- `Unchecked`: currently limited to a hole-free unannotated lambda whose type
  cannot be inferred; evaluable when selected but not registered;
- `Rejected`: duplicate, unresolved hole, undefined constant, or another
  elaboration/kernel error; neither evaluable nor registered.

Each outcome retains the parsed declaration and, when available, its resolved
core, resolved expected or inferred type, and diagnostics. CLI commands consume
this single result instead of independently elaborating and checking the same
file.

## Kernel Semantics

### Lookup and inference

`Kernel.infer(Constant(name))` returns the stored declaration type. A missing
entry produces:

```text
undefined constant: <name>
```

Checking a constant uses that inferred type and ordinary definitional equality.

The public `check` and `infer` entry points first walk the complete input term
(and the expected type for `check`) to validate every `Constant` against the
environment. This preflight is independent of syntax-directed type inference:
an undefined constant nested inside a lambda, constructor field, hole
annotation, or another term is rejected even when the later typing rule would
not inspect that child. The preflight does not reject lexically valid `Var`
nodes; local-variable checking remains part of ordinary kernel typing.

### Delta normalization

`Kernel.normalize(Constant(name))` looks up the declaration body and normalizes
it. This makes all MVP `def` declarations transparent. Normalization of an
alias chain recursively unfolds every reachable transparent definition.

The normalizer carries an immutable set of names currently being unfolded. If
a name is encountered twice on the same unfolding path, it stops unfolding that
reference and leaves `Constant(name)` in the result. Ordered module checking
prevents source-level cycles, while this guard makes the kernel API total even
for a manually constructed cyclic environment.

A missing constant also remains `Constant(name)` during raw normalization;
lookup failure is reported by inference/checking. Normalization itself remains
a total operation returning a `Term`.

Definitional equality uses environment-aware normalization, so a constant is
definitionally equal to its transparent body. Beta and zeta behavior remains
unchanged.

## CLI Data Flow

`check`, `eval`, and `eval --trace` parse once and run `ModuleChecker` once.

### `check`

Print one outcome per declaration in source order:

```text
id: OK
alias: OK
```

Rejected declarations print `FAIL` followed by their diagnostics. A failure
sets the command's failure status using the existing CLI policy, but does not
prevent later declarations from being analyzed.

Unchecked declarations retain the existing non-failing skip policy and print a
message that explicitly says they were not registered. They are not printed as
`OK`.

### `eval`

When the target names a declaration:

- an accepted declaration normalizes in the final module environment;
- a rejected declaration prints its recorded failure and is not evaluated;
- an `Unchecked` declaration may still be normalized as an unchecked selected
  term, preserving existing behavior.

When the target is an inline expression, parse it, resolve free names as
constants, and use the final module environment for preflight, inference, and
normalization. An undefined constant is an evaluation error; `CannotInferLambda`
alone still permits explicitly labelled unchecked normalization. Thus inline
expressions can call accepted module declarations without silently evaluating
unknown globals.

### `eval --trace`

Keep the existing field order. `core` displays global names normally, while
`normalized` reveals delta unfolding. For rejected declarations, print the
recorded module/kernel failure and omit normalization. Unchecked normalization
retains the existing `(unchecked)` label.

Existing annotated closed modules and existing concise evaluation examples keep
their current output. `check` output for an inferable unannotated declaration
intentionally changes from `skipped` to an accepted/inferred result.

## Error Handling

Add structured errors for at least:

- `KernelError.UndefinedConstant(name)`;
- duplicate source declaration names;
- an unannotated declaration whose type cannot be inferred;
- unresolved holes blocking environment registration.

Forward and self references use `UndefinedConstant`; they are not separate
parser errors. Duplicate detection happens before the duplicate body is checked,
so diagnostics are deterministic. Failed environment insertion never mutates
or partially updates module state.

## Tests

### Core and kernel

- infer a known constant's stored type;
- reject an unknown constant;
- reject an unknown constant nested below a lambda or constructor;
- normalize a transparent constant and a multi-hop alias chain;
- compare a constant definitionally equal to its body;
- terminate defensively on a cyclic manually constructed environment;
- preserve constants through substitution and shifting.

### Resolver and module checker

- resolve a free identifier to `Constant`;
- preserve locals under `Pi`, `Lambda`, and `Let` binders;
- prefer a local binder over a same-named global;
- accept a backward reference and register its declaration;
- infer and register an unannotated reference when possible;
- reject forward and direct self references;
- reject duplicates without replacing the first declaration;
- keep failed and unresolved-hole declarations out of the environment;
- continue checking independent declarations after a failure.

### CLI integration

- `check` accepts a module with an alias;
- `eval` delta-normalizes an alias;
- inline `eval` resolves a module constant;
- `eval --trace` shows the constant core and unfolded normal form;
- existing concise evaluation output remains unchanged;
- the complete suite and documented example command pass.

Add `examples/Constants.tes` as the runnable documentation fixture.

## Documentation and Status

Update README, `docs/KERNEL.md`, `docs/ELABORATION.md`,
`IMPLEMENTATION_STATUS.md`, and ADR 16. ADR 16 becomes accepted for the MVP
decision that every registered `def` is transparent; opacity remains a later
feature and must not be claimed as implemented.

The implementation status should claim only ordered same-file constants and
transparent delta reduction, not general modules, imports, recursion, or
opacity.
