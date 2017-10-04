package drestin.akkastream.gitwatcher

import java.io.File

import akka.NotUsed
import akka.stream.scaladsl.Source

import scala.concurrent.duration._

/**
  * Builder object of an Akka-Stream Source sending revision of files in a Git repository.
  *
  * @param repoUrl the URL of the Git repository (required)
  * @param branch the name of the branch to track (default: "master")
  * @param delayBetweenUpdates the time between to fetches (default: 1 minute)
  * @param authentification the authentification (see [[GitAuthentification]] subclasses) (default: none)
  * @param onlySendModified if true, only modified files are sent. If false, [[FileChange]]s of unmodified files
  *                         are also sent (default: true).
  */
final case class GitSourceBuilder(
                                   repoUrl: String,
                                   branch: String = "master",
                                   delayBetweenUpdates: FiniteDuration = 1.minute,
                                   authentification: GitAuthentification = NoAuthentification,
                                   onlySendModified: Boolean = true
                                 ) {

  def setRepoUrl(url: String): GitSourceBuilder = copy(repoUrl = url)
  def setBranch(branch: String): GitSourceBuilder = copy(branch = branch)
  def setDelayBetweenUpdates(delay: FiniteDuration): GitSourceBuilder = copy(delayBetweenUpdates = delay)
  def setAuthentification(auth: GitAuthentification): GitSourceBuilder = copy(authentification = auth)
  def setOnlySendModified(onlySendModified: Boolean): GitSourceBuilder = copy(onlySendModified = onlySendModified)

  /**
    * Build a watcher for one particular file.
    *
    * Only changes related to this file are sent.
    * Since there is only one file, the parameter [[onlySendModified]] has no effect on this build.
    *
    * @param relativePath The relative path of the file from the root directory of the repository
    *
    * @return An Akka-Stream Source producing a [[FileChange]] each time the file changes.
    */
  def buildWatchOneFile(relativePath: File): Source[FileChange, NotUsed] = {
    Source.fromGraph(new GitSourceStage(copy(onlySendModified = true)))
      .mapConcat[FileChange] { (fileChanges) =>
        fileChanges.find(_.relativePath == relativePath).toList
      }
  }

  /**
    * Build a watcher on every file of the repository.
    *
    * @return An Akka-Stream Source producing a collection of [[FileChange]]s each time at least one of the files
    *         is modified.
    */
  def buildWatchAllFiles(): Source[Iterable[FileChange], NotUsed] = Source.fromGraph(new GitSourceStage(this))

  /**
    * Build a watcher on some files of the repository.
    *
    * @param watchedFiles The set of the relative paths from the root of the repository.
    *
    * @return An Akka-Stream Source producing a collection of [[FileChange]]s each time at least one of the watched
    *         files is modified
    */
  def buildWatchSomeFiles(watchedFiles: Set[File]): Source[Iterable[FileChange], NotUsed] = {
    Source.fromGraph(new GitSourceStage(this))
      .map(_.filter((fileChange) => watchedFiles.contains(fileChange.relativePath)))
      .filter(_.nonEmpty)
  }

}
