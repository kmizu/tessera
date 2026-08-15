package tessera.kernel

import tessera.core.Term
import tessera.core.TermAnalysis
import Term.*

sealed trait KernelError:
  def message: String

object KernelError:
  case class UndefinedConstant(name: String) extends KernelError:
    override val message: String = s"undefined constant: $name"

  case class UndefinedVariable(name: String) extends KernelError:
    override val message: String =
      s"undefined variable: $name"

  case class ExpectedSort(found: Term) extends KernelError:
    override val message: String =
      s"expected a universe sort, found: ${Printer.render(found)}"

  case class CannotInferLambda(param: String) extends KernelError:
    override val message: String =
      s"cannot infer type of lambda parameter `$param`"

  case class NotAFunction(actual: Term) extends KernelError:
    override val message: String =
      s"expected a function type, found: ${Printer.render(actual)}"

  case class NotTypeMismatch(expected: Term, found: Term) extends KernelError:
    override val message: String =
      s"type mismatch: expected ${Printer.render(expected)}, found ${Printer.render(found)}"

  case class UnresolvedHole(name: String, expectedType: Option[Term]) extends KernelError:
    override val message: String = Printer.renderUnresolvedHole(name, expectedType)

  case class DeBruijnOutOfRange(index: Int) extends KernelError:
    override val message: String =
      s"de Bruijn index out of range: $index"

final case class KernelReport(errors: Vector[KernelError]):
  def isOk: Boolean = errors.isEmpty

type KernelCheckResult = KernelReport

object Kernel:
  // Kept small enough that a diverging reduction hits the budget before the
  // JVM stack overflows.
  val DefaultNormalizationBudget: Int = 1000

  private final class NormalizationBudget(limit: Int):
    private var steps = 0
    def spend(): Unit =
      if limit >= 0 then
        steps += 1
        if steps > limit then throw new NormalizationBudget.ExhaustedException

  private object NormalizationBudget:
    final class ExhaustedException extends RuntimeException(null, null, false, false)
    val unlimited: NormalizationBudget = new NormalizationBudget(-1)

