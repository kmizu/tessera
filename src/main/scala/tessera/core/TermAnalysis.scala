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
