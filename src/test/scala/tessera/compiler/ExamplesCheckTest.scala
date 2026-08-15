package tessera.compiler

import munit.FunSuite
import tessera.parser.SimpleSyntaxParser
import java.nio.file.{Files, Paths}
import scala.jdk.CollectionConverters.*

// Pins the check status of the shipped example corpus: README documents which
// files are expected to fail, and everything else must stay green.
final class ExamplesCheckTest extends FunSuite:
  private val examplesDir = Paths.get("examples")
  private val expectedFailures = Set("BadId.tes", "Holes.tes", "SynthesisFailure.tes")

  private def checkFile(fileName: String): CheckedModule =
    SimpleSyntaxParser.parseFile(examplesDir.resolve(fileName).toString) match
      case Right(declarations) => ModuleChecker.check(declarations)
      case Left(error) => fail(s"$fileName failed to parse: $error")

  test("every example checks according to its documented status") {
    val files = Files
      .list(examplesDir)
      .iterator
      .asScala
      .map(_.getFileName.toString)
      .filter(_.endsWith(".tes"))
      .toVector
      .sorted
    assert(files.nonEmpty, "no example files found")

    files.foreach { file =>
      val module = checkFile(file)
      if expectedFailures.contains(file) then
        assert(module.hasFailures, s"$file is documented as expected-fail but checked clean")
      else
        assert(
          !module.hasFailures,
          s"$file unexpectedly failed: " +
            module.outcomes.flatMap(_.diagnostics.map(_.message)).mkString("; ")
        )
    }
  }

  test("Constants.tes registers its declarations in order") {
    assertEquals(
      checkFile("Constants.tes").environment.names,
      Vector("id", "alias", "inferredAlias")
    )
  }

  test("Holes.tes reports each unresolved hole") {
    assertEquals(
      checkFile("Holes.tes").outcomes.flatMap(_.diagnostics.map(_.message)),
      Vector("unresolved hole: goal", "unresolved hole: typed : Type[0]")
    )
  }
