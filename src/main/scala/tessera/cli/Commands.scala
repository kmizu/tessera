package tessera.cli

import tessera.compiler.{
  CheckedModule,
  DeclarationOutcome,
  DeclarationStatus,
  ModuleChecker
}
import tessera.core.Term
import tessera.core.TermAnalysis
import tessera.kernel.{Kernel, KernelError}
import tessera.parser.SimpleSyntaxParser
import tessera.elab.{Elaborator, NameResolver}
import Term.*
import SimpleSyntaxParser.ParsedDeclarationBody
import SimpleSyntaxParser.ParsedSynthDo
import SimpleSyntaxParser.ParsedParam
import SimpleSyntaxParser.ParsedBind
import SimpleSyntaxParser.ParsedLet
import SimpleSyntaxParser.ParsedCoreTerm

object TesseraCli:
  private val supported = Set(
    "check",
    "eval",
    "holes",
    "show-term",
    "show-core",
    "show-synth",
    "show-desugared",
    "trace-synth"
  )

  def main(args: Array[String]): Unit =
    args.toList match
      case Nil =>
        printUsage()
      case "check" :: file :: Nil =>
        runCheck(file)
      case "check" :: Nil =>
        println("usage: tessera check <file.tes>")
      case "eval" :: "--trace" :: file :: targetParts if targetParts.nonEmpty =>
        runEvalTrace(file, targetParts.mkString(" "))
      case "eval" :: "--trace" :: _ =>
        println("usage: tessera eval --trace <file.tes> <decl or expression>")
      case cmd :: rest if supported.contains(cmd) =>
        if rest.isEmpty then
          val argHint =
            if cmd == "eval" then "<decl or expression>"
            else "[decl]"
          println(s"usage: tessera $cmd <file.tes> $argHint")
        else
          runReadOnly(cmd, rest.toVector)
      case other :: _ =>
        println(s"unknown command: $other")
        printUsage()

  private def printUsage(): Unit =
    println("Usage: tessera <command> [arguments]")
    println("Commands:")
    println("  check <file.tes>")
    println("  eval <file.tes> <decl or expression>")
    println("  eval --trace <file.tes> <decl or expression>")
    println("  holes <file.tes>")
    println("  show-term <file.tes> [decl]")
    println("  show-core <file.tes> [decl]")
    println("  show-synth <file.tes> [decl]")
    println("  show-desugared <file.tes> [decl]")
    println("  trace-synth <file.tes> [decl]")

  private def runCheck(file: String): Unit =
    parseModule(file) match
      case Left(error) =>
        println(s"parse error: $error")
      case Right(decls) =>
        val module = ModuleChecker.check(decls)
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

  private def printOutcomeFailure(outcome: DeclarationOutcome): Unit =
    println(s"${outcome.name}: FAIL")
    outcome.diagnostics.foreach(diagnostic => println(s"  ${diagnostic.message}"))

  private def runReadOnly(command: String, args: Vector[String]): Unit =
    val file = args.head
    val declName = args.lift(1)
    val evalTargetExpr = args.drop(1).mkString(" ")
    parseModule(file) match
      case Left(error) =>
        println(s"parse error: $error")
      case Right(decls) =>
        command match
          case "show-term" =>
            showForDecl(decls, declName, decl => renderParsedSyntax(decl.value))
          case "show-core" =>
            showForDecl(decls, declName, decl => renderTerm(Elaborator.toCore(decl.value)))
          case "show-synth" =>
            showForDecl(decls, declName, decl => renderParsedSyntax(decl.value))
          case "show-desugared" =>
            showForDecl(decls, declName, decl => renderTerm(Elaborator.toCore(decl.value)))
          case "trace-synth" =>
            showForDecl(decls, declName, renderTrace)
          case "eval" =>
            if args.length < 2 then
              println("usage: tessera eval <file.tes> <decl or expression>")
            else
              evalTarget(ModuleChecker.check(decls), evalTargetExpr)
          case "holes" =>
            showHoles(decls, declName)
          case _ =>
            println(s"$command not implemented in this stage.")

  private def parseModule(path: String): Either[SimpleSyntaxParser.ParseError, Vector[SimpleSyntaxParser.ParsedDeclaration]] =
    SimpleSyntaxParser.parseFile(path)

  private def showForDecl(
    declarations: Vector[SimpleSyntaxParser.ParsedDeclaration],
    declNameOpt: Option[String],
    render: SimpleSyntaxParser.ParsedDeclaration => String
  ): Unit =
    declNameOpt match
      case None =>
        if declarations.size == 1 then
          println(render(declarations.head))
        else
          declarations.foreach(decl => println(s"${decl.name}: ${render(decl)}"))
      case Some(target) =>
        declarations.find(_.name == target) match
          case None =>
            println(s"unknown declaration: $target")
          case Some(decl) =>
            println(render(decl))

  private def evalTarget(module: CheckedModule, target: String): Unit =
    val kernel = Kernel(module.environment)
    module.find(target) match
      case Some(outcome) if outcome.status == DeclarationStatus.Rejected =>
        printOutcomeFailure(outcome)
      case Some(outcome) =>
        println(renderTerm(kernel.normalize(outcome.core.get)))
      case None =>
        SimpleSyntaxParser.parseTermFromSource(target) match
          case Left(error) =>
            println(s"unknown declaration or parse error: $error")
          case Right(parsed) =>
            val term = NameResolver.resolve(parsed)
            kernel.infer(term) match
              case Right(_) | Left(KernelError.CannotInferLambda(_)) =>
                println(renderTerm(kernel.normalize(term)))
              case Left(error) =>
                println(s"unknown declaration or evaluation error: ${error.message}")

  private def evalTraceHeader(file: String, target: String): Vector[String] =
    Vector("eval trace:", s"  file: $file", s"  target: $target")

  private def printEvalTrace(lines: Vector[String]): Unit =
    println(lines.mkString("\n"))

  private def singleLine(value: String): String =
    value.linesIterator.mkString(" ")

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

  private def runEvalTrace(file: String, target: String): Unit =
    val header = evalTraceHeader(file, target)
    parseModule(file) match
      case Left(error) =>
        printEvalTrace(header :+ s"  module parse: FAIL: $error")
      case Right(declarations) =>
        val module = ModuleChecker.check(declarations)
        val kernel = Kernel(module.environment)
        module.find(target) match
          case Some(outcome) =>
            printEvalTrace(traceDeclaration(header, outcome, kernel))
          case None =>
            SimpleSyntaxParser.parseTermFromSource(target) match
              case Left(error) =>
                printEvalTrace(
                  header ++ Vector("  kind: expression", s"  expression parse: FAIL: $error")
                )
              case Right(term) =>
                val resolved = NameResolver.resolve(term)
                val rendered = renderTerm(resolved)
                printEvalTrace(
                  header ++ Vector(
                    "  kind: expression",
                    s"  parsed: $rendered",
                    s"  core: $rendered",
                    "  expected: <none>"
                  ) ++ uncheckedEvaluation(kernel, resolved)
                )

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

  private def renderTerm(term: Term): String =
    tessera.kernel.Printer.render(term)

  private def renderParsedSyntax(value: ParsedDeclarationBody): String =
    value match
      case ParsedCoreTerm(term) =>
        renderTerm(term)
      case synth: ParsedSynthDo =>
        renderSynthDo(synth)

  private def renderTrace(decl: SimpleSyntaxParser.ParsedDeclaration): String =
    decl.value match
      case ParsedCoreTerm(_) =>
        "trace-synth is only available for synth/do declarations"
      case synth: ParsedSynthDo =>
        val otherTraces = synth.statements.zipWithIndex.collect {
          case (SimpleSyntaxParser.ParsedLet(name, term), index) =>
            s"  statement ${index + 1}: let $name = ${renderTerm(term)}"
          case (SimpleSyntaxParser.ParsedBind(name, term), index) =>
            s"  statement ${index + 1}: $name <- ${renderTerm(term)}"
          case (SimpleSyntaxParser.ParsedParam(name, expectedType), index) =>
            s"  statement ${index + 1}: param $name : ${renderTerm(expectedType)}"
        }
        val statementsCount = synth.statements.size
        val yieldTrace = s"  statement ${statementsCount + 1}: yield ${renderTerm(synth.yieldTerm)}"
        val desugared = renderTerm(Elaborator.toCore(synth))
        s"""trace-synth ${decl.name}:
${otherTraces.mkString("\n")}
$yieldTrace
desugared:
  $desugared"""

  private def renderSynthDo(synth: ParsedSynthDo): String =
    val statements = synth.statements.map {
      case ParsedParam(name, expectedType) => s"  param $name : ${renderTerm(expectedType)}"
      case ParsedLet(name, term) => s"  let $name = ${renderTerm(term)}"
      case ParsedBind(name, term) => s"  $name <- ${renderTerm(term)}"
      case other => ""
    }
    val yieldLine = s"  yield ${renderTerm(synth.yieldTerm)}"
    s"synth do {\n${(statements :+ yieldLine).mkString("\n")}\n}"

  private def showHoles(declarations: Vector[SimpleSyntaxParser.ParsedDeclaration], declNameOpt: Option[String]): Unit =
    val elaborated = declarations.map { decl =>
      decl -> TermAnalysis.collectHoles(Elaborator.toCore(decl.value))
    }

    declNameOpt match
      case None =>
        elaborated.foreach { case (decl, holes) =>
          if holes.isEmpty then
            ()
          else
            println(s"${decl.name}:")
            holes.foreach { hole =>
              println(s"  ${renderTerm(Term.Hole(hole.name, hole.expectedType))}")
            }
        }
        if elaborated.forall(_._2.isEmpty) then
          println("no unresolved holes")

      case Some(target) =>
        elaborated.find(_._1.name == target) match
          case None =>
            println(s"unknown declaration: $target")
          case Some((_, holes)) =>
            if holes.isEmpty then
              println("no unresolved holes")
            else
              holes.foreach { hole =>
                println(renderTerm(Term.Hole(hole.name, hole.expectedType)))
              }
