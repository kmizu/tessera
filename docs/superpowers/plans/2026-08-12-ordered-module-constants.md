# Ordered Module Constants Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let declarations refer to earlier accepted declarations in the same file and transparently delta-normalize those constants.

**Architecture:** Add `Term.Constant` and a kernel-owned immutable environment first, then make kernel lookup and normalization environment-aware. Resolve free source names in elaboration, validate declarations in order through a new compiler-layer `ModuleChecker`, and finally route `check`, `eval`, and `eval --trace` through that shared checked-module result.

**Tech Stack:** Scala 3.4.2, sbt 1.10.7, MUnit 1.0.4, existing Tessera parser/elaborator/kernel/CLI.

## Global Constraints

- Same-file declarations are visible only after they have been accepted.
- `Term.Var` is local; `Term.Constant` is global.
- Local binders shadow same-named globals.
- Every registered MVP `def` is transparent; do not add opacity or theorem syntax.
- Forward references, self references, duplicates, unresolved holes, and undefined constants are never registered.
- An unannotated lambda blocked only by `CannotInferLambda` is `Unchecked`, evaluable when selected, and not registered.
- All other inference failures are `Rejected` and not evaluated.
- Kernel and core must not depend on parser, elaborator, compiler orchestration, meta, or CLI packages.
- Normalization remains total and stops defensively on constant cycles.
- Existing concise `eval` output and closed-declaration behavior remain compatible except that inferable unannotated declarations can now be accepted and registered.
- Do not add imports, namespaces, mutual recursion, recursive definitions, persistent compiled environments, JSON output, or new dependencies.

---

## File Structure

- `src/main/scala/tessera/core/Term.scala`: add the global-reference core node.
- `src/main/scala/tessera/core/TermAnalysis.scala`: shared structural hole and constant collection.
- `src/main/scala/tessera/kernel/KernelEnvironment.scala`: immutable accepted-definition table.
- `src/main/scala/tessera/kernel/Kernel.scala`: constant preflight, inference/checking, delta normalization, structural operations, and printing.
- `src/main/scala/tessera/elab/NameResolver.scala`: binder-aware free-name resolution.
- `src/main/scala/tessera/elab/Elaborator.scala`: resolve declaration type and desugared body.
- `src/main/scala/tessera/compiler/ModuleChecker.scala`: ordered declaration validation and environment construction.
- `src/main/scala/tessera/cli/Commands.scala`: consume checked modules for `check`, `eval`, and trace.
- Focused tests mirror each new layer; documentation and one runnable example land last.

---

### Task 1: Global core references and immutable environments

**Files:**
- Modify: `src/main/scala/tessera/core/Term.scala`
- Create: `src/main/scala/tessera/core/TermAnalysis.scala`
- Create: `src/main/scala/tessera/kernel/KernelEnvironment.scala`
- Modify: `src/main/scala/tessera/kernel/Kernel.scala`
- Create: `src/test/scala/tessera/kernel/KernelEnvironmentTest.scala`
- Modify: `src/test/scala/tessera/kernel/KernelTest.scala`

**Interfaces:**
- Produces: `Term.Constant(name: String)`.
- Produces: `TermAnalysis.collectHoles(term: Term): Vector[Term.Hole]` and `TermAnalysis.collectConstants(term: Term): Vector[String]`.
- Produces: `KernelDeclaration`, `KernelEnvironmentError`, and immutable `KernelEnvironment.empty/lookup/contains/define/names`.
- Preserves: existing `Kernel()` construction and closed-term behavior.

- [ ] **Step 1: Write failing core/environment tests**

Create `KernelEnvironmentTest.scala`:

```scala
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
```

Append to `KernelTest.scala`:

```scala
test("constant is a stable structural leaf") {
  val constant = Constant("id")
  assertEquals(Printer.render(constant), "id")
  assertEquals(kernel.shift(constant, 3), constant)
  assertEquals(kernel.substitute(0, Sort(0), constant), constant)
}
```

- [ ] **Step 2: Run the focused tests and verify RED**

```bash
runtime_dir=$(mktemp -d /tmp/tessera-runtime.XXXXXX)
XDG_RUNTIME_DIR="$runtime_dir" sbt \
  'testOnly tessera.kernel.KernelEnvironmentTest tessera.kernel.KernelTest'
```

Expected: compilation fails because `Constant`, `KernelDeclaration`, and
`KernelEnvironment` do not exist.

- [ ] **Step 3: Add the global term and immutable environment**

Add beside `Var` in `Term.scala`:

```scala
case class Constant(name: String) extends Term
```

Create `KernelEnvironment.scala`:

```scala
package tessera.kernel

import tessera.core.Term

final case class KernelDeclaration(name: String, declaredType: Term, value: Term)

sealed trait KernelEnvironmentError:
  def message: String

object KernelEnvironmentError:
  final case class DuplicateDefinition(name: String) extends KernelEnvironmentError:
    override val message: String = s"duplicate definition: $name"

final class KernelEnvironment private (
  private val declarations: Vector[KernelDeclaration]
):
  private val byName: Map[String, KernelDeclaration] =
    declarations.iterator.map(declaration => declaration.name -> declaration).toMap

  def lookup(name: String): Option[KernelDeclaration] = byName.get(name)
  def contains(name: String): Boolean = byName.contains(name)
  def names: Vector[String] = declarations.map(_.name)

  def define(
    declaration: KernelDeclaration
  ): Either[KernelEnvironmentError, KernelEnvironment] =
    if contains(declaration.name) then
      Left(KernelEnvironmentError.DuplicateDefinition(declaration.name))
    else
      Right(new KernelEnvironment(declarations :+ declaration))

object KernelEnvironment:
  val empty: KernelEnvironment = new KernelEnvironment(Vector.empty)
```

- [ ] **Step 4: Add shared structural analysis**

Create `TermAnalysis.scala` with exhaustive recursion:

