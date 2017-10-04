package drestin.akkastream.gitwatcher

import java.io.{BufferedReader, File, InputStreamReader}
import java.nio.file.Files

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.ObjectId
import org.scalatest.{BeforeAndAfter, FlatSpec, Matchers}

import scala.collection.JavaConverters._

class FileChangeSuite extends FlatSpec with BeforeAndAfter with Matchers {
  var git: Git = _
  val configPath = "dir/config.txt"
  var fileObjectId: ObjectId = _

  before {
    git = TestUtils.initTempGit()
    val root = git.getRepository.getDirectory.getParentFile

    Files.createDirectory(new File(root, "dir").toPath)
    Files.write(new File(root, configPath).toPath, Seq("123", "éĕēȩ").asJava)

    git.add()
      .addFilepattern(configPath)
      .call()
    git.commit()
      .setMessage("commit")
      .call()

    fileObjectId = TestUtils.findFileObjectId(git.getRepository, configPath)
  }

  "A FileChange" should "have no contents if the file was destroyed" in {
    val deletedFileChange = new FileChange(git.getRepository, new File("config.txt"), FileChange.ChangeStatus.Deleted)

    deletedFileChange.stillExists shouldBe false
    val stream = deletedFileChange.openContentsStream()
    stream.available() shouldBe 0
    stream.close()
    deletedFileChange.getTextContents.isEmpty shouldBe true
  }

  it should "contain the file contents if the file exists (supporting UTF-8)" in {
    val existingFileChange = new FileChange(git.getRepository,
                                            new File(configPath),
                                            FileChange.ChangeStatus.Modified,
                                            Some(fileObjectId))

    existingFileChange.stillExists shouldBe true
    existingFileChange.getTextContents.toList shouldBe List("123", "éĕēȩ")
  }

  it can "stream the contents concurrently with another FileChange" in {
    val fileChange1 = new FileChange(git.getRepository,
      new File(configPath),
      FileChange.ChangeStatus.Created,
      Some(fileObjectId))
    val fileChange2 = new FileChange(git.getRepository,
      new File(configPath),
      FileChange.ChangeStatus.Unchanged,
      Some(fileObjectId))

    fileChange1.stillExists shouldBe true
    fileChange2.stillExists shouldBe true

    val stream1 = fileChange1.openContentsStream()
    val stream2 = fileChange2.openContentsStream()
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
