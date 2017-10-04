package drestin.akkastream.gitwatcher

import java.io.File
import java.nio.file.{Files, Path}

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.{Constants, ObjectId, Repository}
import org.eclipse.jgit.treewalk.TreeWalk
import org.eclipse.jgit.treewalk.filter.PathFilter

import scala.collection.JavaConverters._

/**
  * Facilites to shorten test code.
  */
object TestUtils {

  /**
    * Returns the [[ObjectId]] of a file in the working tree of the given repository.
    */
  def findFileObjectId(repo: Repository, path: String): ObjectId = {
    val commit = repo.parseCommit(repo.resolve(Constants.HEAD))

    // All this stuff to get the ObjectId of the file...
    val treeWalk = new TreeWalk(repo)
    treeWalk.addTree(commit.getTree)
    treeWalk.setRecursive(true)
    treeWalk.setFilter(PathFilter.create(path))
    treeWalk.next()
    val fileObjectId = treeWalk.getObjectId(0)
    treeWalk.close()

    fileObjectId
  }

  /**
    * Creates a temporary directory and initializes a Git repository inside.
    */
  def initTempGit(prefix: String = "repo"): Git = {
    val git = Git.init().setDirectory(Files.createTempDirectory(prefix).toFile).call()
    git.commit().setAllowEmpty(true).setMessage("Initial commit").call()
    git
  }

  /**
    * Create a new file in the given repository with some optional contents.
    *
    * @param contents the lines to write.
    * @return the absolute path of the created file.
    */
  def createFileInRepo(repo: Repository, relativePath: String, contents: Iterable[String]): Path = {
    val root = repo.getDirectory.getParentFile

    val absolutePath = Files.createFile(new File(root, relativePath).toPath)

    if (contents.nonEmpty) {
      Files.write(absolutePath, contents.asJava)
    }

    absolutePath
  }

}