```scala
package tessera.core

import Term.*

object TermAnalysis:
  def collectHoles(term: Term): Vector[Hole] = term match
    case hole @ Hole(_, expectedType) =>
      Vector(hole) ++ expectedType.toVector.flatMap(collectHoles)
    case Let(_, valueType, value, body) =>
      collectHoles(valueType) ++ collectHoles(value) ++ collectHoles(body)
    case Pi(_, domain, codomain) => collectHoles(domain) ++ collectHoles(codomain)
    case Lambda(_, paramType, body) => collectHoles(paramType) ++ collectHoles(body)
    case App(function, argument) => collectHoles(function) ++ collectHoles(argument)
    case Constructor(_, fields) => fields.toVector.flatMap(collectHoles)
    case Constant(_) | Var(_) | DBVar(_) | Sort(_) | Builtin(_) | UnitLit() => Vector.empty

  def collectConstants(term: Term): Vector[String] = term match
    case Constant(name) => Vector(name)
    case Hole(_, expectedType) => expectedType.toVector.flatMap(collectConstants)
    case Let(_, valueType, value, body) =>
      collectConstants(valueType) ++ collectConstants(value) ++ collectConstants(body)
    case Pi(_, domain, codomain) => collectConstants(domain) ++ collectConstants(codomain)
    case Lambda(_, paramType, body) => collectConstants(paramType) ++ collectConstants(body)
    case App(function, argument) => collectConstants(function) ++ collectConstants(argument)
    case Constructor(_, fields) => fields.toVector.flatMap(collectConstants)
    case Var(_) | DBVar(_) | Sort(_) | Builtin(_) | UnitLit() => Vector.empty
```

- [ ] **Step 5: Make existing kernel structural operations and printer exhaustive**

In every recursive match in `Kernel.scala`, handle constants as immutable leaves:

```scala
case constant @ Constant(_) => constant
```

This applies to named substitution, de Bruijn substitution, shifting, and the
temporary non-delta normalization match. Add to `Printer.render`:

```scala
case Constant(name) => name
```

- [ ] **Step 6: Run focused tests and verify GREEN**

```bash
runtime_dir=$(mktemp -d /tmp/tessera-runtime.XXXXXX)
XDG_RUNTIME_DIR="$runtime_dir" sbt \
  'testOnly tessera.kernel.KernelEnvironmentTest tessera.kernel.KernelTest'
```

Expected: both suites pass with zero failures.

- [ ] **Step 7: Commit the core/environment layer**

```bash
git add src/main/scala/tessera/core/Term.scala \
  src/main/scala/tessera/core/TermAnalysis.scala \
  src/main/scala/tessera/kernel/KernelEnvironment.scala \
  src/main/scala/tessera/kernel/Kernel.scala \
  src/test/scala/tessera/kernel/KernelEnvironmentTest.scala \
  src/test/scala/tessera/kernel/KernelTest.scala
git commit -m "feat: add immutable kernel environments"
```

---

### Task 2: Kernel lookup, preflight, and delta normalization

**Files:**
- Modify: `src/main/scala/tessera/kernel/Kernel.scala`
- Modify: `src/test/scala/tessera/kernel/KernelTest.scala`

**Interfaces:**
- Consumes: `KernelEnvironment.lookup/contains` and `TermAnalysis.collectConstants`.
- Produces: `Kernel(environment: KernelEnvironment = KernelEnvironment.empty)`.
- Produces: `KernelError.UndefinedConstant(name)`.
- Produces: environment-aware `infer`, `check`, `normalize`, and `isDefEq`.

- [ ] **Step 1: Write failing constant-semantics tests**

Add a fixture and tests to `KernelTest.scala`:

```scala
private val idType = Pi("A", Sort(0), Pi("x", Var("A"), Var("A")))
private val idBody = Lambda("A", Sort(0), Lambda("x", Var("A"), Var("x")))

private def environmentOf(declarations: KernelDeclaration*): KernelEnvironment =
  declarations.foldLeft(KernelEnvironment.empty) { (environment, declaration) =>
    environment.define(declaration).toOption.get
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
```

- [ ] **Step 2: Run kernel tests and verify RED**

```bash
runtime_dir=$(mktemp -d /tmp/tessera-runtime.XXXXXX)
XDG_RUNTIME_DIR="$runtime_dir" sbt 'testOnly tessera.kernel.KernelTest'
```

Expected: tests fail because `Kernel` has no environment, constant inference,
preflight, or delta reduction.

- [ ] **Step 3: Add environment construction and undefined-constant error**

Change the class header and add the error:

```scala
case class UndefinedConstant(name: String) extends KernelError:
  override val message: String = s"undefined constant: $name"

class Kernel(
  val environment: KernelEnvironment = KernelEnvironment.empty
):
```

- [ ] **Step 4: Add public-entry constant preflight**

Import `TermAnalysis` and implement deterministic first-error validation:

```scala
private def validateConstants(terms: Vector[Term]): Either[KernelError, Unit] =
  terms.iterator
    .flatMap(TermAnalysis.collectConstants)
    .find(name => !environment.contains(name))
    .map(name => Left(KernelError.UndefinedConstant(name)))
    .getOrElse(Right(()))
```

Run it before syntax-directed work:

```scala
def infer(term: Term): Either[KernelError, Term] =
  validateConstants(Vector(term)).flatMap(_ => inferType(term, Vector.empty))

def check(term: Term, expected: Term, ctx: Vector[(String, Term)]): KernelReport =
  validateConstants(Vector(term, expected) ++ ctx.map(_._2)) match
    case Left(error) => KernelReport(Vector(error))
    case Right(_) =>
      checkWithContext(term, expected, ctx) match
        case Right(_) => KernelReport(Vector.empty)
        case Left(error) => KernelReport(Vector(error))
```

Keep `check(term, expected)` and `checkDecl` delegating to this public path.

- [ ] **Step 5: Add constant inference and checking**

In `inferType`:

