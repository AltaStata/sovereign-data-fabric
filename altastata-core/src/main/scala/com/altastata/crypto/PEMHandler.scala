/*
 * Copyright (c) 2026 AltaStata Inc. All rights reserved.
 *
 * This software is dual-licensed. It is licensed under the Business Source License 1.1 
 * (BSL) for open use and evaluation, with an eventual transition to the Apache 2.0 
 * license on the Change Date.
 * 
 * PATENT NOTICE: Protected by US Patent No. 10,693,660.
 *
 * For the full license text, see the LICENSE.md file in the root of the repository,
 * or https://github.com/AltaStata/sovereign-data-fabric/blob/main/LICENSE.md
 */

package com.altastata.crypto

import org.bouncycastle.asn1.ASN1InputStream
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import org.bouncycastle.openssl.jcajce.{JcaPEMKeyConverter, JcaPEMWriter, JceOpenSSLPKCS8DecryptorProviderBuilder, JcePEMDecryptorProviderBuilder, JcePEMEncryptorBuilder}
import org.bouncycastle.openssl.{PEMEncryptedKeyPair, PEMKeyPair, PEMParser}
import org.bouncycastle.pkcs.PKCS8EncryptedPrivateKeyInfo
import org.bouncycastle.util.io.pem.{PemObject, PemReader}
import org.slf4j.LoggerFactory

import java.io.{Reader, StringReader, StringWriter}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import java.security.spec.{PKCS8EncodedKeySpec, X509EncodedKeySpec}
import java.security.{KeyFactory, KeyPair, PrivateKey, PublicKey, SecureRandom, Security}
import java.util.regex.{Matcher, Pattern}
import scala.collection.JavaConverters.mapAsJavaMapConverter
import scala.util.matching.Regex

/**
 * Trait providing utility methods for handling PEM-encoded cryptographic materials.
 * 
 * Includes serialization and deserialization of RSA and Post-Quantum (PQC) public and private keys,
 * as well as certificate-signing operations using BouncyCastle.
 */
trait PEMHandler {

  private val logger = LoggerFactory.getLogger(getClass)

  // http://stackoverflow.com/questions/22920131/read-an-encrypted-private-key-with-bouncycastle-spongycastle

  if (Security.getProvider(PqcAlgorithms.Provider) == null) {
    Security.addProvider(new BouncyCastleProvider())
  }

  /**
   * Persists a PEM string to a local file.
   *
   * @param pem The PEM string content.
   * @param filePath The local file destination path.
   */
  def storePEM(pem: String, filePath: String): Unit =
    Files.write(Paths.get(filePath), pem.getBytes(java.nio.charset.StandardCharsets.UTF_8))

  /**
   * Generates a PEM certificate for an RSA public key using a default issuer name.
   */
  def createPEMCertificateForRSAPublicKeyReader(
      publiKeyReader: Reader,
      issuerPrivateKeyReader: Reader,
      subjectName: String,
      durationInYears: Int
  ): java.util.Map[String, String] =
    createPEMCertificateForRSAPublicKeyReader(
      publiKeyReader, issuerPrivateKeyReader, subjectName, durationInYears, CertSubject.AltaStataIssuerCn)

  /**
   * Generates a PEM certificate for an RSA public key.
   *
   * @param publiKeyReader Reader accessing the public key PEM.
   * @param issuerPrivateKeyReader Reader accessing the issuer's private key PEM.
   * @param subjectName Subject name (CN) of the certificate.
   * @param durationInYears Certificate duration in years.
   * @param issuerName Issuer name (CN) of the certificate.
   * @return A map containing the generated certificate components.
   */
  def createPEMCertificateForRSAPublicKeyReader(
      publiKeyReader: Reader,
      issuerPrivateKeyReader: Reader,
      subjectName: String,
      durationInYears: Int,
      issuerName: String
  ): java.util.Map[String, String] = {
    val publicKey = extractRSAPublicKeyFromPEM(readerToString(publiKeyReader))
    val issuerPrivateKey = getKeyPairFromRSAPrivateKey(issuerPrivateKeyReader, null).getPrivate

    X509.signPublicKeyWithCertificate(publicKey, issuerPrivateKey, subjectName, durationInYears, issuerName).asJava
  }

