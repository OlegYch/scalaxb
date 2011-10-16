import sbt._

object Builds extends Build {
  import Keys._
  import ScriptedPlugin._
  import sbtappengine.Plugin._
  import sbtscalaxb.Plugin._
  import ScalaxbKeys._

  lazy val buildSettings = Defaults.defaultSettings ++ Seq(
    version := "0.6.6-SNAPSHOT",
    organization := "org.scalaxb",
    scalaVersion := "2.9.1",
    crossScalaVersions := Seq("2.9.1", "2.9.0-1", "2.8.1"), // Scala interpreter bug in 2.9.1
    publishArtifact in (Compile, packageBin) := true,
    publishArtifact in (Test, packageBin) := false,
    publishArtifact in (Compile, packageDoc) := false,
    publishArtifact in (Compile, packageSrc) := false,
    resolvers += ScalaToolsSnapshots,
    publishTo <<= version { (v: String) =>
      val nexus = "http://nexus.scala-tools.org/content/repositories/"
      if(v endsWith "-SNAPSHOT") Some("Scala Tools Nexus" at nexus + "snapshots/")
      else Some("Scala Tools Nexus" at nexus + "releases/")
    },
    credentials += Credentials(Path.userHome / ".ivy2" / ".credentials"),
    publishMavenStyle := true,
    parallelExecution in Test := false,
    offline := true
  )

  val Xsd = config("xsd") extend(Compile)
  val Wsdl = config("wsdl") extend(Compile)
  val Soap11 = config("soap11") extend(Compile)
  val Soap12 = config("soap12") extend(Compile)
  lazy val cliSettings = buildSettings ++ Seq(
    name := "scalaxb",
    libraryDependencies ++= Seq(
      "com.github.scopt" %% "scopt" % "1.1.2",
      "org.scala-tools.sbt" % "launcher-interface" % "0.7.4" % "provided" from (
        "http://databinder.net/repo/org.scala-tools.sbt/launcher-interface/0.7.4/jars/launcher-interface.jar"),
      "com.weiglewilczek.slf4s" %% "slf4s" % "1.0.7",
      "ch.qos.logback" % "logback-classic" % "0.9.29"),
    libraryDependencies <++= scalaVersion(testDeps(_)),
    unmanagedSourceDirectories in Compile <+= baseDirectory( _ / "src_managed" ),
    sourceGenerators in Compile <+= (sourceManaged in Compile, version) map { (dir, version) =>
      val file = dir / "version.scala"
      IO.write(file, """package scalaxb
trait Version { val version = "%s" }
""".format(version))
      Seq(file)
    })  ++
    inConfig(Xsd)(baseScalaxbSettings ++ inTask(scalaxb)(customScalaxbSettings("xmlschema"))) ++
    inConfig(Wsdl)(baseScalaxbSettings ++ inTask(scalaxb)(customScalaxbSettings("wsdl11"))) ++
    inConfig(Soap11)(baseScalaxbSettings ++ inTask(scalaxb)(soapSettings("soapenvelope11"))) ++
    inConfig(Soap12)(baseScalaxbSettings ++ inTask(scalaxb)(soapSettings("soapenvelope12")))

  def customScalaxbSettings(base: String): Seq[Project.Setting[_]] = Seq(
    sources <<= xsdSource map { xsd => Seq(xsd / (base + ".xsd")) },
    sourceManaged <<= baseDirectory / "src_managed",
    packageName := base,
    protocolFileName := base + "_xmlprotocol.scala",
    classPrefix := Some("X")
  )

  def soapSettings(base: String): Seq[Project.Setting[_]] = Seq(
    sources <<= xsdSource map { xsd => Seq(xsd / (base + ".xsd")) },
    sourceManaged <<= sourceDirectory(_ / "main" / "resources"),
    packageName := base,
    protocolFileName := base + "_xmlprotocol.scala",
    packageDir := false,
    generate <<= (generate) map { files =>
      val renamed = files map { file => new File(file.getParentFile, file.getName + ".template") }
      IO.move(files zip renamed)
      renamed
    }
  )

  lazy val itSettings = buildSettings ++ Seq(
    scalaVersion := "2.9.0-1", // Scala interpreter bug in 2.9.1
    crossScalaVersions := Seq("2.9.0-1"),
    libraryDependencies <++= scalaVersion { sv =>
      testDeps(sv) ++
      Seq(
        "net.databinder" %% "dispatch-http" % "0.8.5" % "test",
        "org.scala-lang" % "scala-compiler" % sv
      )
    }
  ) ++ noPublish

  lazy val pluginSettings = buildSettings ++ Seq(
    sbtPlugin := true,
    crossScalaVersions := Seq("2.9.1"),
    publishMavenStyle := true) ++
    ScriptedPlugin.scriptedSettings ++ Seq(
    scriptedBufferLog := false
  )

  lazy val webSettings = buildSettings ++
    appengineSettings ++ Seq(
    scalaVersion := "2.9.0-1",
    crossScalaVersions := Seq("2.9.0-1", "2.8.1"),
    libraryDependencies ++= Seq(
      "net.databinder" %% "unfiltered-filter" % "0.4.0",
      "net.databinder" %% "unfiltered-uploads" % "0.4.0",
      "javax.servlet" % "servlet-api" % "2.3" % "provided"
    )
  ) ++ noPublish

  lazy val noPublish: Seq[Project.Setting[_]] = Seq(
    publish := {},
    publishLocal := {}
  )

  def testDeps(sv: String) = sv match {
    case "2.8.1" =>   Seq("org.specs2" %% "specs2" % "1.4" % "test")
    case "2.9.0-1" => Seq("org.specs2" %% "specs2" % "1.6.1" % "test",
                          "org.specs2" %% "specs2-scalaz-core" % "6.0.RC2" % "test")
    case _ => Seq("org.specs2" %% "specs2" % "1.6.1" % "test",
                  "org.specs2" %% "specs2-scalaz-core" % "6.0.1" % "test")
  }

  lazy val root = Project("root", file("."),
    settings = buildSettings ++ Seq(name := "scalaxb")) aggregate(cli)
  lazy val cli = Project("app", file("cli"),
    settings = cliSettings)
  lazy val integration = Project("integration", file("integration"),
    settings = itSettings) dependsOn(cli % "test->compile")
  lazy val scalaxbPlugin = Project("sbt-scalaxb", file("sbt-scalaxb"),
    settings = pluginSettings) dependsOn(cli)
  lazy val appengine = Project("web", file("web"),
    settings = webSettings) dependsOn(cli)
}
