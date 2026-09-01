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

package com.altastata.cloud.amazon_java2

import com.altastata.utils.Account
import software.amazon.awssdk.auth.credentials.{AnonymousCredentialsProvider, AwsSessionCredentials, StaticCredentialsProvider}
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.cognitoidentityprovider.{CognitoIdentityProviderClient, model => cognitoModel}
import software.amazon.awssdk.services.cognitoidentity.{CognitoIdentityClient, model => identityModel}
import software.amazon.awssdk.services.cognitoidentityprovider.model.{AttributeType, AuthFlowType, ConfirmSignUpRequest, InitiateAuthRequest, SignUpRequest}
import software.amazon.awssdk.services.cognitoidentity.model.GetIdRequest

import scala.collection.JavaConverters._

class CognitoClient(implicit account: Account) {

  /**
   * Registers/Signs up a new user inside the AWS Cognito User Pool.
   *
   * @param username the target username to register
   * @param password the password for the new user
   * @param email the email address associated with the user
   * @param phoneNumber the phone number associated with the user
   */
  def signUpUser(username: String, password: String, email: String, phoneNumber: String): Unit = {

    val CLIENT_APP_ID = account.getProperty("cognito-clientapp-id")
    val REGION = account.getProperty("cognito-region")

    // Create a CognitoIdentityProviderClient using anonymous credentials
    val cognitoClient = CognitoIdentityProviderClient.builder()
      .credentialsProvider(AnonymousCredentialsProvider.create())
      .region(Region.of(REGION))
      .build()

    // Prepare user attributes
    val attributes = List(
      AttributeType.builder().name("phone_number").value(phoneNumber).build(),
      AttributeType.builder().name("email").value(email).build()
    ).asJava

    // Create sign-up request
    val signUpRequest = SignUpRequest.builder()
      .clientId(CLIENT_APP_ID)
      .username(username)
      .password(password)
      .userAttributes(attributes)
      .build()

    // Call sign-up API
    cognitoClient.signUp(signUpRequest)
    cognitoClient.close()
  }

  /**
   * Verifies the user's registration by confirming their sign-up using an MFA/OTP access code.
   *
   * @param username the username to verify
   * @param code the confirmation code sent to the user
   */
  def verifyAccessCode(username: String, code: String): Unit = {

    val CLIENT_APP_ID = account.getProperty("cognito-clientapp-id")
    val REGION = account.getProperty("cognito-region")

    // Create a CognitoIdentityProvider client using anonymous credentials
    val cognitoClient: CognitoIdentityProviderClient = CognitoIdentityProviderClient.builder()
      .credentialsProvider(AnonymousCredentialsProvider.create())
      .region(Region.of(REGION))
      .build()

    // Prepare confirm sign-up request
    val confirmSignUpRequest: ConfirmSignUpRequest = ConfirmSignUpRequest.builder()
      .username(username)
      .confirmationCode(code)
      .clientId(CLIENT_APP_ID)
      .build()

    cognitoClient.confirmSignUp(confirmSignUpRequest)

    cognitoClient.close()
  }

  /**
   * Authenticates user credentials with Cognito and returns the session token if successful.
   *
   * @param username the username to validate
   * @param password the password to validate
   * @return the active AWS session token string, or null on failure
   */
  def validateUser(username: String, password: String): String = {
    try {
      val credentialsProvider = getCredentialsProvider(username, password)

      val credentials = credentialsProvider.resolveCredentials()

      // Check if session token exists (it will be present if using session-based credentials)
      credentials match {
        case sessionCredentials: AwsSessionCredentials =>
          sessionCredentials.sessionToken()
        case _ =>
          null
      }
    }
    catch {
      case ex: Exception =>
        null
    }
  }