  private def readerToString(reader: Reader): String = {
    val sb = new java.lang.StringBuilder
    val buf = new Array[Char](4096)
    var n = 0
    while ({ n = reader.read(buf); n } != -1) sb.append(buf, 0, n)
    reader.close()
    sb.toString
  }

  /**
   * Creates a PEM certificate map for PQC keys (Kyber & Dilithium) signed by an issuer private key,
   * defaulting to the standard AltaStata issuer CN.
   *
   * @param kyberPEM the Kyber public key PEM string
   * @param dilithiumPEM the Dilithium public key PEM string
   * @param issuerPrivateKeyReader Reader supplying the issuer's private key
   * @param subjectName expected certificate subject name
   * @param durationInYears validity duration in years
   * @return a Java Map containing the constructed PEM certificate parts
   */
  def createPEMCertificateForPQCPEMS(
      kyberPEM: String,
      dilithiumPEM: String,
      issuerPrivateKeyReader: Reader,
      subjectName: String,
      durationInYears: Int
  ): java.util.Map[String, String] =
    createPEMCertificateForPQCPEMS(
      kyberPEM, dilithiumPEM, issuerPrivateKeyReader, subjectName, durationInYears, CertSubject.AltaStataIssuerCn)

  /**
   * Creates a PEM certificate map for PQC keys signed by an issuer private key, specifying custom issuer CN.
   *
   * @param kyberPEM the Kyber public key PEM string
   * @param dilithiumPEM the Dilithium public key PEM string
   * @param issuerPrivateKeyReader Reader supplying the issuer's private key
   * @param subjectName expected certificate subject name
   * @param durationInYears validity duration in years
   * @param issuerName custom certificate issuer name (CN)
   * @return a Java Map containing the certificate parts
   */
  def createPEMCertificateForPQCPEMS(
      kyberPEM: String,
      dilithiumPEM: String,
      issuerPrivateKeyReader: Reader,
      subjectName: String,
      durationInYears: Int,
      issuerName: String
  ): java.util.Map[String, String] = {
    val issuerPrivateKey = getKeyPairFromRSAPrivateKey(issuerPrivateKeyReader, null).getPrivate

    X509.signPEMsWithCertificate(kyberPEM, dilithiumPEM, issuerPrivateKey, subjectName, durationInYears, issuerName).asJava
  }

