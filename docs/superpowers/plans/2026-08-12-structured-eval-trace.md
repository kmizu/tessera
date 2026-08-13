# Structured Eval Trace Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add `tessera eval --trace` with deterministic stage-by-stage output while preserving existing `eval` output.

**Architecture:** Route the opt-in flag in `TesseraCli.main` before the generic command path. Keep trace assembly private to `TesseraCli`, reuse the existing parser, elaborator, kernel, normalizer, and printer, and print only stages reached before a failure.

**Tech Stack:** Scala 3.4.2, sbt 1.10.7, MUnit, existing Tessera parser/elaborator/kernel/CLI.

## Global Constraints

- The command form is exactly `tessera eval --trace <file.tes> <declaration-or-expression>`.
- Existing `tessera eval <file.tes> <declaration-or-expression>` output remains unchanged.
- `--trace` is accepted only immediately after `eval`.
- Typed declarations normalize only after a successful expected-type kernel check.
- Inline expressions and unannotated declarations label normalization as `unchecked`.
- Failed stages omit every later stage.
- The parser, elaborator, core AST, kernel, and normalizer interfaces do not change.
- Output is human-facing deterministic text; do not add JSON, colors, or a persistent trace model.

---

### Task 1: Specify every trace path in CLI tests

**Files:**
- Modify: `src/test/scala/tessera/cli/CommandsTest.scala`

**Interfaces:**
- Consumes: `TesseraCli.main(args: Array[String]): Unit` and captured standard output.
- Produces: regression requirements for compatibility, success, inference, rejection, parse failure, and usage.

- [ ] **Step 1: Strengthen the existing concise eval assertion**

Replace the current non-empty declaration assertion with exact output, while
retaining the existing inline expression assertion:

```scala
val evalDeclOutput = withCapturedOut {
  TesseraCli.main(Array("eval", path.toString, "id"))
}
assertEquals(evalDeclOutput, "(lambda x: Type[0] => x)\n")
```

- [ ] **Step 2: Add a checked declaration trace test**

```scala
test("eval trace shows checked declaration stages") {
  val path = writeExample(
    """def id : (Pi A (Sort 0) (Pi x (Var A) (Var A))) =
      |  (Lam A (Sort 0) (Lam x (Var A) (Var x)))""".stripMargin
  )

  val output = withCapturedOut {
    TesseraCli.main(Array("eval", "--trace", path.toString, "id"))
  }

  assert(output.contains("eval trace:\n"))
  assert(output.contains(s"  file: $path\n"))
  assert(output.contains("  target: id\n"))
  assert(output.contains("  kind: declaration\n"))
  assert(output.contains("  parsed: (lambda A: Type[0] =>"))
  assert(output.contains("  core: (lambda A: Type[0] =>"))
  assert(output.contains("  expected: (A: Type[0]) -> (x: A) -> A\n"))
  assert(output.contains("  kernel: OK\n"))
  assert(output.contains("  normalized: (lambda A: Type[0] =>"))
}
```

- [ ] **Step 3: Add inline inference and inference-unavailable tests**

```scala
test("eval trace labels inline normalization as unchecked") {
  val path = writeExample("")
  val output = withCapturedOut {
    TesseraCli.main(Array("eval", "--trace", path.toString, "(Sort 0)"))
  }

  assert(output.contains("  kind: expression\n"))
  assert(output.contains("  parsed: Type[0]\n"))
  assert(output.contains("  core: Type[0]\n"))
  assert(output.contains("  expected: <none>\n"))
  assert(output.contains("  inferred: Type[1]\n"))
  assert(output.contains("  kernel: inference only\n"))
  assert(output.contains("  normalized (unchecked): Type[0]\n"))
}

test("eval trace reports unavailable inline inference") {
  val path = writeExample("")
  val output = withCapturedOut {
    TesseraCli.main(Array("eval", "--trace", path.toString, "(Lam x (Sort 0) (Var x))"))
  }

  assert(output.contains("  inferred: unavailable: cannot infer type of lambda parameter `x`\n"))
  assert(output.contains("  kernel: not checked\n"))
  assert(output.contains("  normalized (unchecked): (lambda x: Type[0] => x)\n"))
}
```

- [ ] **Step 4: Add kernel and parse failure tests**

