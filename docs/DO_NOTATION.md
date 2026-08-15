# `for` / `do` / `param` notation

## Status

Implemented in MVP parser + display path:

- `synth do { ... }`
- `for { ... }` (parser alias for `synth do`)
- `param`, `let`, and `name <- expression` statements inside `synth`/`for`
- mandatory final `yield`

`trace-synth` now prints a statement-level trace and the desugared core form.

## Grammar (MVP)

```
Value ::= "synth" "do" "{" DoStmt* YieldStmt "}"
        | "for" "{" DoStmt* YieldStmt "}"

DoStmt   ::= "param" Identifier ":" TypeExpr
           | "let" Identifier "=" Term
           | Identifier "<-" Term
YieldStmt ::= "yield" Term
```

`param` compiles to nested `lambda` terms during elaboration; `let` and `<-`
both lower to ordinary `Let` terms.

Note:

- `let`/`<-` values are currently elaborated with a hardcoded `Sort 0` value
  type, so only `Sort(0)`-typed values (constructors, registered types) check;
  richer bind semantics remain a later phase.

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
- statements are limited to `param`, `let`, and `<-` binds for now.
- `let`/`<-` value types are fixed to `Sort 0` in the MVP elaborator.
- no mutable proof-state interpreter is used in MVP.