```scala
case Constant(name) =>
  environment.lookup(name)
    .map(_.declaredType)
    .toRight(KernelError.UndefinedConstant(name))
```

In `checkWithContext`:

```scala
case constant @ Constant(_) =>
  inferType(constant, ctx) match
    case Right(actual) if typesEqual(actual, expected) => Right(())
    case Right(actual) => Left(KernelError.NotTypeMismatch(expected, actual))
    case Left(error) => Left(error)
```

At the start of `checkWithContext`, normalize the expected type once and use
that normalized value throughout the existing term match:

```scala
private def checkWithContext(
  term: Term,
  expectedTerm: Term,
  ctx: Vector[(String, Term)]
): Either[KernelError, Unit] =
  val expected = normalize(expectedTerm)
  term match
```

Rename the current parameter to `expectedTerm`, insert the normalized local
named `expected`, and leave all existing exhaustive cases directly under the
same `term match`. Recursive calls continue to call `checkWithContext`, so every
nested expected type receives the same treatment.

This is required for annotations such as `def id : IdType = ...`, where
`IdType` is a prior transparent constant whose body is a `Pi`.

In the `App` inference branch, normalize the inferred function type before
matching it as a `Pi`:

```scala
case App(function, argument) =>
  inferType(function, ctx).map(normalize) match
    case Right(Pi(name, domain, codomain)) =>
      checkWithContext(argument, domain, ctx) match
        case Right(_) => Right(substituteByName(codomain, name, argument))
        case Left(error) => Left(error)
    case Right(other) => Left(KernelError.NotAFunction(other))
    case Left(error) => Left(error)
```

Without this WHNF step, an accepted function whose annotation is a type alias
would be inferable but not callable.

- [ ] **Step 6: Refactor normalization around an unfolding set**

Make `normalize` delegate to a private recursive function and ensure every
recursive call uses the same unfolding set:

```scala
def normalize(term: Term): Term = normalize(term, Set.empty)

private def normalize(term: Term, unfolding: Set[String]): Term = term match
  case Constant(name) if unfolding.contains(name) => Constant(name)
  case Constant(name) =>
    environment.lookup(name) match
      case Some(declaration) => normalize(declaration.value, unfolding + name)
      case None => Constant(name)
  case Hole(name, expectedType) => Hole(name, expectedType.map(normalize(_, unfolding)))
  case App(function, argument) =>
    normalize(function, unfolding) match
      case Lambda(name, _, body) =>
        normalize(substituteByName(body, name, normalize(argument, unfolding)), unfolding)
      case normalizedFunction => App(normalizedFunction, normalize(argument, unfolding))
  case Let(name, _, value, body) =>
    normalize(substituteByName(body, name, value), unfolding)
  case Pi(name, domain, codomain) =>
    Pi(name, normalize(domain, unfolding), normalize(codomain, unfolding))
  case Lambda(name, paramType, body) =>
    Lambda(name, normalize(paramType, unfolding), normalize(body, unfolding))
  case Constructor(name, fields) =>
    Constructor(name, fields.map(normalize(_, unfolding)))
  case leaf @ (Sort(_) | Var(_) | DBVar(_) | Builtin(_) | UnitLit()) => leaf
```

Normalizing constructor fields is intentional: delta reduction must not remain
hidden inside data, tuples, or later eliminator inputs.

- [ ] **Step 7: Run kernel tests and verify GREEN**

```bash
runtime_dir=$(mktemp -d /tmp/tessera-runtime.XXXXXX)
XDG_RUNTIME_DIR="$runtime_dir" sbt \
  'testOnly tessera.kernel.KernelTest tessera.kernel.KernelEnvironmentTest'
```

Expected: both suites pass with zero failures.

- [ ] **Step 8: Commit kernel constant semantics**

```bash
git add src/main/scala/tessera/kernel/Kernel.scala \
  src/test/scala/tessera/kernel/KernelTest.scala
git commit -m "feat: add transparent kernel constants"
```

---

### Task 3: Binder-aware name resolution

**Files:**
- Create: `src/main/scala/tessera/elab/NameResolver.scala`
- Modify: `src/main/scala/tessera/elab/Elaborator.scala`
- Create: `src/test/scala/tessera/elab/NameResolverTest.scala`
- Modify: `src/test/scala/tessera/elab/ElaboratorTest.scala`

**Interfaces:**
- Consumes: parsed/desugared `Term.Var` nodes.
- Produces: `NameResolver.resolve(term: Term): Term`.
- Produces: `Elaborator.elaborate` results with resolved types and bodies.
- Preserves: `Elaborator.toCore` as syntax-directed desugaring without name resolution.

- [ ] **Step 1: Write failing resolver tests**

Create `NameResolverTest.scala`:

```scala
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
```

Append to `ElaboratorTest.scala`:

```scala
test("elaborator resolves declaration types and bodies") {
  val Right(declarations) = SimpleSyntaxParser.parseModule(
    "def alias : ExistingType = existingValue"
  )
  val Right(elaborated) = Elaborator.elaborate(declarations.head)

  assertEquals(elaborated.declaredType, Some(Constant("ExistingType")))
  assertEquals(elaborated.core, Constant("existingValue"))
}
```

- [ ] **Step 2: Run elaboration tests and verify RED**

```bash
runtime_dir=$(mktemp -d /tmp/tessera-runtime.XXXXXX)
XDG_RUNTIME_DIR="$runtime_dir" sbt \
  'testOnly tessera.elab.NameResolverTest tessera.elab.ElaboratorTest'
```

Expected: compilation fails because `NameResolver` does not exist, and the
elaborator still returns free `Var` nodes.

- [ ] **Step 3: Implement exhaustive lexical resolution**

Create `NameResolver.scala`:

```scala
package tessera.elab

import tessera.core.Term
import tessera.core.Term.*

object NameResolver:
  def resolve(term: Term): Term = resolve(term, Set.empty)

  private def resolve(term: Term, locals: Set[String]): Term = term match
    case Var(name) if locals.contains(name) => Var(name)
    case Var(name) => Constant(name)
    case constant @ Constant(_) => constant
    case sort @ Sort(_) => sort
    case index @ DBVar(_) => index
    case Pi(name, domain, codomain) =>
      Pi(name, resolve(domain, locals), resolve(codomain, locals + name))
    case Lambda(name, paramType, body) =>
      Lambda(name, resolve(paramType, locals), resolve(body, locals + name))
    case Let(name, valueType, value, body) =>
      Let(
        name,
        resolve(valueType, locals),
        resolve(value, locals),
        resolve(body, locals + name)
      )
    case App(function, argument) =>
      App(resolve(function, locals), resolve(argument, locals))
    case Constructor(name, fields) =>
      Constructor(name, fields.map(resolve(_, locals)))
    case builtin @ Builtin(_) => builtin
    case unit @ UnitLit() => unit
    case Hole(name, expectedType) =>
      Hole(name, expectedType.map(resolve(_, locals)))
```

- [ ] **Step 4: Resolve elaborated declarations**

Change `Elaborator.elaborate` only; keep `toCore` unchanged:

```scala
def elaborate(decl: ParsedDeclaration): Either[String, ElaboratedDeclaration] =
  Right(
    ElaboratedDeclaration(
      decl.name,
      decl.expectedType.map(NameResolver.resolve),
      NameResolver.resolve(toCore(decl.value))
    )
  )
```

- [ ] **Step 5: Run elaboration tests and verify GREEN**

```bash
runtime_dir=$(mktemp -d /tmp/tessera-runtime.XXXXXX)
XDG_RUNTIME_DIR="$runtime_dir" sbt \
  'testOnly tessera.elab.NameResolverTest tessera.elab.ElaboratorTest'
```

Expected: both suites pass. Existing `synth do` locals remain `Var` nodes.

- [ ] **Step 6: Commit name resolution**

```bash
git add src/main/scala/tessera/elab/NameResolver.scala \
  src/main/scala/tessera/elab/Elaborator.scala \
  src/test/scala/tessera/elab/NameResolverTest.scala \
  src/test/scala/tessera/elab/ElaboratorTest.scala
git commit -m "feat: resolve global declaration names"
```

---

### Task 4: Ordered module checking and registration

**Files:**
- Create: `src/main/scala/tessera/compiler/ModuleChecker.scala`
- Create: `src/test/scala/tessera/compiler/ModuleCheckerTest.scala`
- Modify: `src/test/scala/tessera/architecture/ArchitectureTest.scala`

**Interfaces:**
- Consumes: `Elaborator.elaborate`, `TermAnalysis.collectHoles`, `Kernel`, and `KernelEnvironment.define`.
- Produces: `DeclarationStatus`, `ModuleDiagnostic`, `DeclarationOutcome`, `CheckedModule`, and `ModuleChecker.check`.
- Guarantees: only `Accepted` outcomes enter the final environment.

- [ ] **Step 1: Write failing happy-path module tests**

Create `ModuleCheckerTest.scala` with a parse helper:

```scala
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

  test("module checker unfolds an earlier constant used as a type annotation") {
    val module = check(
      """def IdType : (Sort 0) =
        |  (Pi A (Sort 0) (Pi x (Var A) (Var A)))
        |def id : IdType =
        |  (Lam A (Sort 0) (Lam x (Var A) (Var x)))""".stripMargin
    )

    assertEquals(
      module.outcomes.map(_.status),
      Vector(DeclarationStatus.Accepted, DeclarationStatus.Accepted)
    )
    assertEquals(
      module.environment.lookup("id").map(_.declaredType),
      Some(Constant("IdType"))
    )
  }
```

- [ ] **Step 2: Write failing rejection/isolation tests**

Add these tests to the same file:

```scala
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
```

- [ ] **Step 3: Run module tests and verify RED**

```bash
runtime_dir=$(mktemp -d /tmp/tessera-runtime.XXXXXX)
XDG_RUNTIME_DIR="$runtime_dir" sbt 'testOnly tessera.compiler.ModuleCheckerTest'
```

Expected: compilation fails because the compiler-layer result types and checker
do not exist.

- [ ] **Step 4: Implement the exact module result model**

Create `ModuleChecker.scala` starting with:

```scala
package tessera.compiler

import tessera.core.{Term, TermAnalysis}
import tessera.elab.Elaborator
import tessera.kernel.*
import tessera.parser.SimpleSyntaxParser.ParsedDeclaration

enum DeclarationStatus:
  case Accepted, Unchecked, Rejected

sealed trait ModuleDiagnostic:
  def message: String

object ModuleDiagnostic:
  final case class DuplicateDeclaration(name: String) extends ModuleDiagnostic:
    override val message: String = s"duplicate declaration: $name"

  final case class UnresolvedHole(hole: Term.Hole) extends ModuleDiagnostic:
    override val message: String =
      KernelError.UnresolvedHole(hole.name, hole.expectedType).message

  final case class KernelFailure(error: KernelError) extends ModuleDiagnostic:
    override def message: String = error.message

  final case class ElaborationFailure(detail: String) extends ModuleDiagnostic:
    override def message: String = s"elaboration failed: $detail"

final case class DeclarationOutcome(
  declaration: ParsedDeclaration,
  status: DeclarationStatus,
  core: Option[Term],
  declaredType: Option[Term],
  inferredType: Option[Term],
  diagnostics: Vector[ModuleDiagnostic]
):
  def name: String = declaration.name
  def effectiveType: Option[Term] = declaredType.orElse(inferredType)

final case class CheckedModule(
  outcomes: Vector[DeclarationOutcome],
  environment: KernelEnvironment
):
  def find(name: String): Option[DeclarationOutcome] = outcomes.find(_.name == name)
  def hasFailures: Boolean = outcomes.exists(_.status == DeclarationStatus.Rejected)
```

- [ ] **Step 5: Implement ordered checking**

Implement `ModuleChecker.check` as an immutable fold. Use a private state:

