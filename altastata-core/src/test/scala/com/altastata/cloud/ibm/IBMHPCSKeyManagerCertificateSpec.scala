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

package com.altastata.cloud.ibm

import com.altastata.utils.Account
import org.junit.runner.RunWith
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.junit.JUnitRunner

import java.security.KeyPairGenerator
import java.util.Base64

@RunWith(classOf[JUnitRunner])
class IBMHPCSKeyManagerCertificateSpec extends AnyFlatSpec with Matchers {

  private def rsaKeyPair(bits: Int = 2048) = {
    val kpg = KeyPairGenerator.getInstance("RSA")
    kpg.initialize(bits)
    kpg.generateKeyPair()
  }

  "IBMHPCSKeyManager.createSelfSignedCertificate" should "produce a verifiable self-signed X.509 cert matching the key pair" in {
    implicit val account: Account = new Account()
    val mgr = new IBMHPCSKeyManager()
    val kp = rsaKeyPair()
    val username = "unittest-user"

    val cert = mgr.createSelfSignedCertificate(username, kp, validityYears = 10)

    cert.verify(cert.getPublicKey)

    cert.getPublicKey.getEncoded shouldEqual kp.getPublic.getEncoded

    val subject = cert.getSubjectX500Principal.getName
    subject should include(username)

    cert.getSigAlgName should (equal("SHA256withRSA") or equal("SHA256WithRSA"))

    // Standard PEM wrapper used elsewhere in AltaStata
    val pem = mgr.certificateToPEM(cert)
    pem should include("-----BEGIN CERTIFICATE-----")
    pem should include("-----END CERTIFICATE-----")
    val inner =
      pem.replace("-----BEGIN CERTIFICATE-----", "").replace("-----END CERTIFICATE-----", "").replaceAll("\\s", "")
    Base64.getDecoder.decode(inner) shouldEqual cert.getEncoded
  }

  it should "produce a certificate that passes checkValidity() for now" in {
    implicit val account: Account = new Account()
    val mgr = new IBMHPCSKeyManager()
    val cert = mgr.createSelfSignedCertificate("alice", rsaKeyPair(), validityYears = 1)
    cert.checkValidity()
  }
}
