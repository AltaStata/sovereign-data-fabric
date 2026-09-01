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

import java.security.{KeyPairGenerator, PrivateKey, PublicKey, SecureRandom, Security, Signature}
import java.util.{Base64, Calendar, Date, GregorianCalendar}
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.asn1.x500.X500Name

import java.math.BigInteger
import org.bouncycastle.operator.ContentSigner
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.asn1.{ASN1ObjectIdentifier, ASN1OctetString, ASN1Sequence, DEROctetString}
import org.bouncycastle.operator.jcajce.JcaContentVerifierProviderBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider

import java.io.StringWriter
import org.bouncycastle.openssl.jcajce.JcaPEMWriter

import java.io.ByteArrayInputStream
import java.security.cert.CertificateFactory
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoVerifierBuilder

import java.security.cert.X509Certificate
import java.nio.charset.StandardCharsets
import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.asn1.x509.Certificate
import org.slf4j.LoggerFactory
import org.bouncycastle.cert.CertException

import java.text.SimpleDateFormat
import scala.collection.immutable.Map
import org.bouncycastle.util.io.pem.PemReader
import java.io.StringReader
import java.util.Base64

/**
 * http://www.bouncycastle.org/wiki/display/JA1/BC+Version+2+APIs
 * http://stackoverflow.com/questions/14930381/generating-x509-certificate-using-bouncy-castle-java
 */
object X509 extends AsymmetricCryptoHandler {

  private val logger = LoggerFactory.getLogger(getClass)
    
  // generate key pair for issuer
  val keyPairGenerator = KeyPairGenerator.getInstance("RSA") //, BouncyCastleProvider.PROVIDER_NAME)
  keyPairGenerator.initialize(4096)
    
  val issuerKeyPair = keyPairGenerator.generateKeyPair()

  /**
   * Main entry point to run local verification and signature tests on self-signed certificate generation.
   *
   * @param args command line arguments
   */
  def main(args: Array[String]): Unit = {

    // public key to wrap into certificate
    val subjectKeyPairGenerator = KeyPairGenerator.getInstance("RSA") //, BouncyCastleProvider.PROVIDER_NAME)
    subjectKeyPairGenerator.initialize(4096)

    val subjectKeyPair = subjectKeyPairGenerator.generateKeyPair()

    val pemMap = signPublicKeyWithCertificate(subjectKeyPair.getPublic, issuerKeyPair.getPrivate, "Test")
    
    println(pemMap)
    
    extractPublicKeyFromCertificate(pemMap.get("certificate").get, issuerKeyPair.getPublic, "Test", true)
  }

  /**
   * Signs a standard RSA public key, wrapping it inside a CA-signed X.509 certificate.
   *
   * @param subjectPublicKey The target public key to wrap.
   * @param issuerPrivateKey The private key of the CA issuing/signing the certificate.
   * @param subjectName Subject name (CN) for the certificate.
   * @param durationInYears Validity duration of the certificate in years.
   * @param issuerName Issuer name (CN) of the certificate authority.
   * @return A map containing the resulting certificate and public key as PEM strings.
   */
  def signPublicKeyWithCertificate(
      subjectPublicKey: PublicKey,
      issuerPrivateKey: PrivateKey,
      subjectName: String,
      durationInYears: Int = 1,
      issuerName: String = CertSubject.AltaStataIssuerCn
  ): Map[String, String] = {
    val encodedSubjectPublicKey = subjectPublicKey.getEncoded()
    
    // create X509CertificateHolder
    val subjectPublicKeyInfo = new SubjectPublicKeyInfo(ASN1Sequence.getInstance(encodedSubjectPublicKey))

    val cal = new GregorianCalendar

    cal.add(Calendar.DAY_OF_YEAR, -1)
    val startDate = cal.getTime

    cal.add(Calendar.YEAR, durationInYears)
    val endDate = cal.getTime
 
    val v1CertGen = new X509v3CertificateBuilder(
          new X500Name("CN=" + issuerName),
          BigInteger.ONE,
          startDate, endDate,
          new X500Name("CN=" + subjectName),
          subjectPublicKeyInfo)
    
    // SHA256withRSA — issuer key is RSA-4096 (was SHA1withRSA + 1024 historically)
    val sigGen = new JcaContentSignerBuilder("SHA256withRSA")/*.setProvider(BouncyCastleProvider.PROVIDER_NAME)*/.build(issuerPrivateKey)

    val certHolder = v1CertGen.build(sigGen)
                
    // get ASN1Certificate
    val eeX509CertificateStructure = certHolder.toASN1Structure

    logger.info("signPublicKeyWithCertificate Cert getEndDate: " + eeX509CertificateStructure.getEndDate.getDate)
    System.out.println("signPublicKeyWithCertificate Cert startDate: " + eeX509CertificateStructure.getStartDate.getDate 
        + " EndDate: " + eeX509CertificateStructure.getEndDate.getDate)
        
    // build signedCertificate
    val is1 = new ByteArrayInputStream(eeX509CertificateStructure.getEncoded())
    
    val certificateFactory = CertificateFactory.getInstance("X.509"/*, BouncyCastleProvider.PROVIDER_NAME*/)
    val signedCertificate = certificateFactory.generateCertificate(is1)
    
    Map("Start" -> eeX509CertificateStructure.getStartDate.getDate.toString, 
        "End" -> eeX509CertificateStructure.getEndDate.getDate.toString,
        "certificate" -> convertObjectToPEM(signedCertificate))
  }

