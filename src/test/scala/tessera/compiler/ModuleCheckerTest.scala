package tessera.compiler

import munit.FunSuite
import tessera.core.Term.*
import tessera.parser.SimpleSyntaxParser

final class ModuleCheckerTest extends FunSuite:
  private def check(source: String): CheckedModule =
    val Right(declarations) = SimpleSyntaxParser.parseModule(source)
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
    assert(outcome.diagnostics.exists(_.message.contains("cannot infer type of lambda")))
  }

  test("lambda inference failure below a non-lambda declaration is rejected") {
    val module = check(
      "def applied = (App (Lam x (Sort 0) (Var x)) (Sort 0))"
    )
    val outcome = module.outcomes.head

    assertEquals(outcome.status, DeclarationStatus.Rejected)
    assertEquals(module.environment.names, Vector.empty)
    assert(outcome.diagnostics.exists(_.message.contains("cannot infer type of lambda")))
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
    assert(module.outcomes(0).diagnostics.exists(_.message.contains("unresolved hole: goal")))
    assert(module.outcomes(1).diagnostics.exists(_.message == "undefined constant: missing"))
  }
