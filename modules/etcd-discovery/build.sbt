name := """etcd-discovery"""

resolvers += Resolver.bintrayRepo("yarosman", "maven")

publish := {}

libraryDependencies ++= Seq(
  "com.typesafe.play"         %% "play"                 % "2.6.20" % Provided,
  "com.typesafe.play"         %% "play-json"            % "2.6.10" % Provided,
  ws                                                    % Provided,
  "net.codingwell"            %% "scala-guice"          % Dependencies.guiceVersion % Provided,
  "org.scalatest"             %% "scalatest"            % "3.0.5"  % Test,
  "org.scalatestplus.play"    %% "scalatestplus-play"   % "3.1.2"  % Test,
  "de.leanovate.play-mockws"  %% "play-mockws"          % "2.6.6"  % Test,
  "com.typesafe.akka"         %% "akka-testkit"         % "2.5.17" % Test
)
