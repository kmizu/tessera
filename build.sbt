import sbtassembly.AssemblyPlugin.autoImport.*

ThisBuild / scalaVersion := "3.4.2"

lazy val root = (project in file("."))
  .settings(
    name := "tessera",
    version := "0.1.0",
    Compile / mainClass := Some("tessera.Main"),
    assembly / mainClass := Some("tessera.Main"),
    assembly / assemblyJarName := s"${name.value}-${version.value}.jar",
    scalacOptions ++= Seq(
      "-deprecation",
      "-feature",
      "-unchecked",
      "-Wunused:all",
      "-Xfatal-warnings"
    ),
    libraryDependencies += "org.scalameta" %% "munit" % "1.0.4" % Test
  )