```scala
test("eval trace stops after kernel rejection") {
  val path = writeExample(
    "def bad : (Sort 0) = (Lam x (Sort 0) (Var x))"
  )
  val output = withCapturedOut {
    TesseraCli.main(Array("eval", "--trace", path.toString, "bad"))
  }

  assert(output.contains("  kernel: FAIL\n"))
  assert(output.contains("    expected a function type, found: Type[0]\n"))
  assert(!output.contains("normalized"))
}

test("eval trace reports module and expression parse failures") {
  val brokenModule = writeExample("def broken :")
  val moduleOutput = withCapturedOut {
    TesseraCli.main(Array("eval", "--trace", brokenModule.toString, "broken"))
  }
  assert(moduleOutput.contains("  module parse: FAIL:"))
  assert(!moduleOutput.contains("  kind:"))

  val emptyModule = writeExample("")
  val expressionOutput = withCapturedOut {
    TesseraCli.main(Array("eval", "--trace", emptyModule.toString, "(App"))
  }
  assert(expressionOutput.contains("  kind: expression\n"))
  assert(expressionOutput.contains("  expression parse: FAIL:"))
  assert(!expressionOutput.contains("  core:"))
}
```

- [ ] **Step 5: Add missing-argument usage coverage**

```scala
test("eval trace prints dedicated usage when arguments are missing") {
  val output = withCapturedOut {
    TesseraCli.main(Array("eval", "--trace"))
  }
  assertEquals(output, "usage: tessera eval --trace <file.tes> <decl or expression>\n")
}
```

- [ ] **Step 6: Run CLI tests and verify RED**

```bash
runtime_dir=$(mktemp -d /tmp/tessera-runtime.XXXXXX)
XDG_RUNTIME_DIR="$runtime_dir" sbt 'testOnly tessera.cli.CommandsTest'
```

Expected: existing concise eval remains green; every trace test fails because
`--trace` is currently treated as the input file or has no dedicated routing.

---

### Task 2: Implement structured trace routing and rendering

**Files:**
- Modify: `src/main/scala/tessera/cli/Commands.scala`
- Test: `src/test/scala/tessera/cli/CommandsTest.scala`

**Interfaces:**
- Consumes: `parseModule`, `SimpleSyntaxParser.parseTermFromSource`, `Elaborator.elaborate`, `Kernel.check`, `Kernel.infer`, `Kernel.normalize`, and `Printer.render`.
- Produces: `runEvalTrace(file: String, target: String): Unit` and deterministic trace text.

- [ ] **Step 1: Route the trace form before generic commands**

Add these cases before `case cmd :: rest if supported.contains(cmd)`:

```scala
case "eval" :: "--trace" :: file :: targetParts if targetParts.nonEmpty =>
  runEvalTrace(file, targetParts.mkString(" "))
case "eval" :: "--trace" :: _ =>
  println("usage: tessera eval --trace <file.tes> <decl or expression>")
```

Add this line to `printUsage()`:

```scala
println("  eval --trace <file.tes> <decl or expression>")
```

- [ ] **Step 2: Add shared trace helpers**

Add private helpers to `TesseraCli`:

```scala
private def evalTraceHeader(file: String, target: String): Vector[String] =
  Vector("eval trace:", s"  file: $file", s"  target: $target")

private def printEvalTrace(lines: Vector[String]): Unit =
  println(lines.mkString("\n"))

private def singleLine(value: String): String =
  value.linesIterator.mkString(" ")

private def uncheckedEvaluation(kernel: Kernel, term: Term): Vector[String] =
  val inference = kernel.infer(term) match
    case Right(inferred) =>
      Vector(s"  inferred: ${renderTerm(inferred)}", "  kernel: inference only")
    case Left(error) =>
      Vector(s"  inferred: unavailable: ${error.message}", "  kernel: not checked")
  inference :+ s"  normalized (unchecked): ${renderTerm(kernel.normalize(term))}"
```

- [ ] **Step 3: Implement declaration and expression traces**