  /**
   * Recovers a KeyPair from a Reader supplying an encrypted/unencrypted RSA Private Key.
   *
   * @param reader the Reader supplying the RSA private key in PEM format
   * @param password optional password to decrypt the private key (defaults to null if unencrypted)
   * @return the recovered KeyPair instance
   */
  def getKeyPairFromRSAPrivateKey(reader: Reader, password: Array[Char] = null): KeyPair = {
    val pemParser = new PEMParser(reader)
    val obj = pemParser.readObject()

    val converter = new JcaPEMKeyConverter().setProvider(BouncyCastleProvider.PROVIDER_NAME)

    val keyPair = obj match {
      // OpenSSL PEM format (PEMEncryptedKeyPair)
      case encryptedKeyPair: PEMEncryptedKeyPair =>
        val decryptor = new JcePEMDecryptorProviderBuilder().build(password)
        converter.getKeyPair(encryptedKeyPair.decryptKeyPair(decryptor))

      // Standard PKCS#8 encryption (BEGIN ENCRYPTED PRIVATE KEY) — OpenSSL 3 default
      case encryptedKey: PKCS8EncryptedPrivateKeyInfo =>
        val decryptor = new JceOpenSSLPKCS8DecryptorProviderBuilder()
          .setProvider(BouncyCastleProvider.PROVIDER_NAME)
          .build(password)
        val decryptedInfo = encryptedKey.decryptPrivateKeyInfo(decryptor)
        val privateKey = converter.getPrivateKey(decryptedInfo)
        // OpenSSL PKCS#8 usually has no embedded publicKeyData; derive from RSA CRT.
        privateKey match {
          case rsa: java.security.interfaces.RSAPrivateCrtKey =>
            val publicKey = KeyFactory.getInstance("RSA").generatePublic(
              new java.security.spec.RSAPublicKeySpec(rsa.getModulus, rsa.getPublicExponent)
            )
            new KeyPair(publicKey, privateKey)
          case _ =>
            throw new IllegalArgumentException("Encrypted PKCS#8 private key is not RSA CRT")
        }

      // Unencrypted PEM KeyPair (traditional RSA)
      case keyPair: PEMKeyPair =>
        new JcaPEMKeyConverter().getKeyPair(keyPair)

      // Unencrypted PKCS#8 (BEGIN PRIVATE KEY) — common for local altastata_private.key
      case privateKeyInfo: PrivateKeyInfo =>
        val privateKey = converter.getPrivateKey(privateKeyInfo)
        privateKey match {
          case rsa: java.security.interfaces.RSAPrivateCrtKey =>
            val publicKey = KeyFactory.getInstance("RSA").generatePublic(
              new java.security.spec.RSAPublicKeySpec(rsa.getModulus, rsa.getPublicExponent)
            )
            new KeyPair(publicKey, privateKey)
          case _ =>
            throw new IllegalArgumentException("PKCS#8 private key is not RSA CRT (cannot derive public key)")
        }

      // Unknown format
      case other =>
        throw new IllegalArgumentException(s"Unsupported key format: ${Option(other).map(_.getClass.getName).orNull}")
    }

    logger.trace("\tgetKeyPairFromPrivateKey publicKey: " + keyPair.getPublic)

    reader.close

    keyPair
  }

  /** Parse RSA public key from PEM string. Handles PEM with extra parameters/data (e.g. HPCS keys)
   * that cause standard BouncyCastle parser to fail with "Extra data detected in stream".
   * Uses first ASN.1 object fallback when standard parser throws PEMException, NPE, or Extra data.
   */
  def extractRSAPublicKeyFromPEM(pemString: String): PublicKey = {
    if (pemString == null || pemString.trim.isEmpty)
      throw new IllegalArgumentException("Public key PEM is null or empty")
    val normalized = normalizePEMString(pemString.trim.stripPrefix("\uFEFF"))
    try {
      extractRSAPublicKeyFromPEM(new StringReader(normalized))
    } catch {
      case e: Exception if isStandardParserFailure(e) =>
        extractRSAPublicKeyFromFirstAsn1Object(normalized)
    }
  }

  private def isStandardParserFailure(e: Throwable): Boolean = {
    /**
     * Checks if the given exception is caused by extra data in the PEM string.
     *
     * @param t The exception to check
     * @return true if the exception is caused by extra data, false otherwise
     */
    def hasExtraData(t: Throwable): Boolean =
      t != null && Option(t.getMessage).exists(_.contains("Extra data"))
    e match {
      case _: org.bouncycastle.openssl.PEMException | _: NullPointerException => true
      case _: IllegalArgumentException if Option(e.getMessage).exists(_.contains("Extra data")) => true
      case _ => hasExtraData(e) || hasExtraData(Option(e.getCause).orNull) || Option(e.getCause).exists(_.isInstanceOf[NullPointerException])
    }
  }

  private def normalizePEMString(s: String): String = {
    val lineNorm = s.replace("\\n", "\n").replace("\\r", "\r").replace("\r\n", "\n").replace("\r", "\n")
    lineNorm.replace('\u2013', '-').replace('\u2014', '-').replace('\u2212', '-')
  }

