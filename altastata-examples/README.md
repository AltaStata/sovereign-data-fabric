# AltaStata Examples

Runnable Java and Scala programs in this repository. They show the same
operations as [HOWTO.md](../docs/guides/HOWTO.md) (Java / UI / S3) and
[Low-level-Scala-API.md](../docs/guides/Low-level-Scala-API.md) — upload, download, share,
list, delete — against a real account on disk.

## Before you run anything

1. An account directory with keys and `*user.properties`
   ([USER_SETUP_GUIDE.md](../docs/guides/USER_SETUP_GUIDE.md)).
2. Most samples hard-code a folder name. Point them at **your** account, or
   create one with that name:
   - `~/.altastata/accounts/amazon.rsa.bob123` — Bob (upload / share)
   - `~/.altastata/accounts/amazon.rsa.alice222` — Alice (retrieve shared files)
3. Pass the account passphrase as the first argument (or type it when a
   console is attached).
4. Run every Gradle command from the **repository root**. Samples resolve
   `altastata-examples/files/…` from there.

```bash
# compile
./gradlew :altastata-examples:compileJava :altastata-examples:compileScala
```

Sample files used by the basic demos: `altastata-examples/files/README.txt`
and `altastata-examples/files/video_streaming.png`. Downloads land in `tmp/`.

---

## 1. Basic API (`src/main`)

Scala is the low-level `CloudFile` layer: `Account` + `fileSystemModel` /
`getFileSystemHandler()` / `secureCloudFileSystemModel`. Java
`AltaStataFileSystem` is syntactic sugar over the same calls. Java
`StoreAndRetrieve` and `AliceRetrieve` drop down to `Account` /
`CloudFile`; `SharingTest` stays on the sugar. No Spark, no S3 gateway here.

| Class | What it does | Account |
|-------|----------------|---------|
| `com.altastata.api.SimpleTest` | Upload two files, list, share with `alice222`, download to `tmp/`, store/read a byte buffer, encrypt a string with the file’s AES key, delete | `amazon.rsa.bob123` |
| `com.altastata.api.StoreAndRetrieve` | List, upload one PNG, download it, delete it | `amazon.rsa.alice222` |
| `com.altastata.api.BobUploadAndShare` | Upload into `testdirectory/`, share with `alice222` and `catrina777` | `amazon.rsa.bob123` |
| `com.altastata.api.AliceRetrieve` | Wait 10 s, list `testdirectory/`, download what Bob shared | `amazon.rsa.alice222` |
| `com.altastata.api.Streams` | Append a local text file and stream an MP4 through `AltaStataChunkedOutputStream` | `amazon.rsa.bob123` — reads `~/Desktop/presentation_subtitles.txt` and `~/Desktop/altastata-demo.mp4` |
| `com.altastata.api.SharingTest` | `AccountRegistry.getOrCreateFromDir` + `share(...)` on Azure and AWS accounts | `azure.rsa.alice222` plus inline AWS properties (replace the placeholder keys) |

### Run

Ready-made task for the smoke test (password `123` — change the source if
yours is different):

```bash
./gradlew :altastata-examples:runSimpleTest
```

Any other main-source class:

```bash
./gradlew :altastata-examples:runExample \
  -PmainClass=com.altastata.api.StoreAndRetrieve \
  -PappArgs='your-password'

./gradlew :altastata-examples:runExample \
  -PmainClass=com.altastata.api.BobUploadAndShare \
  -PappArgs='your-password'

# then, as Alice:
./gradlew :altastata-examples:runExample \
  -PmainClass=com.altastata.api.AliceRetrieve \
  -PappArgs='your-password'
```

Share-then-retrieve is the pair that matches [HOWTO.md](../docs/guides/HOWTO.md) § Share
and [Low-level-Scala-API.md](../docs/guides/Low-level-Scala-API.md#share).

Fat JAR of the basic source set (its manifest still starts the old
`VPCRestAPI` demo; use `runExample` above to select another main class):

```bash
./gradlew :altastata-examples:fatJar
```

---

## 2. Spark / Hadoop (`src/spark`)

These put `AltaStataHadoopFileSystem` on a local Spark session and use
`altastata://` paths. You also need the Hadoop uber JAR story from
[altastata-hadoop/README.md](../altastata-hadoop/README.md) if you take the
same job to a cluster.

| Class | What it does | Account |
|-------|----------------|---------|
| `com.altastata.spark.hadoop.HadoopFSTest` | `FileSystem.get("altastata:///")`, create, append, share. **Default `runSpark` target.** Uses inline placeholder AWS properties — replace them or it will not talk to your lake | inline `catrina777` props |
| `com.altastata.spark.hadoop.ParquetSQLTest` | Write a small DataFrame as Parquet to `altastata:///testParquet`, read it back, `show()` | `amazon.rsa.bob123` |
| `com.altastata.spark.hadoop.WordCountingAltaStataFSTest` | `textFile("altastata:///Applications/britannica.txt")` word count. Needs that file already uploaded | inline HSM props |
| `com.altastata.spark.hadoop.MainUploadFile` | `copyFromLocalFile` of `~/Desktop/sample_video.mp4` | `amazon.rsa.bob123` |
| `com.altastata.spark.batch.BatchApp` | Custom `CloudFileReaderRDD` over `Applications/britannica.txt`, word count | `amazon.rsa.bob123` |
| `com.altastata.spark.batch.MakeRDDApp` | Build an RDD from AltaStata storage | `amazon.rsa.bob123` |
| `com.altastata.spark.stream.StreamingApp` | Spark Streaming receiver over `Applications/britannica.txt` | `amazon.rsa.bob123` |

```bash
./gradlew :altastata-examples:compileSparkJava :altastata-examples:compileSparkScala

# HadoopFSTest (edit the inline properties first)
./gradlew :altastata-examples:runSpark

# any other spark class
./gradlew :altastata-examples:runSparkExample \
  -PmainClass=com.altastata.spark.hadoop.ParquetSQLTest \
  -PappArgs='your-password'
```

Shaded JAR for a real cluster:

```bash
./gradlew :altastata-examples:shadowJar
# altastata-examples/build/libs/altastata-examples-all.jar
```

On a cluster you still put the **Hadoop** uber JAR + signed Bouncy Castle on
the Spark classpath ([UBER_JARS.md](../docs/guides/UBER_JARS.md)), not the Services JAR.

---

## 3. Audio streams (`src/streams`)

Publishes a WAV into an AltaStata chunked stream and can feed AWS Transcribe.
Needs a WAV path and AWS credentials for Transcribe (`US_WEST_2` in the
publisher). The current demo also hard-codes account `amazon.rsa.bob123` and
password `123`; change those values before running it.

```bash
./gradlew :altastata-examples:runStreamsApp --args="/path/to/CallRecording_16.wav"

# GraalVM native image (optional)
./gradlew :altastata-examples:nativeCompile
./altastata-examples/build/native/nativeCompile/TranscribeStreamingDemoAppPublisher /path/to/file.wav
```

---

## 4. Performance harnesses

`src/main/java/com/altastata/performance/` compares AltaStata to native GCS /
Azure Blob. **Download is the headline metric.** Results and scripts:
[docs/README-performance.md](docs/README-performance.md).

---

## Changing the hard-coded account

Open the class and replace the folder after `ALTASTATA_ACCOUNTS_HOME` (or the
inline `userProperties` string). Do not commit real keys. Several Spark
samples ship **example** AWS access-key material — those are placeholders and
will not open your fabric.
