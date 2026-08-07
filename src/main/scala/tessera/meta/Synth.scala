package tessera.meta

import tessera.core.Term
import tessera.core.Term.*
import tessera.kernel.{Kernel, Printer}

type Context = Vector[(String, Term)]
type Goal[A] = Term
type Local[A] = Term
type Search[A] = SearchResult[A]
type Refinement[A] = (Map[String, Term], (Map[String, Term]) => Term)
type NamedSynthArguments = Vector[(String, Synth)]
type Branches[S, R] = PartialFunction[Term, Synth]

final case class SearchConfig(alternatives: Vector[Synth], label: Option[String] = None)

sealed trait SearchResult[+A]:
  def trace: Vector[String]

object SearchResult:
  final case class Success[A](value: A, trace: Vector[String] = Vector.empty) extends SearchResult[A]
  final case class Failure(message: String, trace: Vector[String] = Vector.empty) extends SearchResult[Nothing]

  extension [A](result: SearchResult[A])
    def map[B](f: A => B): SearchResult[B] = result match
      case SearchResult.Success(value, trace) => SearchResult.Success(f(value), trace)
      case failure @ SearchResult.Failure(_, _) => failure

    def flatMap[B](f: A => SearchResult[B]): SearchResult[B] = result match
      case SearchResult.Success(value, trace) =>
        f(value) match
          case SearchResult.Success(nextValue, nextTrace) => SearchResult.Success(nextValue, trace ++ nextTrace)
          case SearchResult.Failure(message, failureTrace) => SearchResult.Failure(message, trace ++ failureTrace)
      case failure @ SearchResult.Failure(_, _) =>
        failure

    def withTrace(event: String): SearchResult[A] = result match
      case SearchResult.Success(value, trace) => SearchResult.Success(value, trace :+ event)
      case SearchResult.Failure(message, trace) => SearchResult.Failure(message, trace :+ event)

final class Synth private (private val run: Context => SearchResult[Term], private val events: Vector[String]):
  private def withEvents(result: SearchResult[Term]): SearchResult[Term] =
    result match
      case SearchResult.Success(value, trace) => SearchResult.Success(value, events ++ trace)
      case SearchResult.Failure(message, trace) => SearchResult.Failure(message, events ++ trace)

  def execute(context: Context): SearchResult[Term] =
    withEvents(run(context))

  def map(f: Term => Term): Synth =
    new Synth(ctx => withEvents(run(ctx).map(f)), events)

  def flatMap(next: Term => Synth): Synth =
    new Synth(ctx =>
      run(ctx) match
        case SearchResult.Success(value, trace) =>
          next(value).run(ctx) match
            case SearchResult.Success(nextValue, nextTrace) =>
              SearchResult.Success(nextValue, trace ++ nextTrace ++ events)
            case SearchResult.Failure(message, failureTrace) =>
              SearchResult.Failure(message, trace ++ failureTrace ++ events)
        case SearchResult.Failure(message, trace) =>
          SearchResult.Failure(message, trace ++ events)
    , events)

  def orElse(fallback: => Synth): Synth =
    Synth.choose(this, fallback)

