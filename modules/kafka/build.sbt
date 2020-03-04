import Dependencies._

name := "kafka"

resolvers += "Atlassian Releases" at "https://maven.atlassian.com/public/"
resolvers += "scalaz-bintray" at "https://dl.bintray.com/scalaz/releases"
resolvers += Resolver.jcenterRepo
resolvers += Resolver.bintrayRepo("yarosman", "maven")

publish := {}
