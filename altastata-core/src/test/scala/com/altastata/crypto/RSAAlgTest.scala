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

import java.security.KeyPairGenerator
import org.apache.commons.codec.binary.Base64

import java.nio.file.{Files, Paths}
import java.nio.charset.StandardCharsets
import com.altastata.utils.Constants
import com.altastata.utils.Account

import java.io.File
import javax.crypto.SealedObject
import javax.crypto.Cipher
import java.nio.ByteBuffer

/**
 * RSA sign/decrypt test. Pass the account directory (and optional password).
 * Default: ~/.altastata/accounts/amazon.rsa.hpcs.hpcsdev
 * On Mac with GREP11 (no PKCS#11 .so): set GREP11_YAML to grep11client.yaml (credentials in YAML) and HPCS_PRIV_KEY_BLOB_PATH if needed.
 */
object RSAAlgTest extends SecurityUtils {
  def main(args: Array[String]): Unit = {

    val account: Account = new Account()

    val propsDir = if (args.nonEmpty) args(0) else (Account.ALTASTATA_ACCOUNTS_HOME + File.separator + "amazon.rsa.hpcs.hpcsdev")
    account.loadAccountProperties(propsDir)

    var setPasswordOk = true
    try {
      account.setPassword(if (args.length > 1) args(1).toCharArray else Array.emptyCharArray)
    } catch {
      case e: Exception if account.getProperty("key-protection") == "HPCS" && account.hpcsKeyManager != null =>
        setPasswordOk = false
        // Public key comes from certificate (AWS userdata); until setPassword succeeds we don't have it.
        println("HPCS: setPassword failed (credentials encrypted with old key, e.g. CKR_ENCRYPTED_DATA_INVALID).")
        println("Re-encrypt credentials in AltaStata; then run this test again for full encrypt/decrypt/sign/verify.")
        return
    }

    val recoveredPublicKey =
      if (account.getProperty("key-protection") == "HPCS" && account.hpcsKeyManager != null)
        account.getHPCSPublicKeyOption().getOrElse(throw new IllegalStateException("HPCS: public key not found (read from certificate in AWS userdata after setPassword succeeds)"))
      else
        loadRSAPublicKeyFromPrivateKey()(account)

    val encrypted = encryptRSA(recoveredPublicKey, "Before text editors existed, computer text was punched into cards with keypunch machines. Physical boxes of these thi".getBytes, "RSA/None/NoPadding")
    val encrypted1 = encryptRSA(recoveredPublicKey, "Before text editors existed, computer text was punched into cards with keypunch machines. Physical boxes of these thi".getBytes, "RSA/None/NoPadding")
        
    println("encrypted: " + Base64.encodeBase64URLSafeString(encrypted) + " size: " + encrypted.length)
    println("encrypted1: " + Base64.encodeBase64URLSafeString(encrypted1)  + " size: " + encrypted1.length)

    val decrypted = decryptRSA(encrypted, "RSA/None/NoPadding")(account)

    println(s"Decrypted: \'" + new String(decrypted) + "\'")
    
    
    import scala.concurrent.ExecutionContext.Implicits.global    
    
    val initialBuffer = new String("Instead, you provision exactly the right type and size of computing resources you need to power your newest bright idea or operate your IT department. You can access as many resources as you need, almost instantly, and only pay for what you use. How Does it Work? Cloud Computing provides a simple way to access servers, storage, databases and a broad set of application services over the Internet. Cloud Computing providers such as Amazon Web Services own and maintain the network-connected hardware required for these application services, while you provision and use what you need via a web application.".getBytes)

//    val initialBuffer = new String("dir/file.txt".getBytes)

    println("initialBuffer.length: " + initialBuffer.length)

    val serializedMetadata = encryptArrayWithRSA(initialBuffer.getBytes, recoveredPublicKey, "RSA/ECB/PKCS1Padding")
    
    println("serializedMetadata.length: " + serializedMetadata.length)

    val deser = decryptArrayWithRSA(serializedMetadata, "RSA/ECB/PKCS1Padding") (account)
    
    println(new String(deser))
    
    val encrypted3 = encryptObjectPathIfNeeded("directory1/Моя_dir22/file1.txt", true, recoveredPublicKey)(account)
    println("encrypted3: " + encrypted3)

    val encrypted4 = encryptObjectPathIfNeeded("directory1/Моя_dir22/file2.txt", true, recoveredPublicKey) (account)

    println("encrypted4: " + encrypted4)
    
    val encrypted5 = reencryptObjectPathForOtherUser(encrypted3, recoveredPublicKey)(account)
    println("encrypted5: " + encrypted5)
      
    val decrypted3 = decryptObjectPathIfNeeded(encrypted3)(account)
    println("decrypted3: " + decrypted3)
    
    /**
encrypted3: Y9FiCBJZ1zFMOj06q69S0w/Wmh7fdHMHlgtC6Nk140Tng/y3zaIgv-zjmT_WyOcJj93Q/caC3fKAFm4Im5-Wn4MynzraWMQMdIFEtAU2JUdVOuY22k-leksvWQe34xtREhGM_D8KNmX0alz_byrhwChp3iioa5hJl6xASju4qZzBg-uoPdEpAaood87S5H2OU8hZ0GrOcBp2FTldnxFPPJZYPO7bYVtb1LNJMTNTTQ8Ds2WA
encrypted4: Y9FiCBJZ1zFMOj06q69S0w/Wmh7fdHMHlgtC6Nk140Tng/vofzzXMTmCp1ImSLm71mTg/Kj1ZQ14vd119qCgzLLWiAtw4TciAYxLRYCX5gjpWpkSDec9b7UswRXAmn1RVgiSWdCZ1riSM_Zh8iDfQy4jW70j-dhtxXdInQtBiqOx9_4_t9zFRyP51_-mie19OumlZxtO7B4E2Trgd6uwb3qJBKxCC5_JsETh2WPHbzFg3hsU
encrypted5: Y9FiCBJZ1zFMOj06q69S0w/Wmh7fdHMHlgtC6Nk140Tng/y3zaIgv-zjmT_WyOcJj93Q/caC3fKAFm4Im5-Wn4MynzraWMQMdIFEtAU2JUdVOuY22k-leksvWQe34xtREhGM_D8KNmX0alz_byrhwChp3iioa5hJl6xASju4qZzBg-uoPdEpAaood87S5H2OU8hZ0GrOcBp2FTldnxFPPJZYPO7bYVtb1LNJMTNTTQ8Ds2WA     
     */    
  }
}
