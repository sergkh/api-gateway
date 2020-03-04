name := """etcd-discovery"""

publish := {}

libraryDependencies ++= Seq(
  "com.typesafe.play"         %% "play"                 % Dependencies.playVersion % Provided,
  "com.typesafe.play"         %% "play-json"            % Dependencies.playVersion % Provided,
  ws                                                    % Provided,
  "net.codingwell"            %% "scala-guice"          % Dependencies.guiceVersion % Provided,
  "org.scalatest"             %% "scalatest"            % "3.1.1"  % Test,
  "org.scalatestplus.play"    %% "scalatestplus-play"   % "5.0.0"  % Test,
  "de.leanovate.play-mockws"  %% "play-mockws"          % "2.8.0"  % Test,
  "com.typesafe.akka"         %% "akka-testkit"         % "2.6.3" % Test
)
