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
    }.trim
    assert(evalDeclOutput.nonEmpty)
    assert(evalDeclOutput.contains("(lambda"))

    val evalExprOutput = withCapturedOut {
      TesseraCli.main(
        Array("eval", path.toString, "(App (Lam x (Sort 0) (Var x)) (Sort 0))")
      )
    }.trim
    assertEquals(evalExprOutput, "Type[0]")
  }