  /**
   * Extracts and validates a public key from a PEM-encoded X.509 certificate.
   *
   * This method ensures the certificate was signed by the trusted issuer (e.g., AltaStata or the org's CA),
   * checks for expiration, and verifies that the `Subject` matches the expected user identity.
   *
   * @param pem The PEM-encoded certificate string.
   * @param issuerPublicKey The trusted public key of the issuer (used for signature verification).
   * @param subjectName The expected Common Name (CN) of the subject without the "CN=" prefix (e.g., `user@example.com`).
   * @param checkEndDate If true, asserts that the certificate's expiration date has not passed.
   * @param enforceAltaStataIssuerCn If true, requires the issuer CN to be exactly "CN=AltaStata" (default for Community).
   * @return A tuple containing the extracted `java.security.PublicKey` and its expiration `Date`.
   * @throws CertException if signature verification fails, the subject mismatches, or the certificate is expired.
   */
  def extractPublicKeyFromCertificate(
    pem: String,
    issuerPublicKey: PublicKey,
    subjectName: String,
    checkEndDate: Boolean,
    enforceAltaStataIssuerCn: Boolean = true
  ): (PublicKey, Date) = {
    val signedCertificate = convertPEMToSignedCertificate(pem)

    logger.trace("\tsignedCertificate from PEM: " + signedCertificate.getPublicKey)
    
    // convert java.security.cert.X509Certificate to org.bouncycastle.cert.X509CertificateHolder
    val certHolder = new X509CertificateHolder(signedCertificate.getEncoded)

    try {
      // verify certificate with issuer public key
      val contentVerifierProvider = new JcaContentVerifierProviderBuilder() /*.setProvider(BouncyCastleProvider.PROVIDER_NAME)*/.build(issuerPublicKey)

      if (!certHolder.isSignatureValid(contentVerifierProvider)) {
        logger.error("public key is not signed by AltaStata - wrong issuer")

        throw new CertException("public key is not signed by AltaStata - wrong issuer")
      }
    }
    catch { // DATABRICKS / BouncyCastle binary incompatibility
      case e: LinkageError =>
        logger.error("Cannot verify certificate signature because of a BouncyCastle linkage error", e)
        throw new CertException("Cannot verify certificate signature because of a BouncyCastle linkage error", e)
    }

    val eeX509CertificateStructure2 = certHolder.toASN1Structure()

    logger.debug(s"Issuer: '${eeX509CertificateStructure2.getIssuer}'")
    logger.debug("Subject: " + eeX509CertificateStructure2.getSubject)
    logger.debug("EndDate: " + eeX509CertificateStructure2.getEndDate)
    logger.trace("Public key: " + eeX509CertificateStructure2.getSubjectPublicKeyInfo.parsePublicKey)

    // TODO: AltaStata certificate should be signed by the third party authority
    if (enforceAltaStataIssuerCn) {
      if (eeX509CertificateStructure2.getIssuer.toString != "CN=AltaStata") {
        throw new CertException("Certificate is not issued by AltaStata")
      }
    }
    if (checkEndDate) {
      if (!eeX509CertificateStructure2.getEndDate.getDate.after(new Date)) {
        throw new CertException("Certificate signed by AltaStata has expired")
      }
    }
    val actualSubject = eeX509CertificateStructure2.getSubject.toString
    val expectedCn = "CN=" + subjectName
    if (actualSubject != expectedCn) {
      throw new CertException(
        s"Wrong subject name: expected $expectedCn. Certificate is provided to: $actualSubject."
      )
    }

    (signedCertificate.getPublicKey, eeX509CertificateStructure2.getEndDate.getDate)
  }
    
