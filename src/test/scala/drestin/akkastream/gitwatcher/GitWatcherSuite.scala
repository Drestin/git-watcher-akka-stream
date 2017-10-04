package drestin.akkastream.gitwatcher

import java.io.File
import java.nio.file.{Files, StandardOpenOption}

import akka.actor.ActorSystem
import akka.stream.testkit.scaladsl.TestSink
import akka.stream.{ActorMaterializer, Materializer}
import org.scalatest.{FlatSpec, Matchers}

import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

class GitWatcherSuite extends FlatSpec with Matchers {

  implicit val actorSystem: ActorSystem = ActorSystem("test")
  implicit val materializer: Materializer = ActorMaterializer()
  implicit val ec: ExecutionContext = actorSystem.dispatcher

  "The Git watcher" should "track only the indicated branch" in {
    val git = TestUtils.initTempGit("other-branch")
    val trackedBranch = "release"

    git.branchCreate().setName(trackedBranch).call()
    git.checkout()
      .setName(trackedBranch)
      .call()

    // create a file on branch release (the tracked branch)
    val relativePath = "test.txt"
    val contents = List("line 1", "linȩ 2")
    val absolutePath = TestUtils.createFileInRepo(git.getRepository, relativePath, contents)
    git.add().addFilepattern(relativePath).call()
    git.commit().setMessage("Add test.txt").call()

    // checkout master
    git.checkout().setName("master").call()

    val probe = GitSourceBuilder(git.getRepository.getDirectory.getAbsolutePath)
      .setBranch(trackedBranch)
      .setDelayBetweenUpdates(1.second)
      .buildWatchAllFiles()
      .runWith(TestSink.probe[Iterable[FileChange]])

    val fileChanges = probe.request(3).expectNext(1.5.seconds)

    fileChanges.size shouldBe 1
    val fileChange = fileChanges.head

    fileChange.changeStatus shouldBe FileChange.ChangeStatus.Created
    fileChange.relativePath.toString shouldBe relativePath
    fileChange.getTextContents.toList shouldBe contents

    // merge release into master
    git.merge().setMessage(s"merge $trackedBranch into master").include(git.getRepository.resolve(trackedBranch)).call()

    // modify 'test.txt'
    val contents2 = List("changed content !!")
    Files.write(absolutePath, contents2.asJava)
    git.add().addFilepattern(relativePath).call()
    git.commit().setMessage("Changed content of test.txt").call()

    // should not be pushed
    probe.expectNoMsg(1.5.seconds)

    // merge master into release
    git.checkout().setName(trackedBranch).call()
    git.merge().setMessage(s"merge master into $trackedBranch").include(git.getRepository.resolve("master")).call()

    // expect a modified file
    val fileChanges2 = probe.expectNext(1.5.seconds)

    fileChanges2.size shouldBe 1
    val fileChange2 = fileChanges2.head

    fileChange2.changeStatus shouldBe FileChange.ChangeStatus.Modified
    fileChange2.relativePath.toString shouldBe relativePath
    fileChange2.getTextContents.toList shouldBe contents2
    assertResult(1)(fileChanges2.size)

    // expect nothing more
    probe.expectNoMsg(1.5.seconds)
  }

  it should "detect file suppression" in {
    val git = TestUtils.initTempGit("deletion")
    val fileName = "config.txt"

    // Create the file
    val absolutePath = TestUtils.createFileInRepo(git.getRepository, fileName, Seq("1"))
    git.add().addFilepattern(fileName).call()
    git.commit().setMessage(s"Created $fileName").call()

    // Start the source and the probe watching this file
    val probe = GitSourceBuilder(git.getRepository.getDirectory.getParent)
      .setDelayBetweenUpdates(1.second)
      .buildWatchOneFile(new File(fileName))
      .runWith(TestSink.probe[FileChange])
      .request(10)

    // Should get one message
    val fileCreated = probe.expectNext(1.5.seconds)
    fileCreated.relativePath.toString shouldBe fileName
    fileCreated.getTextContents.toList shouldBe List("1")
    fileCreated.changeStatus shouldBe FileChange.ChangeStatus.Created

    // delete the file
    Files.delete(absolutePath)
    git.rm().addFilepattern(fileName).call()
    git.commit().setMessage(s"Remove $fileName").call()

    // Should get the suppression message
    val fileDeleted = probe.expectNext(1.5.seconds)
    fileDeleted.relativePath.toString shouldBe fileName
    fileDeleted.changeStatus shouldBe FileChange.ChangeStatus.Deleted
    fileDeleted.getTextContents.isEmpty shouldBe true

    // expect no more messages
    probe.expectNoMsg(1.5.seconds)
  }

