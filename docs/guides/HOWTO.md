# How to work with AltaStata

One page, organized by **what you want to do**. For each task: Desktop UI,
Web Console, Java (`AltaStataFileSystem`), and S3 (`aws` against the gateway).

Low-level Scala (`Account` + `CloudFile`) lives in
[Low-level-Scala-API.md](Low-level-Scala-API.md).

Create an account first: [USER_SETUP_GUIDE.md](USER_SETUP_GUIDE.md). Then pick
an interface:

| Interface | How you start it |
|-----------|------------------|
| **Desktop UI** | Installer `AltaStata-UI-…` from [Releases](https://github.com/AltaStata/sovereign-data-fabric/releases), or `./gradlew :altastata-ui:run` |
| **Web Console** | `http://127.0.0.1:9877` after starting Services **with** `ALTASTATA_WEB_UI_DIR` pointing at a Console SPA bundle. At **login**, choose the **account directory** on the host that runs Services — usually `~/.altastata/accounts/<name>`, e.g. `~/.altastata/accounts/amazon.rsa.bob123`. The server reads that path (it is not an upload into the browser). Enter the passphrase; leave it empty for HPCS. |
| **Java** | Path-based `AltaStataFileSystem` (`store`, `share`, `retrieve`) — syntactic sugar over the same model as Scala |
| **Scala** | Low-level `CloudFile` API — [Low-level-Scala-API.md](Low-level-Scala-API.md) |
| **S3** | Enable the S3 gate, then `aws` against `http://127.0.0.1:9876`, bucket `altastata-bucket` |

Java `AltaStataFileSystem` is syntactic sugar on top of the low-level model —
it lists, filters by time, and calls the same methods so you pass a path
string instead of a `CloudFile[]`. Runnable programs:
[altastata-examples](../../altastata-examples/README.md)
(`SharingTest` stays on the sugar; `StoreAndRetrieve` / `AliceRetrieve` can
drop down to `Account` / `CloudFile`).

```java
import com.altastata.api.AccountRegistry;
import com.altastata.api.AltaStataFileSystem;

String home = System.getProperty("user.home") + "/.altastata/accounts/";

// Two backends in one JVM — AWS and Azure accounts side by side
AltaStataFileSystem bobAmazon = AccountRegistry.getOrCreateFromDir(
    home + "amazon.rsa.bob123");
bobAmazon.setPassword("your-password");

AltaStataFileSystem bobAzure = AccountRegistry.getOrCreateFromDir(
    home + "azure.rsa.bob123");
bobAzure.setPassword("your-password");

bobAmazon.listCloudFilesVersions("Public/", true, null, null);
bobAzure.listCloudFilesVersions("Public/", true, null, null);
```

Task examples below use **`bobAmazon`** (AWS). Use **`bobAzure`** (Azure) the same way.

Times are milliseconds since epoch. On Java facade methods whose bounds are
`String` values (`share`, `delete`, `listCloudFilesVersions`), pass `null` for
a bound you do not want. `retrieve` takes a `Long snapshotTime`; pass
`System.currentTimeMillis()` for the latest version.

A **path prefix** names a subtree. `Public/inbox` with the recursive flag
`true` is every file under that folder; the same methods with `false` apply
only to that exact path. `store` / `retrieve` also take a **local prefix**
(stripped from disk paths) and a **cloud prefix** (where the tree lands).

---

## Upload

**Desktop UI.** Open the destination folder. **Upload** (or drag-and-drop).
A new version appears under the same name.

**Web Console.** Open `http://127.0.0.1:9877`, log in with the account
directory (see the table above), then the same upload flow in the browser.

**Java:**

```java
bobAmazon.store(
    java.util.List.of("/data/report.pdf", "/data/images"), // local files/dirs to upload
    "/data",          // local prefix stripped from every selected path
    "Public/inbox",   // destination prefix inside AltaStata
    true);            // wait for every upload to finish

// createFile creates a new version rather than overwriting version history.

bobAmazon.createFile(
    "Public/inbox/hello.txt", // cloud path for the new version
    "hello".getBytes());      // plaintext bytes to store

bobAmazon.appendBufferToFile(
    "Public/inbox/hello.txt",   // existing cloud path
    System.currentTimeMillis(), // append to the latest version at or before "now"
    "\nmore".getBytes());       // bytes appended to that version
```

**Scala.** [Low-level-Scala-API.md](Low-level-Scala-API.md#upload)

**S3** (Services JVM with `-Daltastata.services.s3gateway.enabled=true`):

```bash
aws s3 cp report.pdf s3://altastata-bucket/Public/inbox/report.pdf \
  --endpoint-url http://127.0.0.1:9876
```

Multipart (`aws s3 cp` of a large file) uses the same endpoint.

---

## Download

**Desktop UI.** Select files or folders → **Download** → pick a local directory.
A single file with several versions offers a version picker; a folder download
takes the latest version of each file.

**Web Console.** Select → download.

**Java:**

```java
bobAmazon.retrieve(
    "./out",                    // local destination directory
    "Public/inbox",             // cloud path or prefix to download
    true,                       // include subdirectories
    System.currentTimeMillis(), // latest version at or before this snapshot
    false,                      // false = regular download, true = streaming mode
    true);                      // wait until all downloads finish

try (var in = bobAmazon.getFileInputStream(
        "Public/inbox/hello.txt", // cloud path to open
        null,                     // null selects the latest version for this method
        0L,                       // starting byte offset
        4)) {                     // chunks prefetched in parallel
    byte[] buffer = in.readAllBytes();
}
```

**Scala.** [Low-level-Scala-API.md](Low-level-Scala-API.md#download)

**S3:**

```bash
aws s3 cp s3://altastata-bucket/Public/inbox/hello.txt ./hello.txt \
  --endpoint-url http://127.0.0.1:9876
```

---

## List / see versions

AltaStata keeps **per-file versions** (create-time). The Java facade returns
serialized version rows. The Scala model returns `CloudFile` objects — see
[Low-level-Scala-API.md](Low-level-Scala-API.md#list--see-versions).

**Desktop UI.** Browse the folder. Expand a file to see each version, size,
creator, and current readers.

**Web Console.** Open the folder, expand a file.

**Java:**

```java
bobAmazon.listCloudFilesVersions(
    "Public/", // cloud path or prefix to list
    true,      // recursively include subdirectories
    null,      // no minimum create-time
    null)      // no maximum create-time
    .forEachRemaining(row -> System.out.println(java.util.Arrays.toString(row)));
```

**Scala.** [Low-level-Scala-API.md](Low-level-Scala-API.md#list--see-versions)

**S3** lists object keys (what the gateway exposes). It is not the AltaStata
version catalog:

```bash
aws s3 ls s3://altastata-bucket/Public/ --endpoint-url http://127.0.0.1:9876
```

---

## Share

Share grants **reader** access. The file stays where it is; the other user
sees it in their catalog.

**Desktop UI.** Select → **Share** → pick a user (or a `*.group` file). For a
single file with several versions you can share one version or all of them.

**Web Console.** Select → share → choose readers.

**Java:**

Single file or folder prefix:

```java
bobAmazon.share(
    "Public/inbox/report.pdf", // cloud path to share
    true,                      // include descendants when the path is a directory
    null,                      // no minimum version create-time
    null,                      // no maximum version create-time
    new String[] {"alice222"}); // reader usernames (or *.group files)
```

Several explicit paths:

```java
bobAmazon.sharePaths(
    new String[] {
        "Public/inbox/report.pdf",
        "Public/inbox/notes.txt"},
    null,                      // no minimum version create-time
    null,                      // no maximum version create-time
    new String[] {"alice222"}); // reader usernames (or *.group files)
```

Entire subtree under one prefix — see [Prefix / subtree](#prefix--subtree).

**Scala.** [Low-level-Scala-API.md](Low-level-Scala-API.md#share)

**S3.** No share API.

---

## Revoke

Revoke removes readers. The file is not deleted.

**Desktop UI.** Select → **Revoke** → pick the reader (or group).

**Web Console.** Select → revoke.

**Java:**

Single file or folder prefix:

```java
bobAmazon.revokeReaderAccess(
    "Public/inbox/report.pdf", // cloud path whose readers change
    true,                      // include descendants
    null,                      // no minimum version create-time
    null,                      // no maximum version create-time
    new String[] {"alice222"}); // readers to remove
```

Several explicit paths:

```java
bobAmazon.revokePaths(
    new String[] {
        "Public/inbox/report.pdf",
        "Public/inbox/notes.txt"},
    null,                      // no minimum version create-time
    null,                      // no maximum version create-time
    new String[] {"alice222"}); // readers to remove
```

**Scala.** [Low-level-Scala-API.md](Low-level-Scala-API.md#revoke)

**S3.** No revoke API.

---

## Delete

**Desktop UI.** Select → **Delete** → confirm. A folder deletes every version
under it; a single file can delete one version or all of them.

**Web Console.** Select → delete → confirm.

**Java:**

Single file or folder prefix:

```java
bobAmazon.delete(
    "Public/inbox/report.pdf", // cloud path to delete
    true,                      // recursively delete descendants
    null,                      // no minimum version create-time
    null);                     // no maximum version create-time
```

Several explicit paths:

```java
bobAmazon.deletePaths(
    new String[] {
        "Public/inbox/report.pdf",
        "Public/inbox/notes.txt"},
    null,                      // no minimum version create-time
    null);                     // no maximum version create-time
```

**Scala.** [Low-level-Scala-API.md](Low-level-Scala-API.md#delete)

**S3:**

```bash
aws s3 rm s3://altastata-bucket/Public/inbox/report.pdf \
  --endpoint-url http://127.0.0.1:9876
```

---

## Find / search

**Desktop UI.** Type in the search field. Matches replace the folder view.

**Web Console.** Use the search field in the file manager.

**Java / Scala / S3.** No dedicated search. List under a prefix and filter.

**Java:**

```java
bobAmazon.listCloudFilesVersions(
    "Public/", // cloud path or prefix to list
    true,      // include descendants
    null,      // no minimum create-time
    null)      // no maximum create-time
    .forEachRemaining(row -> {
        if (row[0].contains("report")) System.out.println(row[0]);
    });
```

**Scala.** [Low-level-Scala-API.md](Low-level-Scala-API.md#find--search)

```bash
aws s3 ls s3://altastata-bucket/Public/ --recursive \
  --endpoint-url http://127.0.0.1:9876 | grep report
```

---

## Copy (same fabric)

**Java:**

```java
bobAmazon.copyFile(
    "Public/inbox/report.pdf",    // latest source version is read
    "Public/archive/report.pdf"); // source is preserved
```

**Scala.** No one-call copy on the low-level model —
[Low-level-Scala-API.md](Low-level-Scala-API.md#copy-same-fabric).

**S3:**

```bash
aws s3 cp s3://altastata-bucket/Public/inbox/report.pdf \
          s3://altastata-bucket/Public/archive/report.pdf \
  --endpoint-url http://127.0.0.1:9876
```

**Desktop UI / Console.** Copy from the file menu when a file is selected.

---

## Version attributes

Read metadata from a file version — **size**, **readers**, and **creator**.

**Desktop UI.** Expand the file: the detail line lists size, creator, and readers.

**Java:**

```java
bobAmazon.getFileAttribute(
    "Public/inbox/report.pdf", // cloud path
    null,                      // version create-time; null = latest
    "size");                   // plaintext size in bytes

bobAmazon.getFileAttribute(
    "Public/inbox/report.pdf", // cloud path
    null,                      // version create-time; null = latest
    "readers");                // usernames with reader access
```

`owner` is not a data attribute. The creator is the version tag included by
`listCloudFilesVersions`.

**Scala.** [Low-level-Scala-API.md](Low-level-Scala-API.md#version-attributes)

**S3.** `get-object-tagging` is S3 tags, not the AltaStata reader list.

---

## Prefix / subtree

`list`, `share`, `revoke`, `delete`, and `retrieve` take a cloud path **or**
a prefix. Pass the folder and `true` to include descendants:

```java
bobAmazon.share(
    "Public/inbox",            // prefix: every path that starts with this
    true,                      // include the subtree
    null,                      // no minimum version create-time
    null,                      // no maximum version create-time
    new String[] {"alice222"}); // reader usernames
```

`store` maps a local tree onto a cloud prefix:

```java
bobAmazon.store(
    java.util.List.of("/data/images"), // local directory
    "/data",          // stripped: images/a.png stays images/a.png
    "Public/inbox",   // cloud subtree: Public/inbox/images/a.png
    true);
```

**Scala.** [Low-level-Scala-API.md](Low-level-Scala-API.md#prefix--subtree)

---

## Streams

`getFileInputStream` / `getFileOutputStream` wrap
`AltaStataChunkedInputStream` and `AltaStataChunkedOutputStream`. Use them for
large files instead of loading the whole object into a `byte[]`. Example:
`Streams`.

```java
try (var in = bobAmazon.getFileInputStream(
        "Public/inbox/video.mp4", // cloud path
        null,                     // null = latest version for this method
        0L,                       // starting byte offset
        4)) {                     // chunks prefetched in parallel
    in.transferTo(java.nio.file.Files.newOutputStream(
        java.nio.file.Path.of("./video.mp4")));
}

try (var out = bobAmazon.getFileOutputStream(
        "Public/inbox/hello.txt",   // cloud path
        System.currentTimeMillis(), // latest version at or before "now"
        true)) {                    // true = append; false = replace content
    out.write("\nmore".getBytes());
}
```

**Scala.** [Low-level-Scala-API.md](Low-level-Scala-API.md#streams)

**S3.** `aws s3 cp` of a large file uses multipart against the same endpoint.

---

## Events (share / delete)

When someone **shares** a file with you or **deletes** a version you can see,
the client fires `SHARE` / `DELETE`. The payload is the cloud path (often with
a version suffix). Register a listener and keep the process running.

```java
bobAmazon.addAltaStataEventListener(event -> {
    String name = event.getEventName(); // "SHARE" or "DELETE"
    Object data = event.getData();      // cloud path of the version
    System.out.println(name + " " + data);
});
```

**Scala.** [Low-level-Scala-API.md](Low-level-Scala-API.md#events-share--delete)

**Desktop UI / Console.** The file list refreshes when shares arrive; there is
no separate event API.

**S3.** No event API.

---

## S3 gateway — credentials

The S3 listener is off by default. Start Services with the gate on, log in
through the Console (or issue credentials via gRPC `LoginV2` →
`IssueCredentials`), then point `aws` at `:9876` with those keys:

```bash
java -Daltastata.services.s3gateway.enabled=true \
     -jar altastata-services-YYYY.MM.DD-uber.jar

export AWS_ACCESS_KEY_ID=...
export AWS_SECRET_ACCESS_KEY=...
aws s3 ls s3://altastata-bucket/ --endpoint-url http://127.0.0.1:9876
```

Details: [altastata-s3-gateway/README.md](../../altastata-s3-gateway/README.md),
[altastata-services/README.md](../../altastata-services/README.md).

---

## What to read next

| If you want… | Go to |
|--------------|--------|
| `AltaStataFileSystem` API reference | [Javadoc](../api/javadoc/com/altastata/api/AltaStataFileSystem.html) |
| Low-level Scala / `CloudFile` | [Low-level-Scala-API.md](Low-level-Scala-API.md) |
| Keys and `*user.properties` | [USER_SETUP_GUIDE.md](USER_SETUP_GUIDE.md) |
| Admin / POSIX without a cloud bill | [ADMIN_TOOL_GUIDE.md](ADMIN_TOOL_GUIDE.md) |
| Enterprise / Custodian / PQC | [ENTERPRISE.md](ENTERPRISE.md) |
| Runnable Java / Scala / Spark samples | [altastata-examples/README.md](../../altastata-examples/README.md) |
| Start the JVM that hosts S3 + Console | [altastata-services/README.md](../../altastata-services/README.md) |
| Spark / Hadoop `altastata://` | [altastata-hadoop/README.md](../../altastata-hadoop/README.md) |
| Same tasks in Python | [Python HOWTO](https://github.com/AltaStata/altastata-python-package/blob/main/docs/guides/HOWTO.md) |
