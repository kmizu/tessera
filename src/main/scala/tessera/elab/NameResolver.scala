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