object Synth:
  private val defaultKernel = Kernel()

  def fn(name: String, paramType: Term)(body: String => Synth): Synth =
    new Synth(ctx =>
      val nested = body(name).execute(ctx :+ (name, paramType))
      nested match
        case SearchResult.Success(bodyTerm, trace) =>
          SearchResult.Success(Lambda(name, paramType, bodyTerm), trace ++ Vector(s"fn $name : ${Printer.render(paramType)}"))
        case SearchResult.Failure(message, trace) =>
          SearchResult.Failure(message, trace ++ Vector(s"fn $name failed"))
    , Vector("fn"))

  def depFn(name: String, paramType: Term)(body: String => Synth): Synth =
    fn(name, paramType)(body)

  def fnN(params: Vector[(String, Term)])(body: Vector[Term] => Synth): Synth =
    def build(remaining: Vector[(String, Term)], valuesSoFar: Vector[Term]): Synth =
      remaining.toList match
        case Nil =>
          body(valuesSoFar)
        case (name, paramType) :: tail =>
          fn(name, paramType) { _ =>
            build(tail.toVector, valuesSoFar :+ Var(name))
          }
    build(params, Vector.empty)

  def zip(left: Synth, right: Synth): Synth =
    new Synth(ctx =>
      left.execute(ctx) match
        case SearchResult.Success(firstValue, firstTrace) =>
          right.execute(ctx) match
            case SearchResult.Success(secondValue, secondTrace) =>
              SearchResult.Success(Constructor("Pair", List(firstValue, secondValue)), firstTrace ++ secondTrace ++ Vector("zip"))
            case SearchResult.Failure(message, secondTrace) =>
              SearchResult.Failure(message, firstTrace ++ secondTrace ++ Vector("zip(second failed)"))
        case SearchResult.Failure(message, firstTrace) =>
          SearchResult.Failure(message, firstTrace ++ Vector("zip(first failed)"))
    , Vector("zip"))

  def pure(term: Term): Synth =
    new Synth(_ => SearchResult.Success(term, Vector(s"pure ${Printer.render(term)}")), Vector("pure"))

  def fail(message: String): Synth =
    new Synth(_ => SearchResult.Failure(message, Vector(s"fail: $message")), Vector("fail"))

  def use(term: Term): Synth =
    pure(term)

  def lookup(name: String): Synth =
    new Synth(ctx =>
      ctx.find(_._1 == name) match
        case Some(_) => SearchResult.Success(Var(name), Vector(s"lookup $name"))
        case None => SearchResult.Failure(s"cannot find local term `$name`", Vector(s"lookup failed: $name"))
    , Vector("lookup"))

  def construct(name: String, fields: NamedSynthArguments): Synth =
    if fields.groupMapReduce(_._1)(_ => 1)(_ + _).exists(_._2 > 1) then
      fail(s"duplicate field name in constructor '$name'")
    else
      construct(
        name,
        fields.map(_._2),
        Some(fields.map(_._1))
      )

  def construct(
    name: String,
    fields: Vector[Synth],
    fieldNames: Option[Vector[String]] = None
  ): Synth =
    new Synth(ctx =>
      fields.foldLeft[SearchResult[Vector[Term]]](SearchResult.Success(Vector.empty, Vector(s"construct $name"))) {
        case (acc, synth) =>
          acc.flatMap(collected =>
            synth.execute(ctx) match
              case SearchResult.Success(next, trace) =>
                SearchResult.Success(collected :+ next, trace)
              case failure @ SearchResult.Failure(_, _) => failure
          )
      } match
        case SearchResult.Success(terms, trace) =>
          val withNames = fieldNames.getOrElse(Vector.empty)
          val labeled =
            terms.zipWithIndex.map { case (term, index) =>
              withNames.lift(index).fold(term)(name => term)
            }
          SearchResult.Success(Constructor(name, labeled.toList), trace ++ Vector("construct " + name))
        case SearchResult.Failure(message, trace) =>
          SearchResult.Failure(message, trace)
    , Vector("construct"))

  def construct(name: String, fields: Synth*): Synth =
    construct(name, fields.toVector)

  def inspect[S, R](scrutinee: Term)(branches: Branches[S, R]): Synth =
    new Synth(ctx =>
      branches.lift(scrutinee) match
        case Some(synth) =>
          synth.execute(ctx)
        case None =>
          SearchResult.Failure(
            s"no matching branch for scrutinee: ${Printer.render(scrutinee)}",
            Vector(s"inspect failed: ${Printer.render(scrutinee)}")
          )
    , Vector("inspect"))

  def search(config: SearchConfig): Synth =
    new Synth(ctx =>
      if config.alternatives.isEmpty then
        SearchResult.Failure("search config has no alternatives", Vector("search: empty alternatives"))
      else
        val labels = config.label.fold(Vector.empty[String])(label => Vector(s"search($label)"))
        val (successes, failures) =
          config.alternatives.map(_.execute(ctx)).foldLeft(
            (Vector.empty[SearchResult[Term]], Vector.empty[SearchResult.Failure])
          ) {
            case ((successes, failures), next @ SearchResult.Success(_, _)) =>
              (successes :+ next, failures)
            case ((successes, failures), fail @ SearchResult.Failure(_, _)) =>
              (successes, failures :+ fail)
          }
        successes.headOption match
          case Some(SearchResult.Success(value, trace)) =>
            SearchResult.Success(value, labels ++ trace)
          case _ =>
            val failureMessages = failures.map(_.message).toList
            val failureTrace = failures.flatMap(_.trace).toVector
            SearchResult.Failure(
              s"all search branches failed: ${failureMessages.mkString("; ")}",
              labels ++ failureTrace
            )
    , Vector("search"))

  def label(message: String, source: Synth): Synth =
    new Synth(ctx => source.execute(ctx).withTrace(s"label($message)"), Vector.empty)

  def call(function: Synth, argument: Synth): Synth =
    new Synth(ctx =>
      function.execute(ctx) match
        case SearchResult.Success(functionTerm, functionTrace) =>
          argument.execute(ctx) match
            case SearchResult.Success(argumentTerm, argumentTrace) =>
              SearchResult.Success(App(functionTerm, argumentTerm), functionTrace ++ argumentTrace ++ Vector("call"))
            case SearchResult.Failure(message, argumentTrace) =>
              SearchResult.Failure(message, functionTrace ++ argumentTrace)
        case SearchResult.Failure(message, functionTrace) =>
          SearchResult.Failure(message, functionTrace)
    , Vector("call"))

  def call(function: Term, argument: Synth): Synth =
    call(pure(function), argument)

  def choose(first: Synth, second: Synth): Synth =
    new Synth(ctx =>
      first.execute(ctx) match
        case firstSuccess @ SearchResult.Success(_, _) =>
          firstSuccess
        case firstFailure @ SearchResult.Failure(_, _) =>
          second.execute(ctx) match
            case secondSuccess @ SearchResult.Success(_, _) =>
              secondSuccess
            case SearchResult.Failure(secondMessage, secondTrace) =>
              SearchResult.Failure(
                s"${firstFailure.message}; $secondMessage",
                firstFailure.trace ++ secondTrace :+ "choose failed"
              )
    , Vector("choose"))

  extension (self: Synth)
    def mapTerm(f: Term => Term): Synth = self.map(f)
    def flatMapSynth(next: Term => Synth): Synth = self.flatMap(next)
    def orElseSynth(fallback: => Synth): Synth = self.orElse(fallback)

  def derive(source: Synth, expectedType: Option[Term] = None): SearchResult[Term] =
    source.execute(Vector.empty) match
      case SearchResult.Success(term, trace) =>
        expectedType match
          case None =>
            SearchResult.Success(defaultKernel.normalize(term), trace :+ "derive")
          case Some(expected) =>
            val checkResult = defaultKernel.check(term, expected)
            if checkResult.isOk then
              SearchResult.Success(defaultKernel.normalize(term), trace :+ "derive" :+ "kernel check ok")
            else
              SearchResult.Failure(
                s"generated term rejected by kernel: " + checkResult.errors.map(_.message).mkString("; "),
                trace :+ "derive" :+ "kernel check failed"
              )
      case SearchResult.Failure(message, trace) =>
        SearchResult.Failure(message, trace :+ "derive")
