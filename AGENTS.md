# Tessera AGENTS

This repository follows the following invariants while implementing `tessera_complete.md`:

1. Kernel validation must not depend on parser/elaboration/meta/search/control.
2. No unresolved metavariable is passed into kernel type checking.
3. Public meta APIs must not expose mutable global proof-state.
4. Public meta APIs must not be `List[Goal] => List[Goal]`.
5. New `Synth` combinators must include type tests and failure-display tests.
6. Any bug involving binder/substitution/unification gets regression coverage.
7. Do not add automation that cannot show generated ordinary terms.
8. Implementation status must avoid overclaiming.
9. `for`/`do` are always desugared to explicit combinators.
10. Do not implement a dedicated mutable goal-stack interpreter for `do`.
11. `param` is lexically scoped.
12. `param` does not consume a mutable goal queue.
13. Continuations do not escape `derive`/`synth` delimiters.
14. Raw `call/cc` requires an ADR before public introduction.
15. Source-map fidelity is preserved through desugaring paths.
16. Independent slots should be applicative where possible.
17. Continuation/state replay must avoid mutable sharing.
18. Search replay tests are required for multi-shot style behavior if supported.
19. A feature is not done unless kernel checks for the generated term pass.
20. Architecture checks must be encoded in tests/build where practical.
