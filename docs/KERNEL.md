# Kernel

The kernel checks resolved terms independently of parser, elaboration, compiler
orchestration, and meta/search layers. Lexical variables remain local; global
references are looked up in an immutable `KernelEnvironment` supplied when a
`Kernel` is constructed.

- no parser dependence
- no elaborator dependence
- no continuation/search dependence

Current constant semantics:

- public `infer` and `check` preflight all nested `Term.Constant` references;
- missing constants are reported before syntax-directed typing, including inside
  lambdas, constructors, hole annotations, and expected types;
- every registered MVP `def` is transparent and delta-reduced during normalization;
- normalization carries an unfolding set so a malformed cyclic environment still
  terminates;
- missing constants remain unchanged under normalization, while inference/checking
  reject them.

Open items:

- substitution/shifting correctness
- richer conversion rules
- inductives and recursors
- opaque definitions and theorem-like reducibility controls
