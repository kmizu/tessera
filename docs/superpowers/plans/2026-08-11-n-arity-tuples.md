# N-arity Tuples Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Parse surface tuples with 2 through 10 elements and lower them to ordinary `Tuple2` through `Tuple10` constructor terms.

**Architecture:** Keep tuples entirely in the parser sugar layer. Extend the existing parenthesized-term finisher to collect comma-separated elements, enforce the arity limit, and construct `Term.Constructor(s"Tuple$arity", elements)`; elaboration and the kernel continue to consume ordinary terms unchanged.

**Tech Stack:** Scala 3.4.2, sbt 1.10.7, MUnit, existing `tessera.core.Term` AST and `SimpleSyntaxParser`.

## Global Constraints

- Supported tuple arities are 2 through 10 inclusive.
- `(A, B)` lowers to `Tuple2(A, B)`; tuple sugar never generates `Pair`.
- Explicit `(Ctor Pair ...)` remains valid and unchanged.
- Parenthesized single terms such as `(A)` remain single terms, not `Tuple1`.
- Tuple elements may be arbitrary existing terms, including nested tuples.
- A trailing comma and an eleventh element are parse errors.
- Do not add a tuple-specific core AST node or kernel rule.
- Preserve all pre-existing user changes in the dirty worktree.

---

### Task 1: Lower successful tuple arities to `TupleN`

**Files:**
- Modify: `src/test/scala/tessera/parser/ParserTest.scala`
- Modify: `src/test/scala/tessera/elab/ElaboratorTest.scala`
- Modify: `src/test/scala/tessera/cli/CommandsTest.scala`
- Modify: `src/main/scala/tessera/parser/SimpleSyntaxParser.scala`

**Interfaces:**
- Consumes: `SimpleSyntaxParser.parseTermFromSource(source: String): Either[ParseError, Term]`.
- Produces: parenthesized comma lists as `Constructor(s"Tuple$arity", elements.toList)` for arities 2 through 10.

- [ ] **Step 1: Change the binary tuple expectation and add 3- and 10-element tests**

Replace the current binary `Pair` assertion and add these cases in `ParserTest.scala`:

```scala
test("parser lowers tuple syntax to arity-specific constructors") {
  assertEquals(
    SimpleSyntaxParser.parseTermFromSource("(A, B)"),
    Right(Constructor("Tuple2", List(Var("A"), Var("B"))))
  )
  assertEquals(
    SimpleSyntaxParser.parseTermFromSource("(A, B, C)"),
    Right(Constructor("Tuple3", List(Var("A"), Var("B"), Var("C"))))
  )

  val ten = (1 to 10).toList.map(index => Var(s"x$index"))
  assertEquals(
    SimpleSyntaxParser.parseTermFromSource("(x1, x2, x3, x4, x5, x6, x7, x8, x9, x10)"),
    Right(Constructor("Tuple10", ten))
  )
}
```

Update the existing arbitrary-term and nested-tuple expectations from `Pair` to
`Tuple2` so those tests specify the new mapping.

In `ElaboratorTest.scala`, change the `(A, B)` expectation to:

```scala
Lambda(
  "A",
  Sort(0),
  Lambda("B", Sort(0), Constructor("Tuple2", List(Var("A"), Var("B"))))
)
```

In `CommandsTest.scala`, use tuple surface syntax in the fixture:

```scala
yield (A, B, A)
```

and require these output fragments:

```scala
assert(showSynthOutput.contains("yield Tuple3(A, B, A)"))
assert(showDesugaredOutput.contains("Tuple3(A, B, A)"))
assert(showTraceOutput.contains("yield Tuple3(A, B, A)"))
```

- [ ] **Step 2: Run the parser suite and verify RED**

Run:

```bash
runtime_dir=$(mktemp -d /tmp/tessera-runtime.XXXXXX)
XDG_RUNTIME_DIR="$runtime_dir" sbt \
  'testOnly tessera.parser.ParserTest tessera.elab.ElaboratorTest tessera.cli.CommandsTest'
```

