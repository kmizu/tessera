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
        yield (Ctor Pair (Var A) (Var B))
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
        assertEquals(yieldTerm, Constructor("Pair", List(Var("A"), Var("B"))))
      case _ =>
        fail("expected synth do declaration")
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
