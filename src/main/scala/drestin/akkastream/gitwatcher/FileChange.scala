package drestin.akkastream.gitwatcher
import java.io.{ByteArrayInputStream, File, InputStream}

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
    * Opens an [[InputStream]] streaming the new content of the file.
    *
    * It is left to the user to close it after use.
    * If the file was deleted, the returned stream will be empty.
    *
    * @return An [[InputStream]] of the new content of the file.
    */
  def openContentsStream(): InputStream = {
    if (stillExists) {
      repo.open(objectId.get).openStream()
    } else {
      new ByteArrayInputStream(Array.emptyByteArray)
    }
  }

  /**
    * Returns true if the file still exists, and its contents be accessed
    */
  def stillExists : Boolean = changeStatus != FileChange.ChangeStatus.Deleted

  override def toString: String = s"FileChange[$changeStatus $relativePath]"
}

object FileChange {

  /**
    * Enumeration of the possible modification status.
    */
  final object ChangeStatus extends Enumeration {
    val Created, Modified, Deleted, Unchanged = Value
  }
}
