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

package com.altastata.cloud.amazon_java2.templates

object KMSPolicy {

  /**
   * Generates the AWS KMS key policy JSON string allowing encryption by any authenticated user
   * but restricted administrative and decryption access to specific users/roles.
   *
   * @param amazonAccount the AWS Account ID
   * @param userType the user type context
   * @param user the key owner's user ID
   * @param custodian the custodian's username
   * @return the generated policy JSON document
   */
  def encryptionKeyPolicy(amazonAccount: String, userType: String, user: String, custodian: String) =
    s"""{
           "Version": "2012-10-17",
           "Statement": [
               {
                   "Sid": "Enable Admin Permissions",
                   "Effect": "Allow",
                   "Principal": {
                       "AWS": "arn:aws:iam::$amazonAccount:root"
                   },
                   "Action": [
                      "kms:CancelKeyDeletion",
                      "kms:CreateAlias",
                      "kms:CreateGrant",
                      "kms:CreateKey",
                      "kms:DeleteAlias",
                      "kms:DeleteImportedKeyMaterial",
                      "kms:DescribeKey",
                      "kms:DisableKey",
                      "kms:DisableKeyRotation",
                      "kms:EnableKey",
                      "kms:EnableKeyRotation",
                      "kms:GetKeyPolicy",
                      "kms:GetKeyRotationStatus",
                      "kms:GetParametersForImport",
                      "kms:ImportKeyMaterial",
                      "kms:ListAliases",
                      "kms:ListGrants",
                      "kms:ListKeyPolicies",
                      "kms:ListKeys",
                      "kms:ListRetirableGrants",
                      "kms:RevokeGrant",
                      "kms:ScheduleKeyDeletion",
                      "kms:UpdateAlias",
                      "kms:UpdateKeyDescription"
                   ],
                   "Resource": "*"
               },
               {
                      "Sid": "$custodian is allowed to do everything this KMS key",
                      "Effect": "Allow",
                      "Principal": {
                          "AWS": "arn:aws:iam::$amazonAccount:user/$custodian"
                      },
                      "Action": [
                        "kms:CancelKeyDeletion",
                        "kms:CreateAlias",
                        "kms:CreateGrant",
                        "kms:CreateKey",
                        "kms:DeleteAlias",
                        "kms:DeleteImportedKeyMaterial",
                        "kms:DescribeKey",
                        "kms:DisableKey",
                        "kms:DisableKeyRotation",
                        "kms:EnableKey",
                        "kms:EnableKeyRotation",
                        "kms:GetKeyPolicy",
                        "kms:PutKeyPolicy",
                        "kms:GetKeyRotationStatus",
                        "kms:GetParametersForImport",
                        "kms:ImportKeyMaterial",
                        "kms:ListAliases",
                        "kms:ListGrants",
                        "kms:ListKeyPolicies",
                        "kms:ListKeys",
                        "kms:ListRetirableGrants",
                        "kms:RevokeGrant",
                        "kms:ScheduleKeyDeletion",
                        "kms:UpdateAlias",
                        "kms:UpdateKeyDescription"
                      ],
                      "Resource": "*"
               },
               {
                   "Sid": "The key owner $user is allowed to do encryption using this kms key",
                   "Effect": "Allow",
                   "Principal": {
                       "AWS": "arn:aws:iam::$amazonAccount:$userType/$user"
                   },
                   "Action": [
                       "kms:Encrypt",
                       "kms:Decrypt",
                       "kms:CreateAlias",
                       "kms:ListAliases",
                       "kms:ListGrants",
                       "kms:ListKeyPolicies",
                       "kms:ListKeys",
                       "kms:ListRetirableGrants",
                       "kms:ScheduleKeyDeletion",
                       "kms:GetKeyPolicy",
                       "kms:PutKeyPolicy"
                   ],
                   "Resource": "*"
               },
               {
                   "Sid": "Everyone can kms:Encrypt using this kms key",
                   "Effect": "Allow",
                   "Principal": {
                       "AWS": "*"
                   },
                   "Action": "kms:Encrypt",
                   "Resource": "*"
               }
           ]
       }"""

