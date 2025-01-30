val scala3Version = "3.6.3"

lazy val root = project
  .in(file("."))
  .settings(
    name := "survey-bot",
    version := "0.1.0-SNAPSHOT",

    scalaVersion := scala3Version,

    libraryDependencies ++= Seq(
      "org.apache.pekko" %% "pekko-http"            % "1.1.0",      // Adjust to the latest Pekko HTTP version
      "org.apache.pekko" %% "pekko-http-spray-json" % "1.1.0",      // For JSON support
      "org.apache.pekko" %% "pekko-actor-typed"     % "1.1.0",
      "org.apache.pekko" %% "pekko-stream"          % "1.1.0",
      "com.typesafe.slick" %% "slick"               % "3.5.2",
      "org.postgresql"     %  "postgresql"          % "42.7.5",
      "com.typesafe.slick" %% "slick-hikaricp"      % "3.5.2",
      "org.scalameta" %% "munit" % "1.1.0" % Test
    )
  )