Expected: FAIL because binary tuples still produce `Pair`, while the third comma
is currently rejected after parsing the second element.

- [ ] **Step 3: Replace binary finishing with comma-list collection**

In `SimpleSyntaxParser.scala`, keep ordinary parenthesized terms in
`finishParenthesized`, but delegate comma parsing to a new helper:

```scala
private def finishParenthesized(cursor: Cursor, first: Term): Either[ParseError, Term] =
  skipWhitespaceLike(cursor)
  cursor.peekOption match
    case Some(Comma) =>
      cursor.next()
      parseTupleTail(cursor, Vector(first))
    case Some(RParen) =>
      cursor.next()
      Right(first)
    case Some(tok) =>
      Left(UnexpectedToken(s"expected ',' or ')' after term, found $tok", cursor.pos))
    case None =>
      Left(UnexpectedEof(cursor.pos))

private def parseTupleTail(
  cursor: Cursor,
  elements: Vector[Term]
): Either[ParseError, Term] =
  skipWhitespaceLike(cursor)
  cursor.peekOption match
    case Some(RParen) =>
      Left(UnexpectedToken("unexpected ')' while parsing tuple element", cursor.pos))
    case None =>
      Left(UnexpectedEof(cursor.pos))
    case _ =>
      parseTerm(cursor).flatMap { element =>
        val collected = elements :+ element
        skipWhitespaceLike(cursor)
        cursor.peekOption match
          case Some(Comma) =>
            cursor.next()
            parseTupleTail(cursor, collected)
          case Some(RParen) =>
            cursor.next()
            Right(Constructor(s"Tuple${collected.size}", collected.toList))
          case Some(tok) =>
            Left(UnexpectedToken(s"expected ',' or ')' after tuple element, found $tok", cursor.pos))
          case None =>
            Left(UnexpectedEof(cursor.pos))
      }
```

- [ ] **Step 4: Run the parser suite and verify GREEN**

Run:

```bash
runtime_dir=$(mktemp -d /tmp/tessera-runtime.XXXXXX)
XDG_RUNTIME_DIR="$runtime_dir" sbt \
  'testOnly tessera.parser.ParserTest tessera.elab.ElaboratorTest tessera.cli.CommandsTest'
```

Expected: all `ParserTest` tests pass, including `Tuple2`, `Tuple3`, `Tuple10`,
nested tuples, and compound tuple elements.

- [ ] **Step 5: Commit the successful arity implementation**

```bash
git add src/main/scala/tessera/parser/SimpleSyntaxParser.scala \
  src/test/scala/tessera/parser/ParserTest.scala \
  src/test/scala/tessera/elab/ElaboratorTest.scala \
  src/test/scala/tessera/cli/CommandsTest.scala
git commit -m "feat: add n-arity tuple syntax"
```

---

### Task 2: Reject malformed and oversized tuples

**Files:**
- Modify: `src/test/scala/tessera/parser/ParserTest.scala`
- Modify: `src/main/scala/tessera/parser/SimpleSyntaxParser.scala` only if Task 1's implementation does not already produce the specified diagnostics.

**Interfaces:**
- Consumes: `ParseError.message` from `parseTermFromSource` failures.
- Produces: stable diagnostic fragments for trailing commas and arity greater than 10.

- [ ] **Step 1: Add failure-display tests**

Add to `ParserTest.scala`:

```scala
test("parser rejects malformed and oversized tuples") {
  val trailingComma = SimpleSyntaxParser.parseTermFromSource("(A,)")
  assert(trailingComma.isLeft)
  assert(trailingComma.fold(_.message.contains("expected tuple element after ','"), _ => false))

  val oversized = SimpleSyntaxParser.parseTermFromSource(
    "(x1, x2, x3, x4, x5, x6, x7, x8, x9, x10, x11)"
  )
  assert(oversized.isLeft)
  assert(oversized.fold(_.message.contains("maximum 10"), _ => false))
}
```

- [ ] **Step 2: Run the parser suite and verify RED**

Run:

