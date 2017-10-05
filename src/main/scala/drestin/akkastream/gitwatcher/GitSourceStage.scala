package drestin.akkastream.gitwatcher

import java.io.{File, IOException}
import java.nio.file.Files

import akka.stream.stage.{GraphStage, OutHandler, TimerGraphStageLogic}
import akka.stream.{Attributes, Outlet, SourceShape}
import drestin.akkastream.gitwatcher.GitSourceStage.{WatchAll, WatchPolicy, WatchSome}
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.errors.TransportException
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.treewalk._

import scala.collection.JavaConverters._
import scala.collection.mutable

/**
  * Source Stage of a Git watcher.
  *
  * It produces [[FileChange]]s when the tracked branch changes.
  * @param builder The builder use to create this object. It contains every parameters.
  * @param watchPolicy watch all files or only some.
  */
private[gitwatcher] class GitSourceStage(val builder: GitSourceBuilder, val watchPolicy: WatchPolicy)
  extends GraphStage[SourceShape[Iterable[FileChange]]] {

  private val out = Outlet[Iterable[FileChange]]("GitSourceStage.out")

  override def shape = SourceShape(out)

  override def createLogic(inheritedAttributes: Attributes): TimerGraphStageLogic =
    new TimerGraphStageLogic(shape) {
      private var git: Git = _
      // trace of the last commit sent into the stream
      private var lastSentCommit: ObjectId = _
      // The commit pointed by the head of the tracked branch
      private var newCommit: ObjectId = _

      private var downstreamWaiting: Boolean = false

      override def preStart(): Unit = {
        try {
          // The local repository is created for each object
          val dir = Files.createTempDirectory("gitwatcher")

          git = Git.cloneRepository()
            .setURI(builder.repoUrl)
            .setDirectory(dir.toFile)
            .setTransportConfigCallback(builder.authentification)
            // we don't want to work on this repo
            .setBare(true)
            // we only want the tracked branch
            .setCloneAllBranches(false)
            .setBranch(builder.branch)
            .call()

          val repo = git.getRepository

          val config = repo.getConfig
          // Option needed to fetch the branch on a bare repository
          config.setString("remote", "origin", "fetch",
            s"refs/heads/${builder.branch}:refs/heads/${builder.branch}")
          config.save()

        } catch {
          // todo manage network exceptions
          case e: Exception => fail(out, e)
        }
        fetch()
        // The ticks to check if the remote changed
        scheduleOnce(Unit, builder.delayBetweenUpdates)
      }

      override def postStop(): Unit = {
        if (git != null) {
          git.close()
        }
      }

      setHandler(out, new OutHandler {
        override def onPull(): Unit = {
          // signals that the downstream asked for a value
          downstreamWaiting = true
          // if the branch already changed, push immediatly
          pushIfNeeded()
        }
      })

      /**
        * Called every [[builder.delayBetweenUpdates]]. Fetches the last commit and push the changes if the branch
        * changes and the downstream is already waiting.
        *
        * @param timerKey not used
        */
      override protected def onTimer(timerKey: Any): Unit = {
        fetch()
        pushIfNeeded()
      }

      /**
        * Fetch the last commit on the branch and updates [[newCommit]].
        */
      private def fetch(): Unit = {
        try {
          git.fetch()
            .setTransportConfigCallback(builder.authentification)
            .call()

          val repo = git.getRepository
          newCommit = repo.resolve(s"${builder.branch}^{tree}")

          // compare commits Ids
          if (newCommit == null) throw new IllegalStateException("The tracked branch no longer exists")
        } catch {
          // todo handle network exception
          case e: Exception => fail(out, e)
        } finally {
          scheduleOnce(Unit, builder.delayBetweenUpdates)
        }
      }

      /**
        * If the downstream has pulled and the branch changed: pushes the FileChanges and updates [[downstreamWaiting]],
        * [[newCommit]] and [[lastSentCommit]].
        */
      private def pushIfNeeded(): Unit = {
        // Can't push or no changes: do nothing
        if (!downstreamWaiting || newCommit == lastSentCommit) return

        try {
          val fileChanges = createFileChanges()

          if (fileChanges.nonEmpty) {
            // We send the FileChanges
            push(out, fileChanges)
            downstreamWaiting = false
            // we only update the commit if we send something
            lastSentCommit = newCommit
          }
        } catch {
          case e: Exception => fail(out, e)
        }
      }

      /**
        * Computes the contents to push downstream. Can return an empty iterable if there is nothing to send.
        */
      private def createFileChanges(): Iterable[FileChange] = {
        // fetch changes
        val repo = git.getRepository

        // The files tree of the last commit
        val oldTree = new CanonicalTreeParser()
        if (lastSentCommit != null) {
          oldTree.reset(repo.newObjectReader(), lastSentCommit)
        }

        // The files tree of the new commit
        val newTree = new CanonicalTreeParser()
        newTree.reset(repo.newObjectReader(), newCommit)

        // The diff between the files in the two commits
        val diffResults = git.diff()
          .setOldTree(oldTree)
          .setNewTree(newTree)
          .call()
          .asScala

        // if some files are different, push the list of FileChanges
        if (diffResults.isEmpty) {
          Seq.empty
        } else {
          val allFileChanges = diffResults.map { (diffEntry) => {
            val changeStatus = diffEntry.getChangeType match {
              case DiffEntry.ChangeType.DELETE => FileChange.ChangeStatus.Deleted
              case DiffEntry.ChangeType.MODIFY => FileChange.ChangeStatus.Modified
              case _ => FileChange.ChangeStatus.Created
            }

            val path = if (changeStatus == FileChange.ChangeStatus.Deleted) diffEntry.getOldPath else diffEntry.getNewPath

            new FileChange(repo,
              relativePath = new File(path),
              changeStatus = changeStatus,
              objectId = Some(diffEntry.getNewId.toObjectId))
            }
          }

          // filter on the watched files
          val watchedFileChanges = watchPolicy match {
            case WatchAll => allFileChanges
            case WatchSome(watchedFiles) => allFileChanges.filter((fc) => watchedFiles.contains(fc.relativePath))
          }

          if (watchedFileChanges.isEmpty || builder.onlySendModified) {
            // we don't need to list the unchanged files.
            watchedFileChanges
          } else {
            // We also want the unmodified files: add the files in the tree not in the diffs
            val unchangedWatchedFiles = listWatchedFilesInTree(newTree)
              .filterNot { case (f, _) => allFileChanges.exists((fc) => fc.relativePath == f) }
              .map { case (f, id) =>
                new FileChange(repo, f, FileChange.ChangeStatus.Unchanged, Some(id))
              }

            watchedFileChanges ++ unchangedWatchedFiles
          }
        }
      }

      /**
        * Return a list of every watched files in the given tree iterator.
        */
      private def listWatchedFilesInTree(treeIterator: AbstractTreeIterator): Iterable[(File, ObjectId)] = {
        treeIterator.reset()
        val treeWalk = new TreeWalk(git.getRepository)
        treeWalk.setRecursive(true)
        treeWalk.addTree(treeIterator)

        val files = mutable.Buffer[(File, ObjectId)]()
        while(treeWalk.next()) {
          val path = new File(treeWalk.getPathString)
          val addFile = watchPolicy match {
            case WatchAll => true
            case WatchSome(watchedFiles) => watchedFiles.contains(path)
          }

          if (addFile) files.append((path, treeWalk.getObjectId(0)))
        }

        files
      }
    }

}

private[gitwatcher] object GitSourceStage {
  sealed trait WatchPolicy

  case object WatchAll extends WatchPolicy
  final case class WatchSome(watchedFiles: Set[File]) extends WatchPolicy
}