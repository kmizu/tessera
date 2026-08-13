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
    override val message: String =
      s"unresolved hole: ${name}" +
        expectedType.fold("")(expected => s" : ${Printer.render(expected)}")

  case class DeBruijnOutOfRange(index: Int) extends KernelError:
    override val message: String =
      s"de Bruijn index out of range: $index"

final case class KernelReport(errors: Vector[KernelError]):
  def isOk: Boolean = errors.isEmpty

type KernelCheckResult = KernelReport

class Kernel(
  val environment: KernelEnvironment = KernelEnvironment.empty
):
  def checkDecl(declarationType: Term, term: Term): KernelReport =
    check(term, declarationType, Vector.empty)

  def check(term: Term, expected: Term): KernelReport =
    check(term, expected, Vector.empty)

  def check(term: Term, expected: Term, ctx: Vector[(String, Term)]): KernelReport =
    validateConstants(Vector(term, expected) ++ ctx.map(_._2)) match
      case Left(error) => KernelReport(Vector(error))
      case Right(_) =>
        checkWithContext(term, expected, ctx) match
          case Right(_) => KernelReport(Vector.empty)
          case Left(error) => KernelReport(Vector(error))

  def infer(term: Term): Either[KernelError, Term] =
    validateConstants(Vector(term)).flatMap(_ => inferType(term, Vector.empty))

  def normalize(term: Term): Term = normalize(term, Set.empty)

  def isDefEq(left: Term, right: Term): Boolean =
    normalize(left) == normalize(right)

  def checkSort(term: Term): Either[KernelError, Unit] =
    checkSort(term, Vector.empty)

  def shift(term: Term, amount: Int, cutoff: Int = 0): Term =
    shiftByIndex(term, amount, cutoff)

  def substitute(index: Int, replacement: Term, term: Term): Term =
    substituteByIndex(term, index, replacement, 0)

  private def validateConstants(terms: Vector[Term]): Either[KernelError, Unit] =
    terms.iterator
      .flatMap(TermAnalysis.collectConstants)
      .find(name => !environment.contains(name))
      .map(name => Left(KernelError.UndefinedConstant(name)))
      .getOrElse(Right(()))

  private def normalize(term: Term, unfolding: Set[String]): Term = term match
    case Constant(name) if unfolding.contains(name) => Constant(name)
    case Constant(name) =>
      environment.lookup(name) match
        case Some(declaration) => normalize(declaration.value, unfolding + name)
        case None => Constant(name)
    case Hole(name, expectedType) =>
      Hole(name, expectedType.map(normalize(_, unfolding)))
    case App(function, argument) =>
      normalize(function, unfolding) match
        case Lambda(name, _, body) =>
          normalize(
            substituteByName(body, name, normalize(argument, unfolding)),
            unfolding
          )
        case normalizedFunction =>
          App(normalizedFunction, normalize(argument, unfolding))
    case Let(name, _, value, body) =>
      normalize(substituteByName(body, name, value), unfolding)
    case Pi(name, domain, codomain) =>
      Pi(name, normalize(domain, unfolding), normalize(codomain, unfolding))
    case Lambda(name, paramType, body) =>
      Lambda(name, normalize(paramType, unfolding), normalize(body, unfolding))
    case Constructor(name, fields) =>
      Constructor(name, fields.map(normalize(_, unfolding)))
    case leaf @ (Sort(_) | Var(_) | DBVar(_) | Builtin(_) | UnitLit()) => leaf

  private def inferType(term: Term, ctx: Vector[(String, Term)]): Either[KernelError, Term] =
    term match
      case s: Sort =>
        if s.level >= 0 then Right(Sort(s.level + 1))
        else Left(KernelError.ExpectedSort(s))

      case Var(name) =>
        ctx.find(_._1 == name).map(_._2)
          .toRight(KernelError.UndefinedVariable(name))

      case Constant(name) =>
        environment.lookup(name)
          .map(_.declaredType)
          .toRight(KernelError.UndefinedConstant(name))

      case DBVar(index) =>
        if index >= 0 && index < ctx.length then Right(ctx(index)._2)
        else Left(KernelError.DeBruijnOutOfRange(index))

      case Pi(_, domain, codomain) =>
        for
          _ <- checkSort(domain, ctx)
          _ <- checkSort(codomain, ctx :+ ("_", domain))
          codomainSort <- inferType(codomain, ctx :+ ("_", domain))
          _ <- ensureSort(codomainSort)
        yield codomainSort

      case l: Lambda =>
        Left(KernelError.CannotInferLambda(l.name))

      case App(function, argument) =>
        inferType(function, ctx).map(normalize) match
          case Right(Pi(name, domain, codomain)) =>
            checkWithContext(argument, domain, ctx) match
              case Right(_) => Right(substituteByName(codomain, name, argument))
              case Left(err) => Left(err)
          case Right(other) => Left(KernelError.NotAFunction(other))
          case Left(err) => Left(err)

      case Let(name, valueType, value, body) =>
        for
          _ <- checkWithContext(value, valueType, ctx)
          _ <- checkWithContext(valueType, Sort(0), ctx)
          checkedBody <- inferType(body, ctx :+ (name, valueType))
        yield checkedBody

      case c: Constructor =>
        Right(Sort(0))

      case Builtin(name) =>
        Right(if name == "Unit" then Sort(0) else Sort(0))

      case UnitLit() =>
        Right(Sort(0))

      case Hole(name, expectedType) =>
        expectedType match
          case Some(expected) => Right(expected)
          case None => Right(Sort(0))

  private def checkWithContext(term: Term, expectedTerm: Term, ctx: Vector[(String, Term)]): Either[KernelError, Unit] =
    val expected = normalize(expectedTerm)
    term match
      case l: Lambda =>
        expected match
          case Pi(name, paramType, bodyType) =>
            for
              _ <- checkSort(l.paramType, ctx)
              _ <- checkSort(paramType, ctx)
              _ <- if typesEqual(normalize(l.paramType), normalize(paramType)) then Right(())
                else Left(KernelError.NotTypeMismatch(paramType, l.paramType))
              _ <- checkWithContext(
                l.body,
                substituteByName(bodyType, name, Var(l.name)),
                ctx :+ (l.name, paramType)
              )
            yield ()
          case other =>
            Left(KernelError.NotAFunction(other))

      case s: Sort =>
        expected match
          case Sort(_) => Right(())
          case other => Left(KernelError.NotTypeMismatch(other, s))

      case DBVar(index) =>
        if index >= 0 && index < ctx.length then
          val foundType = ctx(index)._2
          for
            actual <- inferType(foundType, ctx)
            expectedInferred <- inferType(expected, ctx)
            _ <- if typesEqual(actual, expectedInferred) then Right(())
              else Left(KernelError.NotTypeMismatch(expectedInferred, actual))
          yield ()
        else
          Left(KernelError.DeBruijnOutOfRange(index))

      case Var(name) =>
        ctx.find(_._1 == name) match
          case Some((_, foundType)) =>
            inferType(foundType, ctx) match
              case Right(actualType) =>
                inferType(expected, ctx) match
                  case Right(expectedType) =>
                    if typesEqual(actualType, expectedType) then Right(())
                    else Left(KernelError.NotTypeMismatch(expectedType, foundType))
                  case Left(err) => Left(err)
              case Left(err) => Left(err)
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
        for
          _ <- checkSort(domain, ctx)
          _ <- checkSort(codomain, ctx :+ (name, domain))
          inferred <- inferType(p, ctx)
          _ <- if typesEqual(inferred, expected) then Right(()) else Left(KernelError.NotTypeMismatch(expected, inferred))
        yield ()

      case l @ Let(name, valueType, value, body) =>
        for
          _ <- checkSort(valueType, ctx)
          _ <- checkWithContext(value, valueType, ctx)
          _ <- checkWithContext(body, expected, ctx :+ (name, valueType))
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
    normalize(left) == normalize(right)

  private def substituteByName(term: Term, name: String, replacement: Term): Term =
    term match
      case h @ Hole(holeName, expectedType) =>
        Hole(holeName, expectedType.map(substituteByName(_, name, replacement)))
      case s @ Sort(_) => s
      case constant @ Constant(_) => constant
      case v @ Var(current) =>
        if current == name then replacement else v
      case DBVar(index) => DBVar(index)
      case p @ Pi(current, domain, codomain) =>
        val nextCodomain = if current == name then codomain else substituteByName(codomain, name, replacement)
        Pi(current, substituteByName(domain, name, replacement), nextCodomain)
      case l @ Lambda(current, paramType, body) =>
        val nextBody = if current == name then body else substituteByName(body, name, replacement)
        Lambda(current, substituteByName(paramType, name, replacement), nextBody)
      case Let(current, valueType, value, body) =>
        val nextBody = if current == name then body else substituteByName(body, name, replacement)
        Let(current, substituteByName(valueType, name, replacement), substituteByName(value, name, replacement), nextBody)
      case App(function, argument) =>
        App(substituteByName(function, name, replacement), substituteByName(argument, name, replacement))
      case Constructor(constructorName, fields) =>
        Constructor(constructorName, fields.map(substituteByName(_, name, replacement)))
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
