val scala3Version = "3.6.4"
val catsEffectVersion = "3.5.7"
val fs2Version = "3.11.0"
val munitVersion = "1.1.0"
val munitCEVersion = "2.0.0"
val scalacheckVersion = "1.18.1"
val scodecBitsVersion = "1.1.37"
val scodecCoreVersion = "2.1.0"
val http4sVersion = "0.23.30"
val circeVersion = "0.14.10"
val log4catsVersion = "2.7.0"
val logbackVersion = "1.5.16"
val bouncyCastleVersion = "1.80"

ThisBuild / organization := "io.github.clawdano"
ThisBuild / version := "0.1.0-SNAPSHOT"
ThisBuild / scalaVersion := scala3Version

ThisBuild / scalacOptions ++= Seq(
  "-deprecation",
  "-feature",
  "-unchecked",
  "-Xfatal-warnings",
  "-Wunused:all",
  "-language:higherKinds",
)

lazy val commonSettings = Seq(
  libraryDependencies ++= Seq(
    "org.scalameta" %% "munit" % munitVersion % Test,
    "org.typelevel" %% "munit-cats-effect" % munitCEVersion % Test,
    "org.scalacheck" %% "scalacheck" % scalacheckVersion % Test,
  ),
)

lazy val root = project
  .in(file("."))
  .aggregate(core, serialization, network, consensus, ledger, mempool, storage, node, cli)
  .settings(
    name := "stretto",
    publish / skip := true,
  )

lazy val core = project
  .in(file("modules/core"))
  .settings(commonSettings)
  .settings(
    name := "stretto-core",
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-effect" % catsEffectVersion,
      "org.scodec" %% "scodec-bits" % scodecBitsVersion,
      "org.scodec" %% "scodec-core" % scodecCoreVersion,
      "org.bouncycastle" % "bcprov-jdk18on" % bouncyCastleVersion,
    ),
  )

lazy val serialization = project
  .in(file("modules/serialization"))
  .dependsOn(core)
  .settings(commonSettings)
  .settings(
    name := "stretto-serialization",
    libraryDependencies ++= Seq(
      "org.scodec" %% "scodec-bits" % scodecBitsVersion,
      "org.scodec" %% "scodec-core" % scodecCoreVersion,
    ),
  )

lazy val network = project
  .in(file("modules/network"))
  .dependsOn(core, serialization)
  .settings(commonSettings)
  .settings(
    name := "stretto-network",
    libraryDependencies ++= Seq(
      "co.fs2" %% "fs2-core" % fs2Version,
      "co.fs2" %% "fs2-io" % fs2Version,
    ),
  )

lazy val consensus = project
  .in(file("modules/consensus"))
  .dependsOn(core, ledger, network)
  .settings(commonSettings)
  .settings(
    name := "stretto-consensus",
  )

lazy val ledger = project
  .in(file("modules/ledger"))
  .dependsOn(core, serialization)
  .settings(commonSettings)
  .settings(
    name := "stretto-ledger",
    libraryDependencies ++= Seq(
      // scalus for Plutus evaluation
      "org.scalus" %% "scalus" % "0.15.1",
    ),
  )

lazy val mempool = project
  .in(file("modules/mempool"))
  .dependsOn(core, ledger)
  .settings(commonSettings)
  .settings(
    name := "stretto-mempool",
  )

lazy val storage = project
  .in(file("modules/storage"))
  .dependsOn(core, serialization)
  .settings(commonSettings)
  .settings(
    name := "stretto-storage",
    libraryDependencies ++= Seq(
      "co.fs2" %% "fs2-io" % fs2Version,
      "org.typelevel" %% "cats-effect" % catsEffectVersion,
      "org.rocksdb" % "rocksdbjni" % "9.7.3",
    ),
  )

lazy val node = project
  .in(file("modules/node"))
  .dependsOn(core, serialization, network, consensus, ledger, mempool, storage)
  .enablePlugins(BuildInfoPlugin)
  .settings(commonSettings)
  .settings(
    name := "stretto-node",
    buildInfoKeys := Seq[BuildInfoKey](name, version, scalaVersion, sbtVersion),
    buildInfoPackage := "stretto",
    libraryDependencies ++= Seq(
      "org.typelevel" %% "log4cats-slf4j" % log4catsVersion,
      "ch.qos.logback" % "logback-classic" % logbackVersion % Runtime,
      "org.http4s" %% "http4s-ember-server" % http4sVersion,
      "org.http4s" %% "http4s-dsl" % http4sVersion,
      "io.circe" %% "circe-core" % circeVersion,
    ),
  )

lazy val cli = project
  .in(file("modules/cli"))
  .dependsOn(node)
  .settings(commonSettings)
  .settings(
    name := "stretto-cli",
    Compile / mainClass := Some("stretto.cli.Main"),
    Compile / run / fork := true,
    libraryDependencies ++= Seq(
      "org.typelevel" %% "log4cats-slf4j" % log4catsVersion,
      "ch.qos.logback" % "logback-classic" % logbackVersion % Runtime,
    ),
  )
