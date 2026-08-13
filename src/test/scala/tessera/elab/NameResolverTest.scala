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