  /**
   * Generates the AWS KMS key policy JSON string specifically tailored for signatures, allowing decrypt/sign
   * rights to specified owners and custodians while restricting others.
   *
   * @param amazonAccount the AWS Account ID
   * @param userType the user type context
   * @param user the key owner's user ID
   * @param custodian the custodian's username
   * @return the generated policy JSON document
   */
  def signatureKeyPolicy(amazonAccount: String, userType: String, user: String, custodian: String) =
    s"""{
           "Version": "2012-10-17",
           "Statement": [
                {
                    "Sid": "Enable Admin Permissions",
                    "Effect": "Allow",
                    "Principal": {
                        "AWS": "arn:aws:iam::$amazonAccount:root"
                    },
                    "Action": [
                      "kms:CancelKeyDeletion",
                      "kms:CreateAlias",
                      "kms:CreateGrant",
                      "kms:CreateKey",
                      "kms:DeleteAlias",
                      "kms:DeleteImportedKeyMaterial",
                      "kms:DescribeKey",
                      "kms:DisableKey",
                      "kms:DisableKeyRotation",
                      "kms:EnableKey",
                      "kms:EnableKeyRotation",
                      "kms:GetKeyPolicy",
                      "kms:GetKeyRotationStatus",
                      "kms:GetParametersForImport",
                      "kms:ImportKeyMaterial",
                      "kms:ListAliases",
                      "kms:ListGrants",
                      "kms:ListKeyPolicies",
                      "kms:ListKeys",
                      "kms:ListRetirableGrants",
                      "kms:RevokeGrant",
                      "kms:ScheduleKeyDeletion",
                      "kms:UpdateAlias",
                      "kms:UpdateKeyDescription"
                    ],
                    "Resource": "*"
               },
               {
                     "Sid": "$custodian is allowed to do everything this KMS key",
                     "Effect": "Allow",
                     "Principal": {
                         "AWS": "arn:aws:iam::$amazonAccount:user/$custodian"
                     },
                     "Action": [
                       "kms:CancelKeyDeletion",
                       "kms:CreateAlias",
                       "kms:CreateGrant",
                       "kms:CreateKey",
                       "kms:DeleteAlias",
                       "kms:DeleteImportedKeyMaterial",
                       "kms:DescribeKey",
                       "kms:DisableKey",
                       "kms:DisableKeyRotation",
                       "kms:EnableKey",
                       "kms:EnableKeyRotation",
                       "kms:GetKeyPolicy",
                       "kms:PutKeyPolicy",
                       "kms:GetKeyRotationStatus",
                       "kms:GetParametersForImport",
                       "kms:ImportKeyMaterial",
                       "kms:ListAliases",
                       "kms:ListGrants",
                       "kms:ListKeyPolicies",
                       "kms:ListKeys",
                       "kms:ListRetirableGrants",
                       "kms:RevokeGrant",
                       "kms:ScheduleKeyDeletion",
                       "kms:UpdateAlias",
                       "kms:UpdateKeyDescription"
                     ],
                     "Resource": "*"
               },
               {
                   "Sid": "The key owner $user is allowed to do encryption/decryption using this kms key",
                   "Effect": "Allow",
                   "Principal": {
                       "AWS": "arn:aws:iam::$amazonAccount:$userType/$user"
                   },
                   "Action": [
                       "kms:Encrypt",
                       "kms:Decrypt",
                       "kms:CreateAlias",
                       "kms:ListAliases",
                       "kms:ListGrants",
                       "kms:ListKeyPolicies",
                       "kms:ListKeys",
                       "kms:ListRetirableGrants",
                       "kms:ScheduleKeyDeletion",
                       "kms:GetKeyPolicy",
                       "kms:PutKeyPolicy"
                   ],
                   "Resource": "*"
               },
               {
                   "Sid": "Everyone can kms:Decrypt using this kms key",
                   "Effect": "Allow",
                   "Principal": {
                       "AWS": "*"
                   },
                   "Action": "kms:Decrypt",
                   "Resource": "*"
               }
           ]
       }"""
}
