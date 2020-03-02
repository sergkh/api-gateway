import Dependencies._
import sbt.Keys._
import scalariform.formatter.preferences._

name := """api-gateway"""

lazy val commonSettings = Seq(
  version := (version in ThisBuild).value,
  scalaVersion := "2.12.7"
)

lazy val `base-models` = project.settings(commonSettings: _*)
lazy val registration = project.settings(commonSettings: _*).dependsOn(`base-models`, kamon)
lazy val kamon = (project in file("./modules/kamon")).settings(commonSettings: _*)
lazy val etcdDiscovery = (project in file("./modules/etcd-discovery")).settings(commonSettings: _*).dependsOn(`base-models`)
lazy val kafka = (project in file("./modules/kafka")).settings(commonSettings: _*).dependsOn(`base-models`)

lazy val root = (project in file("."))
  .enablePlugins(PlayScala, SbtNativePackager, BuildInfoPlugin)
  .aggregate(`base-models`, registration, kamon, etcdDiscovery, kafka)
  .dependsOn(`base-models`, registration, kamon, etcdDiscovery, kafka)
  .settings(commonSettings: _*)


resolvers += "Atlassian Releases" at "https://maven.atlassian.com/public/"
resolvers += "Typesafe repository" at "http://repo.typesafe.com/typesafe/releases/"
resolvers += "Sonatype OSS Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots"
resolvers += Resolver.bintrayRepo("sergkh", "maven")
resolvers += Resolver.bintrayRepo("yarosman", "maven")
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
  "com.mohiva"        %% "play-silhouette"                  % silhouetteVersion,
  "com.mohiva"        %% "play-silhouette-password-bcrypt"  % silhouetteVersion,
  "com.mohiva"        %% "play-silhouette-persistence"      % silhouetteVersion,
  "com.mohiva"        %% "play-silhouette-crypto-jca"       % silhouetteVersion,
  "com.pauldijou"     %% "jwt-core"                         % "4.2.0",
  "org.reactivemongo" %% "play2-reactivemongo"              % "0.20.3-play27",
  "com.twitter"       %% "chill"                            % "0.9.5",
  "org.gnieh"         %% "diffson-play-json"                % "4.0.2",
  "com.impactua"      %% "redis-scala"                      % redisVersion,
  "net.codingwell"    %% "scala-guice"                      % guiceVersion,
  "org.webjars"       % "swagger-ui"                        % "3.22.2",
  "com.iheart"        %% "play-swagger"                     % "0.7.5",
  "com.iheart"        %% "ficus"                            % "1.4.6",
  "org.scala-lang"    %  "scala-reflect"                    % "2.12.7",
  "com.mohiva"        %% "play-silhouette-testkit"          % silhouetteVersion  % Test,
  "org.scalatest"     %% "scalatest"                        % "3.0.8"  % Test,
  "org.mockito"       %  "mockito-core"                     % "2.28.2" % Test,
  "com.typesafe.akka" %% "akka-testkit"                     % "2.5.17" % Test,
  "org.scalatestplus.play"    %% "scalatestplus-play"       % "3.1.2"  % Test,
  "de.leanovate.play-mockws"  %% "play-mockws"              % "2.6.6"  % Test
)

routesGenerator := InjectedRoutesGenerator

scalacOptions in ThisBuild ++= Seq("-feature", "-language:postfixOps","-Xmax-classfile-name","78")

fork in run := true
javaOptions in run += "-Xmx2G"