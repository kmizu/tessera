package tessera.kernel

import munit.FunSuite
import tessera.core.Term
import tessera.core.Term._

class KernelTest extends FunSuite:
  private val kernel = Kernel()
  private val idType = Pi("A", Sort(0), Pi("x", Var("A"), Var("A")))
  private val idBody = Lambda("A", Sort(0), Lambda("x", Var("A"), Var("x")))

  private def environmentOf(declarations: KernelDeclaration*): KernelEnvironment =
    declarations.foldLeft(KernelEnvironment.empty) { (environment, declaration) =>
      environment.define(declaration).toOption.get
    }

  test("kernel accepts a closed identity term type") {
    val termType: Term =
      Pi("A", Sort(0), Pi("x", Var("A"), Var("A")))

    val identity: Term =
      Lambda("A", Sort(0),
        Lambda("x", Var("A"), Var("x"))
      )

    val report = kernel.check(identity, termType)
    assert(report.isOk)
    assertEquals(report.errors.size, 0)
  }

  test("kernel rejects a mismatched body type for a closed declaration") {
    val badType: Term =
      Pi("A", Sort(0), Pi("x", Var("A"), Sort(1)))

    val wrongIdentity: Term =
      Lambda("A", Sort(0),
        Lambda("x", Var("A"), Var("x"))
      )

    val report = kernel.check(wrongIdentity, badType)
    assert(!report.isOk)
    assert(report.errors.exists(_.isInstanceOf[KernelError.NotTypeMismatch]))
  }

  test("kernel compares definitional equality after normalization") {
    val value: Term =
      App(Lambda("x", Sort(0), Var("x")), Constructor("Unit", Nil))
    val normalized = kernel.normalize(value)

    assertEquals(normalized, Constructor("Unit", Nil))
  }

  test("kernel supports deBruijn shift and substitution helpers") {
    val term: Term = Lambda("_", Sort(0), DBVar(1))
    val shifted = kernel.shift(term, 1)
    assertEquals(shifted, Lambda("_", Sort(0), DBVar(2)))

    val substituted = kernel.substitute(0, Constructor("Unit", Nil), term)
    assertEquals(substituted, Lambda("_", Sort(0), Constructor("Unit", Nil)))
  }

  test("kernel accepts annotated holes only as unresolved") {
    val term = Hole("goal", Some(Sort(0)))
    val report = kernel.check(term, Sort(0))
    assert(!report.isOk)
    assertEquals(report.errors.size, 1)
    report.errors.head match
      case KernelError.UnresolvedHole(name, expected) =>
        assertEquals(name, "goal")
        assertEquals(expected, Some(Sort(0)))
      case other =>
        fail(s"unexpected error: $other")
  }

  test("kernel reports mismatch when a hole annotation conflicts with expected type") {
    val term = Hole("goal", Some(Sort(1)))
    val report = kernel.check(term, Sort(0))
    assert(!report.isOk)
    assertEquals(report.errors.size, 1)
    assert(report.errors.head.isInstanceOf[KernelError.NotTypeMismatch])
  }

  test("constant is a stable structural leaf") {
    val constant = Constant("id")
    assertEquals(Printer.render(constant), "id")
    assertEquals(kernel.shift(constant, 3), constant)
    assertEquals(kernel.substitute(0, Sort(0), constant), constant)
  }

  test("kernel infers known constants and rejects unknown constants") {
    val environment = environmentOf(KernelDeclaration("id", idType, idBody))
    val withEnvironment = Kernel(environment)

    assertEquals(withEnvironment.infer(Constant("id")), Right(idType))
    assertEquals(
      withEnvironment.infer(Constant("missing")),
      Left(KernelError.UndefinedConstant("missing"))
    )
    assertEquals(withEnvironment.normalize(Constant("missing")), Constant("missing"))
  }

  test("kernel preflight rejects unknown constants nested below opaque typing rules") {
    val lambda = Lambda("x", Sort(0), Constant("missing"))
    val constructor = Constructor("Box", List(Constant("missing")))
    val annotatedHole = Hole("goal", Some(Constant("missing")))

    assertEquals(Kernel().infer(lambda), Left(KernelError.UndefinedConstant("missing")))
    assertEquals(Kernel().infer(constructor), Left(KernelError.UndefinedConstant("missing")))
    assertEquals(Kernel().infer(annotatedHole), Left(KernelError.UndefinedConstant("missing")))
    assertEquals(
      Kernel().check(Sort(0), Constant("missing")).errors,
      Vector(KernelError.UndefinedConstant("missing"))
    )
  }

  test("kernel delta-normalizes aliases and uses them in definitional equality") {
    val environment = environmentOf(
      KernelDeclaration("id", idType, idBody),
      KernelDeclaration("alias", idType, Constant("id")),
      KernelDeclaration("secondAlias", idType, Constant("alias")),
      KernelDeclaration("IdType", Sort(0), idType),
      KernelDeclaration("typedId", Constant("IdType"), idBody)
    )
    val withEnvironment = Kernel(environment)

    assertEquals(withEnvironment.normalize(Constant("secondAlias")), idBody)
    assert(withEnvironment.isDefEq(Constant("alias"), idBody))
    assert(withEnvironment.check(Constant("alias"), idType).isOk)
    assert(withEnvironment.check(idBody, Constant("IdType")).isOk)
    assertEquals(
      withEnvironment.infer(App(Constant("typedId"), Sort(0))),
      Right(Pi("x", Sort(0), Sort(0)))
    )
  }

  test("kernel normalization terminates on a manually cyclic environment") {
    val environment = environmentOf(
      KernelDeclaration("a", Sort(0), Constant("b")),
      KernelDeclaration("b", Sort(0), Constant("a"))
    )

    assertEquals(Kernel(environment).normalize(Constant("a")), Constant("a"))
  }

  test("kernel normalization preserves its cycle guard across beta reduction") {
    val recursiveBody = Lambda(
      "x",
      Sort(0),
      App(Constant("f"), Var("x"))
    )
    val environment = environmentOf(
      KernelDeclaration("f", Pi("x", Sort(0), Sort(0)), recursiveBody)
    )
    val application = App(Constant("f"), UnitLit())

    assertEquals(Kernel(environment).normalize(application), application)
  }

  test("kernel infers a dependent Pi using its named binder") {
    assertEquals(kernel.infer(idType), Right(Sort(0)))
  }

  test("kernel lookup uses the nearest same-named Pi binder") {
    val term = Pi("A", Sort(0), Pi("A", Sort(1), Var("A")))
    assertEquals(kernel.infer(term), Right(Sort(1)))
  }

  test("kernel lookup uses the nearest same-named lambda binder") {
    val expected = Pi("x", Sort(0), Pi("x", Sort(1), Sort(1)))
    val term = Lambda("x", Sort(0), Lambda("x", Sort(1), Var("x")))
    assert(kernel.check(term, expected).isOk)
  }

  test("kernel lookup uses the nearest same-named let binder") {
    val term = Let(
      "x",
      Sort(0),
      Sort(0),
      Let("x", Sort(1), Sort(1), Var("x"))
    )
    assertEquals(kernel.infer(term), Right(Sort(1)))
  }
end KernelTest
