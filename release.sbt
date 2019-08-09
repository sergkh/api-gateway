import ReleaseTransformations._

skip in publish := true

releaseIgnoreUntrackedFiles := true

releaseProcess := Seq[ReleaseStep](
  checkSnapshotDependencies,
  inquireVersions,
  runClean,
  runTest,
  setReleaseVersion,
  commitReleaseVersion,
  tagRelease,
  // releaseStepTask(publish in Docker),
  setNextVersion,
  commitNextVersion,
  pushChanges
)