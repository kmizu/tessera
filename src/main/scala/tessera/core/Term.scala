package tessera.core

/**
 * Minimal shared term language for the first Tessera kernel slice.
 */
sealed trait Term

object Term:
  /**
   * Named-variable representation used by the early elaboration surface.
   */
  case class Var(name: String) extends Term

  /**
   * A global declaration reference, distinct from lexically scoped variables.
   */
  case class Constant(name: String) extends Term

  /**
   * De Bruijn index for future kernel-normal forms.
   */
  case class DBVar(index: Int) extends Term

  case class Sort(level: Int) extends Term
  case class Let(name: String, valueType: Term, value: Term, body: Term) extends Term
  case class Pi(name: String, domain: Term, codomain: Term) extends Term
  case class Lambda(name: String, paramType: Term, body: Term) extends Term
  case class App(function: Term, argument: Term) extends Term
  case class Constructor(name: String, fields: List[Term]) extends Term
  case class Builtin(name: String) extends Term
  case class UnitLit() extends Term
  final case class Hole(name: String, expectedType: Option[Term] = None) extends Term

  val UniverseType0: Term = Sort(0)
  val UniverseType1: Term = Sort(1)
