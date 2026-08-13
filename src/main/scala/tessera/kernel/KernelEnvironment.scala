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
