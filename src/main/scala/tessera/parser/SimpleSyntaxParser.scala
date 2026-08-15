package tessera.parser

import scala.io.Source
import java.io.IOException
import scala.util.Using

import tessera.core.Term
import Term.*

object SimpleSyntaxParser:
  private val MaxTupleArity = 10

  sealed trait ParseError:
    def message: String
    def position: Int
    override def toString: String = s"at $position: $message"

  final case class UnexpectedToken(message: String, position: Int) extends ParseError
  final case class UnexpectedEof(position: Int) extends ParseError:
    override val message = "unexpected end of input"

  sealed trait ParsedDeclarationBody
  final case class ParsedCoreTerm(term: Term) extends ParsedDeclarationBody
  final case class ParsedSynthDo(statements: Vector[ParsedDoStatement], yieldTerm: Term) extends ParsedDeclarationBody

  sealed trait ParsedDoStatement
  final case class ParsedParam(name: String, expectedType: Term) extends ParsedDoStatement
  final case class ParsedBind(name: String, term: Term) extends ParsedDoStatement
  final case class ParsedLet(name: String, term: Term) extends ParsedDoStatement
  final case class ParsedYield(term: Term) extends ParsedDoStatement

  final case class ParsedDeclaration(name: String, expectedType: Option[Term], value: ParsedDeclarationBody)

  def parseFile(path: String): Either[ParseError, Vector[ParsedDeclaration]] =
    try
      Using.resource(Source.fromFile(path)) { source =>
        parseModule(source.mkString)
      }
    catch
      case ex: IOException =>
        Left(UnexpectedToken(s"cannot read file '$path': ${ex.getMessage}", 0))

  def parseModule(source: String): Either[ParseError, Vector[ParsedDeclaration]] =
    val tokens = tokenize(source)
    val cursor = new Cursor(tokens)
    val declarations = Vector.newBuilder[ParsedDeclaration]
    while cursor.hasNext do
      skipWhitespaceLike(cursor)
      if !cursor.hasNext then
        ()
      else
        parseDeclaration(cursor) match
          case Left(err) => return Left(err)
          case Right(decl) => declarations += decl
    Right(declarations.result())

  def parseTermFromSource(source: String): Either[ParseError, Term] =
    val tokens = tokenize(source)
    val cursor = new Cursor(tokens)
    parseTerm(cursor).flatMap { term =>
      skipWhitespaceLike(cursor)
      if cursor.hasNext then
        Left(UnexpectedToken(s"unexpected extra input '${cursor.peekOption.getOrElse("EOF")}'", cursor.pos))
      else
        Right(term)
    }

  private def parseDeclaration(cursor: Cursor): Either[ParseError, ParsedDeclaration] =
    skipWhitespaceLike(cursor)
    for
      _ <- expectWord(cursor, "def")
      name <- expectIdentifier(cursor)
      optType <- expectOptionalType(cursor)
      _ <- expectEquals(cursor)
      value <- parseDeclarationValue(cursor)
    yield ParsedDeclaration(name, optType, value)

  private def parseDeclarationValue(cursor: Cursor): Either[ParseError, ParsedDeclarationBody] =
    skipWhitespaceLike(cursor)
    if cursor.peekOption.contains(Word("synth")) then
      parseSynthDo(cursor).map(identity)
    else if cursor.peekOption.contains(Word("for")) then
      parseForDo(cursor).map(identity)
    else
      parseTerm(cursor).map(ParsedCoreTerm.apply)

  private def parseSynthDo(cursor: Cursor): Either[ParseError, ParsedDeclarationBody] =
    for
      _ <- expectWord(cursor, "synth")
      _ <- parseOptionalDoKeyword(cursor)
      _ <- expectToken(cursor, LBrace)
      body <- parseDoBlock(cursor, Vector.empty)
      _ <- expectToken(cursor, RBrace)
    yield body

  private def parseForDo(cursor: Cursor): Either[ParseError, ParsedDeclarationBody] =
    for
      _ <- expectWord(cursor, "for")
      _ <- parseOptionalDoKeyword(cursor)
      _ <- expectToken(cursor, LBrace)
      body <- parseDoBlock(cursor, Vector.empty)
      _ <- expectToken(cursor, RBrace)
    yield body

  private def parseOptionalDoKeyword(cursor: Cursor): Either[ParseError, Unit] =
    skipWhitespaceLike(cursor)
    cursor.peekOption match
      case Some(Word("do")) =>
        cursor.next()
        Right(())
      case _ =>
        Right(())

  private def parseDoBlock(
    cursor: Cursor,
    statements: Vector[ParsedDoStatement]
  ): Either[ParseError, ParsedSynthDo] =
    skipWhitespaceLike(cursor)
    cursor.peekOption match
      case None =>
        Left(UnexpectedEof(cursor.pos))
      case Some(RBrace) =>
        Left(UnexpectedToken("do block is missing final yield", cursor.pos))
      case Some(_) =>
        parseDoStatement(cursor) match
          case Left(err) => Left(err)
          case Right(statement) =>
            parseOptionalSeparator(cursor)
            statement match
              case yieldStmt: ParsedYield =>
                skipWhitespaceLike(cursor)
                cursor.peekOption match
                  case Some(RBrace) =>
                    Right(ParsedSynthDo(statements, yieldStmt.term))
                  case _ =>
                    Left(UnexpectedToken("nothing can follow final yield", cursor.pos))
              case _ =>
                parseDoBlock(cursor, statements :+ statement)

  private def parseDoStatement(cursor: Cursor): Either[ParseError, ParsedDoStatement] =
    skipWhitespaceLike(cursor)
    cursor.consume() match
      case Some(Word("param")) =>
        for
          name <- expectIdentifier(cursor)
          _ <- expectColon(cursor)
          expectedType <- parseTerm(cursor)
        yield ParsedParam(name, expectedType)
      case Some(Word("let")) =>
        for
          name <- expectIdentifier(cursor)
          _ <- expectEquals(cursor)
          value <- parseTerm(cursor)
        yield ParsedLet(name, value)
      case Some(Word("yield")) =>
        parseTerm(cursor).map(ParsedYield.apply)
      case Some(Word(name)) =>
        skipWhitespaceLike(cursor)
        cursor.peekOption match
          case Some(ArrowLeft) =>
            cursor.next()
            parseTerm(cursor).map(ParsedBind(name, _))
          case None =>
            Left(UnexpectedEof(cursor.pos))
          case Some(tok) =>
            Left(UnexpectedToken(s"unsupported do statement '$tok'", cursor.pos))
      case Some(ArrowLeft) =>
        Left(UnexpectedToken("unexpected '<-' at do statement start; expected pattern before '<-'", cursor.pos))
      case Some(tok) =>
        Left(UnexpectedToken(s"unsupported do statement '$tok'", cursor.pos))
      case None =>
        Left(UnexpectedEof(cursor.pos))

  private def parseOptionalSeparator(cursor: Cursor): Unit =
    skipWhitespaceLike(cursor)
    if cursor.peekOption.contains(Semi) then
      cursor.next()

  private def expectToken(cursor: Cursor, expected: Token): Either[ParseError, Unit] =
    skipWhitespaceLike(cursor)
    cursor.consume() match
      case Some(token) if token == expected => Right(())
      case Some(other) => Left(UnexpectedToken(s"expected '$expected', found $other", cursor.pos))
      case None => Left(UnexpectedEof(cursor.pos))

  private def parseTerm(cursor: Cursor): Either[ParseError, Term] =
    skipWhitespaceLike(cursor)
    cursor.consume() match
      case None => Left(UnexpectedEof(cursor.pos))
      case Some(LParen) =>
        cursor.consume() match
          case None => Left(UnexpectedEof(cursor.pos))
          case Some(tok) =>
            tok match
              case LParen =>
                cursor.unconsume(LParen)
                parseTerm(cursor).flatMap(term => finishParenthesized(cursor, term))
              case Word("Sort") =>
                for
                  level <- expectNumber(cursor)
                  term <- finishParenthesized(cursor, Sort(level))
                yield term
              case Word("Var") =>
                for
                  name <- expectIdentifier(cursor)
                  term <- finishParenthesized(cursor, Var(name))
                yield term
              case Word("Builtin") =>
                for
                  name <- expectIdentifier(cursor)
                  term <- finishParenthesized(cursor, Builtin(name))
                yield term
              case Word("Pi") =>
                for
                  name <- expectIdentifier(cursor)
                  domain <- parseTerm(cursor)
                  codomain <- parseTerm(cursor)
                  term <- finishParenthesized(cursor, Pi(name, domain, codomain))
                yield term
              case Word("Lam") =>
                for
                  name <- expectIdentifier(cursor)
                  paramType <- parseTerm(cursor)
                  body <- parseTerm(cursor)
                  term <- finishParenthesized(cursor, Lambda(name, paramType, body))
                yield term
              case Word("App") =>
                for
                  fn <- parseTerm(cursor)
                  arg <- parseTerm(cursor)
                  term <- finishParenthesized(cursor, App(fn, arg))
                yield term
              case Word("Let") =>
                for
                  name <- expectIdentifier(cursor)
                  valueType <- parseTerm(cursor)
                  value <- parseTerm(cursor)
                  body <- parseTerm(cursor)
                  term <- finishParenthesized(cursor, Let(name, valueType, value, body))
                yield term
              case Word("Ctor") =>
                for
                  name <- expectIdentifier(cursor)
                  args <- parseRepeatTerm(cursor, isEndOfCompoundOrTuple)
                  term <- finishParenthesized(cursor, Constructor(name, args))
                yield term
              case Word("Unit") =>
                finishParenthesized(cursor, Constructor("Unit", Nil))
              case Number(value) =>
                finishParenthesized(cursor, Term.Sort(value))
              case Word(name) =>
                finishParenthesized(cursor, Term.Var(name))
              case _ =>
                Left(UnexpectedToken(s"unknown term form $tok", cursor.pos))
      case Some(Word("Sort")) =>
        expectNumber(cursor).map(Sort.apply)
      case Some(Word("Unit")) =>
        Right(Constructor("Unit", Nil))
      case Some(Word(value)) if value == "_" =>
        parseHoleAnnotation(cursor, "_")
      case Some(Word(value)) if value.startsWith("?") =>
        parseHoleAnnotation(cursor, value.stripPrefix("?"))
      case Some(Word(word)) =>
        Right(Var(word))
      case Some(Number(value)) =>
        Right(Sort(value))
      case Some(WhitespaceLike) =>
        parseTerm(cursor)
      case Some(Equals) =>
        Left(UnexpectedToken("unexpected '=' while parsing term", cursor.pos))
      case Some(Colon) =>
        Left(UnexpectedToken("unexpected ':' while parsing term", cursor.pos))
      case Some(RParen) =>
        Left(UnexpectedToken("unexpected ')' while parsing term", cursor.pos))
      case Some(tok) =>
        Left(UnexpectedToken(s"unexpected token $tok", cursor.pos))

  private def finishParenthesized(cursor: Cursor, first: Term): Either[ParseError, Term] =
    skipWhitespaceLike(cursor)
    cursor.peekOption match
      case Some(Comma) =>
        cursor.next()
        parseTupleTail(cursor, Vector(first))
      case Some(RParen) =>
        cursor.next()
        Right(first)
      case Some(tok) =>
        Left(UnexpectedToken(s"expected ',' or ')' after term, found $tok", cursor.pos))
      case None =>
        Left(UnexpectedEof(cursor.pos))

  private def parseTupleTail(
    cursor: Cursor,
    elements: Vector[Term]
  ): Either[ParseError, Term] =
    skipWhitespaceLike(cursor)
    cursor.peekOption match
      case Some(RParen) =>
        Left(UnexpectedToken("expected tuple element after ','", cursor.pos))
      case None =>
        Left(UnexpectedEof(cursor.pos))
      case _ =>
        parseTerm(cursor).flatMap { element =>
          val collected = elements :+ element
          skipWhitespaceLike(cursor)
          cursor.peekOption match
            case Some(Comma) if collected.size >= MaxTupleArity =>
              Left(UnexpectedToken(s"tuple arity exceeds maximum $MaxTupleArity", cursor.pos))
            case Some(Comma) =>
              cursor.next()
              parseTupleTail(cursor, collected)
            case Some(RParen) =>
              cursor.next()
              Right(Constructor(s"Tuple${collected.size}", collected.toList))
            case Some(tok) =>
              Left(UnexpectedToken(s"expected ',' or ')' after tuple element, found $tok", cursor.pos))
            case None =>
              Left(UnexpectedEof(cursor.pos))
        }

  private def parseHoleAnnotation(cursor: Cursor, name: String): Either[ParseError, Term] =
    skipWhitespaceLike(cursor)
    cursor.peekOption match
      case Some(Colon) =>
        cursor.next()
        parseTerm(cursor).map(expected => Term.Hole(name, Some(expected)))
      case _ =>
        Right(Term.Hole(name, None))

  private def parseRepeatTerm(cursor: Cursor, stop: Cursor => Boolean): Either[ParseError, List[Term]] =
    skipWhitespaceLike(cursor)
    if stop(cursor) then Right(Nil)
    else
      for
        head <- parseTerm(cursor)
        tail <- parseRepeatTerm(cursor, stop)
      yield head :: tail

  private def expectWord(cursor: Cursor, word: String): Either[ParseError, Unit] =
    skipWhitespaceLike(cursor)
    cursor.consume() match
      case Some(Word(actual)) if actual == word => Right(())
      case Some(other) => Left(UnexpectedToken(s"expected '$word', found $other", cursor.pos))
      case None => Left(UnexpectedEof(cursor.pos))

  private def expectOptionalType(cursor: Cursor): Either[ParseError, Option[Term]] =
    skipWhitespaceLike(cursor)
    if cursor.peekOption.contains(Colon) then
      cursor.next()
      parseTerm(cursor).map(Some.apply)
    else
      Right(None)

  private def expectIdentifier(cursor: Cursor): Either[ParseError, String] =
    skipWhitespaceLike(cursor)
    cursor.consume() match
      case Some(Word(value)) => Right(value)
      case Some(other) => Left(UnexpectedToken(s"expected identifier, found $other", cursor.pos))
      case None => Left(UnexpectedEof(cursor.pos))

  private def expectNumber(cursor: Cursor): Either[ParseError, Int] =
    skipWhitespaceLike(cursor)
    cursor.consume() match
      case Some(Number(value)) => Right(value)
      case Some(Word(value)) =>
        value.toIntOption
          .map(Right(_))
          .getOrElse(Left(UnexpectedToken(s"expected number, found '$value'", cursor.pos)))
      case Some(other) => Left(UnexpectedToken(s"expected number, found $other", cursor.pos))
      case None => Left(UnexpectedEof(cursor.pos))

  private def expectColon(cursor: Cursor): Either[ParseError, Unit] =
    skipWhitespaceLike(cursor)
    cursor.consume() match
      case Some(Colon) => Right(())
      case Some(other) => Left(UnexpectedToken(s"expected ':', found $other", cursor.pos))
      case None => Left(UnexpectedEof(cursor.pos))

  private def expectEquals(cursor: Cursor): Either[ParseError, Unit] =
    skipWhitespaceLike(cursor)
    cursor.consume() match
      case Some(Equals) => Right(())
      case Some(other) => Left(UnexpectedToken(s"expected '=', found $other", cursor.pos))
      case None => Left(UnexpectedEof(cursor.pos))

  private def skipWhitespaceLike(cursor: Cursor): Unit =
    while cursor.peekOption.contains(WhitespaceLike) do
      cursor.next()

  private def isEndOfCompoundOrTuple(cursor: Cursor): Boolean =
    cursor.peekOption.exists(tok => tok == RParen || tok == Comma)

  private def tokenize(source: String): Vector[Token] =
    val b = Vector.newBuilder[Token]
    var index = 0
    while index < source.length do
      source(index) match
        case c if c.isWhitespace =>
          b += WhitespaceLike
          index += 1
        case '(' =>
          b += LParen
          index += 1
        case ')' =>
          b += RParen
          index += 1
        case '{' =>
          b += LBrace
          index += 1
        case '}' =>
          b += RBrace
          index += 1
        case ';' =>
          b += Semi
          index += 1
        case ':' =>
          b += Colon
          index += 1
        case ',' =>
          b += Comma
          index += 1
        case '=' =>
          b += Equals
          index += 1
        case '/' if index + 1 < source.length && source(index + 1) == '/' =>
          while index < source.length && source(index) != '\n' do
            index += 1
        case c if c.isLetter || c == '_' || c == '.' || c == '?' =>
          val start = index
          index += 1
          while index < source.length && (source(index).isLetterOrDigit || source(index) == '_' || source(index) == '.' || source(index) == '?') do
            index += 1
          b += Word(source.substring(start, index))
        case c if c.isDigit =>
          val start = index
          index += 1
          while index < source.length && source(index).isDigit do
            index += 1
          b += Number(source.substring(start, index).toInt)
        case c if c == '-' =>
          if index + 1 < source.length && source(index + 1) == '>' then
            b += Arrow
            index += 2
          else
            index += 1
        case '<' if index + 1 < source.length && source(index + 1) == '-' =>
          b += ArrowLeft
          index += 2
        case _ =>
          index += 1
    b.result()

  private sealed trait Token
  private case object WhitespaceLike extends Token
  private case object LParen extends Token
  private case object RParen extends Token
  private case object LBrace extends Token
  private case object RBrace extends Token
  private case object Semi extends Token
  private case object Colon extends Token
  private case object Equals extends Token
  private case object Arrow extends Token
  private case object ArrowLeft extends Token
  private case object Comma extends Token
  private final case class Word(value: String) extends Token
  private final case class Number(value: Int) extends Token

  private final class Cursor(initial: Vector[Token]):
    private var posInt = 0
    private var pushback: Option[Token] = None

    def pos: Int = posInt
    def hasNext: Boolean = pushback.isDefined || posInt < initial.length

    def peekOption: Option[Token] = pushback.orElse(if posInt < initial.length then Some(initial(posInt)) else None)

    def consume(): Option[Token] =
      pushback match
        case Some(tok) =>
          pushback = None
          Some(tok)
        case None =>
          if posInt < initial.length then
            val nextToken = initial(posInt)
            posInt += 1
            Some(nextToken)
          else None

    def next(): Unit = consume()

    def unconsume(token: Token): Unit =
      if pushback.isDefined then
        throw new IllegalStateException("token pushback already occupied")
      else
        pushback = Some(token)
