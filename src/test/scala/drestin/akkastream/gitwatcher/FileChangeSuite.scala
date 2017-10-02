package drestin.akkastream.gitwatcher

import java.io.{BufferedReader, File, InputStreamReader}
import java.nio.file.Files

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.{Constants, ObjectId}
import org.eclipse.jgit.treewalk.TreeWalk
import org.eclipse.jgit.treewalk.filter.PathFilter
import org.scalatest.{BeforeAndAfter, FlatSpec, Matchers}

import scala.collection.JavaConverters._

class FileChangeSuite extends FlatSpec with BeforeAndAfter with Matchers {
  var git: Git = _
  val config = "dir/config.txt"
  var fileOblectId: ObjectId = _

  before {
    val root = Files.createTempDirectory("repo").toFile
    git = Git.init()
      .setDirectory(root)
      .call()

    Files.createDirectory(new File(root, "dir").toPath)
    Files.write(new File(root, config).toPath, Seq("123", "éĕēȩ").asJava)

    git.add()
      .addFilepattern(config)
      .call()
    git.commit()
      .setMessage("commit")
      .call()

    val repo = git.getRepository
    val commit = repo.parseCommit(repo.resolve(Constants.HEAD))

    // All this stuff to get the ObjectId of the file...
    val treeWalk = new TreeWalk(repo)
    treeWalk.addTree(commit.getTree)
    treeWalk.setRecursive(true)
    treeWalk.setFilter(PathFilter.create(config))
    treeWalk.next()
    fileOblectId = treeWalk.getObjectId(0)
    treeWalk.close()
  }

  "The FileChange of a deleted file" should "not be readable" in {
    val deletedFileChange = new FileChange(git.getRepository, new File("config.txt"), FileChange.ChangeStatus.Deleted)

    deletedFileChange.stillExists() shouldBe false
    an [IllegalStateException] shouldBe thrownBy {
      deletedFileChange.openContentStream()
    }
  }

  "The FileChange of an existing file" should "stream all the contents of the related file" in {
    val existingFileChange = new FileChange(git.getRepository,
                                            new File(config),
                                            FileChange.ChangeStatus.Modified,
                                            Some(fileOblectId))

    existingFileChange.stillExists() shouldBe true
    val stream = existingFileChange.openContentStream()
    val textStream = new BufferedReader(new InputStreamReader(stream))

    textStream.readLine() shouldBe "123"
    textStream.readLine() shouldBe "éĕēȩ"
    // End of the stream
    textStream.readLine() shouldBe null
    textStream.close()
  }

  "A file" can "be read from 2 FileChanges concurrently" in {
    val fileChange1 = new FileChange(git.getRepository,
      new File(config),
      FileChange.ChangeStatus.Created,
      Some(fileOblectId))
    val fileChange2 = new FileChange(git.getRepository,
      new File(config),
      FileChange.ChangeStatus.Unchanged,
      Some(fileOblectId))

    fileChange1.stillExists() shouldBe true
    fileChange2.stillExists() shouldBe true

    val stream1 = fileChange1.openContentStream()
    val stream2 = fileChange2.openContentStream()
    val textStream1 = new BufferedReader(new InputStreamReader(stream1))
    val textStream2 = new BufferedReader(new InputStreamReader(stream2))

    textStream1.readLine() shouldBe "123"
    textStream2.readLine() shouldBe "123"
    textStream2.readLine() shouldBe "éĕēȩ"
    textStream1.readLine() shouldBe "éĕēȩ"
    // End of the streams
    textStream1.readLine() shouldBe null
    textStream2.readLine() shouldBe null

    textStream1.close()
    textStream2.close()
  }

  after {
    git.close()
  }


}
