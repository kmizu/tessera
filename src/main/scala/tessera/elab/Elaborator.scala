package tessera.elab

import tessera.core.Term
import tessera.parser.SimpleSyntaxParser.{ParsedDeclaration, ParsedDeclarationBody, ParsedSynthDo, ParsedCoreTerm}
import tessera.parser.SimpleSyntaxParser.ParsedParam
import tessera.parser.SimpleSyntaxParser.ParsedBind
import tessera.parser.SimpleSyntaxParser.ParsedLet

final case class ElaboratedDeclaration(name: String, declaredType: Option[Term], core: Term)

object Elaborator:
  def toCore(body: ParsedDeclarationBody): Term = body match
    case ParsedCoreTerm(term) =>
      term
    case ParsedSynthDo(statements, yieldTerm) =>
      statements.foldRight(yieldTerm) { (statement, body) =>
        statement match
          case ParsedParam(name, expectedType) =>
            Term.Lambda(name, expectedType, body)
          case ParsedLet(name, term) =>
            Term.Let(name, Term.Sort(0), term, body)
          case ParsedBind(name, term) =>
            Term.Let(name, Term.Sort(0), term, body)
          case _ =>
            body
      }

  def elaborate(decl: ParsedDeclaration): Either[String, ElaboratedDeclaration] =
    Right(ElaboratedDeclaration(decl.name, decl.expectedType, toCore(decl.value)))

  def elaborateAll(decls: Vector[ParsedDeclaration]): Either[String, Vector[ElaboratedDeclaration]] =
    decls.foldLeft[Either[String, Vector[ElaboratedDeclaration]]](Right(Vector.empty)) {
      case (Right(acc), decl) =>
        elaborate(decl).map(elab => acc :+ elab)
      case (left, _) => left
    }
