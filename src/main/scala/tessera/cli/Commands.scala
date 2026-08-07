package tessera.cli

import tessera.core.Term
import tessera.kernel.Kernel
import tessera.parser.SimpleSyntaxParser
import tessera.elab.{Elaborator, ElaboratedDeclaration}
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
        val kernel = Kernel()
        val elaboratedDecls = decls.flatMap: decl =>
          Elaborator.elaborate(decl).fold(
            msg =>
              println(s"${decl.name}: elaboration failed: $msg")
              Nil
            , elaborated =>
              Vector(elaborated)
          )
        val allOk = elaboratedDecls.forall { decl =>
          decl.declaredType match
            case None =>
              println(s"${decl.name}: no type annotation, skipped")
              true
            case Some(expected) =>
              kernel.check(decl.core, expected) match
                case report if report.isOk =>
                  println(s"${decl.name}: OK")
                  true
                case report =>
                  println(s"${decl.name}: FAIL")
                  report.errors.foreach(err => println(s"  ${err.message}"))
                  false
        }
        if !allOk then System.exit(1)

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
              evalTarget(decls, evalTargetExpr)
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

  private def evalTarget(declarations: Vector[SimpleSyntaxParser.ParsedDeclaration], target: String): Unit =
    val kernel = Kernel()
    declarations.find(_.name == target) match
      case Some(decl) =>
        Elaborator.elaborate(decl) match
          case Left(msg) =>
            println(s"${decl.name}: elaboration failed: $msg")
          case Right(elaborated: ElaboratedDeclaration) =>
            checkAndNormalize(kernel, elaborated)
      case None =>
        SimpleSyntaxParser.parseTermFromSource(target) match
          case Right(term) =>
            println(renderTerm(kernel.normalize(term)))
          case Left(error) =>
            println(s"unknown declaration or parse error: $error")

  private def checkAndNormalize(kernel: Kernel, elaborated: ElaboratedDeclaration): Unit =
    elaborated.declaredType match
      case Some(expected) =>
        kernel.check(elaborated.core, expected) match
          case report if report.isOk =>
            println(renderTerm(kernel.normalize(elaborated.core)))
          case report =>
            println(s"${elaborated.name}: FAIL")
            report.errors.foreach(err => println(s"  ${err.message}"))
      case None =>
        println(renderTerm(kernel.normalize(elaborated.core)))

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
      decl -> collectHoles(Elaborator.toCore(decl.value))
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

  private def collectHoles(term: Term): Vector[Term.Hole] =
    term match
      case hole @ Term.Hole(_, _) => Vector(hole)
      case App(function, argument) => collectHoles(function) ++ collectHoles(argument)
      case Let(_, valueType, value, body) =>
        collectHoles(valueType) ++ collectHoles(value) ++ collectHoles(body)
      case Pi(_, domain, codomain) =>
        collectHoles(domain) ++ collectHoles(codomain)
      case Lambda(_, paramType, body) =>
        collectHoles(paramType) ++ collectHoles(body)
      case Constructor(_, fields) => fields.toVector.flatMap(collectHoles)
      case _ => Vector.empty
