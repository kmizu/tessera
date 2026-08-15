package tessera.compiler

import tessera.core.{Term, TermAnalysis}
import tessera.elab.{ElaboratedDeclaration, Elaborator}
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
      Printer.renderUnresolvedHole(hole.name, hole.expectedType)

  final case class KernelFailure(error: KernelError) extends ModuleDiagnostic:
    override val message: String = error.message

  final case class EnvironmentFailure(error: KernelEnvironmentError) extends ModuleDiagnostic:
    override val message: String = error.message

  final case class ElaborationFailure(detail: String) extends ModuleDiagnostic:
    override val message: String = s"elaboration failed: $detail"

final case class DeclarationOutcome(
  declaration: ParsedDeclaration,
  status: DeclarationStatus,
  core: Option[Term],
  declaredType: Option[Term],
  inferredType: Option[Term],
  diagnostics: Vector[ModuleDiagnostic]
):
  def name: String = declaration.name

final case class CheckedModule(
  outcomes: Vector[DeclarationOutcome],
  environment: KernelEnvironment
):
  def find(name: String): Option[DeclarationOutcome] = outcomes.find(_.name == name)
  def hasFailures: Boolean = outcomes.exists(_.status == DeclarationStatus.Rejected)

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
          checkElaborated(marked, declaration, elaborated)

  private def checkElaborated(
    state: State,
    declaration: ParsedDeclaration,
    elaborated: ElaboratedDeclaration
  ): State =
    val holes =
      elaborated.declaredType.toVector.flatMap(TermAnalysis.collectHoles) ++
        TermAnalysis.collectHoles(elaborated.core)
    if holes.nonEmpty then
      append(
        state,
        resolvedOutcome(
          declaration,
          elaborated,
          DeclarationStatus.Rejected,
          None,
          holes.map(ModuleDiagnostic.UnresolvedHole.apply)
        )
      )
    else
      val kernel = Kernel(state.environment)
      elaborated.declaredType match
        case Some(expected) => checkAnnotated(state, declaration, elaborated, kernel, expected)
        case None => checkUnannotated(state, declaration, elaborated, kernel)

  private def checkAnnotated(
    state: State,
    declaration: ParsedDeclaration,
    elaborated: ElaboratedDeclaration,
    kernel: Kernel,
    expected: Term
  ): State =
    val report = kernel.check(elaborated.core, expected)
    if report.isOk then register(state, declaration, elaborated, expected, None)
    else
      append(
        state,
        resolvedOutcome(
          declaration,
          elaborated,
          DeclarationStatus.Rejected,
          None,
          report.errors.map(ModuleDiagnostic.KernelFailure.apply)
        )
      )

  private def checkUnannotated(
    state: State,
    declaration: ParsedDeclaration,
    elaborated: ElaboratedDeclaration,
    kernel: Kernel
  ): State =
    kernel.infer(elaborated.core) match
      case Right(inferred) =>
        register(state, declaration, elaborated, inferred, Some(inferred))
      case Left(error @ KernelError.CannotInferLambda(_))
          if elaborated.core.isInstanceOf[Term.Lambda] =>
        // Only an outermost unannotated lambda is safely "unchecked"; a
        // CannotInferLambda from deeper inside a term may mask a genuine
        // type error, so those reject.
        append(
          state,
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
          state,
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
    elaborated: ElaboratedDeclaration,
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
      case Left(error) =>
        append(
          state,
          resolvedOutcome(
            declaration,
            elaborated,
            DeclarationStatus.Rejected,
            inferredType,
            Vector(ModuleDiagnostic.EnvironmentFailure(error))
          )
        )

  private def resolvedOutcome(
    declaration: ParsedDeclaration,
    elaborated: ElaboratedDeclaration,
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
