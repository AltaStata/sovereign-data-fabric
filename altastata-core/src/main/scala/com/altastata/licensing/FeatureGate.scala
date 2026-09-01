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

package com.altastata.licensing

object FeatureGate {

  /**
   * Asserts that the license includes a specific feature entitlement, throwing SecurityException if missing.
   *
   * @param license the organization license to inspect
   * @param feature the feature identifier name
   * @throws SecurityException if the feature is not active
   */
  def requireFeature(license: OrgLicense, feature: String): Unit = {
    if (!license.hasFeature(feature)) {
      throw new SecurityException(
        s"License tier '${license.tier}' does not include required feature '$feature'"
      )
    }
  }
}
