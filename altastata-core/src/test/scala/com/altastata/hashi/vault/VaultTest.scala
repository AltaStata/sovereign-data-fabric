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

package com.altastata.hashi.vault

import com.bettercloud.vault.VaultConfig
import com.bettercloud.vault.Vault

object VaultTest extends App {
  val token = sys.env.getOrElse("VAULT_TOKEN", "")
  if (token.isEmpty) {
    System.err.println("Set VAULT_TOKEN to the root token printed by `vault server -dev`.")
    sys.exit(1)
  }
  val vaultConfig = new VaultConfig("http://127.0.0.1:8200", token)
  
  val vault = new Vault(vaultConfig)
  
  val value = vault.logical()
                       .read("cubbyhole/demo")
                       .getData().get("passwordkey")
                       
  println(value)
}
