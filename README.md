# git-watcher-akka-stream
An Akka-Stream `Source` giving the versions of a git repo in real time. **(still in development)**

## Why?

Using a Git repository as a configuration store permits to keep changes and revert easily any foolish action.
Moreover, it would be great to deploy configurations with a simple commit on a given branch, without restarting the server.

This little library aims to facilitate this. This is no more than a watcher on a Git repository, implemented as a `Source` from Akka-Stream.

## Usage

Create a `GitSourceBuilder` and pass your parameters (repo URL, branch, authentification, delay between fetches, ...).
```scala
val builder = GitSourceBuilder("https://myrepogit")
  .setAuthentification(UsernamePasswordAuthentification("username", "password"))
  .setOnlySendModified(false) // Send every files at each changes (even if those particular files didn't change)
```

Then build the Akka `Source` using one of the `buildxxx` methods.
```scala
val source: Source[FileChange, NotUsed] = builder.buildWatchOneFile("config.txt")
```

The `FileChange` objects contain information on the nature of the change, and provide the unique access to the file as an
`java.io.InputStream`.

The following code prints the contents of the file each time it is changed:
```scala
source.runForeach{ (fileChange) =>
  val source = Source.fromInputStream(fileChange.openContentsStream())
  println(source.mkString)
  source.close()
}
```

## Some technical details

Each time a `buildxxx` method is called on a `GitSourceBuilder`, it actually create a new temporary local bare repository, cloned from the given URL.
Then, it periodically fetches changes from the remote and push them downstream.

Since the repository is bare, the only way to access the files' contents is from the Git API, which is hidden from the user.
The `FileChange` objects provide the sole access to the contents of the files, and each `FileChange` points to a file in a particular commit. The contents of a `FileChange` are thus always the same, even after further fetches.

## Used libraries
- [Akka](https://akka.io/) and in particular, Akka-Stream
- [JGit](http://www.eclipse.org/jgit/): the Java Git API.
- [ScalaTest](http://www.scalatest.org/) and Akka-Testkit for the tests
