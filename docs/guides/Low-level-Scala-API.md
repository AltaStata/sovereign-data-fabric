# Low-level Scala API

Scala talks to AltaStata through `Account` and `CloudFile` objects — path plus
versions — via `fileSystemModel`, `getFileSystemHandler()`, and
`secureCloudFileSystemModel`.

API reference: [Scaladoc](https://altastata.github.io/sovereign-data-fabric/api/scaladoc/com/altastata/filesystem/securecloud/)
(`FileSystemModel`, Streams) ·
[Javadoc — `CloudFile` / `FileSystemHandler`](https://altastata.github.io/sovereign-data-fabric/api/javadoc/com/altastata/filesystem/common/package-summary.html).

Java path-based sugar (`AltaStataFileSystem`) is in [HOWTO.md](HOWTO.md).
Runnable programs: [altastata-examples](../../altastata-examples/README.md)
(`SimpleTest`, `BobUploadAndShare`, `Streams`). Java `StoreAndRetrieve` and
`AliceRetrieve` also use this `Account` / `CloudFile` layer.

```scala
import com.altastata.utils.Account
import java.io.File
import scala.collection.JavaConverters._

val home = Account.ALTASTATA_ACCOUNTS_HOME + File.separator

// Two backends in one JVM — AWS and Azure accounts side by side
val bobAmazon = new Account()
bobAmazon.loadAccountProperties(home + "amazon.rsa.bob123")
bobAmazon.setPassword("your-password".toCharArray)

val bobAzure = new Account()
bobAzure.loadAccountProperties(home + "azure.rsa.bob123")
bobAzure.setPassword("your-password".toCharArray)
```

Task examples below use **`bobAmazon`** (AWS). Use **`bobAzure`** (Azure) the same way.

Times are milliseconds since epoch. Share, revoke, and delete filters must
contain the **exact create-times** of the selected versions (reuse the
`createTime` from upload).

A **path prefix** names a subtree. `listCloudFiles("Public/inbox", true)` is
every `CloudFile` under that folder. `mapFilesTreeToCloudFileList` takes a
local base path to strip and a cloud prefix where the tree lands.

---

## Upload

Examples: `SimpleTest`, `BobUploadAndShare`, `Streams`.

```scala
import java.nio.ByteBuffer
import com.altastata.filesystem.securecloud.SecureCloudStream.AltaStataChunkedOutputStream

val filesList = List(
  new File("altastata-examples/files/video_streaming.png"),
  new File("altastata-examples/files/README.txt"))

// Reuse one timestamp: share/revoke/delete later require exact version times.
val createTime = System.currentTimeMillis()

// Convert the local tree to (local File, target CloudFile) pairs.
val listForSubTree = bobAmazon.getFileSystemHandler().mapFilesTreeToCloudFileList(
  filesList.asJava,                         // selected local files/directories
  filesList.head.getAbsoluteFile.getParent, // base local path to strip
  "",                                       // target cloud prefix; "" = catalog root
  createTime)                               // create-time assigned to every new version

val storeResults = bobAmazon.fileSystemModel.uploadLocalFilesToCloud(
  listForSubTree, // (local File, target CloudFile) pairs
  true)           // waitUntilDone: return final rather than in-progress statuses

val cloudFile = bobAmazon.getFileSystemHandler()
  .createCloudFileVersion(
    "program/test.bak",        // target cloud path
    false,                     // isDirectory
    System.currentTimeMillis)  // version create-time

bobAmazon.secureCloudFileSystemModel.storeByteBufferToCloudFile(
  ByteBuffer.wrap("hello".getBytes), // plaintext source buffer
  cloudFile,                         // destination path/version metadata
  true)                              // waitUntilDone

val os = new AltaStataChunkedOutputStream(
  "Applications/test.txt",    // cloud path
  System.currentTimeMillis(), // latest existing version at or before this time
  true                        // append instead of replacing existing content
)(bobAmazon)                    // bobAmazon supplies keys and storage handlers

os.write("\nmore".getBytes) // plaintext chunk written through encryption
os.close
```

---

## Download

Example: `SimpleTest`. Alice's corresponding recipient example,
`AliceRetrieve`, is Java on the same `CloudFile` layer.

```scala
import com.altastata.api.AltaStataFileSystem.OperationState
import com.altastata.filesystem.common.CloudFile
import java.nio.ByteBuffer
import scala.collection.mutable.ListBuffer

val toRetrieve = ListBuffer[CloudFile] ()

// Successful upload statuses contain a serialized path + version.
// Parse each one back into the CloudFile object required by the low-level API.
for (storeResult <- storeResults) {
  storeResult.getOperationState match {
    case OperationState.DONE =>
      toRetrieve += bobAmazon.getFileSystemHandler()
        .parseObjectPathIncludingVersion(
          storeResult.getCloudFileVersionPath) // serialized "path + create-time"
    case OperationState.ERROR => // skip failed uploads
  }
}

// Exact create-times select the uploaded versions, not older versions of a path.
val timestampFilter = List(java.lang.Long.valueOf(createTime))

bobAmazon.fileSystemModel.retrieveCloudFilesToLocalDirectory(
  toRetrieve.toArray,       // CloudFile objects to download
  "tmp/",                   // local destination directory
  timestampFilter.asJava)   // exact create-times that select versions

val lastFile = toRetrieve.last

// The byte API needs a caller-owned buffer large enough for the plaintext.
val buffer = ByteBuffer.allocate(
  lastFile.getVersions.last
    .getVersionDataAttribute("size") // plaintext size of this version
    .toLong.toInt)                   // capacity for the destination buffer

bobAmazon.secureCloudFileSystemModel.retrieveCloudFileToByteBuffer(
  buffer,                   // caller-owned destination for plaintext
  lastFile,                 // CloudFile whose version is read
  List(lastFile.getVersions.last.getCreateTime)
    .map(java.lang.Long.valueOf).asJava, // exact version create-time
  0L,                       // first encrypted chunk index
  null)                     // no reusable chunk cache

// Omitted defaults: waitUntilDone=true and trustCachedSize=false.
```

---

## List / see versions

The model returns `CloudFile` objects; each file's `getVersions` set holds the
versions. Examples: `SimpleTest`, `BobUploadAndShare`.

```scala
bobAmazon.fileSystemModel.listCloudFiles(
  "",    // "" lists the whole catalog
  true)  // flat recursive listing; each CloudFile groups its versions
  .asScala
  .foreach(file => println(file))

bobAmazon.fileSystemModel.listCloudFiles(
  "testdirectory", // cloud path or prefix to list
  true)            // include descendants
  .asScala
  .foreach { file =>
    file.getVersions.asScala.foreach { v =>
      println(s"${file.getPath} ${v.getCreateTime}")
    }
  }
```

---

## Share

Parse successful upload statuses into `CloudFile` objects (same pattern as
Download), then call `shareCloudFiles`. Examples: `BobUploadAndShare`,
`SimpleTest`.

```scala
import com.altastata.api.AltaStataFileSystem.OperationState
import com.altastata.filesystem.common.CloudFile
import scala.collection.mutable.ListBuffer

val toShare = ListBuffer[CloudFile]()

for (storeResult <- storeResults) {
  storeResult.getOperationState match {
    case OperationState.DONE =>
      toShare += bobAmazon.getFileSystemHandler()
        .parseObjectPathIncludingVersion(
          storeResult.getCloudFileVersionPath)
    case OperationState.ERROR => // skip failed uploads
  }
}

val timestampFilter = List(java.lang.Long.valueOf(createTime))

bobAmazon.fileSystemModel.shareCloudFiles(
  toShare.toArray,           // CloudFile objects to share
  Array("alice222"),         // reader usernames (or *.group files)
  timestampFilter.asJava)    // exact versions whose key envelopes are shared
```

Then Alice lists `testdirectory` and downloads (`AliceRetrieve`).

---

## Revoke

Same parsing as share — build `toRevoke` from the `CloudFile`s you want to
change. There is no dedicated sample class; call
`FileSystemModel.revokeReaderAccess` directly.

```scala
import com.altastata.api.AltaStataFileSystem.OperationState
import com.altastata.filesystem.common.CloudFile
import scala.collection.mutable.ListBuffer

val toRevoke = ListBuffer[CloudFile]()

for (storeResult <- storeResults) {
  storeResult.getOperationState match {
    case OperationState.DONE =>
      toRevoke += bobAmazon.getFileSystemHandler()
        .parseObjectPathIncludingVersion(
          storeResult.getCloudFileVersionPath)
    case OperationState.ERROR => // skip failed uploads
  }
}

val timestampFilter = List(java.lang.Long.valueOf(createTime))

bobAmazon.fileSystemModel.revokeReaderAccess(
  toRevoke.toArray,          // CloudFile objects to change
  Array("alice222"),         // readers to remove
  timestampFilter.asJava)    // exact versions to change
```

---

## Delete

Example: `SimpleTest`. Build `toDelete` from the versions you want removed.

```scala
import com.altastata.api.AltaStataFileSystem.OperationState
import com.altastata.filesystem.common.CloudFile
import scala.collection.mutable.ListBuffer

val toDelete = ListBuffer[CloudFile]()

for (storeResult <- storeResults) {
  storeResult.getOperationState match {
    case OperationState.DONE =>
      toDelete += bobAmazon.getFileSystemHandler()
        .parseObjectPathIncludingVersion(
          storeResult.getCloudFileVersionPath)
    case OperationState.ERROR => // skip failed uploads
  }
}

val timestampFilter = List(java.lang.Long.valueOf(createTime))

bobAmazon.fileSystemModel.deleteCloudFiles(
  toDelete.toArray,          // CloudFile objects to delete
  timestampFilter.asJava)    // exact versions to delete
```

---

## Find / search

No dedicated search API. List under a prefix and filter locally.

```scala
// listCloudFiles is prefix-based; filter locally for substring-style search.

bobAmazon.fileSystemModel.listCloudFiles(
  "Public/", // cloud path or prefix to list
  true)      // recursively list descendants
  .asScala
  .filter(_.getPath.contains("report"))
  .foreach(file => println(file.getPath))
```

---

## Copy (same fabric)

The low-level model has no one-call copy operation. Use the Java facade's
`copyFile` ([HOWTO.md](HOWTO.md#copy-same-fabric)), or read the selected
version and create/store a new `CloudFile`. Do not use `renameCloudFiles`: it
moves the source.

---

## Version attributes

Read metadata from a `CloudFile` version — **size**, **readers**, and **creator**.
`SimpleTest` uses `"size"` to size a download buffer; the same version object
exposes sharing and ownership fields.

```scala
import com.altastata.api.AltaStataFileSystem.OperationState

// CloudFile for a successful upload (skip ERROR statuses):
val storedPath = storeResults.collectFirst {
  case r if r.getOperationState == OperationState.DONE =>
    r.getCloudFileVersionPath
}.get
val storedFile = bobAmazon.getFileSystemHandler()
  .parseObjectPathIncludingVersion(storedPath)
val version = storedFile.getVersions.last

version.getVersionDataAttribute("size")    // plaintext size in bytes
version.getVersionDataAttribute("readers") // usernames with reader access
version.getTag                             // creator identity for this version
```

`owner` is not a data attribute — use `getTag` for creator.

---

## Prefix / subtree

`listCloudFiles` is prefix-based. `true` includes descendants:

```scala
bobAmazon.fileSystemModel.listCloudFiles(
  "Public/inbox", // prefix of the subtree
  true)           // include descendants
  .asScala
  .foreach(file => println(file.getPath))
```

Upload maps a local tree onto a cloud prefix (third argument of
`mapFilesTreeToCloudFileList`). `""` is the catalog root.

Share / revoke / delete then take the `CloudFile[]` for that subtree plus
exact `createTime`s — not a path string.

---

## Streams

`AltaStataChunkedInputStream` / `AltaStataChunkedOutputStream` are the
low-level encrypted streams. Java wraps them as `getFileInputStream` /
`getFileOutputStream`. Example: `Streams`.

```scala
import com.altastata.filesystem.securecloud.SecureCloudStream.{
  AltaStataChunkedInputStream, AltaStataChunkedOutputStream}

val in = new AltaStataChunkedInputStream(
  "Public/inbox/video.mp4",   // cloud path
  0L,                         // starting byte offset
  4,                          // chunks prefetched in parallel
  System.currentTimeMillis()  // latest version at or before this time
)(bobAmazon)

val buf = new Array[Byte] (8192)
Iterator.continually(in.read(buf)).takeWhile(_ != -1).foreach { n =>
  // process buf(0 until n)
}
in.close

val os = new AltaStataChunkedOutputStream(
  "Applications/test.txt",    // cloud path
  System.currentTimeMillis(), // latest existing version at or before this time
  true                        // append instead of replacing existing content
)(bobAmazon)

os.write("\nmore".getBytes)
os.close
```

---

## Events (share / delete)

`SHARE` and `DELETE` arrive on `FileSystemHandler`. Keep the process running.

```scala
import com.altastata.api.{AltaStataEvent, AltaStataEventListener}

bobAmazon.getFileSystemHandler().addAltaStataEventListener(
  new AltaStataEventListener {
    override def notify(event: AltaStataEvent): Unit = {
      println(s"${event.getEventName} ${event.getData}") // SHARE|DELETE + path
    }
  })
```

---

## What to read next

| If you want… | Go to |
|--------------|--------|
| Desktop UI / Console / Java / S3 by task | [HOWTO.md](HOWTO.md) |
| Runnable Scala samples | [altastata-examples/README.md](../../altastata-examples/README.md) |
| Core module overview | [altastata-core/README.md](../../altastata-core/README.md) |
