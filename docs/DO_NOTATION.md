# `for` / `do` / `param` notation

## Status

Implemented in MVP parser + display path:

- `synth do { ... }`
- `for { ... }` (parser alias for `synth do`)
- `param` declarations inside `synth`/`for`
- mandatory final `yield`

`trace-synth` now prints a statement-level trace and the desugared core form.

## Grammar (MVP)

```
Value ::= "synth" "do" "{" DoStmt* YieldStmt "}"
        | "for" "{" DoStmt* YieldStmt "}"

DoStmt   ::= "param" Identifier ":" TypeExpr
YieldStmt ::= "yield" Term
```

`param` form is currently required for lambda-style synthesis blocks and compiles to
nested `lambda` terms during elaboration.

Note:

- `let` and `<-`-style monadic binds are part of the planned do-notation surface,
  but this MVP keeps them out to keep elaboration focused on explicit lambda
  lowering and inspectable output.

## Desugaring intuition

- `param A : T` introduces a binder in lexical scope.
- `yield e` selects the final ordinary term.
- the block becomes nested lambdas:

```
synth do {
  param A : T
  param B : U
  yield e
}
```

becomes

```
(Lam A T (Lam B U e))
```

## Restrictions

- final `yield` is required in `synth` / `for` blocks.
- statement bodies are limited to `param` and `yield` for now.
- no mutable proof-state interpreter is used in MVP.
