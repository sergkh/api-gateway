import Dependencies._

name := "kafka"

publish := {}

libraryDependencies ++= Seq(
  "dev.zio"                   %% "zio-kafka"        % "0.8.0",
  "net.codingwell"            %% "scala-guice"      % Dependencies.guiceVersion % Provided
)