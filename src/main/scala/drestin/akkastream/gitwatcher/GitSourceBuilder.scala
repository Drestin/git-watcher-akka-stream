package drestin.akkastream.gitwatcher

import java.io.File

import akka.NotUsed
import akka.stream.scaladsl.Source

import scala.concurrent.duration._

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

  def buildWatchOneFile(relativePath: File): Source[FileChange, NotUsed] = {
    Source.fromGraph(new GitSourceStage(copy(onlySendModified = true)))
      .mapConcat[FileChange] { (fileChanges) =>
        fileChanges.find(_.relativePath == relativePath).toList
      }
  }

  def buildWatchAllFiles(): Source[Iterable[FileChange], NotUsed] = Source.fromGraph(new GitSourceStage(this))

  def buildWatchSomeFiles(watchedFiles: Set[File]): Source[Iterable[FileChange], NotUsed] = {
    Source.fromGraph(new GitSourceStage(this))
      .map(_.filter((fileChange) => watchedFiles.contains(fileChange.relativePath)))
      .filter(_.nonEmpty)
  }

}
