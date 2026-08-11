package tessera.cli

import munit.FunSuite
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.io.{ByteArrayOutputStream, PrintStream}

class CommandsTest extends FunSuite:
  private def withCapturedOut(body: => Unit): String =
    val buffer = ByteArrayOutputStream()
    val stream = PrintStream(buffer, true, StandardCharsets.UTF_8)
    Console.withOut(stream) {
      body
      stream.flush()
    }
    buffer.toString(StandardCharsets.UTF_8)

  private def writeExample(source: String): Path =
    val path = Files.createTempFile("tessera-cli-example", ".tes")
    Files.writeString(path, source, StandardCharsets.UTF_8)
    path

  test("show-synth prints parsed do form and show-desugared prints lambda core") {
    val path = writeExample(
      """def andIntro : (Sort 0) = synth do {
        |  param A : (Sort 0)
        |  param B : (Sort 0)
        |  yield (A, B, A)
        |}""".stripMargin
    )

    val showSynthOutput = withCapturedOut {
      TesseraCli.main(Array("show-synth", path.toString, "andIntro"))
    }
    assert(showSynthOutput.contains("synth do {"))
    assert(showSynthOutput.contains("param A : Type[0]"))
    assert(showSynthOutput.contains("yield Tuple3(A, B, A)"))

    val showDesugaredOutput = withCapturedOut {
      TesseraCli.main(Array("show-desugared", path.toString, "andIntro"))
    }
    assert(showDesugaredOutput.nonEmpty)
    assert(showDesugaredOutput.contains("(lambda A: Type[0] =>"))
    assert(showDesugaredOutput.contains("Tuple3(A, B, A)"))

    val showTraceOutput = withCapturedOut {
      TesseraCli.main(Array("trace-synth", path.toString, "andIntro"))
    }
    assert(showTraceOutput.contains("trace-synth andIntro:"))
    assert(showTraceOutput.contains("param A : Type[0]"))
    assert(showTraceOutput.contains("param B : Type[0]"))
    assert(showTraceOutput.contains("yield Tuple3(A, B, A)"))
    assert(showTraceOutput.contains("desugared:"))
    assert(showTraceOutput.contains("(lambda A: Type[0] =>"))
  }

  test("holes command lists unresolved holes") {
    val path = writeExample(
      """def withHole : (Sort 0) = ?goal : (Sort 0)
        |def plain : (Sort 0) = (Sort 0)""".stripMargin
    )

    val allOutput = withCapturedOut {
      TesseraCli.main(Array("holes", path.toString))
    }
    assert(allOutput.contains("withHole:"))
    assert(allOutput.contains("?goal : Type[0]"))

    val declOutput = withCapturedOut {
      TesseraCli.main(Array("holes", path.toString, "withHole"))
    }.trim
    assertEquals(declOutput, "?goal : Type[0]")
  }

  test("eval command evaluates declarations and inline terms") {
    val path = writeExample(
      """def id = (Lam x (Sort 0) (Var x))
        |""".stripMargin
    )

    val evalDeclOutput = withCapturedOut {
      TesseraCli.main(Array("eval", path.toString, "id"))
    }
    assertEquals(evalDeclOutput, "(lambda x: Type[0] => x)\n")

    val evalExprOutput = withCapturedOut {
      TesseraCli.main(
        Array("eval", path.toString, "(App (Lam x (Sort 0) (Var x)) (Sort 0))")
      )
    }.trim
    assertEquals(evalExprOutput, "Type[0]")
  }

  test("eval trace shows checked declaration stages") {
    val path = writeExample(
      """def id : (Pi A (Sort 0) (Pi x (Var A) (Var A))) =
        |  (Lam A (Sort 0) (Lam x (Var A) (Var x)))""".stripMargin
    )

    val output = withCapturedOut {
      TesseraCli.main(Array("eval", "--trace", path.toString, "id"))
    }

    assertEquals(
      output,
      s"""eval trace:
         |  file: $path
         |  target: id
         |  kind: declaration
         |  parsed: (lambda A: Type[0] => (lambda x: A => x))
         |  core: (lambda A: Type[0] => (lambda x: A => x))
         |  expected: (A: Type[0]) -> (x: A) -> A
         |  kernel: OK
         |  normalized: (lambda A: Type[0] => (lambda x: A => x))
         |""".stripMargin
    )
  }

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

  test("eval trace keeps an unannotated declaration distinct from an inline expression") {
    val path = writeExample("def id = (Lam x (Sort 0) (Var x))")
    val output = withCapturedOut {
      TesseraCli.main(Array("eval", "--trace", path.toString, "id"))
    }

    assertEquals(
      output,
      s"""eval trace:
         |  file: $path
         |  target: id
         |  kind: declaration
         |  parsed: (lambda x: Type[0] => x)
         |  core: (lambda x: Type[0] => x)
         |  expected: <none>
         |  inferred: unavailable: cannot infer type of lambda parameter `x`
         |  kernel: not checked
         |  normalized (unchecked): (lambda x: Type[0] => x)
         |""".stripMargin
    )
  }

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

  test("eval trace prints dedicated usage when arguments are missing") {
    val missingFile = withCapturedOut {
      TesseraCli.main(Array("eval", "--trace"))
    }
    val missingTarget = withCapturedOut {
      TesseraCli.main(Array("eval", "--trace", "examples/Identity.tes"))
    }
    val expected = "usage: tessera eval --trace <file.tes> <decl or expression>\n"
    assertEquals(missingFile, expected)
    assertEquals(missingTarget, expected)
  }
