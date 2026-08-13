package tessera.kernel

import munit.FunSuite
import tessera.core.Term
import tessera.core.Term._

class KernelTest extends FunSuite:
  private val kernel = Kernel()

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
end KernelTest
