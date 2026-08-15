package tessera.architecture

import munit.FunSuite
import java.nio.file.{Files, Paths}
import scala.io.Source

final class ArchitectureTest extends FunSuite:
  private val projectRoot = Paths.get(".").toAbsolutePath.normalize
  private val mainSrc = projectRoot.resolve("src/main/scala")

  // Directory -> packages its files must never reference. Matching is on the
  // bare package name so both imports and fully-qualified references count.
  private val layerRules: Seq[(String, Seq[String])] = Seq(
    "tessera/core" -> Seq(
      "tessera.kernel",
      "tessera.parser",
      "tessera.elab",
      "tessera.meta",
      "tessera.compiler",
      "tessera.cli"
    ),
    "tessera/kernel" -> Seq(
      "tessera.parser",
      "tessera.elab",
      "tessera.meta",
      "tessera.compiler",
      "tessera.cli"
    ),
    "tessera/meta" -> Seq(
      "tessera.parser",
      "tessera.elab",
      "tessera.compiler",
      "tessera.cli"
    ),
    "tessera/parser" -> Seq(
      "tessera.elab",
      "tessera.meta",
      "tessera.compiler",
      "tessera.cli"
    ),
    "tessera/elab" -> Seq(
      "tessera.meta",
      "tessera.compiler",
      "tessera.cli"
    ),
    "tessera/compiler" -> Seq(
      "tessera.cli"
    )
  )

  private def readText(path: String): String =
    Source.fromFile(path).mkString

  private def forbiddenReferences(source: String, forbiddens: Seq[String]): Seq[String] =
    forbiddens.filter(source.contains).map(phrase => s"contains forbidden reference: $phrase")

  private def readScalaFiles(directory: String): Vector[String] =
    val dir = mainSrc.resolve(directory).toFile
    if !dir.exists() then Vector.empty
    else
      Files.walk(dir.toPath)
        .toArray
        .iterator
        .toArray
        .collect { case p: java.nio.file.Path if p.toString.endsWith(".scala") => p.toString }
        .toVector

  layerRules.foreach { (directory, forbiddens) =>
    test(s"$directory must not reference ${forbiddens.mkString(", ")}") {
      val files = readScalaFiles(directory)
      assert(files.nonEmpty, s"no Scala files found under $directory")

      files.foreach { path =>
        val issues = forbiddenReferences(readText(path), forbiddens)
        assertEquals(
          issues,
          Seq.empty,
          s"file $path violates layering: ${issues.mkString(", ")}"
        )
      }
    }
  }
