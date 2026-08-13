package tessera.core

import munit.FunSuite
import tessera.core.Term.*

final class TermAnalysisTest extends FunSuite:
  test("term analysis finds constants and holes through every nested position") {
    val term = Let(
      "x",
      Constant("ValueType"),
      Hole("value", Some(Constant("Expected"))),
      Constructor(
        "Pair",
        List(
          App(Constant("make"), Var("x")),
          Hole("result", None)
        )
      )
    )

    assertEquals(TermAnalysis.collectConstants(term), Vector("ValueType", "Expected", "make"))
    assertEquals(
      TermAnalysis.collectHoles(term),
      Vector(Hole("value", Some(Constant("Expected"))), Hole("result", None))
    )
  }
