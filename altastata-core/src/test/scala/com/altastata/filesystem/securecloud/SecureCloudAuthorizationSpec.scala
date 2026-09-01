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

package com.altastata.filesystem.securecloud

import com.altastata.filesystem.UserMetadata
import org.junit.runner.RunWith
import org.scalatest.funsuite.AnyFunSuite
import org.scalatestplus.junit.JUnitRunner

import java.security.{KeyPairGenerator, PublicKey, Signature}
import java.util.Base64

@RunWith(classOf[JUnitRunner])
class SecureCloudAuthorizationSpec extends AnyFunSuite {

  private object Ops extends SecureCloudOperations {
    def userdata(signer: String, m: UserMetadata): String = userdataAuthorityString(signer, m)
    def metadata(signer: String, m: StorageObjectMetadata): String = metadataAuthorityString(signer, m)
    def verifyRsa(key: PublicKey, data: Array[Byte], sig: Array[Byte]): Boolean =
      verifySignatureWithRSA(key, data, sig)
  }

  /** Mirror of admin-side signStringWithOrgCa: SHA256withRSA over the authority string, Base64. */
  private def signSha256Rsa(privateKey: java.security.PrivateKey, str: String): String = {
    val sig = Signature.getInstance("SHA256withRSA")
    sig.initSign(privateKey)
    sig.update(str.getBytes("UTF-8"))
    Base64.getEncoder.encodeToString(sig.sign())
  }

  test("userdata authority string binds signer, user name, and public key") {
    val original = UserMetadata("bob", "user", "org", metadataEncryption = Some("RSA"), publicKey = Some("PEM-A"))
    val tampered = original.copy(publicKey = Some("PEM-B"))

    assert(Ops.userdata("bob", original) === "bob/bob/PEM-A")
    assert(Ops.userdata("bob", original) !== Ops.userdata("bob", tampered))
    assert(Ops.userdata("bob", original) !== Ops.userdata("admin", original))
  }

  test("metadata authority string binds DEK and attribute locator") {
    val original = StorageObjectMetadata(
      metadataVersion = "v1.0",
      fileAttrs = FileAttrs("/data/file.bin", "owner", 1L, "1"),
      encryptionAttrs = EncryptionAttrs("AES-256-GCM", "key-A", ""),
      storageAttrs = StorageAttrs("owner", "data-locator", false, "owner", "attrs-locator")
    )
    val tamperedKey = original.copy()
    tamperedKey.encryptionAttrs = EncryptionAttrs("AES-256-GCM", "key-B", "")
    val tamperedAttrs = original.copy()
    tamperedAttrs.storageAttrs = StorageAttrs("owner", "data-locator", false, "owner", "other-attrs")

    assert(Ops.metadata("owner", original) !== Ops.metadata("owner", tamperedKey))
    assert(Ops.metadata("owner", original) !== Ops.metadata("owner", tamperedAttrs))
  }

  test("admin ADD_USERDATA: org-CA signature verifies against org-ca.pem trust anchor") {
    // Org CA keypair: admin signs with the private key, custodian holds only the public key (org-ca.pem).
    val kpg = KeyPairGenerator.getInstance("RSA")
    kpg.initialize(2048)
    val orgCa = kpg.generateKeyPair()

    val newUser = UserMetadata("catrina", "user", "org", metadataEncryption = Some("RSA"), publicKey = Some("USER-PEM"))
    val authorityString = Ops.userdata("admin", newUser) // "admin/catrina/USER-PEM"

    // Admin side (signStringWithOrgCa) -> custodian side (checkIfUserdataAuthorityValid else-branch).
    val signature = signSha256Rsa(orgCa.getPrivate, authorityString)
    assert(Ops.verifyRsa(orgCa.getPublic, authorityString.getBytes("UTF-8"), Base64.getDecoder.decode(signature)))
  }

  test("admin ADD_USERDATA: rejected when signed by a non-CA key (spoofing) or payload tampered") {
    val kpg = KeyPairGenerator.getInstance("RSA")
    kpg.initialize(2048)
    val orgCa = kpg.generateKeyPair()
    val attacker = kpg.generateKeyPair()

    val newUser = UserMetadata("catrina", "user", "org", metadataEncryption = Some("RSA"), publicKey = Some("USER-PEM"))
    val authorityString = Ops.userdata("admin", newUser)

    // Signed by an attacker's key, not the org CA: must fail against org-ca.pem.
    val forged = signSha256Rsa(attacker.getPrivate, authorityString)
    assert(!Ops.verifyRsa(orgCa.getPublic, authorityString.getBytes("UTF-8"), Base64.getDecoder.decode(forged)))

    // Valid CA signature but payload changed after signing: must fail.
    val validSig = signSha256Rsa(orgCa.getPrivate, authorityString)
    val tampered = Ops.userdata("admin", newUser.copy(publicKey = Some("EVIL-PEM")))
    assert(!Ops.verifyRsa(orgCa.getPublic, tampered.getBytes("UTF-8"), Base64.getDecoder.decode(validSig)))
  }
}
