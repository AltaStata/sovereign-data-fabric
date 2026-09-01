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

object UserPolicy {

  /**
   * Generates the AWS IAM policy JSON string for a Custodian user under the specified organization.
   * Gives Custodian full permissions over the catalog, changes, and user metadata S3 buckets.
   *
   * @param org the organization name tag
   * @return the generated policy JSON string
   */
  def custodianPolicy(org: String) =
    s"""{
            "Version": "2012-10-17",
            "Statement": [
                {
                    "Sid": "1",
                    "Effect": "Allow",
                    "Action": [
                        "s3:GetObject",
                        "s3:PutObject",
                        "s3:DeleteObject"
                    ],
                    "Resource": [
                        "arn:aws:s3:::altastata-$org-users/*"
                    ]
                },
                {
                    "Sid": "2",
                    "Effect": "Allow",
                    "Action": [
                        "s3:ListBucket"
                    ],
                    "Resource": [
                        "arn:aws:s3:::altastata-$org-users"
                    ]
                },
                {
                    "Sid": "3",
                    "Effect": "Allow",
                    "Action": [
                        "s3:*"
                    ],
                    "Resource": [
                        "arn:aws:s3:::altastata-$org-catalog/*"
                    ]
                },
                {
                    "Sid": "4",
                    "Effect": "Allow",
                    "Action": [
                        "s3:ListBucket"
                    ],
                    "Resource": [
                        "arn:aws:s3:::altastata-$org-catalog"
                    ]
                },
                {
                    "Sid": "5",
                    "Effect": "Allow",
                    "Action": [
                        "s3:*"
                    ],
                    "Resource": [
                        "arn:aws:s3:::altastata-$org-changes/*"
                    ]
                },
                {
                    "Sid": "6",
                    "Effect": "Allow",
                    "Action": [
                        "s3:ListBucket"
                    ],
                    "Resource": [
                        "arn:aws:s3:::altastata-$org-changes"
                    ]
                },
                {
                    "Sid": "7",
                    "Effect": "Deny",
                    "Action": [
                        "s3:*"
                    ],
                    "Resource": [
                        "arn:aws:s3:::altastata-$org-chunks/*"
                    ]
                },
                {
                    "Sid": "8",
                    "Effect": "Allow",
                    "Action": [
                        "s3:*"
                    ],
                    "Resource": [
                        "arn:aws:s3:::altastata-$org-dataattributes/*"
                    ]
                },
                {
                    "Sid": "9",
                    "Effect": "Allow",
                    "Action": [
                        "s3:ListBucket"
                    ],
                    "Resource": [
                        "arn:aws:s3:::altastata-$org-dataattributes"
                    ]
                },
                {
                    "Sid": "12",
                    "Effect": "Allow",
                    "Action": [
                      "kms:CreateKey",
                      "iam:GetUser",
                      "iam:GetRole"
                    ],
                    "Resource": [
                      "*"
                    ]
                }
            ]
        }"""

  /**
   * Generates the AWS IAM policy JSON string for a standard Client/User under the specified organization.
   * Scopes S3 resource access specifically to the user's namespace directory within key buckets.
   *
   * @param org the organization name tag
   * @param user the username to scope permissions for
   * @return the generated policy JSON string
   */
  def userPolicy(org: String, user: String) =
    s"""{
      "Version": "2012-10-17",
      "Statement": [
          {
              "Effect": "Allow",
              "Action": [
                  "s3:GetObject"
              ],
              "Resource": [
                  "arn:aws:s3:::altastata-$org-users/*"
              ]
          },
          {
              "Effect": "Allow",
              "Action": [
                  "s3:ListBucket"
              ],
              "Resource": [
                  "arn:aws:s3:::altastata-$org-users"
              ]
          },
          {
              "Effect": "Allow",
              "Action": [
                  "s3:*"
              ],
              "Resource": [
                  "arn:aws:s3:::altastata-$org-catalog/$user/*"
              ]
          },
          {
              "Effect": "Allow",
              "Action": [
                  "s3:ListBucket"
              ],
              "Resource": [
                  "arn:aws:s3:::altastata-$org-catalog"
              ]
          },
          {
              "Effect": "Allow",
              "Action": [
                  "s3:*"
              ],
              "Resource": [
                  "arn:aws:s3:::altastata-$org-changes/$user/*"
              ]
          },
          {
              "Effect": "Allow",
              "Action": [
                  "s3:ListBucket"
              ],
              "Resource": [
                  "arn:aws:s3:::altastata-$org-changes"
              ]
          },
          {
              "Effect": "Allow",
              "Action": [
                  "s3:PutObject"
              ],
              "Resource": [
                  "arn:aws:s3:::altastata-$org-changes/*"
              ]
          },
          {
              "Effect": "Allow",
              "Action": [
                  "s3:GetObject"
              ],
              "Resource": [
                  "arn:aws:s3:::altastata-$org-chunks/*"
              ]
          },
          {
              "Effect": "Allow",
              "Action": [
                  "s3:PutAccelerateConfiguration",
                  "s3:GetAccelerateConfiguration"
              ],
              "Resource": [
                  "arn:aws:s3:::altastata-$org-chunks"
              ]
          },
          {
              "Effect": "Allow",
              "Action": [
                  "s3:PutObject",
                  "s3:DeleteObject"
              ],
              "Resource": [
                  "arn:aws:s3:::altastata-$org-chunks/$user/*"
              ]
          },
          {
              "Effect": "Allow",
              "Action": [
                  "s3:GetObject",
                  "s3:PutObject",
                  "s3:DeleteObject"
              ],
              "Resource": [
                  "arn:aws:s3:::altastata-$org-dataattributes/$user/*"
              ]
          },
          {
              "Effect": "Allow",
              "Action": [
                  "s3:GetObject"
              ],
              "Resource": [
                  "arn:aws:s3:::altastata-$org-dataattributes/*"
              ]
          },
          {
              "Effect": "Allow",
              "Action": [
                  "s3:ListBucket"
              ],
              "Resource": [
                  "arn:aws:s3:::altastata-$org-dataattributes"
              ]
          },
          {
              "Effect": "Allow",
              "Action": [
                "kms:CreateKey",
                "iam:GetUser",
                "iam:GetRole"
              ],
              "Resource": [
                "*"
              ]
          }
      ]
  }"""

  /**
   * Generates the trust relationship JSON policy document permitting EC2 instances to assume an IAM role.
   *
   * @return the trust policy JSON string
   */
  def ec2TrustRelationship() =
    """{
        "Version": "2012-10-17",
        "Statement": [
          {
            "Sid": "",
            "Effect": "Allow",
            "Principal": {
              "Service": "ec2.amazonaws.com"
            },
            "Action": "sts:AssumeRole"
          }
        ]
      }"""
}
