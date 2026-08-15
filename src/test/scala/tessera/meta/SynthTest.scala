package tessera.meta

import munit.FunSuite
import tessera.core.Term
import tessera.kernel.{KernelDeclaration, KernelEnvironment}
import Term.*
class SynthTest extends FunSuite:

  test("derive checks generated constants against a supplied environment") {
    val idType = Pi("A", Sort(0), Pi("x", Var("A"), Var("A")))
    val idBody = Lambda("A", Sort(0), Lambda("x", Var("A"), Var("x")))
    val environment = KernelEnvironment.empty
      .define(KernelDeclaration("id", idType, idBody))
      .toOption
      .get
    val aliasSynth = Synth.pure(Constant("id"))

    val checkedTerm: Term = Synth.derive(aliasSynth, Some(idType), environment) match
      case SearchResult.Success(value, _) => value
      case SearchResult.Failure(message, _) => fail(message)
    assertEquals(checkedTerm, idBody: Term)

    Synth.derive(aliasSynth, Some(idType)) match
      case SearchResult.Success(term, _) => fail(s"expected a kernel rejection, got $term")
      case SearchResult.Failure(message, _) =>
        assert(message.contains("undefined constant: id"), message)
  }

  test("derive accepts a caller-supplied normalization budget") {
    val redex = Synth.pure(App(Lambda("x", Sort(0), Var("x")), Sort(0)))

    Synth.derive(redex, None, KernelEnvironment.empty, 0) match
      case SearchResult.Failure(message, _) => assert(message.contains("budget"), message)
      case SearchResult.Success(term, _) => fail(s"expected budget failure, got $term")

    val normalized: Term = Synth.derive(redex, None) match
      case SearchResult.Success(value, _) => value
      case SearchResult.Failure(message, _) => fail(message)
    assertEquals(normalized, Sort(0): Term)
  }

  test("derive without an expected type reports budget exhaustion on divergence") {
    val omega = Lambda("x", Sort(0), App(Var("x"), Var("x")))
    val diverging = Synth.pure(App(omega, omega))

    Synth.derive(diverging, None) match
      case SearchResult.Success(term, _) => fail(s"expected budget exhaustion, got $term")
      case SearchResult.Failure(message, _) =>
        assert(message.contains("normalization budget exhausted"), message)
  }

  test("Synth combinators build an explicit lambda-style term and kernel-check it") {
    val explicitSynth =
      Synth.fn("A", Sort(0)) { _ =>
        Synth.fn("B", Sort(0)) { _ =>
          Synth.construct("Pair", Vector(
            Synth.lookup("A"),
            Synth.lookup("B")
          ))
        }
      }

    val expected = Pi(
      "A",
      Sort(0),
      Pi("B", Sort(0), Sort(0))
    )

    val result = Synth.derive(explicitSynth, Some(expected))
    val term = result match
      case SearchResult.Success(value, _) => value
      case SearchResult.Failure(message, _) => fail(message)
    val expectedTerm =
      Lambda("A", Sort(0), Lambda("B", Sort(0), Constructor("Pair", List(Var("A"), Var("B")))))
    assertEquals(
      term,
      expectedTerm,
      "synth generated term should match explicit structure"
    )
  }

  test("Synth.flatMap/construct form application from explicit combinators") {
    val applicative =
      Synth.fn("f", Pi("x", Sort(0), Sort(0))) { fnName =>
        Synth.lookup(fnName).flatMapSynth { functionTerm =>
          Synth.call(Synth.use(functionTerm), Synth.pure(Sort(0)))
        }
      }

    val expected = Pi("f", Pi("x", Sort(0), Sort(0)), Sort(0))
    val result = Synth.derive(applicative, Some(expected))
    val term = result match
      case SearchResult.Success(value, _) => value
      case SearchResult.Failure(message, _) => fail(message)
    assertEquals(term, Lambda("f", Pi("x", Sort(0), Sort(0)), App(Var("f"), Sort(0))))
  }

  test("Synth.choose falls back to second branch on first failure") {
    val preferSecond = Synth.choose(
      Synth.fail("first branch is intentionally failing"),
      Synth.pure(Var("fallback"))
    )

    val result = Synth.derive(preferSecond, None)
    val term = result match
      case SearchResult.Success(value, _) => value
      case SearchResult.Failure(message, _) => fail(message)
    assertEquals(term, Var("fallback"))
  }

  test("Synth.lookup fails when symbol is unknown") {
    val unknown = Synth.lookup("missing")
    val result = Synth.derive(unknown, None)
    assert(result.isInstanceOf[SearchResult.Failure])
    val SearchResult.Failure(message, _) = result: @unchecked
    assert(message.contains("cannot find local term `missing`"))
  }

  test("Synth.depFn aliases fn semantics") {
    val dep =
      Synth.depFn("A", Sort(0)) { _ =>
        Synth.depFn("B", Sort(0)) { _ =>
          Synth.construct("Pair", Vector(
            Synth.lookup("A"),
            Synth.lookup("B")
          ))
        }
      }

    val result = Synth.derive(dep, None)
    val term = result match
      case SearchResult.Success(value, _) => value
      case SearchResult.Failure(message, _) => fail(message)
    assertEquals(
      term,
      Lambda("A", Sort(0), Lambda("B", Sort(0), Constructor("Pair", List(Var("A"), Var("B")))))
    )
  }

  test("Synth.fnN builds multi-arg lambdas") {
    val stacked =
      Synth.fnN(Vector("A" -> Sort(0), "B" -> Sort(0), "C" -> Sort(0))) { args =>
        Synth.construct("Pair", Vector(
          Synth.pure(args(0)),
          Synth.pure(args(1)),
          Synth.pure(args(2))
        ))
      }

    val result = Synth.derive(stacked, None)
    val term = result match
      case SearchResult.Success(value, _) => value
      case SearchResult.Failure(message, _) => fail(message)
    val expectParams = Lambda(
      "A",
      Sort(0),
      Lambda(
        "B",
        Sort(0),
        Lambda("C", Sort(0), Constructor("Pair", List(Var("A"), Var("B"), Var("C"))))
      ))
    assertEquals(term, expectParams)
  }

  test("Synth.construct accepts named fields with stable order") {
    val namedConstruct = Synth.construct(
      "Pair",
      Vector(
        "left" -> Synth.pure(Var("A")),
        "right" -> Synth.pure(Var("B"))
      )
    )

    val result = Synth.derive(Synth.fn("A", Sort(0)) { _ =>
      Synth.fn("B", Sort(0)) { _ =>
        namedConstruct
      }
    }, None)

    val term = result match
      case SearchResult.Success(value, _) => value
      case SearchResult.Failure(message, _) => fail(message)
    assertEquals(
      term,
      Lambda("A", Sort(0), Lambda("B", Sort(0), Constructor("Pair", List(Var("A"), Var("B")))))
    )
  }

  test("Synth.construct rejects duplicate named fields") {
    val duplicates =
      Synth.construct(
        "Pair",
        Vector(
          "x" -> Synth.pure(Var("A")),
          "x" -> Synth.pure(Var("B"))
        )
      )
    val result = Synth.derive(
      Synth.fn("A", Sort(0)) { _ => duplicates },
      None
    )
    assert(result.isInstanceOf[SearchResult.Failure])
    val SearchResult.Failure(message, _) = result: @unchecked
    assert(message.contains("duplicate field name in constructor 'Pair'"))
  }

  test("Synth.zip keeps applicative combination independent") {
    val zipped =
      Synth.zip(
        Synth.lookup("A"),
        Synth.lookup("B")
      ).flatMapSynth { pair =>
        Synth.pure(pair)
      }

    val result = Synth.derive(
      Synth.fn("A", Sort(0)) { _ =>
        Synth.fn("B", Sort(0)) { _ =>
          zipped
        }
      }
    )
    val term = result match
      case SearchResult.Success(value, _) => value
      case SearchResult.Failure(message, _) => fail(message)
    assertEquals(term, Lambda("A", Sort(0), Lambda("B", Sort(0), Constructor("Pair", List(Var("A"), Var("B"))))))
  }

  test("Synth.search picks first successful branch") {
    val configured = SearchConfig(
      Vector(
        Synth.fail("first"),
        Synth.pure(Var("ok")),
        Synth.fail("second")
      ),
      Some("pick-first")
    )
    val result = Synth.search(configured)
    val term = result.execute(Vector.empty) match
      case SearchResult.Success(value, _) => value
      case SearchResult.Failure(message, _) => fail(message)
    assertEquals(term, Var("ok"))
  }

  test("Synth.inspect selects matching branch by term shape") {
    val inspected =
      Synth.inspect[Term, Term](Constructor("Left", List(Var("x")))) {
        case Constructor("Left", _) => Synth.pure(Sort(0))
        case Constructor("Right", _) => Synth.pure(Sort(1))
      }

    val result = Synth.derive(inspected, Some(Sort(0)))
    val term = result match
      case SearchResult.Success(value, _) => value
      case SearchResult.Failure(message, _) => fail(message)
    assertEquals(term, Sort(0))
  }

  test("Synth.derive rejects terms that do not type-check") {
    val broken =
      Synth.pure(Var("nope"))

    val result = Synth.derive(broken, Some(Sort(1)))
    assert(result.isInstanceOf[SearchResult.Failure])
    val SearchResult.Failure(message, _) = result: @unchecked
    assert(message.contains("generated term rejected by kernel"))
  }

  test("Synth.search with all failures aggregates failure messages") {
    val configured = SearchConfig(
      Vector(
        Synth.fail("branch A failed"),
        Synth.fail("branch B failed")
      )
    )
    val result = Synth.search(configured).execute(Vector.empty)
    assert(result.isInstanceOf[SearchResult.Failure])
    val SearchResult.Failure(message, trace) = result: @unchecked
    assert(message.contains("all search branches failed"))
    assert(message.contains("branch A failed"))
    assert(message.contains("branch B failed"))
    assert(trace.contains("search"))
  }

  test("Synth.inspect reports failure when no branch matches") {
    val inspected =
      Synth.inspect[Term, Term](Constructor("Unknown", Nil)) {
        case Constructor("Known", _) => Synth.pure(Sort(0))
      }

    val result = Synth.derive(inspected, Some(Sort(0)))
    assert(result.isInstanceOf[SearchResult.Failure])
    val SearchResult.Failure(message, _) = result: @unchecked
    assert(message.contains("no matching branch"))
  }
