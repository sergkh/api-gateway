name := """registration"""

resolvers += "Atlassian Releases" at "https://maven.atlassian.com/public/"
resolvers += "scalaz-bintray" at "https://dl.bintray.com/scalaz/releases"
resolvers += Resolver.bintrayRepo("sergkh", "maven")

publish := {}

libraryDependencies ++= Seq(
  "com.typesafe.play" %% "play-json" % "2.6.10" % Provided,
  "com.typesafe.play" %% "play" % "2.6.20" % Provided,
  "com.impactua" %% "play2-auth" % Dependencies.authVersion % Provided,
  "com.impactua" %% "redis-scala" % Dependencies.redisVersion % Provided,
  "net.codingwell" %% "scala-guice" % Dependencies.guiceVersion,
  "com.mohiva" %% "play-silhouette" % Dependencies.silhouetteVersion % Provided,
  "com.mohiva" %% "play-silhouette-password-bcrypt" % Dependencies.silhouetteVersion % Provided
)
