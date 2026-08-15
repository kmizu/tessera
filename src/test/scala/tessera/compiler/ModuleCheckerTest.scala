package tessera.compiler

import munit.FunSuite
import tessera.core.Term.*
import tessera.parser.SimpleSyntaxParser

final class ModuleCheckerTest extends FunSuite:
  private def rightOrFail[L, R](result: Either[L, R]): R =
    result.fold(error => fail(s"expected Right, got Left($error)"), identity)

  private def check(source: String): CheckedModule =
    val declarations = rightOrFail(SimpleSyntaxParser.parseModule(source))
    ModuleChecker.check(declarations)

  private val identitySource =
    """def id : (Pi A (Sort 0) (Pi x (Var A) (Var A))) =
      |  (Lam A (Sort 0) (Lam x (Var A) (Var x)))
      |def alias : (Pi A (Sort 0) (Pi x (Var A) (Var A))) = id
      |def inferred = alias""".stripMargin

  test("module checker accepts backward references and inferred aliases") {
    val module = check(identitySource)

    assertEquals(module.outcomes.map(_.status), Vector.fill(3)(DeclarationStatus.Accepted))
    assertEquals(module.environment.names, Vector("id", "alias", "inferred"))
    assertEquals(module.environment.lookup("alias").map(_.value), Some(Constant("id")))
    assertEquals(
      module.environment.lookup("inferred").map(_.declaredType),
      module.environment.lookup("id").map(_.declaredType)
    )
  }

  test("unannotated lambda is unchecked and not registered") {
    val module = check("def localId = (Lam x (Sort 0) (Var x))")
    val outcome = module.outcomes.head

    assertEquals(outcome.status, DeclarationStatus.Unchecked)
    assertEquals(module.environment.lookup("localId"), None)
    assertEquals(
      outcome.diagnostics.map(_.message),
      Vector("cannot infer type of lambda parameter `x`")
    )
  }

  test("lambda inference failure below a non-lambda declaration is rejected") {
    // Deliberately strict: a CannotInferLambda from inside a non-lambda core
    // may mask a genuine type error, so only outermost lambdas are Unchecked.
    val module = check(
      "def applied = (App (Lam x (Sort 0) (Var x)) (Sort 0))"
    )
    val outcome = module.outcomes.head

    assertEquals(outcome.status, DeclarationStatus.Rejected)
    assertEquals(module.environment.names, Vector.empty)
    assertEquals(
      outcome.diagnostics.map(_.message),
      Vector("cannot infer type of lambda parameter `x`")
    )
  }

  test("an ill-typed application chain is rejected, not unchecked") {
    val module = check(
      "def bad = (App (App (Lam x (Sort 0) (Var x)) (Sort 0)) (Sort 0))"
    )

    assertEquals(module.outcomes.map(_.status), Vector(DeclarationStatus.Rejected))
    assert(module.hasFailures)
  }

  test("a diverging term cannot hide inside a constructor field") {
    val omega = "(App (Lam x (Sort 0) (App (Var x) (Var x))) (Lam x (Sort 0) (App (Var x) (Var x))))"
    val module = check(s"def boom : (Sort 0) = (Ctor Box $omega)")

    assertEquals(module.outcomes.map(_.status), Vector(DeclarationStatus.Rejected))
  }

  test("module checker unfolds an earlier constant used as a type annotation") {
    val module = check(
      """def IdType : (Sort 0) =
        |  (Pi A (Sort 0) (Pi x (Var A) (Var A)))
        |def id : IdType =
        |  (Lam A (Sort 0) (Lam x (Var A) (Var x)))""".stripMargin
    )

    assertEquals(
      module.outcomes.map(_.status),
      Vector(DeclarationStatus.Accepted, DeclarationStatus.Accepted),
      module.outcomes.flatMap(_.diagnostics.map(_.message)).mkString("; ")
    )
    assertEquals(
      module.environment.lookup("id").map(_.declaredType),
      Some(Constant("IdType"))
    )
  }

  test("module checker rejects forward self and failed-declaration references") {
    val module = check(
      """def forward : (Sort 0) = later
        |def self : (Sort 0) = self
        |def later : (Sort 0) = (Sort 0)
        |def usesForward : (Sort 0) = forward""".stripMargin
    )

    assertEquals(
      module.outcomes.map(_.status),
      Vector(
        DeclarationStatus.Rejected,
        DeclarationStatus.Rejected,
        DeclarationStatus.Accepted,
        DeclarationStatus.Rejected
      )
    )
    assertEquals(module.environment.names, Vector("later"))
    assert(module.outcomes(0).diagnostics.exists(_.message == "undefined constant: later"))
    assert(module.outcomes(1).diagnostics.exists(_.message == "undefined constant: self"))
    assert(module.outcomes(3).diagnostics.exists(_.message == "undefined constant: forward"))
  }

  test("module checker rejects duplicate source names without replacing the first") {
    val module = check(
      """def value : (Sort 0) = (Sort 0)
        |def value : (Sort 1) = (Sort 1)""".stripMargin
    )

    assertEquals(
      module.outcomes.map(_.status),
      Vector(DeclarationStatus.Accepted, DeclarationStatus.Rejected)
    )
    assertEquals(module.environment.lookup("value").map(_.declaredType), Some(Sort(0)))
    assertEquals(
      module.outcomes(1).diagnostics.map(_.message),
      Vector("duplicate declaration: value")
    )
  }

  test("a rejected first declaration still reserves its source name") {
    val module = check(
      """def value : (Sort 0) = missing
        |def value : (Sort 0) = (Sort 0)""".stripMargin
    )

    assertEquals(
      module.outcomes.map(_.status),
      Vector(DeclarationStatus.Rejected, DeclarationStatus.Rejected)
    )
    assertEquals(module.environment.names, Vector.empty)
    assertEquals(
      module.outcomes(1).diagnostics.map(_.message),
      Vector("duplicate declaration: value")
    )
  }

  test("module checker keeps holes and nested unknown constants out of the environment") {
    val module = check(
      """def holey : (Sort 0) = ?goal : (Sort 0)
        |def hiddenUnknown = (Lam x (Sort 0) missing)
        |def independent : (Sort 0) = (Sort 0)""".stripMargin
    )

    assertEquals(
      module.outcomes.map(_.status),
      Vector(DeclarationStatus.Rejected, DeclarationStatus.Rejected, DeclarationStatus.Accepted)
    )
    assertEquals(module.environment.names, Vector("independent"))
    assertEquals(
      module.outcomes(0).diagnostics.map(_.message),
      Vector("unresolved hole: goal : Type[0]")
    )
    assertEquals(
      module.outcomes(1).diagnostics.map(_.message),
      Vector("undefined constant: missing")
    )
  }

  test("a forward reference inside a declared type is rejected") {
    val module = check(
      """def early : Later = (Sort 0)
        |def Later : (Sort 1) = (Sort 0)""".stripMargin
    )

    assertEquals(
      module.outcomes.map(_.status),
      Vector(DeclarationStatus.Rejected, DeclarationStatus.Accepted)
    )
    assertEquals(
      module.outcomes(0).diagnostics.map(_.message),
      Vector("undefined constant: Later")
    )
    assertEquals(module.environment.names, Vector("Later"))
  }

  test("an unchecked declaration reserves its name but is not referenceable") {
    val module = check(
      """def f = (Lam x (Sort 0) (Var x))
        |def g : (Sort 0) = f
        |def f : (Sort 0) = (Sort 0)""".stripMargin
    )

    assertEquals(
      module.outcomes.map(_.status),
      Vector(DeclarationStatus.Unchecked, DeclarationStatus.Rejected, DeclarationStatus.Rejected)
    )
    assertEquals(
      module.outcomes(1).diagnostics.map(_.message),
      Vector("undefined constant: f")
    )
    assertEquals(
      module.outcomes(2).diagnostics.map(_.message),
      Vector("duplicate declaration: f")
    )
    assertEquals(module.environment.names, Vector.empty)
  }

  test("a hole inside a declared type is rejected") {
    val module = check("def domainHole : (Pi x ?g (Sort 0)) = (Sort 0)")

    assertEquals(module.outcomes.map(_.status), Vector(DeclarationStatus.Rejected))
    assertEquals(
      module.outcomes(0).diagnostics.map(_.message),
      Vector("unresolved hole: g")
    )
  }

  test("universe levels of registered constants are not yet constrained (ADR-01 pin)") {
    val module = check("def big : (Sort 0) = (Sort 5)")

    assertEquals(module.outcomes.map(_.status), Vector(DeclarationStatus.Accepted))
  }

  test("a synth do declaration checks end-to-end through resolver and kernel") {
    val module = check(
      """def andIntro : (Pi A (Sort 0) (Pi B (Sort 0) (Sort 0))) = synth do {
        |  param A : (Sort 0)
        |  param B : (Sort 0)
        |  yield (A, B)
        |}""".stripMargin
    )
    val outcome = module.outcomes.head

    assertEquals(outcome.status, DeclarationStatus.Accepted)
    assertEquals(
      outcome.core,
      Some(
        Lambda(
          "A",
          Sort(0),
          Lambda("B", Sort(0), Constructor("Tuple2", List(Var("A"), Var("B"))))
        )
      )
    )
  }

  test("a synth do let binds a Sort(0)-typed value (current elaboration limit)") {
    val module = check(
      """def withLet : (Pi A (Sort 0) (Sort 0)) = synth do {
        |  param A : (Sort 0)
        |  let p = (Ctor Pair (Var A) (Var A))
        |  yield (Var p)
        |}""".stripMargin
    )
    val outcome = module.outcomes.head

    assertEquals(
      outcome.status,
      DeclarationStatus.Accepted,
      outcome.diagnostics.map(_.message).mkString("; ")
    )
  }

  test("elaboration failure diagnostics render their detail") {
    assertEquals(
      ModuleDiagnostic.ElaborationFailure("boom").message,
      "elaboration failed: boom"
    )
  }

  test("a declaration named after a parser primitive cannot be referenced (known trap)") {
    val module = check(
      """def Unit : (Sort 0) = (Sort 0)
        |def useUnit : (Sort 0) = Unit""".stripMargin
    )

    // The parser lowers a bare `Unit` to Constructor("Unit", Nil) before name
    // resolution runs, so `useUnit` silently refers to the builtin
    // constructor, never the declaration above. Pinned as a known trap.
    assertEquals(
      module.outcomes.map(_.status),
      Vector(DeclarationStatus.Accepted, DeclarationStatus.Accepted)
    )
    assertEquals(module.outcomes(1).core, Some(Constructor("Unit", Nil)))
  }

  test("unannotated non-lambda cores that hit lambda inference are rejected (guard boundary)") {
    val letCore = check("def viaLet = (Let x (Sort 0) (Sort 0) (Lam y (Sort 0) (Var y)))")
    assertEquals(letCore.outcomes.map(_.status), Vector(DeclarationStatus.Rejected))

    val piDomain = check("def viaPi = (Pi x (Lam y (Sort 0) (Var y)) (Sort 0))")
    assertEquals(piDomain.outcomes.map(_.status), Vector(DeclarationStatus.Rejected))
  }

  test("find returns the first outcome for a duplicated name") {
    val module = check(
      """def v : (Sort 0) = (Sort 0)
        |def v : (Sort 1) = (Sort 1)""".stripMargin
    )

    assertEquals(module.find("v").map(_.status), Some(DeclarationStatus.Accepted))
  }
