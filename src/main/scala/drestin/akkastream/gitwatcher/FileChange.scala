package drestin.akkastream.gitwatcher
import java.io.{File, InputStream}

import org.eclipse.jgit.lib.{ObjectId, Repository}

/**
  * The status of a file since the last revision.
  *
  * It also offer the unique access to the file's contents.
  *
  * @param relativePath the file's relative path inside the repository tree.
  * @param changeStatus the change status since the last revision.
  */
final class FileChange private[gitwatcher] (private val repo: Repository,
                                            val relativePath: File,
                                            val changeStatus: FileChange.ChangeStatus.Value,
                                            private val objectId: Option[ObjectId] = None) {

  /**
    * Opens an InputStream streaming the new content of the file.
    *
    * It is left to the user to close the stream after use.
    * Must not be used if the file has been deleted ([[changeStatus]] is [[FileChange.ChangeStatus.Deleted]] and
    * [[stillExists{}]] returns false.
    *
    * @return the InputStream of the new content of the file.
    * @throws java.lang.IllegalStateException if the file no longer exists.
    */
  def openContentStream(): InputStream = {
    if (!stillExists()) {
      throw new IllegalStateException("Trying to read the content of a deleted file.")
    }

    repo.open(objectId.get).openStream()
  }

  /**
    * Returns true if the file still exists, and its contents be accessed
    */
  def stillExists() : Boolean = changeStatus != FileChange.ChangeStatus.Deleted
}

object FileChange {

  /**
    * Enumeration of the possible modification status.
    */
  final object ChangeStatus extends Enumeration {
    val Created, Modified, Deleted, Unchanged = Value
  }
}
