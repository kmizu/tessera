package tessera.elab

import munit.FunSuite
import tessera.core.Term
import tessera.parser.SimpleSyntaxParser
import Term.*

class ElaboratorTest extends FunSuite:
  test("elaborator desugars synth do as nested lambdas") {
    val source =
      """
      def andIntro : (Sort 0) = synth do {
        param A : (Sort 0)
        param B : (Sort 0)
        yield (A, B)
      }
      """

    val Right(decls) = SimpleSyntaxParser.parseModule(source)
    val decl = decls.head
    val Right(elab) = Elaborator.elaborate(decl)

    assertEquals(
      elab.core,
      Lambda(
        "A",
        Sort(0),
        Lambda("B", Sort(0), Constructor("Tuple2", List(Var("A"), Var("B"))))
      )
    )
  }

  test("elaborator desugars let and bind statements as lets") {
    val source =
      """
      def andIntro : (Sort 0) = synth do {
        param A : (Sort 0)
        let tmp = (Sort 0)
        x <- (Sort 0)
        yield (Ctor Pair (Var A) (Var x))
      }
      """

    val Right(decls) = SimpleSyntaxParser.parseModule(source)
    val decl = decls.head
    val Right(elab) = Elaborator.elaborate(decl)

    assertEquals(
      elab.core,
      Lambda(
        "A",
        Sort(0),
        Let(
          "tmp",
          Sort(0),
          Sort(0),
          Let(
            "x",
            Sort(0),
            Sort(0),
            Constructor("Pair", List(Var("A"), Var("x")))
          )
        )
      )
    )
  }

  test("elaborator resolves declaration types and bodies") {
    val Right(declarations) = SimpleSyntaxParser.parseModule(
      "def alias : ExistingType = existingValue"
    )
    val Right(elaborated) = Elaborator.elaborate(declarations.head)

    assertEquals(elaborated.declaredType, Some(Constant("ExistingType")))
    assertEquals(elaborated.core, Constant("existingValue"))
  }
