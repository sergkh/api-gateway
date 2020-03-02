name := """registration"""

resolvers += "Atlassian Releases" at "https://maven.atlassian.com/public/"
resolvers += "scalaz-bintray" at "https://dl.bintray.com/scalaz/releases"
resolvers += Resolver.bintrayRepo("sergkh", "maven")

publish := {}

libraryDependencies ++= Seq(
  "com.typesafe.play" %% "play-json" % "2.7.4" % Provided,
  "com.typesafe.play" %% "play" % "2.7.4" % Provided,
  "com.impactua" %% "redis-scala" % Dependencies.redisVersion % Provided,
  "net.codingwell" %% "scala-guice" % Dependencies.guiceVersion,
  "com.mohiva" %% "play-silhouette" % Dependencies.silhouetteVersion % Provided,
  "com.mohiva" %% "play-silhouette-password-bcrypt" % Dependencies.silhouetteVersion % Provided
)
