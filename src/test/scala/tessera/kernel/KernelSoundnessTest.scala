package tessera.kernel

import munit.FunSuite
import tessera.core.Term
import Term.*

// Regression coverage for kernel soundness: variable typing, capture-avoiding
// substitution, alpha-insensitive definitional equality, metavariable
// rejection (AGENTS.md invariants 2 and 6), and normalization budgets.
final class KernelSoundnessTest extends FunSuite:
  private val kernel = Kernel()

  test("variable checking compares types, not their sorts") {
    val idBody = Lambda("A", Sort(0), Lambda("B", Sort(0), Lambda("x", Var("A"), Var("x"))))
    val claimed = Pi("A", Sort(0), Pi("B", Sort(0), Pi("x", Var("A"), Var("B"))))
    val honest = Pi("A", Sort(0), Pi("B", Sort(0), Pi("x", Var("A"), Var("A"))))

    assert(!kernel.check(idBody, claimed).isOk, "A -> B must not be inhabited by the identity")
    assert(kernel.check(idBody, honest).isOk)
  }

  test("de Bruijn checking indexes the context innermost-first and compares types") {
    val ctx = Vector("A" -> Sort(0), "x" -> Var("A"))

    assert(kernel.check(DBVar(0), Var("A"), ctx).isOk)
    assert(!kernel.check(DBVar(0), Sort(0), ctx).isOk)
    assert(kernel.check(DBVar(1), Sort(0), ctx).isOk)
  }

  test("beta reduction does not capture free variables") {
    val term = App(Lambda("x", Sort(0), Lambda("y", Sort(0), Var("x"))), Var("y"))

    kernel.normalize(term) match
      case Lambda(binder, _, body) =>
        assertNotEquals(binder, "y")
        assertEquals(body, Var("y"))
      case other => fail(s"expected a lambda, got: $other")
  }

  test("let normalization does not capture free variables") {
    val term = Let("a", Sort(0), Var("y"), Lambda("y", Sort(0), Var("a")))

    kernel.normalize(term) match
      case Lambda(binder, _, body) =>
        assertNotEquals(binder, "y")
        assertEquals(body, Var("y"))
      case other => fail(s"expected a lambda, got: $other")
  }

  test("delta-unfolded constant bodies substitute without capture") {
    val konst = Lambda(
      "f",
      Pi("x", Sort(0), Sort(0)),
      Lambda("x", Sort(0), App(Var("f"), Var("x")))
    )
    val declaration = KernelDeclaration(
      "konst",
      Pi("f", Pi("x", Sort(0), Sort(0)), Pi("x", Sort(0), Sort(0))),
      konst
    )
    val environment = KernelEnvironment.empty.define(declaration).toOption.get

    Kernel(environment).normalize(App(Constant("konst"), Var("x"))) match
      case Lambda(binder, _, App(Var(function), Var(argument))) =>
        assertNotEquals(binder, "x")
        assertEquals(function, "x")
        assertEquals(argument, binder)
      case other => fail(s"unexpected normal form: $other")
  }

  test("application inference does not capture codomain binders") {
    val gType = Pi("A", Sort(0), Pi("B", Var("A"), Sort(0)))
    val environment = KernelEnvironment.empty
      .define(KernelDeclaration("g", gType, Lambda("A", Sort(0), Lambda("B", Var("A"), Sort(0)))))
      .toOption
      .get
    val app = App(Constant("g"), Var("B"))
    val ctx = Vector("B" -> Sort(0))

    assert(Kernel(environment).check(app, Pi("C", Var("B"), Sort(0)), ctx).isOk)
    assert(!Kernel(environment).check(app, Pi("C", Var("C"), Sort(0)), ctx).isOk)
  }

  test("definitional equality is alpha-insensitive") {
    assert(kernel.isDefEq(Pi("x", Sort(0), Var("x")), Pi("y", Sort(0), Var("y"))))
    assert(
      kernel.isDefEq(
        Lambda("x", Sort(0), Lambda("y", Sort(1), Var("x"))),
        Lambda("a", Sort(0), Lambda("b", Sort(1), Var("a")))
      )
    )
    assert(!kernel.isDefEq(Pi("x", Sort(0), Var("x")), Pi("y", Sort(0), Var("x"))))
  }

  test("kernel rejects unresolved holes anywhere in its input (invariant 2)") {
    assert(kernel.infer(Hole("goal", None)).isLeft)
    assert(kernel.infer(App(Hole("h", Some(Pi("y", Sort(0), Sort(0)))), Sort(0))).isLeft)
    assert(!kernel.check(App(Hole("h", Some(Pi("y", Sort(0), Sort(0)))), Sort(0)), Sort(0)).isOk)
  }

  test("normalization budget reports exhaustion on a diverging term") {
    val omega = Lambda("x", Sort(0), App(Var("x"), Var("x")))
    val diverging = App(omega, omega)

    assertEquals(kernel.normalizeWithBudget(diverging, 1000), None)
    assertEquals(kernel.normalizeWithBudget(Sort(0), 10), Some(Sort(0)))
    assertEquals(
      kernel.normalizeWithBudget(App(Lambda("x", Sort(0), Var("x")), Sort(0)), 10),
      Some(Sort(0))
    )
  }

  test("repeated renaming does not leak the outer binder (two-stage capture)") {
    val term = Lambda(
      "x",
      Sort(0),
      App(
        App(
          Lambda(
            "f",
            Sort(0),
            Lambda("x", Sort(0), Lambda("f", Sort(0), Lambda("x", Sort(0), Var("x"))))
          ),
          Var("x")
        ),
        Var("x")
      )
    )

    kernel.normalize(term) match
      case Lambda(_, _, Lambda(_, _, Lambda(binder, _, body))) =>
        assertEquals(body, Var(binder))
      case other => fail(s"unexpected normal form: $other")
  }

  test("shadowed context binders do not alias unrelated types") {
    val cast = Lambda("A", Sort(0), Lambda("x", Var("A"), Lambda("A", Sort(0), Var("x"))))
    val claimed = Pi("A", Sort(0), Pi("x", Var("A"), Pi("A", Sort(0), Var("A"))))
    val honest = Pi("A", Sort(0), Pi("x", Var("A"), Pi("B", Sort(0), Var("A"))))

    assert(!kernel.check(cast, claimed).isOk, "universal coercion must not be inhabited")
    assert(kernel.check(cast, honest).isOk)
  }

  test("public checkSort and isDefEq validate their inputs") {
    assert(kernel.checkSort(Constructor("Box", List(Hole("g", None)))).isLeft)
    assert(kernel.checkSort(Constructor("Box", List(Constant("missing")))).isLeft)
    assert(!kernel.isDefEq(Hole("h", None), Hole("h", None)))
    assert(!kernel.isDefEq(Constant("nope"), Constant("nope")))
  }

  test("a non-positive normalization budget is exhausted, never unlimited") {
    val omega = Lambda("x", Sort(0), App(Var("x"), Var("x")))

    assertEquals(kernel.normalizeWithBudget(App(omega, omega), -1), None)
    assertEquals(kernel.normalizeWithBudget(App(omega, omega), 0), None)
  }

  test("size-exploding reductions exhaust the budget instead of memory") {
    val grower = Lambda("x", Sort(0), App(App(Var("x"), Var("x")), Var("x")))

    assertEquals(kernel.normalizeWithBudget(App(grower, grower), 1000), None)
  }

  test("de Bruijn context types are shifted into the current scope") {
    assert(!kernel.check(DBVar(0), DBVar(0), Vector("A" -> Sort(0), "x" -> DBVar(0))).isOk)
  }

  test("universe levels are not compared beyond sort-versus-sort (ADR-01 pin)") {
    // ADR-01 defers explicit level constraints: any sort currently checks
    // against any sort. This pin makes the known hole visible.
    assert(kernel.check(Sort(5), Sort(0)).isOk)
  }
end KernelSoundnessTest
