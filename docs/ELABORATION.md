# Elaboration

The current MVP elaboration handles:

- declaration parse into `ParsedDeclaration`
- straightforward parsed core term lifting (`ParsedCoreTerm`)
- basic `synth do`/`for` block desugaring to nested lambdas via `ParsedSynthDo`
- binder-aware name resolution: bound `Var` nodes remain local while free names
  become `Constant` nodes
- ordered `ModuleChecker` orchestration that checks each declaration against only
  earlier accepted declarations and registers only accepted results
- explicit `Synth` combinator tests in Scala (`tessera.meta`), used with `derive` and kernel
  recheck on declaration-level examples

There is no full bidirectional elaborator for a rich surface language yet.
The MVP focus remains:

1. stable parse / check pipeline
2. generated ordinary core terms from sugar
3. kernel recheck before acceptance
4. `derive`-based validation for explicit meta synthesis values

Current `synth` blocks also parse `let` and `<-` statements into ordinary `Let`
terms. Future phases add typed metas, `guard`, richer body expressions, and explicit
meta-level elaboration.