  private def extractRSAPublicKeyFromFirstAsn1Object(normalizedPem: String): PublicKey = {
    val begin = normalizedPem.indexOf("-----BEGIN PUBLIC KEY-----")
    val end = normalizedPem.indexOf("-----END PUBLIC KEY-----")
    val headStr = headForError(normalizedPem)
    if (begin < 0 || end <= begin)
      throw new IllegalArgumentException(s"Invalid PUBLIC KEY PEM (begin=$begin, end=$end, head=$headStr)")
    val base64 = normalizedPem.substring(begin + "-----BEGIN PUBLIC KEY-----".length, end).replaceAll("\\s", "")
    val der = java.util.Base64.getDecoder.decode(base64)
    val asn1 = new ASN1InputStream(new java.io.ByteArrayInputStream(der))
    try {
      val first = asn1.readObject()
      if (first == null) throw new IllegalArgumentException("No ASN.1 object in PEM content")
      val spki = SubjectPublicKeyInfo.getInstance(first.getEncoded)
      new JcaPEMKeyConverter().setProvider(BouncyCastleProvider.PROVIDER_NAME).getPublicKey(spki)
    } finally asn1.close()
  }

  private def headForError(s: String, maxLen: Int = 80): String = {
    val head = if (s.length <= maxLen) s else s.substring(0, maxLen)
    head.replace("\r", "\\r").replace("\n", "\\n")
  }

  /**
   * Extracts an RSA public key from a Reader supplying standard PEM data.
   *
   * @param reader the Reader supplying public key PEM data
   * @return the extracted PublicKey instance
   */
  def extractRSAPublicKeyFromPEM(reader: Reader): PublicKey = {
    val pemParser = new PEMParser(reader)
    val obj = pemParser.readObject()

    val converter = new JcaPEMKeyConverter().setProvider(BouncyCastleProvider.PROVIDER_NAME)

    val spk = obj.asInstanceOf[SubjectPublicKeyInfo]
    val publicKey = converter.getPublicKey(spk)

    logger.trace("\tgetRSAPublicKey: " + publicKey)

    reader.close

    publicKey
  }

  /**
   * Serializes a cryptographic object (e.g. PublicKey, PrivateKey) into a PEM-formatted string.
   * Encrypts the payload if encryption details are supplied.
   *
   * @param obj the object to convert to PEM format
   * @param encryptionAlgorithm optional symmetric encryption algorithm name (e.g. "AES-256-CBC")
   * @param encryptionPassword optional password bytes to encrypt with
   * @return the generated PEM string
   */
  def convertObjectToPEM(obj: Object, encryptionAlgorithm: String = null, encryptionPassword: Array[Char] = null): String = {
    val stringWriter = new StringWriter()

    val pemWriter = new JcaPEMWriter(stringWriter)

    if (encryptionAlgorithm == null) {
      pemWriter.writeObject(obj)
    }
    else {
      val jcePEMEncryptorBuilder = new JcePEMEncryptorBuilder(encryptionAlgorithm).
        //setProvider(BouncyCastleProvider.PROVIDER_NAME).
        setSecureRandom(new SecureRandom())

      val pemEncryptor = jcePEMEncryptorBuilder.build(encryptionPassword)

      pemWriter.writeObject(obj, pemEncryptor)
    }

    pemWriter.close()

    stringWriter.toString()
  }

  /**
   * Converts a byte array to a PEM string format.
   *
   * @param encodedKey The byte array to be converted
   * @param keyType The type of the key (e.g., "PUBLIC KEY", "PRIVATE KEY")
   * @return The PEM string representation of the byte array
   */
  def byteArrayToPEM(encodedKey: Array[Byte], keyType: String): String = {
    val stringWriter = new StringWriter()
    val pemWriter = new JcaPEMWriter(stringWriter)

    try {
      // Create PemObject with the keyType label (PUBLIC KEY, PRIVATE KEY) and the encoded byte data
      val pemObject = new PemObject(keyType, encodedKey)

      // Write the PEM object to the writer
      pemWriter.writeObject(pemObject)
    } catch {
      case e: Exception =>
        throw new RuntimeException("Failed to convert byte array to PEM", e)
    } finally {
      pemWriter.close()
    }

    stringWriter.toString // Return PEM-formatted string
  }