```scala
object ModuleChecker:
  def check(declarations: Vector[ParsedDeclaration]): CheckedModule =
    val finalState = declarations.foldLeft(State.empty)(checkOne)
    CheckedModule(finalState.outcomes, finalState.environment)

  private final case class State(
    outcomes: Vector[DeclarationOutcome],
    environment: KernelEnvironment,
    encounteredNames: Set[String]
  )

  private object State:
    val empty: State = State(Vector.empty, KernelEnvironment.empty, Set.empty)
```

`checkOne` must implement these exact transitions:

1. Add every first-seen name to `encounteredNames`, including names whose
   declaration later fails.
2. Emit `Rejected` with only `DuplicateDeclaration` before elaborating a repeat.
3. Call `Elaborator.elaborate` for a first-seen declaration.
4. Collect holes from resolved type and body; emit one `UnresolvedHole` per
   stable occurrence and skip kernel work if any exist.
5. For an annotation, run `Kernel(environment).check(core, expected)`.
6. Without an annotation, run `Kernel(environment).infer(core)`.
7. Convert only `CannotInferLambda` to `Unchecked`; convert every other kernel
   error to `Rejected`.
8. On successful checking/inference, call `environment.define` with the resolved
   core and effective type and emit `Accepted` with the returned environment.
9. Keep the previous environment for `Unchecked` and `Rejected` outcomes.

Use these outcome field rules:

```text
Accepted typed:    core=Some, declaredType=Some, inferredType=None
Accepted inferred: core=Some, declaredType=None, inferredType=Some
Unchecked:         core=Some, declaredType=None, inferredType=None,
                   diagnostics=[KernelFailure(CannotInferLambda)]
Rejected resolved: core=Some, resolved declaredType if present, diagnostics nonempty
Rejected duplicate/elaboration: core=None, diagnostics nonempty
```

Use this implementation shape so failures cannot update the environment:

```scala
private def checkOne(state: State, declaration: ParsedDeclaration): State =
  if state.encounteredNames.contains(declaration.name) then
    append(
      state,
      DeclarationOutcome(
        declaration,
        DeclarationStatus.Rejected,
        None,
        None,
        None,
        Vector(ModuleDiagnostic.DuplicateDeclaration(declaration.name))
      )
    )
  else
    val marked = state.copy(encounteredNames = state.encounteredNames + declaration.name)
    Elaborator.elaborate(declaration) match
      case Left(message) =>
        append(
          marked,
          DeclarationOutcome(
            declaration,
            DeclarationStatus.Rejected,
            None,
            None,
            None,
            Vector(ModuleDiagnostic.ElaborationFailure(message))
          )
        )
      case Right(elaborated) =>
        val holes =
          elaborated.declaredType.toVector.flatMap(TermAnalysis.collectHoles) ++
            TermAnalysis.collectHoles(elaborated.core)
        if holes.nonEmpty then
          append(
            marked,
            resolvedOutcome(
              declaration,
              elaborated,
              DeclarationStatus.Rejected,
              None,
              holes.map(ModuleDiagnostic.UnresolvedHole.apply)
            )
          )
        else
          val kernel = Kernel(marked.environment)
          elaborated.declaredType match
            case Some(expected) =>
              val report = kernel.check(elaborated.core, expected)
              if report.isOk then register(marked, declaration, elaborated, expected, None)
              else
                append(
                  marked,
                  resolvedOutcome(
                    declaration,
                    elaborated,
                    DeclarationStatus.Rejected,
                    None,
                    report.errors.map(ModuleDiagnostic.KernelFailure.apply)
                  )
                )
            case None =>
              kernel.infer(elaborated.core) match
                case Right(inferred) =>
                  register(marked, declaration, elaborated, inferred, Some(inferred))
                case Left(error @ KernelError.CannotInferLambda(_)) =>
                  append(
                    marked,
                    resolvedOutcome(
                      declaration,
                      elaborated,
                      DeclarationStatus.Unchecked,
                      None,
                      Vector(ModuleDiagnostic.KernelFailure(error))
                    )
                  )
                case Left(error) =>
                  append(
                    marked,
                    resolvedOutcome(
                      declaration,
                      elaborated,
                      DeclarationStatus.Rejected,
                      None,
                      Vector(ModuleDiagnostic.KernelFailure(error))
                    )
                  )

private def register(
  state: State,
  declaration: ParsedDeclaration,
  elaborated: tessera.elab.ElaboratedDeclaration,
  effectiveType: Term,
  inferredType: Option[Term]
): State =
  val kernelDeclaration =
    KernelDeclaration(declaration.name, effectiveType, elaborated.core)
  state.environment.define(kernelDeclaration) match
    case Right(nextEnvironment) =>
      append(
        state.copy(environment = nextEnvironment),
        resolvedOutcome(
          declaration,
          elaborated,
          DeclarationStatus.Accepted,
          inferredType,
          Vector.empty
        )
      )
    case Left(_) =>
      append(
        state,
        resolvedOutcome(
          declaration,
          elaborated,
          DeclarationStatus.Rejected,
          inferredType,
          Vector(ModuleDiagnostic.DuplicateDeclaration(declaration.name))
        )
      )

private def resolvedOutcome(
  declaration: ParsedDeclaration,
  elaborated: tessera.elab.ElaboratedDeclaration,
  status: DeclarationStatus,
  inferredType: Option[Term],
  diagnostics: Vector[ModuleDiagnostic]
): DeclarationOutcome =
  DeclarationOutcome(
    declaration,
    status,
    Some(elaborated.core),
    elaborated.declaredType,
    inferredType,
    diagnostics
  )

private def append(state: State, outcome: DeclarationOutcome): State =
  state.copy(outcomes = state.outcomes :+ outcome)
```

In `register`, the `Left(_)` branch is structurally unreachable after the
source-name guard; keep it deterministic and do not mutate the prior environment.

- [ ] **Step 6: Strengthen architecture guardrails**

