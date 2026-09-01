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

import org.bouncycastle.jce.provider.BouncyCastleProvider

import java.nio.charset.StandardCharsets
import java.security.{PrivateKey, PublicKey, Security, Signature}
import java.util.Base64

/** Minimal RS256 JWT sign/verify (no external JWT dependency). */
object JwtRs256 {

  private val Alg = "RS256"

  if (Security.getProvider("BC") == null) {
    Security.addProvider(new BouncyCastleProvider())
  }

  /**
   * Generates a signed RS256 JWT for the given JSON payload.
   *
   * @param payloadJson The JSON payload to be wrapped in the JWT.
   * @param privateKey The RSA private key used to sign the token.
   * @return A standard Base64URL-encoded JWT string.
   */
  def sign(payloadJson: String, privateKey: PrivateKey): String = {
    val headerJson = s"""{"alg":"$Alg","typ":"JWT"}"""
    val signingInput = s"${b64Url(headerJson)}.${b64Url(payloadJson)}"
    val signature = signBytes(signingInput.getBytes(StandardCharsets.UTF_8), privateKey)
    s"$signingInput.${b64Url(signature)}"
  }

  /**
   * Verifies an RS256 JWT against an RSA public key and extracts the raw JSON payload.
   *
   * @param jwt The Base64URL-encoded JWT token string.
   * @param publicKey The RSA public key of the signer.
   * @return The verified JSON payload string.
   * @throws IllegalArgumentException if the JWT format is invalid.
   * @throws SecurityException if signature verification fails.
   */
  def verifyAndGetPayload(jwt: String, publicKey: PublicKey): String = {
    val parts = jwt.split("\\.", 3)
    if (parts.length != 3) {
      throw new IllegalArgumentException("Invalid JWT format")
    }
    val signingInput = s"${parts(0)}.${parts(1)}"
    val signature = b64UrlDecode(parts(2))
    if (!verifyBytes(signingInput.getBytes(StandardCharsets.UTF_8), signature, publicKey)) {
      throw new SecurityException("Invalid JWT signature")
    }
    new String(b64UrlDecode(parts(1)), StandardCharsets.UTF_8)
  }

  /**
   * Helper method to calculate SHA256withRSA signature using the BouncyCastle provider.
   */
  private def signBytes(data: Array[Byte], privateKey: PrivateKey): Array[Byte] = {
    val sig = Signature.getInstance("SHA256withRSA", "BC")
    sig.initSign(privateKey)
    sig.update(data)
    sig.sign()
  }

  /**
   * Helper method to verify SHA256withRSA signature using the BouncyCastle provider.
   */
  private def verifyBytes(data: Array[Byte], signature: Array[Byte], publicKey: PublicKey): Boolean = {
    val sig = Signature.getInstance("SHA256withRSA", "BC")
    sig.initVerify(publicKey)
    sig.update(data)
    sig.verify(signature)
  }

  /**
   * Helper method to perform URL-safe Base64 encoding without padding on a string.
   */
  private def b64Url(text: String): String =
    b64Url(text.getBytes(StandardCharsets.UTF_8))

  /**
   * Helper method to perform URL-safe Base64 encoding without padding on a byte array.
   */
  private def b64Url(bytes: Array[Byte]): String =
    Base64.getUrlEncoder.withoutPadding.encodeToString(bytes)

  /**
   * Helper method to decode a URL-safe Base64 encoded string.
   */
  private def b64UrlDecode(text: String): Array[Byte] =
    Base64.getUrlDecoder.decode(text)
}
