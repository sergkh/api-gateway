import Dependencies._

name := """base-models"""

publish := ()

resolvers += "Atlassian Releases" at "https://maven.atlassian.com/public/"
resolvers += "Typesafe repository" at "https://repo.typesafe.com/typesafe/releases/"
resolvers += "Sonatype OSS Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots"
resolvers += "scalaz-bintray" at "https://dl.bintray.com/scalaz/releases"
resolvers += Resolver.bintrayRepo("sergkh", "maven")
resolvers += Resolver.jcenterRepo
resolvers += Resolver.mavenLocal

libraryDependencies ++= Seq(
  "com.typesafe.play"         %% "play"                             % playVersion % Provided,
  "com.typesafe.play"         %% "play-json"                        % playVersion % Provided,
  "com.mohiva"                %% "play-silhouette"                  % silhouetteVersion,
  "com.mohiva"                %% "play-silhouette-password-bcrypt"  % silhouetteVersion,
  "com.mohiva"                %% "play-silhouette-persistence"      % silhouetteVersion,
  "com.mohiva"                %% "play-silhouette-crypto-jca"       % silhouetteVersion,
  "org.mongodb.scala"         %% "mongo-scala-driver"               % "2.9.0",
  "dev.zio"                   %% "zio"                              % "1.0.0-RC18-2",
  "dev.zio"                   %% "zio-streams"                      % "1.0.0-RC18-2",
  "org.scalatest"             %% "scalatest"                        % "3.1.1"  % Test
)