Update `ArchitectureTest.scala` so the kernel, core, and meta forbidden lists
also include `tessera.compiler`, and add:

```scala
test("parser and elaboration layers must not depend on compiler orchestration") {
  Vector("tessera/parser", "tessera/elab").foreach { directory =>
    val files = readScalaFiles(directory)
    assert(files.nonEmpty)
    files.foreach { path =>
      val issues = forbiddenImports(readText(path), Seq("import tessera.compiler"))
      assertEquals(issues, Vector.empty, s"lower-layer file $path violates layering")
    }
  }
}
```

- [ ] **Step 7: Run module and architecture tests and verify GREEN**

```bash
runtime_dir=$(mktemp -d /tmp/tessera-runtime.XXXXXX)
XDG_RUNTIME_DIR="$runtime_dir" sbt \
  'testOnly tessera.compiler.ModuleCheckerTest tessera.architecture.ArchitectureTest'
```

Expected: both suites pass with zero failures.

- [ ] **Step 8: Commit module checking**

```bash
git add src/main/scala/tessera/compiler/ModuleChecker.scala \
  src/test/scala/tessera/compiler/ModuleCheckerTest.scala \
  src/test/scala/tessera/architecture/ArchitectureTest.scala
git commit -m "feat: check ordered declaration modules"
```

---

### Task 5: Module-aware CLI checking, evaluation, and traces

**Files:**
- Modify: `src/main/scala/tessera/cli/Commands.scala`
- Modify: `src/test/scala/tessera/cli/CommandsTest.scala`

**Interfaces:**
- Consumes: `ModuleChecker.check`, `CheckedModule.find/environment`, `DeclarationOutcome`, and `NameResolver.resolve`.
- Produces: shared module semantics for `check`, `eval`, and `eval --trace`.
- Preserves: show/hole commands and existing concise eval formatting.

- [ ] **Step 1: Write failing CLI integration tests**

Append to `CommandsTest.scala`:

```scala
private val constantsSource =
  """def id : (Pi A (Sort 0) (Pi x (Var A) (Var A))) =
    |  (Lam A (Sort 0) (Lam x (Var A) (Var x)))
    |def alias : (Pi A (Sort 0) (Pi x (Var A) (Var A))) = id""".stripMargin

test("check accepts ordered constants") {
  val path = writeExample(constantsSource)
  val output = withCapturedOut {
    TesseraCli.main(Array("check", path.toString))
  }
  assertEquals(output, "id: OK\nalias: OK\n")
}

test("check labels an unregistered unannotated lambda") {
  val path = writeExample("def localId = (Lam x (Sort 0) (Var x))")
  val output = withCapturedOut {
    TesseraCli.main(Array("check", path.toString))
  }
  assertEquals(
    output,
    "localId: no type annotation, not registered: " +
      "cannot infer type of lambda parameter `x`\n"
  )
}

test("eval unfolds a declaration alias and resolves constants inline") {
  val path = writeExample(constantsSource)
  val expected = "(lambda A: Type[0] => (lambda x: A => x))\n"

  val aliasOutput = withCapturedOut {
    TesseraCli.main(Array("eval", path.toString, "alias"))
  }
  val inlineOutput = withCapturedOut {
    TesseraCli.main(Array("eval", path.toString, "(App id (Sort 0))"))
  }

  assertEquals(aliasOutput, expected)
  assertEquals(inlineOutput, "(lambda x: Type[0] => x)\n")
}

test("eval trace shows a constant core and delta-normalized value") {
  val path = writeExample(constantsSource)
  val output = withCapturedOut {
    TesseraCli.main(Array("eval", "--trace", path.toString, "alias"))
  }

  assert(output.contains("  parsed: id\n"))
  assert(output.contains("  core: id\n"))
  assert(output.contains("  kernel: OK\n"))
  assert(output.contains(
    "  normalized: (lambda A: Type[0] => (lambda x: A => x))\n"
  ))
}

test("eval does not normalize a rejected declaration or unknown inline constant") {
  val path = writeExample(
    """def bad : (Sort 0) = missing
      |def ok : (Sort 0) = (Sort 0)""".stripMargin
  )

  val rejected = withCapturedOut {
    TesseraCli.main(Array("eval", path.toString, "bad"))
  }
  val unknown = withCapturedOut {
    TesseraCli.main(Array("eval", path.toString, "anotherMissing"))
  }

  assertEquals(rejected, "bad: FAIL\n  undefined constant: missing\n")
  assertEquals(
    unknown,
    "unknown declaration or evaluation error: undefined constant: anotherMissing\n"
  )
}

test("eval trace stops before normalizing an unknown inline constant") {
  val path = writeExample("")
  val output = withCapturedOut {
    TesseraCli.main(Array("eval", "--trace", path.toString, "missing"))
  }

  assert(output.contains("  inferred: unavailable: undefined constant: missing\n"))
  assert(output.contains("  kernel: FAIL\n"))
  assert(!output.contains("normalized"))
}
```

Keep the existing byte-for-byte concise eval test unchanged.

- [ ] **Step 2: Run CLI tests and verify RED**

```bash
runtime_dir=$(mktemp -d /tmp/tessera-runtime.XXXXXX)
XDG_RUNTIME_DIR="$runtime_dir" sbt 'testOnly tessera.cli.CommandsTest'
```

Expected: new tests fail because CLI commands still create an empty kernel and
independently elaborate selected declarations.

- [ ] **Step 3: Route `check` through `ModuleChecker`**

Add these imports:

```scala
import tessera.compiler.{
  CheckedModule,
  DeclarationOutcome,
  DeclarationStatus,
  ModuleChecker
}
import tessera.core.TermAnalysis
import tessera.elab.NameResolver
import tessera.kernel.KernelError
```

Then replace the successful parse branch of `runCheck`:

