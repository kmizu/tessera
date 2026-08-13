# N-arity Tuple Surface Syntax

## Goal

Extend Tessera's tuple surface syntax from binary `Pair` sugar to flat tuples with
between 2 and 10 elements. The syntax remains elaboration-free parser sugar: it
produces ordinary constructor terms before kernel checking.

## Surface and core mapping

For every supported arity `N`, the parser maps:

```text
(e1, e2, ..., eN)
```

to:

```text
Constructor("TupleN", List(e1, e2, ..., eN))
```

Examples:

```text
(A, B)       -> Tuple2(A, B)
(A, B, C)    -> Tuple3(A, B, C)
(A, (B, C))  -> Tuple2(A, Tuple2(B, C))
```

Explicit `(Ctor Pair ...)` terms remain valid and unchanged. Tuple sugar no longer
generates `Pair`.

## Parsing

After parsing the first term inside parentheses, the parser distinguishes an
ordinary parenthesized term from a tuple by the next significant token:

- `)` finishes an ordinary parenthesized term.
- `,` starts a comma-separated tuple element sequence.

Tuple elements use the existing term parser, so variables, sorts, constructor
forms, applications, holes, and nested tuples are all accepted. The parser
collects elements in source order and chooses the constructor name from the final
arity.

Supported arities are 2 through 10 inclusive. The following are errors:

- a trailing comma, such as `(A,)`;
- an eleventh element;
- a missing closing parenthesis;
- malformed elements between commas.

Parenthesized single terms such as `(A)` retain their current meaning and are not
represented as `Tuple1`.

## Boundaries

This change does not add a dedicated tuple node to `Term`, tuple-specific kernel
rules, projections, tuple type declarations, or runtime representation. `Tuple2`
through `Tuple10` are ordinary constructor names in the current core model. A
future data declaration feature may provide their full types without changing
this surface desugaring.

## Diagnostics

When tuple arity exceeds 10, parsing fails with a message that states the maximum
supported arity. A trailing comma fails at the missing element rather than being
silently accepted.

## Tests

Parser tests cover:

- two elements desugaring to `Tuple2`;
- three elements desugaring to `Tuple3`;
- ten elements desugaring to `Tuple10`;
- nested tuples retaining their own arities;
- arbitrary compound terms as elements;
- rejection of a trailing comma;
- rejection of eleven elements.

Elaborator and CLI tests verify that `synth do` yields carry the generated
`TupleN` constructor unchanged into displayed core terms. Existing explicit
`Pair` tests remain valid where they exercise explicit constructor APIs.
