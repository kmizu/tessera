package tessera.kernel

import munit.FunSuite
import tessera.core.Term.*

final class KernelEnvironmentTest extends FunSuite:
  private def rightOrFail[L, R](result: Either[L, R]): R =
    result.fold(error => fail(s"expected Right, got Left($error)"), identity)

  test("kernel environment defines immutably and preserves declaration order") {
    val id = KernelDeclaration("id", Sort(0), Sort(0))
    val alias = KernelDeclaration("alias", Sort(0), Constant("id"))

    val withId = rightOrFail(KernelEnvironment.empty.define(id))
    val withAlias = rightOrFail(withId.define(alias))

    assertEquals(KernelEnvironment.empty.lookup("id"), None)
    assertEquals(withId.lookup("alias"), None)
    assertEquals(withAlias.lookup("id"), Some(id))
    assertEquals(withAlias.lookup("alias"), Some(alias))
    assertEquals(withAlias.names, Vector("id", "alias"))
  }

  test("kernel environment rejects duplicate definitions without replacement") {
    val first = KernelDeclaration("value", Sort(0), Sort(0))
    val second = KernelDeclaration("value", Sort(1), Sort(1))
    val environment = rightOrFail(KernelEnvironment.empty.define(first))

    assertEquals(
      environment.define(second),
      Left(KernelEnvironmentError.DuplicateDefinition("value"))
    )
    assertEquals(
      environment.define(second).left.map(_.message),
      Left("duplicate definition: value")
    )
    assertEquals(environment.lookup("value"), Some(first))
    assertEquals(environment.names, Vector("value"))
    assert(environment.contains("value"))
  }

  test("kernel environment rejects definitions containing holes") {
    val holed = KernelDeclaration("bogus", Sort(0), Hole("g", None))

    assertEquals(
      KernelEnvironment.empty.define(holed).left.map(_.message),
      Left("definition bogus contains unresolved hole: g")
    )
  }

  test("kernel environment surfaces undefined references with their message") {
    val dangling = KernelDeclaration("alias", Constant("MissingType"), Sort(0))

    assertEquals(
      KernelEnvironment.empty.define(dangling).left.map(_.message),
      Left("definition alias references undefined constant: MissingType")
    )
  }
