package tessera.kernel

import tessera.core.{Term, TermAnalysis}

final case class KernelDeclaration(name: String, declaredType: Term, value: Term)

sealed trait KernelEnvironmentError:
  def message: String

object KernelEnvironmentError:
  final case class DuplicateDefinition(name: String) extends KernelEnvironmentError:
    override val message: String = s"duplicate definition: $name"

  final case class UndefinedReference(name: String, reference: String) extends KernelEnvironmentError:
    override val message: String =
      s"definition $name references undefined constant: $reference"

  final case class UnresolvedHoleReference(name: String, hole: String) extends KernelEnvironmentError:
    override val message: String =
      s"definition $name contains unresolved hole: $hole"

final class KernelEnvironment private (
  private val declarations: Vector[KernelDeclaration],
  private val byName: Map[String, KernelDeclaration]
):
  def lookup(name: String): Option[KernelDeclaration] = byName.get(name)
  def contains(name: String): Boolean = byName.contains(name)
  def names: Vector[String] = declarations.map(_.name)

  // Definitions may only reference already-defined constants (keeping the
  // environment acyclic without relying on callers for well-foundedness) and
  // may not contain unresolved holes (invariant 2 at the environment boundary).
  def define(
    declaration: KernelDeclaration
  ): Either[KernelEnvironmentError, KernelEnvironment] =
    if contains(declaration.name) then
      Left(KernelEnvironmentError.DuplicateDefinition(declaration.name))
    else
      (TermAnalysis.collectHoles(declaration.declaredType).iterator ++
        TermAnalysis.collectHoles(declaration.value).iterator).nextOption() match
        case Some(hole) =>
          Left(KernelEnvironmentError.UnresolvedHoleReference(declaration.name, hole.name))
        case None =>
          (TermAnalysis.collectConstants(declaration.declaredType).iterator ++
            TermAnalysis.collectConstants(declaration.value).iterator)
            .find(reference => !contains(reference)) match
            case Some(missing) =>
              Left(KernelEnvironmentError.UndefinedReference(declaration.name, missing))
            case None =>
              Right(
                new KernelEnvironment(
                  declarations :+ declaration,
                  byName + (declaration.name -> declaration)
                )
              )

object KernelEnvironment:
  val empty: KernelEnvironment = new KernelEnvironment(Vector.empty, Map.empty)