```scala
val module = ModuleChecker.check(declarations)
module.outcomes.foreach {
  case outcome if outcome.status == DeclarationStatus.Accepted =>
    println(s"${outcome.name}: OK")
  case outcome if outcome.status == DeclarationStatus.Unchecked =>
    val detail = outcome.diagnostics.map(_.message).mkString("; ")
    println(s"${outcome.name}: no type annotation, not registered: $detail")
  case outcome =>
    printOutcomeFailure(outcome)
}
if module.hasFailures then System.exit(1)
```

Add:

```scala
private def printOutcomeFailure(outcome: DeclarationOutcome): Unit =
  println(s"${outcome.name}: FAIL")
  outcome.diagnostics.foreach(diagnostic => println(s"  ${diagnostic.message}"))
```

- [ ] **Step 4: Make concise evaluation module-aware**

For the `eval` branch in `runReadOnly`, build `ModuleChecker.check(decls)` once
and pass it to `evalTarget`. Replace `evalTarget` with:

```scala
private def evalTarget(module: CheckedModule, target: String): Unit =
  val kernel = Kernel(module.environment)
  module.find(target) match
    case Some(outcome) if outcome.status == DeclarationStatus.Rejected =>
      printOutcomeFailure(outcome)
    case Some(outcome) =>
      println(renderTerm(kernel.normalize(outcome.core.get)))
    case None =>
      SimpleSyntaxParser.parseTermFromSource(target) match
        case Left(error) => println(s"unknown declaration or parse error: $error")
        case Right(parsed) =>
          val term = NameResolver.resolve(parsed)
          kernel.infer(term) match
            case Right(_) | Left(KernelError.CannotInferLambda(_)) =>
              println(renderTerm(kernel.normalize(term)))
            case Left(error) =>
              println(s"unknown declaration or evaluation error: ${error.message}")
```

- [ ] **Step 5: Make trace evaluation module-aware**

After parsing in `runEvalTrace`, call `ModuleChecker.check(declarations)` once.
For a declaration outcome:

- start with the existing header, `kind`, and parsed body;
- add `core` when `outcome.core` exists;
- for `Accepted` with `declaredType`, add `expected`, `kernel: OK`, and checked
  `normalized` using `Kernel(module.environment)`;
- for `Accepted` with `inferredType`, add `expected: <none>`, `inferred`,
  `kernel: inference only`, and `normalized (unchecked)`;
- for `Unchecked`, add `expected: <none>`, `inferred: unavailable: ...`,
  `kernel: not checked`, and `normalized (unchecked)`;
- for `Rejected`, add `kernel: FAIL` and indented diagnostics, and never add a
  normalized line; if the duplicate failed before core construction, add
  `module: FAIL` instead of `core`/`kernel`.

Implement the declaration branch through this pure line builder:

```scala
private def traceDeclaration(
  header: Vector[String],
  outcome: DeclarationOutcome,
  kernel: Kernel
): Vector[String] =
  val parsed = singleLine(renderParsedSyntax(outcome.declaration.value))
  val prefix = header ++ Vector("  kind: declaration", s"  parsed: $parsed")
  outcome.core match
    case None =>
      prefix ++ Vector("  module: FAIL") ++ diagnosticLines(outcome)
    case Some(core) =>
      val withCore = prefix :+ s"  core: ${renderTerm(core)}"
      outcome.status match
        case DeclarationStatus.Accepted =>
          outcome.declaredType match
            case Some(expected) =>
              withCore ++ Vector(
                s"  expected: ${renderTerm(expected)}",
                "  kernel: OK",
                s"  normalized: ${renderTerm(kernel.normalize(core))}"
              )
            case None =>
              withCore ++ Vector(
                "  expected: <none>",
                s"  inferred: ${renderTerm(outcome.inferredType.get)}",
                "  kernel: inference only",
                s"  normalized (unchecked): ${renderTerm(kernel.normalize(core))}"
              )
        case DeclarationStatus.Unchecked =>
          val detail = outcome.diagnostics.map(_.message).mkString("; ")
          withCore ++ Vector(
            "  expected: <none>",
            s"  inferred: unavailable: $detail",
            "  kernel: not checked",
            s"  normalized (unchecked): ${renderTerm(kernel.normalize(core))}"
          )
        case DeclarationStatus.Rejected =>
          val expected = outcome.declaredType
            .map(term => Vector(s"  expected: ${renderTerm(term)}"))
            .getOrElse(Vector("  expected: <none>"))
          withCore ++ expected ++ Vector("  kernel: FAIL") ++ diagnosticLines(outcome)

private def diagnosticLines(outcome: DeclarationOutcome): Vector[String] =
  outcome.diagnostics.map(diagnostic => s"    ${diagnostic.message}")
```

For an inline term, resolve with `NameResolver.resolve` and call the existing
unchecked trace helper with `Kernel(module.environment)`. Change that helper so
only `CannotInferLambda` emits unchecked normalization; any other error emits
`inferred: unavailable`, `kernel: FAIL`, and no normalized line.

Use this exact inline helper:

```scala
private def uncheckedEvaluation(kernel: Kernel, term: Term): Vector[String] =
  kernel.infer(term) match
    case Right(inferred) =>
      Vector(
        s"  inferred: ${renderTerm(inferred)}",
        "  kernel: inference only",
        s"  normalized (unchecked): ${renderTerm(kernel.normalize(term))}"
      )
    case Left(error @ KernelError.CannotInferLambda(_)) =>
      Vector(
        s"  inferred: unavailable: ${error.message}",
        "  kernel: not checked",
        s"  normalized (unchecked): ${renderTerm(kernel.normalize(term))}"
      )
    case Left(error) =>
      Vector(s"  inferred: unavailable: ${error.message}", "  kernel: FAIL")
```

- [ ] **Step 6: Replace private CLI hole recursion with `TermAnalysis`**

Change `showHoles` to call `TermAnalysis.collectHoles(Elaborator.toCore(...))`,
then remove the private `collectHoles`. This keeps `Constant` handling in the
shared exhaustive traversal without changing hole command output.