```bash
runtime_dir=$(mktemp -d /tmp/tessera-runtime.XXXXXX)
XDG_RUNTIME_DIR="$runtime_dir" sbt 'testOnly tessera.parser.ParserTest'
```

Expected: both new assertions fail because Task 1 reports a generic trailing-comma
error and accepts an eleven-element tuple.

- [ ] **Step 3: Implement the exact bounds diagnostics**

Add `private val MaxTupleArity = 10`. Ensure `parseTupleTail` checks for `RParen`
before parsing an element and checks `collected.size >= MaxTupleArity` before
consuming another comma:

```scala
private val MaxTupleArity = 10

// In parseTupleTail, before parseTerm(cursor):
case Some(RParen) =>
  Left(UnexpectedToken("expected tuple element after ','", cursor.pos))

// In the post-element cursor.peekOption match, before the general Comma case:
case Some(Comma) if collected.size >= MaxTupleArity =>
  Left(UnexpectedToken(s"tuple arity exceeds maximum $MaxTupleArity", cursor.pos))
```

- [ ] **Step 4: Run the parser suite and verify GREEN**

Run the same `testOnly` command. Expected: all parser tests pass and both error
messages contain the asserted fragments.

- [ ] **Step 5: Commit tuple bounds coverage**

```bash
git add src/main/scala/tessera/parser/SimpleSyntaxParser.scala \
  src/test/scala/tessera/parser/ParserTest.scala
git commit -m "test: cover tuple arity bounds"
```

---

### Task 3: Align examples and documentation

**Files:**
- Modify: `examples/AndIntro.tes`
- Modify: `examples/SynthesisDo.tes`
- Modify: `examples/SynthesisFor.tes`
- Modify: `examples/SynthesisParam.tes`
- Modify: `README.md`

**Interfaces:**
- Consumes: parser-produced `Constructor("TupleN", fields)` terms.
- Produces: checked examples and documentation that visibly describe `TupleN`.

- [ ] **Step 1: Update examples and README wording**

Keep the existing tuple surface examples, and replace the README tuple bullet with:

```markdown
- tuple sugar `(e1, ..., eN)` for 2–10 elements, which lowers to the ordinary constructor `TupleN(e1, ..., eN)`; explicit `Pair` constructors remain available
```

The four synthesis examples continue to use `yield (A, B)` and their valid `Pi`
annotations already present in the worktree.

- [ ] **Step 2: Run example checks**

Run:

```bash
runtime_dir=$(mktemp -d /tmp/tessera-runtime.XXXXXX)
XDG_RUNTIME_DIR="$runtime_dir" sbt \
  'runMain tessera.Main check examples/AndIntro.tes' \
  'runMain tessera.Main check examples/SynthesisDo.tes' \
  'runMain tessera.Main check examples/SynthesisFor.tes' \
  'runMain tessera.Main check examples/SynthesisParam.tes'
```

Expected: all targeted checks pass; every command prints `<declaration>: OK`.

- [ ] **Step 3: Commit downstream alignment**

```bash
git add README.md examples/AndIntro.tes examples/SynthesisDo.tes \
  examples/SynthesisFor.tes examples/SynthesisParam.tes
git commit -m "docs: demonstrate n-arity tuples"
```

---

### Task 4: Full verification

**Files:**
- Verify only; no planned source changes.

**Interfaces:**
- Consumes: the complete repository after Tasks 1 through 3.
- Produces: evidence that the parser, elaborator, CLI, kernel, meta layer, examples, and architecture constraints still agree.

- [ ] **Step 1: Run the complete test suite**

```bash
runtime_dir=$(mktemp -d /tmp/tessera-runtime.XXXXXX)
XDG_RUNTIME_DIR="$runtime_dir" sbt test
```

Expected: all suites pass with zero failed tests.

- [ ] **Step 2: Inspect formatting and the final diff**

```bash
git diff --check
git status --short
git log --oneline -5
```

Expected: `git diff --check` is silent; status contains no accidental generated
files; recent commits contain the tuple design and implementation commits.
