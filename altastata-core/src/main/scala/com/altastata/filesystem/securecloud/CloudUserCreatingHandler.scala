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

import java.security.PublicKey
import java.util.Properties

trait CloudUserCreatingHandler {
  
  /**
   * Modifies, validates, or enhances properties files/configurations for a newly initialized user's cloud account structure.
   *
   * @param publicKey the user's RSA public key to integrate
   * @return the generated/enhanced Properties map containing the configuration keys
   */
  def enhanceUserPropertiesIfNeeded(publicKey: PublicKey): Properties
}
