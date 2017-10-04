name := "git-watcher-akka-stream"

version := "0.1"

scalaVersion := "2.12.3"

val akkaVersion = "2.5.4"

// Akka-Stream
libraryDependencies += "com.typesafe.akka" % "akka-stream_2.12" % akkaVersion
// JGit
libraryDependencies += "org.eclipse.jgit" % "org.eclipse.jgit" % "4.8.0.201706111038-r"
// SLF4J needed by JGit
libraryDependencies += "org.slf4j" % "slf4j-simple" % "1.7.25"

// ScalaTest
libraryDependencies += "org.scalatest" % "scalatest_2.12" % "3.0.4" % Test
// Akka TestKit
libraryDependencies += "com.typesafe.akka" % "akka-testkit_2.12" % akkaVersion % Test
// Akka-Stream TestKit
libraryDependencies += "com.typesafe.akka" % "akka-stream-testkit_2.12" % akkaVersion % Test