- [ ] **Step 7: Run CLI tests and verify GREEN**

```bash
runtime_dir=$(mktemp -d /tmp/tessera-runtime.XXXXXX)
XDG_RUNTIME_DIR="$runtime_dir" sbt 'testOnly tessera.cli.CommandsTest'
```

Expected: all CLI tests pass, including the prior exact trace and concise eval
contracts.

- [ ] **Step 8: Commit CLI integration**

```bash
git add src/main/scala/tessera/cli/Commands.scala \
  src/test/scala/tessera/cli/CommandsTest.scala
git commit -m "feat: evaluate ordered module constants"
```

---

### Task 6: Runnable example, documentation, and complete verification

**Files:**
- Create: `examples/Constants.tes`
- Modify: `README.md`
- Modify: `IMPLEMENTATION_STATUS.md`
- Modify: `docs/KERNEL.md`
- Modify: `docs/CORE_CALCULUS.md`
- Modify: `docs/ELABORATION.md`
- Modify: `docs/DIAGNOSTICS.md`
- Modify: `docs/ROADMAP.md`
- Modify: `docs/adr/16-reducibility.md`
- Modify: `src/test/scala/tessera/parser/ParserTest.scala`

**Interfaces:**
- Consumes: completed same-file constant semantics and CLI.
- Produces: a documented runnable fixture and accurate status claims.

- [ ] **Step 1: Add and parse-guard the constants example**

Create `examples/Constants.tes`:

```text
def id : (Pi A (Sort 0) (Pi x (Var A) (Var A))) =
  (Lam A (Sort 0) (Lam x (Var A) (Var x)))

def alias : (Pi A (Sort 0) (Pi x (Var A) (Var A))) = id

def inferredAlias = alias
```

Add `examples/Constants.tes` to the literal example path vector in
`ParserTest.scala` so deleting or breaking it fails the suite.

- [ ] **Step 2: Update README commands and capability notes**

Add these runnable commands:

```markdown
sbt "runMain tessera.Main check examples/Constants.tes"
sbt "runMain tessera.Main eval examples/Constants.tes alias"
sbt "runMain tessera.Main eval --trace examples/Constants.tes alias"
```

Add `examples/Constants.tes` to the corpus and describe only:

```markdown
- declarations may reference earlier accepted declarations in the same file;
- same-file `def` declarations are transparent during normalization;
- forward references, recursion, imports, and opacity are not implemented.
```

- [ ] **Step 3: Update kernel, elaboration, roadmap, status, and ADR 16**

Document these exact boundaries:

- `docs/KERNEL.md`: immutable environments, constant preflight, transparent
  delta normalization, cycle guard, and missing future opacity.
- `docs/CORE_CALCULUS.md`: `Term.Constant` as the global-reference node distinct
  from lexically scoped `Term.Var`.
- `docs/ELABORATION.md`: binder-aware `Var`/`Constant` resolution and ordered
  `ModuleChecker` orchestration.
- `docs/DIAGNOSTICS.md`: undefined constants, duplicate source declarations,
  unresolved-hole registration failures, and `Accepted`/`Unchecked`/`Rejected`.
- `docs/ROADMAP.md`: mark constants/environment/delta complete within Phase 1;
  leave the rest of Phase 1 and Phase 2 in progress.
- `IMPLEMENTATION_STATUS.md`: add a checked item for ordered same-file constants
  and transparent delta reduction; do not claim general modules.
- `docs/adr/16-reducibility.md`: change status from `pending` to `accepted`,
  record that registered MVP `def` declarations are transparent, and keep
  `opaque def`/`theorem` as future work.

- [ ] **Step 4: Run the documented commands**

```bash
runtime_dir=$(mktemp -d /tmp/tessera-runtime.XXXXXX)
XDG_RUNTIME_DIR="$runtime_dir" sbt \
  'runMain tessera.Main check examples/Constants.tes' \
  'runMain tessera.Main eval examples/Constants.tes alias' \
  'runMain tessera.Main eval --trace examples/Constants.tes alias'
```

Expected:

- `check` prints `id: OK`, `alias: OK`, and `inferredAlias: OK`;
- concise eval prints the identity lambda;
- trace prints `core: id`, `kernel: OK`, and the unfolded identity lambda.

- [ ] **Step 5: Run the complete suite**

```bash
runtime_dir=$(mktemp -d /tmp/tessera-runtime.XXXXXX)
XDG_RUNTIME_DIR="$runtime_dir" sbt test
```

Expected: every suite passes with zero failed or errored tests.

- [ ] **Step 6: Inspect the implementation against the spec**

```bash
git diff --check
git status --short
git log --oneline -10
rg -n "Constant|KernelEnvironment|ModuleChecker|delta|transparent" \
  src/main/scala src/test/scala README.md IMPLEMENTATION_STATUS.md docs examples
```

Confirm from the output that:

- every `Term` traversal handles `Constant`;
- lower layers do not import `tessera.compiler`;
- only accepted declarations appear in `KernelEnvironment.names`;
- rejected trace paths omit normalization;
- docs do not claim imports, recursion, opacity, or theorem support.

- [ ] **Step 7: Commit docs and the example**

```bash
git add examples/Constants.tes README.md IMPLEMENTATION_STATUS.md \
  docs/KERNEL.md docs/CORE_CALCULUS.md docs/ELABORATION.md \
  docs/DIAGNOSTICS.md docs/ROADMAP.md \
  docs/adr/16-reducibility.md src/test/scala/tessera/parser/ParserTest.scala
git commit -m "docs: demonstrate ordered module constants"
```

- [ ] **Step 8: Verify the committed tree is clean and green**

```bash
runtime_dir=$(mktemp -d /tmp/tessera-runtime.XXXXXX)
XDG_RUNTIME_DIR="$runtime_dir" sbt test
git diff --check HEAD^ HEAD
git status --short --branch
```

Expected: all tests pass, diff check is silent, and the feature branch has no
uncommitted files.
