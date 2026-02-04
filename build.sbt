import sbt.Keys.*
import sbt.*
// shadow sbt-scalajs' crossProject and CrossType from Scala.js 0.6.x
import sbtcrossproject.CrossPlugin.autoImport.{crossProject, CrossType}
import ScalaVersionUtil.*

ThisBuild / scalafmtOnCompile := true

Global / onChangedBuildSource := ReloadOnSourceChanges

ThisBuild / scalaVersion       := "2.13.17"
ThisBuild / crossScalaVersions := Seq("2.13.17", "3.7.4")

val commonSettings = Seq(
  semanticdbEnabled := true,
  semanticdbVersion := scalafixSemanticdb.revision,
  scalacOptions     := Seq(
    "-deprecation",
    "-encoding",
    "UTF-8",
    "-feature",
    "-unchecked"
  ),
  scalacOptions ++= scalaVerDependentSeq {
    case (2, _) =>
      Seq(
        "-Xsource:3",
        "-Wconf:cat=scala3-migration:w",
        "-Xlint",
        "-Ywarn-dead-code",
        "-Ywarn-numeric-widen",
        "-Ywarn-value-discard",
        "-language:experimental.macros",
        "-language:existentials"
      )
    case (3, _) =>
      // Suppress warnings for cross-compiled code that can't use Scala 3 syntax
      Seq(
        "-Wconf:msg=Implicit parameters should be provided:s",
        "-Wconf:msg=has been deprecated.*uninitialized:s"
      )
  }.value,
  scalacOptions ++= scalaVerDependentSeq {
    case (2, 13) => Seq("-Werror")
    case (3, _)  => Seq("-Xfatal-warnings")
  }.value,
  Compile / scalacOptions -= scalaVerDependent {
    case (2, _) => "-Ywarn-value-discard"
  }.value,
  Compile / doc / scalacOptions -= scalaVerDependent {
    case (3, _)  => "-Xfatal-warnings"
    case (2, 13) => "-Werror"
  }.value,
  testFrameworks += new TestFramework("utest.runner.Framework"),
  libraryDependencies ++= Seq(
    "com.lihaoyi" %%% "utest" % "0.8.0" % "test"
  ),
  libraryDependencies += scalaVerDependent {
    case (2, _) => "org.scala-lang.modules" %% "scala-collection-compat" % "2.8.1"
  }.value
)

inThisBuild(
  List(
    homepage   := Some(url("https://github.com/mikla/diode")),
    licenses   := List("Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0")),
    developers := List(
      Developer("ochrons", "Otto Chrons", "", url("https://github.com/ochrons"))
    ),
    organization := "io.github.mikla",
    scmInfo      := Some(
      ScmInfo(
        url("https://github.com/mikla/diode"),
        "scm:git:git@github.com:mikla/diode.git",
        Some("scm:git:git@github.com:mikla/diode.git")
      )
    ),
    Test / publishArtifact := false
  )
)

val sourceMapSetting: Def.Initialize[Option[String]] = Def.settingDyn(
  if (isSnapshot.value) Def.setting(None)
  else {
    val a   = baseDirectory.value.toURI.toString.replaceFirst("[^/]+/?$", "")
    val g   = "https://raw.githubusercontent.com/mikla/diode"
    val uri = s"$a->$g/v${version.value}/${name.value}/"
    scalaVerDependent {
      case (2, _) => s"-P:scalajs:mapSourceURI:$uri"
      case (3, _) => s"-scalajs-mapSourceURI:$uri"
    }
  }
)

val commonJsSettings = Seq(
  scalacOptions += sourceMapSetting.value,
  scalacOptions ++= scalaVerDependent {
    case (2, _) => "-P:scalajs:nowarnGlobalExecutionContext"
    case (3, _) => "-scalajs"
  }.value
)

lazy val diodeCore = crossProject(JSPlatform, JVMPlatform)
  .crossType(CrossType.Full)
  .in(file("diode-core"))
  .settings(commonSettings: _*)
  .settings(
    name := "diode-core",
    libraryDependencies += Def.settingDyn {
      val scalaVer = scalaVersion.value
      scalaVerDependent {
        case (2, _) => "org.scala-lang" % "scala-reflect" % scalaVer % "provided"
      }
    }.value
  )
  .jsSettings(commonJsSettings: _*)

lazy val diodeData = crossProject(JSPlatform, JVMPlatform)
  .crossType(CrossType.Full)
  .in(file("diode-data"))
  .settings(commonSettings: _*)
  .settings(
    name := "diode-data"
  )
  .jsSettings(commonJsSettings: _*)
  .dependsOn(diodeCore)

lazy val diode = crossProject(JSPlatform, JVMPlatform)
  .crossType(CrossType.Full)
  .in(file("diode"))
  .settings(commonSettings: _*)
  .settings(
    name := "diode",
    test := {}
  )
  .dependsOn(diodeCore, diodeData)

lazy val diodeDevtools = crossProject(JSPlatform, JVMPlatform)
  .crossType(CrossType.Full)
  .in(file("diode-devtools"))
  .settings(commonSettings: _*)
  .settings(
    name := "diode-devtools"
  )
  .jsSettings(commonJsSettings: _*)
  .jsSettings(
    libraryDependencies ++= Seq("org.scala-js" %%% "scalajs-dom" % "2.8.0")
  )
  .dependsOn(diodeCore)

lazy val diodeReact: Project = project
  .enablePlugins(ScalaJSPlugin)
  .in(file("diode-react"))
  .settings(commonSettings: _*)
  .settings(commonJsSettings: _*)
  .settings(
    name := "diode-react",
    libraryDependencies ++= Seq(
      "com.github.japgolly.scalajs-react" %%% "core" % "3.0.0"
    )
  )
  .dependsOn(diode.js)