  /**
   * Retrieves the Cognito Identity ID for a user authenticated via the User Pool ID token.
   *
   * @param username the target username
   * @param password the password
   * @return the resolved Cognito Identity ID string
   */
  def getIdentityId(username: String, password: String): String = {

    val USER_POOL_ID = account.getProperty("cognito-user-pool-id")
    val REGION = account.getProperty("cognito-region")
    val FED_POOL_ID = account.getProperty("cognito-fed-identity-pool-id")

    val tokenId = getTokenId(username, password)

    // Create Cognito Identity Client
    val cognitoIdentityClient = CognitoIdentityClient.builder()
      .region(Region.of(REGION)) // Specify the region where the Identity Pool is
      .build()

    // Create GetIdRequest
    val getIdRequest = GetIdRequest.builder()
      .identityPoolId(FED_POOL_ID)
      .logins(Map(s"cognito-idp.${REGION}.amazonaws.com/${USER_POOL_ID}" -> tokenId).asJava) // Cognito logins mapping
      .build()

    // Get the Identity ID
    val getIdResponse = cognitoIdentityClient.getId(getIdRequest)
    val identityId = getIdResponse.identityId()

    identityId
  }

  private def getTokenId(username: String, password: String) = {
    val CLIENT_APP_ID = account.getProperty("cognito-clientapp-id")
    val REGION = account.getProperty("cognito-region")

    val cognitoClient = CognitoIdentityProviderClient.builder()
      .region(Region.of(REGION))
      .build()

    val authRequest = InitiateAuthRequest.builder()
      .clientId(CLIENT_APP_ID) // Cognito app client ID, replace with yours
      .authFlow(AuthFlowType.USER_PASSWORD_AUTH) // Non-admin user auth flow
      .authParameters(Map(
        "USERNAME" -> username,
        "PASSWORD" -> password
      ).asJava)
      .build()

    val authResponse = cognitoClient.initiateAuth(authRequest)

    authResponse.authenticationResult().idToken()
  }

  /**
   * Resolves a StaticCredentialsProvider supplying temporary, scoped AWS session credentials.
   *
   * @param username the target username
   * @param password the password
   * @return the resolved static credentials provider wrapping session credentials
   */
  def getCredentialsProvider(username: String, password: String): StaticCredentialsProvider = {

    // Step 1: Authenticate the user
    val tokenId: String = getTokenId(username, password)

    // Step 2: Get temporary AWS credentials using Identity Pool
    val credentials = getAWSCredentials(tokenId)

    //cognitoClient.close()

    val statCredentialsProvider = StaticCredentialsProvider.create(credentials)

    statCredentialsProvider
  }

  /**
   * Exchanges a Cognito User Pool ID token for temporary AWS Session Credentials via Cognito Identity Pools.
   *
   * @param tokenId the OIDC id token from User Pool login
   * @return the temporary AWS session credentials
   */
  def getAWSCredentials(tokenId: String): AwsSessionCredentials = {

    val USER_POOL_ID = account.getProperty("cognito-user-pool-id")
    val REGION = account.getProperty("cognito-region")
    val FED_POOL_ID = account.getProperty("cognito-fed-identity-pool-id")

    val identityClient = CognitoIdentityClient.builder()
      .region(Region.of(REGION))
      .build()

    val getIdRequest = identityModel.GetIdRequest.builder()
      .identityPoolId(FED_POOL_ID)
      .logins(Map(s"cognito-idp.${REGION}.amazonaws.com/${USER_POOL_ID}" -> tokenId).asJava)
      .build()

    val getIdResponse = identityClient.getId(getIdRequest)

    val getCredentialsRequest = identityModel.GetCredentialsForIdentityRequest.builder()
      .identityId(getIdResponse.identityId())
      .logins(Map(s"cognito-idp.${REGION}.amazonaws.com/${USER_POOL_ID}" -> tokenId).asJava)
      .build()

    val getCredentialsResponse = identityClient.getCredentialsForIdentity(getCredentialsRequest)

    AwsSessionCredentials.create(
      getCredentialsResponse.credentials().accessKeyId(),
      getCredentialsResponse.credentials().secretKey(),
      getCredentialsResponse.credentials().sessionToken()
    )
  }
}
