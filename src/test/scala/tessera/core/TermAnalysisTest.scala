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

  test("term analysis traverses Pi and Lambda binders") {
    val term = Pi(
      "a",
      Hole("domain", None),
      Lambda("b", Constant("ParamType"), App(Var("b"), Hole("body", Some(Constant("Wanted")))))
    )

    assertEquals(TermAnalysis.collectConstants(term), Vector("ParamType", "Wanted"))
    assertEquals(
      TermAnalysis.collectHoles(term),
      Vector(Hole("domain", None), Hole("body", Some(Constant("Wanted"))))
    )
  }

  test("freeVars respects binder scope") {
    assertEquals(
      TermAnalysis.freeVars(Lambda("x", Var("t"), App(Var("x"), Var("y")))),
      Set("t", "y")
    )
    assertEquals(TermAnalysis.freeVars(Pi("a", Sort(0), Var("a"))), Set.empty[String])
    assertEquals(
      TermAnalysis.freeVars(Let("a", Var("q"), Var("r"), Var("a"))),
      Set("q", "r")
    )
  }

  test("variableNames includes binders and free variables") {
    assertEquals(
      TermAnalysis.variableNames(Lambda("x", Var("t"), Var("y"))),
      Set("x", "t", "y")
    )
  }