```scala
private def runEvalTrace(file: String, target: String): Unit =
  val header = evalTraceHeader(file, target)
  parseModule(file) match
    case Left(error) =>
      printEvalTrace(header :+ s"  module parse: FAIL: $error")
    case Right(declarations) =>
      declarations.find(_.name == target) match
        case Some(declaration) =>
          val parsed = singleLine(renderParsedSyntax(declaration.value))
          val prefix = header ++ Vector("  kind: declaration", s"  parsed: $parsed")
          Elaborator.elaborate(declaration) match
            case Left(message) =>
              printEvalTrace(prefix :+ s"  elaboration: FAIL: $message")
            case Right(elaborated) =>
              val core = elaborated.core
              val withCore = prefix :+ s"  core: ${renderTerm(core)}"
              elaborated.declaredType match
                case Some(expected) =>
                  val withExpected = withCore :+ s"  expected: ${renderTerm(expected)}"
                  val kernel = Kernel()
                  val report = kernel.check(core, expected)
                  if report.isOk then
                    printEvalTrace(
                      withExpected ++ Vector(
                        "  kernel: OK",
                        s"  normalized: ${renderTerm(kernel.normalize(core))}"
                      )
                    )
                  else
                    printEvalTrace(
                      withExpected ++ Vector("  kernel: FAIL") ++
                        report.errors.map(error => s"    ${error.message}")
                    )
                case None =>
                  val kernel = Kernel()
                  printEvalTrace(
                    withCore ++ Vector("  expected: <none>") ++ uncheckedEvaluation(kernel, core)
                  )
        case None =>
          SimpleSyntaxParser.parseTermFromSource(target) match
            case Left(error) =>
              printEvalTrace(
                header ++ Vector("  kind: expression", s"  expression parse: FAIL: $error")
              )
            case Right(term) =>
              val rendered = renderTerm(term)
              val kernel = Kernel()
              printEvalTrace(
                header ++ Vector(
                  "  kind: expression",
                  s"  parsed: $rendered",
                  s"  core: $rendered",
                  "  expected: <none>"
                ) ++ uncheckedEvaluation(kernel, term)
              )
```

- [ ] **Step 4: Run CLI tests and verify GREEN**

```bash
runtime_dir=$(mktemp -d /tmp/tessera-runtime.XXXXXX)
XDG_RUNTIME_DIR="$runtime_dir" sbt 'testOnly tessera.cli.CommandsTest'
```

Expected: every CLI test passes with zero failures.

- [ ] **Step 5: Commit the CLI implementation**

```bash
git add src/main/scala/tessera/cli/Commands.scala \
  src/test/scala/tessera/cli/CommandsTest.scala
git commit -m "feat: add structured eval trace"
```

---

### Task 3: Document the command and close the status item

**Files:**
- Modify: `README.md`
- Modify: `IMPLEMENTATION_STATUS.md`

**Interfaces:**
- Consumes: the implemented `eval --trace` command.
- Produces: discoverable usage and an accurate implementation-status claim.

- [ ] **Step 1: Add the runnable command to README**

Add beside the existing eval examples:

```markdown
sbt "runMain tessera.Main eval --trace examples/Identity.tes id"
```

Add to the implemented command list:

```markdown
- `sbt "runMain tessera.Main eval --trace <file.tes> <decl or expression>"`
```

- [ ] **Step 2: Mark structured eval trace implemented**

Change the status line to:

```markdown
- [x] structured `eval --trace` output for parse, elaboration, kernel, and normalization stages
```

- [ ] **Step 3: Run the documented command**

```bash
runtime_dir=$(mktemp -d /tmp/tessera-runtime.XXXXXX)
XDG_RUNTIME_DIR="$runtime_dir" sbt \
  'runMain tessera.Main eval --trace examples/Identity.tes id'
```

Expected: output contains `kind: declaration`, `kernel: OK`, and `normalized:`.

- [ ] **Step 4: Commit documentation and status**

```bash
git add README.md IMPLEMENTATION_STATUS.md
git commit -m "docs: document structured eval trace"
```

---

### Task 4: Full verification

**Files:**
- Verify only; no planned source changes.

**Interfaces:**
- Consumes: the completed CLI feature and repository.
- Produces: evidence for compatibility, structured failure behavior, and full-suite health.

- [ ] **Step 1: Run the complete test suite**

```bash
runtime_dir=$(mktemp -d /tmp/tessera-runtime.XXXXXX)
XDG_RUNTIME_DIR="$runtime_dir" sbt test
```

Expected: all suites pass with zero failed tests.

- [ ] **Step 2: Inspect formatting and repository state**

```bash
git diff --check
git status --short
git log --oneline -6
```

Expected: `git diff --check` is silent; the worktree is clean; recent commits
contain the design, implementation-plan, CLI, and documentation commits.
