package tessera.architecture

import munit.FunSuite
import java.nio.file.{Files, Paths}
import scala.io.Source

final class ArchitectureTest extends FunSuite:
  private val projectRoot = Paths.get(".").toAbsolutePath.normalize
  private val mainSrc = projectRoot.resolve("src/main/scala")

  private def readText(path: String): String =
    Source.fromFile(path).mkString

  private def forbiddenImports(source: String, forbiddens: Seq[String]): Seq[String] =
    forbiddens.collect { phrase =>
      if source.contains(phrase) then s"contains forbidden reference: $phrase" else ""
    }.filter(_.nonEmpty)

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

  test("kernel must not depend on parser/elab/meta/control packages") {
    val kernelFiles = readScalaFiles("tessera/kernel")
    assert(kernelFiles.nonEmpty)

    kernelFiles.foreach { path =>
      val source = readText(path)
      val issues = forbiddenImports(
        source,
        Seq(
          "tessera.parser",
          "tessera.elab",
          "tessera.meta",
          "tessera.cli"
        )
      )
      assertEquals(
        issues,
        Vector.empty,
        s"kernel file $path violates layering: ${issues.mkString(", ")}"
      )
    }
  }

  test("core should remain layer-independent of parser/elab/meta/cli") {
    val coreFiles = readScalaFiles("tessera/core")
    assert(coreFiles.nonEmpty)

    coreFiles.foreach { path =>
      val source = readText(path)
      val issues = forbiddenImports(
        source,
        Seq(
          "import tessera.parser",
          "import tessera.elab",
          "import tessera.meta",
          "import tessera.cli"
        )
      )
      assertEquals(
        issues,
        Vector.empty,
        s"core file $path violates layering: ${issues.mkString(", ")}"
      )
    }
  }

  test("meta layer must not import parser/elab/cli internals directly") {
    val metaFiles = readScalaFiles("tessera/meta")
    assert(metaFiles.nonEmpty)

    metaFiles.foreach { path =>
      val source = readText(path)
      val issues = forbiddenImports(
        source,
        Seq(
          "import tessera.parser",
          "import tessera.elab",
          "import tessera.cli"
        )
      )
      assertEquals(
        issues,
        Vector.empty,
        s"meta file $path violates layering: ${issues.mkString(", ")}"
      )
    }
  }
