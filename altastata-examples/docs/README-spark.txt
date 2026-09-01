This is an example of using AltaStata with Spark

unzip build/distributions/altastata-examples.zip 

java -classpath "lib/*" me.spark.batch.BatchApp <accounts_location> [opt<password>]


Test (with user catrina777):

Add to the code WordCountingAltaStataFSTest.scala instead of reading the directory and inputing the password:

  /** START */

  val userProperties =
    """
    AWSAccessKeyId=***
    AWSSecretKey=***
    hsmKey=***
    hsmSignKey=***
    myuser=catrina777
    accounttype=amazon-s3-secure
    region=us-east-1
    kms-region=us-east-2
    metadata-encryption=HSM
    acccontainer-prefix=altastata-myorg321-
    """

  Account.loadAccountProperties(userProperties, null)

Build:
gradle clean build shadowJar

Run:
java --add-opens=java.base/java.lang=ALL-UNNAMED     --add-opens=java.base/java.lang.invoke=ALL-UNNAMED     --add-opens=java.base/java.lang.reflect=ALL-UNNAMED     --add-opens=java.base/java.io=ALL-UNNAMED     --add-opens=java.base/java.net=ALL-UNNAMED     --add-opens=java.base/java.nio=ALL-UNNAMED     --add-opens=java.base/java.util=ALL-UNNAMED     --add-opens=java.base/java.util.concurrent=ALL-UNNAMED     --add-opens=java.base/java.util.concurrent.atomic=ALL-UNNAMED     --add-opens=java.base/sun.nio.ch=ALL-UNNAMED     --add-opens=java.base/sun.nio.cs=ALL-UNNAMED     --add-opens=java.base/sun.security.action=ALL-UNNAMED     --add-opens=java.base/sun.util.calendar=ALL-UNNAMED     --add-opens=java.security.jgss/sun.security.krb5=ALL-UNNAMED     -cp build/libs/altastata-examples-spark-all.jar com.altastata.spark.hadoop.WordCountingAltaStataFSTest