class Kernel(
  val environment: KernelEnvironment = KernelEnvironment.empty
):
  import Kernel.NormalizationBudget

  def checkDecl(declarationType: Term, term: Term): KernelReport =
    check(term, declarationType, Vector.empty)

  def check(term: Term, expected: Term): KernelReport =
    check(term, expected, Vector.empty)

  def check(term: Term, expected: Term, ctx: Vector[(String, Term)]): KernelReport =
    validateKernelInput(Vector(term, expected) ++ ctx.map(_._2)) match
      case Left(error) => KernelReport(Vector(error))
      case Right(_) =>
        checkWithContext(term, expected, ctx) match
          case Right(_) => KernelReport(Vector.empty)
          case Left(error) => KernelReport(Vector(error))

  def infer(term: Term): Either[KernelError, Term] =
    validateKernelInput(Vector(term)).flatMap(_ => inferType(term, Vector.empty))

  def normalize(term: Term): Term =
    normalize(term, Set.empty, NormalizationBudget.unlimited)

  // Bounded normalization for terms the kernel has not certified; returns
  // None when the work budget runs out instead of diverging.
  def normalizeWithBudget(
    term: Term,
    maxSteps: Int = Kernel.DefaultNormalizationBudget
  ): Option[Term] =
    // A non-positive budget is exhausted immediately, never unlimited.
    try Some(normalize(term, Set.empty, new NormalizationBudget(maxSteps.max(0))))
    catch case _: NormalizationBudget.ExhaustedException => None

  // A definitional-equality judgement is only meaningful on valid kernel
  // input; terms with holes or unknown constants are never equal.
  def isDefEq(left: Term, right: Term): Boolean =
    validateKernelInput(Vector(left, right)).isRight && typesEqual(left, right)

  def checkSort(term: Term): Either[KernelError, Unit] =
    validateKernelInput(Vector(term)).flatMap(_ => checkSort(term, Vector.empty))

  def shift(term: Term, amount: Int, cutoff: Int = 0): Term =
    shiftByIndex(term, amount, cutoff)

  def substitute(index: Int, replacement: Term, term: Term): Term =
    substituteByIndex(term, index, replacement, 0)

  private def validateKernelInput(terms: Vector[Term]): Either[KernelError, Unit] =
    terms.iterator
      .flatMap(TermAnalysis.collectConstants)
      .find(name => !environment.contains(name)) match
      case Some(name) => Left(KernelError.UndefinedConstant(name))
      case None =>
        terms.iterator.flatMap(TermAnalysis.collectHoles).nextOption() match
          case Some(hole) => Left(KernelError.UnresolvedHole(hole.name, hole.expectedType))
          case None => Right(())

  private def normalize(
    term: Term,
    unfolding: Set[String],
    budget: NormalizationBudget
  ): Term = term match
    case Constant(name) if unfolding.contains(name) => Constant(name)
    case Constant(name) =>
      environment.lookup(name) match
        case Some(declaration) =>
          budget.spend()
          normalize(declaration.value, unfolding + name, budget)
        case None => Constant(name)
    case Hole(name, expectedType) =>
      Hole(name, expectedType.map(normalize(_, unfolding, budget)))
    case App(function, argument) =>
      val (normalizedFunction, functionUnfolding) =
        normalizeFunction(function, unfolding, budget)
      normalizedFunction match
        case Lambda(name, _, body) =>
          budget.spend()
          normalize(
            substituteByName(body, name, normalize(argument, unfolding, budget), budget),
            functionUnfolding,
            budget
          )
        case other =>
          App(other, normalize(argument, unfolding, budget))
    case Let(name, _, value, body) =>
      budget.spend()
      normalize(substituteByName(body, name, value, budget), unfolding, budget)
    case Pi(name, domain, codomain) =>
      Pi(name, normalize(domain, unfolding, budget), normalize(codomain, unfolding, budget))
    case Lambda(name, paramType, body) =>
      Lambda(name, normalize(paramType, unfolding, budget), normalize(body, unfolding, budget))
    case Constructor(name, fields) =>
      Constructor(name, fields.map(normalize(_, unfolding, budget)))
    case leaf @ (Sort(_) | Var(_) | DBVar(_) | Builtin(_) | UnitLit()) => leaf

  private def normalizeFunction(
    term: Term,
    unfolding: Set[String],
    budget: NormalizationBudget
  ): (Term, Set[String]) = term match
    case Constant(name) if unfolding.contains(name) =>
      Constant(name) -> unfolding
    case Constant(name) =>
      environment.lookup(name) match
        case Some(declaration) =>
          budget.spend()
          normalizeFunction(declaration.value, unfolding + name, budget)
        case None => Constant(name) -> unfolding
    case Let(name, _, value, body) =>
      budget.spend()
      normalizeFunction(substituteByName(body, name, value, budget), unfolding, budget)
    case App(function, argument) =>
      val (normalizedFunction, functionUnfolding) =
        normalizeFunction(function, unfolding, budget)
      normalizedFunction match
        case Lambda(name, _, body) =>
          budget.spend()
          normalizeFunction(
            substituteByName(body, name, normalize(argument, unfolding, budget), budget),
            functionUnfolding,
            budget
          )
        case other =>
          App(other, normalize(argument, unfolding, budget)) -> functionUnfolding
    case other => normalize(other, unfolding, budget) -> unfolding

  private def inferType(term: Term, ctx: Vector[(String, Term)]): Either[KernelError, Term] =
    term match
      case s: Sort =>
        if s.level >= 0 then Right(Sort(s.level + 1))
        else Left(KernelError.ExpectedSort(s))

      case Var(name) =>
        ctx.reverseIterator.find(_._1 == name).map(_._2)
          .toRight(KernelError.UndefinedVariable(name))

      case Constant(name) =>
        environment.lookup(name)
          .map(_.declaredType)
          .toRight(KernelError.UndefinedConstant(name))

      case DBVar(index) =>
        // DBVar(0) is the innermost binder; ctx grows outermost-first. The
        // stored type is shifted into the current scope.
        if index >= 0 && index < ctx.length then
          Right(shiftByIndex(ctx(ctx.length - 1 - index)._2, index + 1, 0))
        else Left(KernelError.DeBruijnOutOfRange(index))

      case Pi(name, domain, codomain) =>
        val (freshCodomain, extendedCtx) = extendContext(name, domain, codomain, ctx)
        for
          _ <- checkSort(domain, ctx)
          _ <- checkSort(freshCodomain, extendedCtx)
          codomainSort <- inferType(freshCodomain, extendedCtx)
          _ <- ensureSort(codomainSort)
        yield codomainSort

      case l: Lambda =>
        Left(KernelError.CannotInferLambda(l.name))

      case App(function, argument) =>
        inferType(function, ctx).map(normalize) match
          case Right(Pi(name, domain, codomain)) =>
            checkWithContext(argument, domain, ctx) match
              case Right(_) =>
                Right(substituteByName(codomain, name, argument, NormalizationBudget.unlimited))
              case Left(err) => Left(err)
          case Right(other) => Left(KernelError.NotAFunction(other))
          case Left(err) => Left(err)

      case Let(name, valueType, value, body) =>
        val (freshBody, extendedCtx) = extendContext(name, valueType, body, ctx)
        for
          _ <- checkWithContext(value, valueType, ctx)
          _ <- checkWithContext(valueType, Sort(0), ctx)
          checkedBody <- inferType(freshBody, extendedCtx)
        yield checkedBody

      case Constructor(_, fields) =>
        // Fields must themselves be typable so an ill-typed or diverging term
        // cannot hide inside an opaque constructor; the constructor itself
        // stays typed Sort(0) under the MVP base-value rules.
        fields
          .foldLeft[Either[KernelError, Unit]](Right(())) { (accumulated, field) =>
            accumulated.flatMap(_ => inferType(field, ctx).map(_ => ()))
          }
          .map(_ => Sort(0))

      case Builtin(_) =>
        Right(Sort(0))

      case UnitLit() =>
        Right(Sort(0))

      case Hole(name, expectedType) =>
        // Holes are rejected by the input preflight; refuse them here as well
        // so no internal path can type a metavariable (invariant 2).
        Left(KernelError.UnresolvedHole(name, expectedType))

  private def checkWithContext(term: Term, expectedTerm: Term, ctx: Vector[(String, Term)]): Either[KernelError, Unit] =
    val expected = normalize(expectedTerm)
    term match
      case l: Lambda =>
        expected match
          case Pi(name, paramType, bodyType) =>
            val (freshBody, extendedCtx) = extendContext(l.name, paramType, l.body, ctx)
            val binder = extendedCtx.last._1
            for
              _ <- checkSort(l.paramType, ctx)
              _ <- checkSort(paramType, ctx)
              _ <- if typesEqual(l.paramType, paramType) then Right(())
                else Left(KernelError.NotTypeMismatch(paramType, l.paramType))
              _ <- checkWithContext(
                freshBody,
                substituteByName(bodyType, name, Var(binder), NormalizationBudget.unlimited),
                extendedCtx
              )
            yield ()
          case other =>
            Left(KernelError.NotAFunction(other))

      case s: Sort =>
        expected match
          case Sort(_) => Right(())
          case other => Left(KernelError.NotTypeMismatch(other, s))

      case DBVar(index) =>
        // DBVar(0) is the innermost binder; ctx grows outermost-first. The
        // stored type is shifted into the current scope.
        if index >= 0 && index < ctx.length then
          val foundType = shiftByIndex(ctx(ctx.length - 1 - index)._2, index + 1, 0)
          if typesEqual(foundType, expected) then Right(())
          else Left(KernelError.NotTypeMismatch(expected, foundType))
        else
          Left(KernelError.DeBruijnOutOfRange(index))

      case Var(name) =>
        ctx.reverseIterator.find(_._1 == name) match
          case Some((_, foundType)) =>
            if typesEqual(foundType, expected) then Right(())
            else Left(KernelError.NotTypeMismatch(expected, foundType))
          case None => Left(KernelError.UndefinedVariable(name))

      case constant @ Constant(_) =>
        inferType(constant, ctx) match
          case Right(actual) if typesEqual(actual, expected) => Right(())
          case Right(actual) => Left(KernelError.NotTypeMismatch(expected, actual))
          case Left(error) => Left(error)

      case app @ App(_, _) =>
        inferType(app, ctx) match
          case Right(actual) if typesEqual(actual, expected) => Right(())
          case Right(actual) => Left(KernelError.NotTypeMismatch(expected, actual))
          case Left(err) => Left(err)

      case p @ Pi(name, domain, codomain) =>
        val (freshCodomain, extendedCtx) = extendContext(name, domain, codomain, ctx)
        for
          _ <- checkSort(domain, ctx)
          _ <- checkSort(freshCodomain, extendedCtx)
          inferred <- inferType(p, ctx)
          _ <- if typesEqual(inferred, expected) then Right(()) else Left(KernelError.NotTypeMismatch(expected, inferred))
        yield ()

      case Let(name, valueType, value, body) =>
        val (freshBody, extendedCtx) = extendContext(name, valueType, body, ctx)
        for
          _ <- checkSort(valueType, ctx)
          _ <- checkWithContext(value, valueType, ctx)
          _ <- checkWithContext(freshBody, expected, extendedCtx)
        yield ()

      case c @ Constructor(_, _) =>
        inferType(c, ctx) match
          case Right(actual) if typesEqual(actual, expected) => Right(())
          case Right(actual) => Left(KernelError.NotTypeMismatch(expected, actual))
          case Left(err) => Left(err)

      case Builtin(name) =>
        if expected == Builtin(name) then Right(()) else Left(KernelError.NotTypeMismatch(expected, Builtin(name)))

      case UnitLit() =>
        if expected == Constructor("Unit", Nil) || expected == Sort(0) then Right(())
        else Left(KernelError.NotTypeMismatch(expected, Builtin("Unit")))

      case Hole(name, expectedType) =>
        expectedType match
          case Some(annotation) =>
            if typesEqual(expected, annotation) then Left(KernelError.UnresolvedHole(name, Some(annotation)))
            else Left(KernelError.NotTypeMismatch(expected, annotation))
          case None =>
            Left(KernelError.UnresolvedHole(name, Some(expected)))

  private def checkSort(term: Term, ctx: Vector[(String, Term)]): Either[KernelError, Unit] =
    inferType(term, ctx).flatMap:
      case Sort(_) => Right(())
      case other => Left(KernelError.ExpectedSort(other))

  private def ensureSort(term: Term): Either[KernelError, Unit] =
    term match
      case _: Sort => Right(())
      case other => Left(KernelError.ExpectedSort(other))

  private def typesEqual(left: Term, right: Term): Boolean =
    alphaEqual(normalize(left), normalize(right))

  private def alphaEqual(left: Term, right: Term): Boolean =
    def go(l: Term, r: Term, leftNames: List[String], rightNames: List[String]): Boolean =
      (l, r) match
        case (Var(a), Var(b)) =>
          val leftIndex = leftNames.indexOf(a)
          val rightIndex = rightNames.indexOf(b)
          if leftIndex >= 0 || rightIndex >= 0 then leftIndex == rightIndex else a == b
        case (Sort(a), Sort(b)) => a == b
        case (Constant(a), Constant(b)) => a == b
        case (DBVar(a), DBVar(b)) => a == b
        case (Pi(ln, ld, lc), Pi(rn, rd, rc)) =>
          go(ld, rd, leftNames, rightNames) && go(lc, rc, ln :: leftNames, rn :: rightNames)
        case (Lambda(ln, lt, lb), Lambda(rn, rt, rb)) =>
          go(lt, rt, leftNames, rightNames) && go(lb, rb, ln :: leftNames, rn :: rightNames)
        case (Let(ln, lvt, lv, lb), Let(rn, rvt, rv, rb)) =>
          go(lvt, rvt, leftNames, rightNames) &&
            go(lv, rv, leftNames, rightNames) &&
            go(lb, rb, ln :: leftNames, rn :: rightNames)
        case (App(lf, la), App(rf, ra)) =>
          go(lf, rf, leftNames, rightNames) && go(la, ra, leftNames, rightNames)
        case (Constructor(lname, lfields), Constructor(rname, rfields)) =>
          lname == rname && lfields.length == rfields.length &&
            lfields.lazyZip(rfields).forall((lf, rf) => go(lf, rf, leftNames, rightNames))
        case (Builtin(a), Builtin(b)) => a == b
        case (UnitLit(), UnitLit()) => true
        case (Hole(ln, lt), Hole(rn, rt)) =>
          ln == rn && ((lt, rt) match
            case (Some(a), Some(b)) => go(a, b, leftNames, rightNames)
            case (None, None) => true
            case _ => false)
        case _ => false
    go(left, right, Nil, Nil)

  // Keeps context binder names unique: a shadowing binder is renamed (and its
  // body occurrences with it) before extension, so a stored context type can
  // never alias a later binder of the same name.
  private def extendContext(
    binder: String,
    binderType: Term,
    body: Term,
    ctx: Vector[(String, Term)]
  ): (Term, Vector[(String, Term)]) =
    if ctx.exists(_._1 == binder) then
      val avoid = ctx.map(_._1).toSet ++
        TermAnalysis.variableNames(body) ++
        TermAnalysis.variableNames(binderType) + binder
      val fresh = freshName(binder, avoid)
      (
        substituteByName(body, binder, Var(fresh), NormalizationBudget.unlimited),
        ctx :+ (fresh, binderType)
      )
    else (body, ctx :+ (binder, binderType))

  private def freshName(base: String, avoid: Set[String]): String =
    Iterator
      .from(1)
      .map(index => s"$base$$$index")
      .find(candidate => !avoid.contains(candidate))
      .get

  // Renames `binder` when it would capture a free variable of `replacement`.
  // The avoid set includes `substituted` (the variable currently being
  // replaced) so a fresh name can never be substituted away again downstream.
  private def avoidCapture(
    binder: String,
    body: Term,
    replacement: Term,
    substituted: String,
    budget: NormalizationBudget
  ): (String, Term) =
    if TermAnalysis.freeVars(replacement).contains(binder) then
      val avoid =
        TermAnalysis.variableNames(replacement) ++
          TermAnalysis.variableNames(body) + binder + substituted
      val fresh = freshName(binder, avoid)
      (fresh, substituteByName(body, binder, Var(fresh), budget))
    else (binder, body)

  // Spends one budget unit per visited node so bounded normalization also
  // bounds the total work of size-exploding substitutions, not just the
  // number of reduction steps.
  private def substituteByName(
    term: Term,
    name: String,
    replacement: Term,
    budget: NormalizationBudget
  ): Term =
    budget.spend()
    term match
      case Hole(holeName, expectedType) =>
        Hole(holeName, expectedType.map(substituteByName(_, name, replacement, budget)))
      case s @ Sort(_) => s
      case constant @ Constant(_) => constant
      case v @ Var(current) =>
        if current == name then replacement else v
      case DBVar(index) => DBVar(index)
      case Pi(current, domain, codomain) =>
        val nextDomain = substituteByName(domain, name, replacement, budget)
        if current == name then Pi(current, nextDomain, codomain)
        else
          val (binder, adjusted) = avoidCapture(current, codomain, replacement, name, budget)
          Pi(binder, nextDomain, substituteByName(adjusted, name, replacement, budget))
      case Lambda(current, paramType, body) =>
        val nextParamType = substituteByName(paramType, name, replacement, budget)
        if current == name then Lambda(current, nextParamType, body)
        else
          val (binder, adjusted) = avoidCapture(current, body, replacement, name, budget)
          Lambda(binder, nextParamType, substituteByName(adjusted, name, replacement, budget))
      case Let(current, valueType, value, body) =>
        val nextValueType = substituteByName(valueType, name, replacement, budget)
        val nextValue = substituteByName(value, name, replacement, budget)
        if current == name then Let(current, nextValueType, nextValue, body)
        else
          val (binder, adjusted) = avoidCapture(current, body, replacement, name, budget)
          Let(binder, nextValueType, nextValue, substituteByName(adjusted, name, replacement, budget))
      case App(function, argument) =>
        App(
          substituteByName(function, name, replacement, budget),
          substituteByName(argument, name, replacement, budget)
        )
      case Constructor(constructorName, fields) =>
        Constructor(constructorName, fields.map(substituteByName(_, name, replacement, budget)))
      case b @ Builtin(_) => b
      case UnitLit() => UnitLit()

  private def substituteByIndex(term: Term, index: Int, replacement: Term, level: Int): Term =
    term match
      case h @ Hole(holeName, expectedType) =>
        Hole(holeName, expectedType.map(field => substituteByIndex(field, index, replacement, level)))
      case s @ Sort(_) => s
      case constant @ Constant(_) => constant
      case db @ DBVar(i) =>
        if i == index + level then
          shiftByIndex(replacement, level, 0)
        else
          db
      case Var(_) => term
      case p: Pi =>
        p.copy(
          domain = substituteByIndex(p.domain, index, replacement, level),
          codomain = substituteByIndex(p.codomain, index, replacement, level + 1)
        )
      case l: Lambda =>
        l.copy(
          paramType = substituteByIndex(l.paramType, index, replacement, level),
          body = substituteByIndex(l.body, index, replacement, level + 1)
        )
      case Let(name, valueType, value, body) =>
        Let(
          name,
          substituteByIndex(valueType, index, replacement, level),
          substituteByIndex(value, index, replacement, level),
          substituteByIndex(body, index, replacement, level + 1)
        )
      case App(function, argument) =>
        App(
          substituteByIndex(function, index, replacement, level),
          substituteByIndex(argument, index, replacement, level)
        )
      case Constructor(constructorName, fields) =>
        Constructor(constructorName, fields.map(field => substituteByIndex(field, index, replacement, level)))
      case b @ Builtin(_) => b
      case UnitLit() => UnitLit()

  private def shiftByIndex(term: Term, amount: Int, cutoff: Int): Term =
    term match
      case h @ Hole(holeName, expectedType) =>
        Hole(holeName, expectedType.map(field => shiftByIndex(field, amount, cutoff)))
      case s @ Sort(_) => s
      case constant @ Constant(_) => constant
      case DBVar(index) => if index >= cutoff then DBVar(index + amount) else DBVar(index)
      case v @ Var(_) => v
      case p @ Pi(name, domain, codomain) =>
        Pi(name, shiftByIndex(domain, amount, cutoff), shiftByIndex(codomain, amount, cutoff + 1))
      case l @ Lambda(name, paramType, body) =>
        Lambda(name, shiftByIndex(paramType, amount, cutoff), shiftByIndex(body, amount, cutoff + 1))
      case Let(name, valueType, value, body) =>
        Let(name, shiftByIndex(valueType, amount, cutoff), shiftByIndex(value, amount, cutoff), shiftByIndex(body, amount, cutoff + 1))
      case App(function, argument) =>
        App(shiftByIndex(function, amount, cutoff), shiftByIndex(argument, amount, cutoff))
      case Constructor(constructorName, fields) =>
        Constructor(constructorName, fields.map(field => shiftByIndex(field, amount, cutoff)))
      case b @ Builtin(_) => b
      case UnitLit() => UnitLit()

object Printer:
  def renderUnresolvedHole(name: String, expectedType: Option[Term]): String =
    s"unresolved hole: $name" + expectedType.fold("")(expected => s" : ${render(expected)}")

  def render(term: Term): String = term match
    case Sort(level) => s"Type[$level]"
    case DBVar(index) => s"#$index"
    case Constant(name) => name
    case Var(name) => name
    case Let(name, valueType, value, body) =>
      s"let $name: ${render(valueType)} = ${render(value)} in ${render(body)}"
    case Pi(name, domain, codomain) =>
      s"($name: ${render(domain)}) -> ${render(codomain)}"
    case Lambda(name, paramType, body) =>
      s"(lambda $name: ${render(paramType)} => ${render(body)})"
    case App(function, argument) =>
      s"${render(function)} ${render(argument)}"
    case Constructor(name, fields) =>
      if fields.isEmpty then name else s"$name(${fields.map(render).mkString(", ")})"
    case Builtin(name) => name
    case UnitLit() => "Unit"
    case Hole(name, expectedType) =>
      val renderedName = if name == "_" then "_" else s"?$name"
      expectedType match
        case Some(expected) => s"$renderedName : ${render(expected)}"
        case None => renderedName
