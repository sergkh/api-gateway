import com.typesafe.sbt.packager.docker.{Cmd, ExecCmd}

def matchArtifact(artifact: Artifact, name: String): Boolean = name.contains(artifact.name) && name.endsWith(artifact.extension)

mappings in Docker := (mappings in Docker).value.map {
  case (jar, path) if matchArtifact((artifact in Docker).value, jar.getName) =>
    jar -> path.replace("/lib/", "/app/")
  case (scala, path) if path.contains("scala-lang") =>
    scala -> path.replace("/lib/", "/scala/")
  case other: Any => other
}

// TODO: fix version change forces full images invalidation
dockerCommands in Docker := dockerCommands.value.flatMap {
  // only replace the command 'ADD --chown=root:root opt /opt' (chown part can change)
  case Cmd("ADD", args @ _*) if args.exists(_.contains("opt /opt")) => {
    val user = (daemonUser in Docker).value
    val group = (daemonGroup in Docker).value
    val loc = (defaultLinuxInstallLocation in Docker).value
    Seq(
      ExecCmd("RUN", "mkdir", "-p", loc),
      ExecCmd("RUN", "chown", "-R", s"$user:$group", "."),
      Cmd("ADD", "opt/docker/scala", loc + "/lib"),
      Cmd("ADD", "opt/docker/lib", loc + "/lib"),
      Cmd("ADD", s"--chown=$user:$group", "opt/docker/bin", loc + "/bin")
    )
  }
  case ExecCmd("RUN", args @ _*) if args.contains("chown") =>
    Nil // remove original chown command
  case other: Any => Seq(other)
} ++ Seq(
  // put application jar at the very end of docker commands
  Cmd("ADD", s"--chown=${(daemonUser in Docker).value}:${(daemonGroup in Docker).value}", "opt/docker/app", (defaultLinuxInstallLocation in Docker).value + "/lib"),
  Cmd("ADD", s"--chown=${(daemonUser in Docker).value}:${(daemonGroup in Docker).value}", "opt/docker/conf", (defaultLinuxInstallLocation in Docker).value + "/conf")
)
