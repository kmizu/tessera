package tessera.parser

import munit.FunSuite
import tessera.core.Term
import Term.*
import SimpleSyntaxParser.ParsedCoreTerm
import SimpleSyntaxParser.ParsedSynthDo
import SimpleSyntaxParser.ParsedParam
import SimpleSyntaxParser.ParsedLet
import SimpleSyntaxParser.ParsedBind
import java.nio.file.{Files, Paths}

class ParserTest extends FunSuite:
  test("parser reads a declaration module and terms") {
    val source =
      """
      def id : (Pi A (Sort 0) (Pi x (Var A) (Var x))) = (Lam A (Sort 0) (Lam x (Var A) (Var x)))
      """

    val parsed = SimpleSyntaxParser.parseModule(source)

    assert(parsed.isRight, parsed.fold(_.toString, _ => ""))
    val Right(decls) = parsed
    assertEquals(decls.size, 1)
    val decl = decls.head
    assertEquals(decl.name, "id")
    assertEquals(decl.expectedType.nonEmpty, true)
  }

  test("parser supports constructor and app forms") {
    val source =
      """
      def pair : (Sort 0) = (Ctor Pair (Unit) (Sort 0))
      """

    val parsed = SimpleSyntaxParser.parseModule(source)

    assert(parsed.isRight, parsed.fold(_.toString, _ => ""))
    val Right(decls) = parsed
    val decl = decls.head
    assertEquals(decl.name, "pair")
  }

  test("parser supports synth-do with param/yield") {
    val source =
      """
      def andIntro : (Sort 0) = synth do {
        param A : (Sort 0)
        param B : (Sort 0)
        yield (A, B)
      }
      """

    val parsed = SimpleSyntaxParser.parseModule(source)

    assert(parsed.isRight, parsed.fold(_.toString, _ => ""))
    val Right(decls) = parsed
    assertEquals(decls.size, 1)
    val decl = decls.head
    assertEquals(decl.name, "andIntro")
    decl.value match
      case ParsedSynthDo(statements, yieldTerm) =>
        assertEquals(statements.size, 2)
        assertEquals(statements.head, ParsedParam("A", Sort(0)))
        assertEquals(statements(1), ParsedParam("B", Sort(0)))
        assertEquals(yieldTerm, Constructor("Tuple2", List(Var("A"), Var("B"))))
      case _ =>
        fail("expected synth do declaration")
  }

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

  test("parser accepts arbitrary terms inside tuple syntax") {
    val Right(term) = SimpleSyntaxParser.parseTermFromSource(
      "((Ctor F (Var x)), (App (Var f) (Var x)))"
    )
    assertEquals(
      term,
      Constructor(
        "Tuple2",
        List(
          Constructor("F", List(Var("x"))),
          App(Var("f"), Var("x"))
        )
      )
    )
  }

  test("parser supports nested tuple syntax") {
    val Right(term) = SimpleSyntaxParser.parseTermFromSource("((A, B), C)")
    assertEquals(
      term,
      Constructor(
        "Tuple2",
        List(Constructor("Tuple2", List(Var("A"), Var("B"))), Var("C"))
      )
    )
  }

  test("parser rejects a trailing tuple comma") {
    val result = SimpleSyntaxParser.parseTermFromSource("(A,)")
    assert(result.isLeft)
    assert(result.fold(_.message.contains("expected tuple element after ','"), _ => false))
  }

  test("parser rejects tuple arity greater than ten") {
    val result = SimpleSyntaxParser.parseTermFromSource(
      "(x1, x2, x3, x4, x5, x6, x7, x8, x9, x10, x11)"
    )
    assert(result.isLeft)
    assert(result.fold(_.message.contains("maximum 10"), _ => false))
  }

  test("parser supports for-do as synth-do alias") {
    val source =
      """
      def forIntro : (Sort 0) = for {
        param A : (Sort 0)
        yield (Ctor Pair (Var A))
      }
      """

    val parsed = SimpleSyntaxParser.parseModule(source)

    assert(parsed.isRight, parsed.fold(_.toString, _ => ""))
    val Right(decls) = parsed
    assertEquals(decls.size, 1)
    val decl = decls.head
    assertEquals(decl.name, "forIntro")
    decl.value match
      case ParsedSynthDo(statements, yieldTerm) =>
        assertEquals(statements.size, 1)
        assertEquals(statements.head, ParsedParam("A", Sort(0)))
        assertEquals(yieldTerm, Constructor("Pair", List(Var("A"))))
      case _ =>
        fail("expected for-do declaration")
  }

  test("parser supports let and bind statements") {
    val source =
      """
      def andIntro : (Sort 0) = synth do {
        let tmp = (Sort 0)
        x <- (Sort 0)
        yield (Ctor Pair (Var tmp) (Var x))
      }
      """

    val parsed = SimpleSyntaxParser.parseModule(source)

    assert(parsed.isRight, parsed.fold(_.toString, _ => ""))
    val Right(decls) = parsed
    assertEquals(decls.size, 1)
    val decl = decls.head
    assertEquals(decl.name, "andIntro")
    decl.value match
      case ParsedSynthDo(statements, yieldTerm) =>
        assertEquals(statements.size, 2)
        assertEquals(statements.head, ParsedLet("tmp", Sort(0)))
        assertEquals(statements(1), ParsedBind("x", Sort(0)))
        assertEquals(yieldTerm, Constructor("Pair", List(Var("tmp"), Var("x"))))
      case _ =>
        fail("expected synth do declaration")
  }

  test("parser supports unnamed and named holes") {
    val source =
      """
      def h0 : (Sort 0) = _
      def h1 : (Sort 0) = ?goal : (Sort 0)
      """

    val parsed = SimpleSyntaxParser.parseModule(source)

    assert(parsed.isRight, parsed.fold(_.toString, _ => ""))
    val Right(decls) = parsed
    assertEquals(decls.size, 2)

    decls.head.value match
      case ParsedCoreTerm(term) =>
        assertEquals(term, Hole("_", None))
      case _ =>
        fail("expected core term declaration")

    decls(1).value match
      case ParsedCoreTerm(term) =>
        assertEquals(term, Hole("goal", Some(Sort(0))))
      case _ =>
        fail("expected core term declaration")
  }

  test("parseTermFromSource parses full term and rejects trailing tokens") {
    val Right(term) = SimpleSyntaxParser.parseTermFromSource("(App (Var f) (Var x))")
    assertEquals(term, App(Var("f"), Var("x")))

    val errorResult = SimpleSyntaxParser.parseTermFromSource("(App (Var f) (Var x)) extra")
    assert(errorResult.isLeft)
  }

  test("example files currently used in docs are parseable") {
    val exampleNames = Vector(
      "Identity.tes",
      "BadId.tes",
      "AndIntro.tes",
      "SynthesisDo.tes",
      "SynthesisFor.tes",
      "Functions.tes",
      "AndOr.tes",
      "Nat.tes",
      "Equality.tes",
      "Holes.tes",
      "SynthesisExplicit.tes",
      "SynthesisParam.tes",
      "SynthesisFailure.tes",
      "SynthesisBranching.tes"
    )

    exampleNames.foreach { name =>
      val source = Files.readString(Paths.get("examples", name))
      val parsed = SimpleSyntaxParser.parseModule(source)
      assert(parsed.isRight, parsed.fold(_.toString, _ => "" + "failed to parse " + name))
    }
  }
