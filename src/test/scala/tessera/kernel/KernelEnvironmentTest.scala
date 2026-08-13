package tessera.kernel

import munit.FunSuite
import tessera.core.Term.*

final class KernelEnvironmentTest extends FunSuite:
  test("kernel environment defines immutably and preserves declaration order") {
    val id = KernelDeclaration("id", Sort(0), Sort(0))
    val alias = KernelDeclaration("alias", Sort(0), Constant("id"))

    val Right(withId) = KernelEnvironment.empty.define(id)
    val Right(withAlias) = withId.define(alias)

    assertEquals(KernelEnvironment.empty.lookup("id"), None)
    assertEquals(withId.lookup("alias"), None)
    assertEquals(withAlias.lookup("id"), Some(id))
    assertEquals(withAlias.lookup("alias"), Some(alias))
    assertEquals(withAlias.names, Vector("id", "alias"))
  }

  test("kernel environment rejects duplicate definitions without replacement") {
    val first = KernelDeclaration("value", Sort(0), Sort(0))
    val second = KernelDeclaration("value", Sort(1), Sort(1))
    val Right(environment) = KernelEnvironment.empty.define(first)

    assertEquals(
      environment.define(second),
      Left(KernelEnvironmentError.DuplicateDefinition("value"))
    )
    assertEquals(environment.lookup("value"), Some(first))
  }
