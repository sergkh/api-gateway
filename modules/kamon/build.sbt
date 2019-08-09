name := "kamon"

resolvers += "Atlassian Releases" at "https://maven.atlassian.com/public/"
resolvers += "scalaz-bintray" at "https://dl.bintray.com/scalaz/releases"
resolvers += Resolver.jcenterRepo

val kamonVersion = "0.6.7"

publish := {}

libraryDependencies ++= Seq(
  "com.typesafe.play" %% "play"               % "2.6.20" % Provided,
  "net.codingwell"    %% "scala-guice"        % Dependencies.guiceVersion,
  "io.kamon"          %% "kamon-core"         % kamonVersion
)
