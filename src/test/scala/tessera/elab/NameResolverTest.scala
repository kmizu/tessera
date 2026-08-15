package tessera.elab

import munit.FunSuite
import tessera.core.Term.*

final class NameResolverTest extends FunSuite:
  test("resolver turns free names into constants") {
    assertEquals(
      NameResolver.resolve(App(Var("id"), Var("argument"))),
      App(Constant("id"), Constant("argument"))
    )
  }

  test("resolver preserves Pi lambda and let locals with lexical scope") {
    val term = Pi(
      "A",
      Var("GlobalType"),
      Lambda(
        "x",
        Var("A"),
        Let("y", Var("A"), Var("x"), App(Var("y"), Var("outside")))
      )
    )

    assertEquals(
      NameResolver.resolve(term),
      Pi(
        "A",
        Constant("GlobalType"),
        Lambda(
          "x",
          Var("A"),
          Let("y", Var("A"), Var("x"), App(Var("y"), Constant("outside")))
        )
      )
    )
  }

  test("local binder shadows a same-named global") {
    assertEquals(
      NameResolver.resolve(Lambda("id", Sort(0), Var("id"))),
      Lambda("id", Sort(0), Var("id"))
    )
  }

  test("a binder does not scope over its own domain or annotation") {
    assertEquals(
      NameResolver.resolve(Pi("A", Var("A"), Sort(0))),
      Pi("A", Constant("A"), Sort(0))
    )
    assertEquals(
      NameResolver.resolve(Lambda("x", Var("x"), Var("x"))),
      Lambda("x", Constant("x"), Var("x"))
    )
    assertEquals(
      NameResolver.resolve(Let("y", Var("y"), Var("y"), Var("y"))),
      Let("y", Constant("y"), Constant("y"), Var("y"))
    )
  }

  test("hole annotations and constructor fields resolve through the current scope") {
    assertEquals(
      NameResolver.resolve(Hole("g", Some(Var("T")))),
      Hole("g", Some(Constant("T")))
    )
    assertEquals(
      NameResolver.resolve(Lambda("x", Sort(0), Hole("g", Some(Var("x"))))),
      Lambda("x", Sort(0), Hole("g", Some(Var("x"))))
    )
    assertEquals(
      NameResolver.resolve(Lambda("x", Sort(0), Constructor("Pair", List(Var("x"), Var("g"))))),
      Lambda("x", Sort(0), Constructor("Pair", List(Var("x"), Constant("g"))))
    )
  }

  test("resolution is idempotent") {
    val term = Lambda("x", Sort(0), App(Var("free"), Var("x")))
    val once = NameResolver.resolve(term)

    assertEquals(NameResolver.resolve(once), once)
  }