  /**
   * Convetrs a PEM-formatted string into its raw decoded byte array content.
   *
   * @param pemString the PEM string to decode
   * @return the raw decoded byte array payload
   */
  def pemToByteArray(pemString: String): Array[Byte] = {
    val pemReader = new PemReader(new StringReader(pemString))
    try {
      // Read the PEM object from the PEM string
      val pemObject = pemReader.readPemObject()

      // Return the raw byte content from the PEM object
      pemObject.getContent
    } catch {
      case e: Exception =>
        throw new RuntimeException("Failed to convert PEM to byte array", e)
    } finally {
      pemReader.close()
    }
  }

  private def pqcKeyFactoryAlgorithm(algorithm: String): (String, String) = {
    algorithm.trim.toUpperCase match {
      case s if s.contains("KYBER") => (PqcAlgorithms.Provider, PqcAlgorithms.KemAlgorithm)
      case s if s.contains("DILITHIUM") => (PqcAlgorithms.Provider, PqcAlgorithms.SignatureAlgorithm)
      case other =>
        throw new IllegalArgumentException(s"Unsupported PQC algorithm: $other (expected KYBER or DILITHIUM)")
    }
  }

  /**
   * Reconstructs a Post-Quantum (PQC) public key from its encoded X.509 byte array format.
   *
   * @param algorithm the target algorithm ("Kyber" or "Dilithium")
   * @param encodedKey the X.509 encoded public key bytes
   * @return Some(PublicKey) on success; None on failure
   */
  def getPQCPublicKeyFromEncoded(algorithm: String, encodedKey: Array[Byte]): Option[PublicKey] = {
    try {
      val (provider, keyFactoryAlg) = pqcKeyFactoryAlgorithm(algorithm)
      val keyFactory = KeyFactory.getInstance(keyFactoryAlg, provider)
      Some(keyFactory.generatePublic(new X509EncodedKeySpec(encodedKey)))
    } catch {
      case e: Exception =>
        logger.warn(s"Failed to load PQC public key for algorithm=$algorithm", e)
        None
    }
  }

  /**
   * Parses and extracts a Post-Quantum public key matching a pattern inside a given PEM string.
   *
   * @param keyType the target PQC algorithm name ("KYBER" or "DILITHIUM")
   * @param input the PEM-formatted string to extract from
   * @return Some(PublicKey) if found and extracted successfully; None otherwise
   */
  def extractPQCPublicKeyFromPEM(keyType: String, input: String): Option[PublicKey] = {
    val pattern = s"-----BEGIN PUBLIC $keyType-----.*?-----END PUBLIC $keyType-----"

    // Compile the regex with DOTALL mode
    val regex: Pattern = Pattern.compile(pattern, Pattern.DOTALL)
    val matcher: Matcher = regex.matcher(input)

    // Return the first match or None if not found
    if (matcher.find()) {
      val byteArray = AsymmetricKeysGenerator.pemToByteArray(matcher.group())

      getPQCPublicKeyFromEncoded(keyType, byteArray)
    } else {
      None
    }
  }

  /**
   * Reconstructs a Post-Quantum private key from its encoded PKCS8 byte array format.
   *
   * @param algorithm the target algorithm ("Kyber" or "Dilithium")
   * @param encodedKey the PKCS8 encoded private key bytes
   * @return Some(PrivateKey) on success; None on failure
   */
  def getPQCPrivateKeyFromEncoded(algorithm: String, encodedKey: Array[Byte]): Option[PrivateKey] = {
    try {
      val (provider, keyFactoryAlg) = pqcKeyFactoryAlgorithm(algorithm)
      val keyFactory = KeyFactory.getInstance(keyFactoryAlg, provider)
      Some(keyFactory.generatePrivate(new PKCS8EncodedKeySpec(encodedKey)))
    } catch {
      case e: Exception =>
        logger.warn(s"Failed to load PQC private key for algorithm=$algorithm", e)
        None
    }
  }

}
