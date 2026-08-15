# Kernel

The kernel checks resolved terms independently of parser, elaboration, compiler
orchestration, and meta/search layers. Lexical variables remain local; global
references are looked up in an immutable `KernelEnvironment` supplied when a
`Kernel` is constructed.

- no parser dependence
- no elaborator dependence
- no continuation/search dependence

Current constant semantics:

- public `infer` and `check` preflight all nested `Term.Constant` references
  and reject any unresolved hole, so no metavariable reaches typing;
- missing constants are reported before syntax-directed typing, including inside
  lambdas, constructors, hole annotations, and expected types;
- `KernelEnvironment.define` only accepts declarations whose constants are
  already defined and which contain no holes, so environments are acyclic and
  metavariable-free by construction; the normalization unfolding set remains
  as a defensive guard;
- every registered MVP `def` is transparent and delta-reduced during normalization;
- missing constants remain unchanged under normalization, while inference/checking
  reject them.

Current equality and substitution semantics:

- named substitution is capture-avoiding: binders are renamed (`x$1`, `x$2`, …)
  when they would capture a free variable of the substituted term;
- definitional equality (`isDefEq`) normalizes both sides and compares them up
  to alpha-equivalence;
- variable checking compares the declared type against the expected type
  directly (not just their sorts);
- context binder names are kept unique: a shadowing binder is renamed before
  the context is extended, so stored context types cannot alias later binders;
- constructor fields must themselves be typable, so an ill-typed or diverging
  term cannot hide inside an opaque constructor;
- `isDefEq` and the public `checkSort` validate their inputs like `check` and
  `infer` do;
- `normalizeWithBudget` bounds total reduction work (steps and substitution
  nodes) for terms the kernel has not certified, reporting exhaustion instead
  of diverging or exhausting memory.

Open items:

- universe level constraints (ADR-01: any sort currently checks against any sort)
- richer conversion rules
- inductives and recursors
- opaque definitions and theorem-like reducibility controls
