val akkaVersion     = "2.8.5"
val akkaHttpVersion = "10.5.3"

name := "optimum-bot"
version := "1.0.0"

ThisBuild / scalaVersion := "2.13.12"

lazy val root = (project in file("."))
  .settings(
    libraryDependencies ++= Seq(
      "com.typesafe" % "config" % "1.4.2",
      "com.typesafe.akka" %% "akka-actor-typed" % akkaVersion,
      "com.typesafe.akka" %% "akka-stream" % akkaVersion,
      "com.typesafe.akka" %% "akka-http" % akkaHttpVersion,
      "com.typesafe.akka" %% "akka-http-spray-json" % akkaHttpVersion,
      "com.typesafe.scala-logging" %% "scala-logging" % "3.9.5",
      "ch.qos.logback" % "logback-classic" % "1.4.14",
      "org.codehaus.janino" % "janino" % "3.1.6",
      "com.github.napstr" % "logback-discord-appender" % "1.0.0",
      "net.dv8tion" % "JDA" % "5.2.1",
      "club.minnced" % "discord-webhooks" % "0.8.2",
      "org.apache.commons" % "commons-text" % "1.10.0",
      "org.postgresql" % "postgresql" % "42.5.4",
      "com.google.guava" % "guava" % "30.1.1-jre",
      "dev.arbjerg" % "lavaplayer" % "2.1.1",
      "org.scalactic" %% "scalactic" % "3.2.15",
      "org.scalatest" %% "scalatest" % "3.2.15" % Test,
      "org.scalamock" %% "scalamock" % "5.2.0" % Test,
      "com.softwaremill.sttp.client3" %% "core" % "3.3.18",
      "org.jsoup" % "jsoup" % "1.17.2",
      "io.circe" %% "circe-core" % "0.14.10",
      "io.circe" %% "circe-parser" % "0.14.10"
    ),

    resolvers += "jitpack" at "https://jitpack.io"
  )

assembly / mainClass := Some("com.tibiabot.BotApp")

assembly / assemblyMergeStrategy := {
  case PathList("google", "protobuf", xs @ _*) => MergeStrategy.first
  case PathList("META-INF", "okio.kotlin_module") => MergeStrategy.first

  case PathList("reference.conf")   => MergeStrategy.concat
  case PathList("application.conf") => MergeStrategy.concat

  case PathList("META-INF", "services", xs @ _*) => MergeStrategy.concat

  case PathList("META-INF", "versions", xs @ _*)
      if xs.lastOption.contains("module-info.class") =>
    MergeStrategy.discard

  case "module-info.class" => MergeStrategy.discard

  case PathList("META-INF", "MANIFEST.MF") => MergeStrategy.discard
  case PathList("META-INF", "LICENSE")     => MergeStrategy.discard
  case PathList("META-INF", "LICENSE.txt") => MergeStrategy.discard
  case PathList("META-INF", "NOTICE")      => MergeStrategy.discard
  case PathList("META-INF", "NOTICE.txt")  => MergeStrategy.discard

  case PathList("META-INF", xs @ _*)
      if xs.lastOption.exists(_.endsWith(".SF")) =>
    MergeStrategy.discard

  case PathList("META-INF", xs @ _*)
      if xs.lastOption.exists(_.endsWith(".DSA")) =>
    MergeStrategy.discard

  case PathList("META-INF", xs @ _*)
      if xs.lastOption.exists(_.endsWith(".RSA")) =>
    MergeStrategy.discard

  case x =>
    val old = (assembly / assemblyMergeStrategy).value
    old(x)
}