  private def convertPEMToSignedCertificate(pem: String):  java.security.cert.Certificate = {
    val cerfificateFactory = CertificateFactory.getInstance("X.509")
    cerfificateFactory.generateCertificate(new ByteArrayInputStream(pem.getBytes(StandardCharsets.UTF_8)))
  }

  /**
   * Encapsulates two PEM keys (e.g. Kyber and Dilithium) into a custom single X.509 certificate extensions structure
   * and signs it using the issuer private key.
   *
   * @param pem1 the first public key PEM string (e.g. Kyber)
   * @param pem2 the second public key PEM string (e.g. Dilithium)
   * @param issuerPrivateKey the signing authority's private key
   * @param subjectName the target certificate subject name (CN)
   * @param durationInYears the certificate validity period in years
   * @param issuerName the certificate issuer name (CN)
   * @return a map of (Start, End, certificate PEM string) representing the newly generated certificate
   */
  def signPEMsWithCertificate(
                               pem1: String,
                               pem2: String,
                               issuerPrivateKey: PrivateKey,
                               subjectName: String,
                               durationInYears: Int = 1,
                               issuerName: String = CertSubject.AltaStataIssuerCn
                             ): Map[String, String] = {
    /**
     * Decodes a PEM string into a byte array.
     *
     * @param pem the PEM string to decode
     * @return the decoded byte array
     */
    def decodePEM(pem: String): Array[Byte] = {
      val reader = new PemReader(new StringReader(pem))
      val pemObject = reader.readPemObject()
      reader.close()

      if (pemObject == null) {
        throw new IllegalArgumentException("Invalid PEM format: Missing or malformed headers (BEGIN/END).")
      }

      pemObject.getContent
    }

    // Convert PEM1 to SubjectPublicKeyInfo
    val decodedPem1 = decodePEM(pem1)
    val subjectPublicKeyInfo = SubjectPublicKeyInfo.getInstance(decodedPem1)

    // Create the certificate validity period
    val cal = new GregorianCalendar
    cal.add(Calendar.DAY_OF_YEAR, -1) // Start date: 1 day in the past
    val startDate = cal.getTime
    cal.add(Calendar.YEAR, durationInYears) // End date: durationInYears from now
    val endDate = cal.getTime

    // Build the certificate subject and issuer
    val issuer = new X500Name("CN=" + issuerName)
    val subject = new X500Name(s"CN=$subjectName")
    val serialNumber = BigInteger.ONE // For demo purposes, set a static serial number

    // Build the certificate
    val certBuilder = new X509v3CertificateBuilder(
      issuer,
      serialNumber,
      startDate,
      endDate,
      subject,
      subjectPublicKeyInfo
    )

    // AltaStata enterprise extensions: embedded ML-KEM and ML-DSA public key PEMs.
    certBuilder.addExtension(
      new ASN1ObjectIdentifier(AltaStataEnterpriseOids.KemPublicKeyPemExtensionOid),
      false,
      new DEROctetString(pem1.getBytes(StandardCharsets.UTF_8))
    )
    certBuilder.addExtension(
      new ASN1ObjectIdentifier(AltaStataEnterpriseOids.SignaturePublicKeyPemExtensionOid),
      false,
      new DEROctetString(pem2.getBytes(StandardCharsets.UTF_8))
    )

    // Sign the certificate with the issuer's private key
    val contentSigner = new JcaContentSignerBuilder("SHA256withRSA").build(issuerPrivateKey)
    val certHolder = certBuilder.build(contentSigner)

    // Convert to PEM format
    convertObjectToPEM(certHolder)

    Map("Start" -> startDate.toString,
      "End" -> endDate.toString,
      "certificate" -> convertObjectToPEM(certHolder))

  }

