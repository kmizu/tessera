# Core calculus

Tessera core uses named-term constructors for now (MVP) and keeps a clear path to
de Bruijn in later phases.

Current core requirements:

- sorts and universes
- dependent-like function types using named variables
- lambda/application
- let
- constructors for base values

Name representation is deliberately split:

- `Term.Var` is a lexically scoped local name;
- `Term.Constant` is a global declaration reference resolved through the kernel
  environment;
- `Term.DBVar` remains available for de Bruijn-oriented kernel operations.
