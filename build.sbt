import Dependencies._
import sbt.Keys._

name := """api-gateway"""

ThisBuild / scalaVersion := "2.13.2"
ThisBuild / crossScalaVersions := Seq("2.13.2")

lazy val commonSettings = Seq(
  version := (version in ThisBuild).value,
  scalaVersion := "2.13.2"
)

lazy val `core` = (project in file("core")).settings(commonSettings: _*)
lazy val etcdDiscovery = (project in file("modules/etcd-discovery")).settings(commonSettings: _*).dependsOn(`core`)
lazy val kafka = (project in file("modules/kafka")).settings(commonSettings: _*).dependsOn(`core`)

lazy val root = (project in file("."))
  .enablePlugins(PlayScala, JavaAgent, SbtNativePackager, BuildInfoPlugin)
  .aggregate(`core`, etcdDiscovery, kafka)
  .dependsOn(`core`, etcdDiscovery, kafka)
  .settings(commonSettings: _*)


resolvers += "Atlassian Releases" at "https://maven.atlassian.com/public/"
resolvers += "Typesafe repository" at "https://repo.typesafe.com/typesafe/releases/"
resolvers += "Sonatype OSS Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots"
resolvers += "scalaz-bintray" at "https://dl.bintray.com/scalaz/releases"
resolvers += Resolver.bintrayRepo("sergkh", "maven")
resolvers += Resolver.jcenterRepo
resolvers += Resolver.mavenLocal

packageName in Docker := sys.env.getOrElse("PROJECT_NAME", name.value)
version in Docker := version.value
dockerExposedPorts in Docker := Seq(9000)
dockerBaseImage := "anapsix/alpine-java:8_server-jre_unlimited"
dockerUsername := Some(sys.env.getOrElse("REGISTRY_USER_NAME", "sergkh"))
dockerRepository := Some(sys.env.getOrElse("REGISTRY_NAME", "repo.treescale.com"))
dockerUpdateLatest := true

buildInfoKeys := Seq[BuildInfoKey](name,  version in ThisBuild, scalaVersion, sbtVersion)
buildInfoPackage := "utils"
buildInfoUsePackageAsPath := true
buildInfoOptions += BuildInfoOption.ToJson


libraryDependencies ++= Seq(
  ehcache,
  filters,
  guice,
  ws,
  "com.pauldijou"     %% "jwt-play"                         % "4.3.0",
  "com.iheart"        %% "ficus"                            % "1.4.7",
  "com.iheart"        %% "play-swagger"                     % "0.9.1-PLAY2.8",
  "org.gnieh"         %% "diffson-play-json"                % "4.0.2",
  "com.impactua"      %% "redis-scala"                      % redisVersion,
  "net.codingwell"    %% "scala-guice"                      % guiceVersion,
  "org.webjars"       % "swagger-ui"                        % "3.25.2",
  "org.scala-lang"    %  "scala-reflect"                    % "2.13.2",
  "io.kamon"          %% "kamon-bundle"                     % "2.1.0",
  "com.mohiva"        %% "play-silhouette-testkit"          % silhouetteVersion  % Test,
  "org.scalatest"     %% "scalatest"                        % "3.1.2"  % Test,
  "org.mockito"       %  "mockito-core"                     % "3.3.3"  % Test,
  "org.mockito"        %% "mockito-scala"                   % "1.14.0" % Test,
  "com.typesafe.akka" %% "akka-testkit"                     % "2.6.3"  % Test,
  "org.scalatestplus.play"   %% "scalatestplus-play"        % "5.1.0"  % Test,
  "de.leanovate.play-mockws" %% "play-mockws"               % "2.8.0"  % Test,
  "de.flapdoodle.embed"       % "de.flapdoodle.embed.mongo" % "2.2.0" % Test
)

routesGenerator := InjectedRoutesGenerator

scalacOptions in ThisBuild ++= Seq("-feature", "-language:postfixOps")

fork in run := true
javaOptions in run += "-Xmx2G"