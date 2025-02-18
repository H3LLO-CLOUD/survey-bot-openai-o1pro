
val pekkoVersion = "1.1.0"
val SlickVersion = "3.5.1"

run / mainClass := Some("ru.h3llo.surveybot.SurveyMicroservice")
Compile / run / mainClass := Some("ru.h3llo.surveybot.SurveyMicroservice")

enablePlugins(JavaServerAppPackaging)

lazy val root = (project in file("."))
  .settings(
    organization := "ru.h3llo",
    name := "survey-bot",
    version := "0.0.1-SNAPSHOT",
    scalaVersion := "3.3.4",
    libraryDependencies ++= Seq(
      "org.apache.pekko" %% "pekko-actor"            % pekkoVersion,
      "org.apache.pekko" %% "pekko-stream"           % pekkoVersion,
      "org.apache.pekko" %% "pekko-http"             % pekkoVersion,
      "org.apache.pekko" %% "pekko-http-spray-json"  % pekkoVersion,  // for JSON marshalling
      "com.typesafe.slick" %% "slick"                % SlickVersion,
      "com.typesafe.slick" %% "slick-hikaricp"       % SlickVersion,  // HikariCP for connection pooling
      "org.postgresql"      % "postgresql"           % "42.7.5",
      "ch.qos.logback"      % "logback-classic"      % "1.5.16"  // logging
    )
  )