  /**
   * Extracts custom extensions (specifically PEMs for PQC keys) from a signed X.509 certificate.
   *
   * This is heavily used in the PQC (Post-Quantum Cryptography) implementation where Kyber and 
   * Dilithium public keys are embedded as custom X.509 extensions within a standard RSA certificate shell.
   *
   * @param pem The PEM-encoded wrapper certificate.
   * @param issuerPublicKey The trusted issuer public key used to verify the wrapper certificate's signature.
   * @param subjectName The expected CN of the subject.
   * @param checkEndDate If true, asserts that the certificate's expiration date has not passed.
   * @param enforceAltaStataIssuerCn If true, requires the issuer CN to match the default AltaStata community issuer.
   * @return A tuple containing (Kyber public key PEM, Dilithium public key PEM, Certificate expiration date).
   */
  def extractPEMsFromCertificate(
    pem: String,
    issuerPublicKey: PublicKey,
    subjectName: String,
    checkEndDate: Boolean = false,
    enforceAltaStataIssuerCn: Boolean = true
  ): (String, String, Date) = {
    val signedCertificate = convertPEMToSignedCertificate(pem).asInstanceOf[X509Certificate]

    logger.trace(s"Signed Certificate from PEM: ${signedCertificate.getPublicKey}")

    // Convert java.security.cert.X509Certificate to org.bouncycastle.cert.X509CertificateHolder
    val certHolder = new X509CertificateHolder(signedCertificate.getEncoded)

    try {
      // Verify certificate with issuer's public key
      val contentVerifierProvider = new JcaContentVerifierProviderBuilder().build(issuerPublicKey)
      if (!certHolder.isSignatureValid(contentVerifierProvider)) {
        logger.error("Certificate is not signed by AltaStata - wrong issuer")
        throw new CertException("Certificate is not signed by AltaStata - wrong issuer")
      }
    }
    catch { // DATABRICKS / BouncyCastle binary incompatibility
      case e: LinkageError =>
        logger.error("Cannot verify certificate signature because of a BouncyCastle linkage error", e)
        throw new CertException("Cannot verify certificate signature because of a BouncyCastle linkage error", e)
    }

    val eeX509CertificateStructure = certHolder.toASN1Structure()

    logger.debug(s"Issuer: ${eeX509CertificateStructure.getIssuer}")
    logger.debug(s"Subject: ${eeX509CertificateStructure.getSubject}")
    logger.debug(s"End Date: ${eeX509CertificateStructure.getEndDate}")

    // Ensure certificate properties match AltaStata and the subject name (Community path only).
    if (enforceAltaStataIssuerCn) {
      if (eeX509CertificateStructure.getIssuer.toString != "CN=AltaStata") {
        throw new CertException("Certificate is not issued by AltaStata")
      }
    }
    if (checkEndDate) {
      if (!eeX509CertificateStructure.getEndDate.getDate.after(new Date)) {
        throw new CertException("Certificate signed by AltaStata has expired")
      }
    }
    val actualSubject = eeX509CertificateStructure.getSubject.toString
    val expectedCn = s"CN=$subjectName"
    if (actualSubject != expectedCn) {
      throw new CertException(
        s"Wrong subject name: expected $expectedCn, but found $actualSubject."
      )
    }

    val pem1 = extractPEMFromExtension(certHolder, AltaStataEnterpriseOids.KemPublicKeyPemExtensionOid)
    val pem2 = extractPEMFromExtension(certHolder, AltaStataEnterpriseOids.SignaturePublicKeyPemExtensionOid)

    (pem1, pem2, eeX509CertificateStructure.getEndDate.getDate)
  }

  private def extractPEMFromExtension(certHolder: X509CertificateHolder, oid: String): String = {
    val extension = certHolder.getExtension(new ASN1ObjectIdentifier(oid))
    if (extension == null) throw new CertException(s"Missing extension for OID: $oid")

    // Decode the extension value as a string (assuming it's a UTF-8 encoded PEM)
    val octetString = ASN1OctetString.getInstance(extension.getParsedValue)
    new String(octetString.getOctets, StandardCharsets.UTF_8)
  }

}