  it should "not send FileChanges when no tracked file is changed" in {
    val tracked = "tracked"
    val watched = "watched"
    val trackedContents = List("tracked", "contents")
    val watchedContents = List("watched", "contents")

    val git = TestUtils.initTempGit("ignored")
    // create an ignored file
    val ignoredPath = TestUtils.createFileInRepo(git.getRepository, "ignored", Seq("not important"))
    git.add().addFilepattern("ignored").call()
    git.commit().setMessage("Add ignored").call()

    // Start the source and the probe
    val probe = GitSourceBuilder(git.getRepository.getDirectory.getParent)
      .setOnlySendModified(false)
      .setDelayBetweenUpdates(1.second)
      .buildWatchSomeFiles(Set(tracked, watched).map(new File(_)))
      .runWith(TestSink.probe[Iterable[FileChange]])

    // Should not detect ignored alone
    probe.request(3).expectNoMsg(1.5.seconds)

    // create the watched files
    TestUtils.createFileInRepo(git.getRepository, tracked, trackedContents)
    TestUtils.createFileInRepo(git.getRepository, watched, watchedContents)

    git.add().addFilepattern(".").call()
    git.commit().setMessage("Add tracked and watched").call()

    // Should get a collecton of 3 FileChanges
    val fileChanges = probe.expectNext(1.5.seconds)

    fileChanges.size shouldBe 2
    fileChanges.map((fc) => (fc.relativePath.toString, fc.changeStatus, fc.getTextContents.toList))
      .toSet shouldBe Set (
      (tracked, FileChange.ChangeStatus.Created, trackedContents),
      (watched, FileChange.ChangeStatus.Created, watchedContents)
    )
    // Modify a non-tracked file
    Files.write(ignoredPath, Seq("still not important").asJava)
    git.add().addFilepattern("ignored").call()
    git.commit().setMessage("Change ignored").call()

    // No message should be sent
    probe.expectNoMsg(1.5.seconds)
  }

  it should "merge changes between several commits if downstream backpressures" in {
    val git = TestUtils.initTempGit("backpressure")
    val fileName = "config.txt"

    // Create the file
    val absolutePath = TestUtils.createFileInRepo(git.getRepository, fileName, Seq("1"))
    git.add().addFilepattern(fileName).call()
    git.commit().setMessage(s"Created $fileName").call()

    // Start the source and the probe watching this file
    val probe = GitSourceBuilder(git.getRepository.getDirectory.getParent)
      .setDelayBetweenUpdates(1.second)
      .buildWatchOneFile(new File(fileName))
      .runWith(TestSink.probe[FileChange])
      .request(1)

    // Should get one message
    val fileCreated = probe.expectNext(1.5.seconds)
    fileCreated.relativePath.toString shouldBe fileName
    fileCreated.getTextContents.toList shouldBe List("1")
    fileCreated.changeStatus shouldBe FileChange.ChangeStatus.Created

    // make two commits
    Files.write(absolutePath, Seq("2").asJava, StandardOpenOption.APPEND)
    git.add().addFilepattern(fileName).call()
    git.commit().setMessage("Add 2nd line").call()

    Files.write(absolutePath, Seq("3").asJava, StandardOpenOption.APPEND)
    git.add().addFilepattern(fileName).call()
    git.commit().setMessage("Add 3rd line").call()

    // Should get only one message, reflecting both changes
    val fileModified = probe.request(2).expectNext(1.5.seconds)
    fileModified.relativePath.toString shouldBe fileName
    fileModified.getTextContents.toList shouldBe List("1", "2", "3")
    fileModified.changeStatus shouldBe FileChange.ChangeStatus.Modified

    // Should not get other messages
    probe.expectNoMsg(1.5.seconds)
  }

  it should "send unchanged files if onlySendModified is false" in {
    val git = TestUtils.initTempGit("backpressure")
    val configFile = "config.txt"
    val configText = List("value=1")

    // Create one file
    TestUtils.createFileInRepo(git.getRepository, configFile, configText)
    git.add().addFilepattern(configFile).call()
    git.commit().setMessage(s"Created $configFile").call()

    // Start the source and the probe watching all files
    val probe = GitSourceBuilder(git.getRepository.getDirectory.getParent)
      .setDelayBetweenUpdates(1.second)
      .setOnlySendModified(false)
      .buildWatchAllFiles()
      .runWith(TestSink.probe[Iterable[FileChange]])
      .request(10)

    // get one message (don't test it, other tests do it already)
    probe.expectNext(1.5.seconds)

    // Add another file
    val licenseFile = "license.txt"
    val licenseText = List("LICENSE")
    TestUtils.createFileInRepo(git.getRepository, licenseFile, licenseText)
    git.add().addFilepattern(licenseFile).call()
    git.commit().setMessage(s"Created $licenseFile").call()

    // Should get one message with two files (one created and one unchanged)
    val fileChanges = probe.expectNext(1.5.seconds)
    fileChanges.size shouldBe 2
    fileChanges.map((fc) => (fc.relativePath.toString, fc.changeStatus, fc.getTextContents.toList))
      .toSet shouldBe Set(
      (configFile, FileChange.ChangeStatus.Unchanged, configText),
      (licenseFile, FileChange.ChangeStatus.Created, licenseText)
    )

    probe.expectNoMsg()
  }
}
