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

/*
 * Browser gRPC-Web example for AltaStata auth + UsersService.
 *
 * Prerequisites:
 * 1) Generate grpc-web JS stubs into ./gen (see altastata-grpc/README.md).
 * 2) Install deps in your frontend project:
 *      npm install grpc-web google-protobuf
 * 3) Ensure altastata-grpc server is running on http://127.0.0.1:9877
 * 4) Account folder on the same machine as the gateway (loopback co-located login).
 */

import { AuthServiceClient } from "../gen/altastata/grpc/v1/AuthServiceClientPb";
import { UsersServiceClient } from "../gen/altastata/grpc/v1/UsersServiceClientPb";
import { LoginV2Request } from "../gen/altastata/grpc/v1/auth_pb";
import { GetMyAccountRequest } from "../gen/altastata/grpc/v1/users_pb";

const endpoint = "http://127.0.0.1:9877";
const accountDirectory = "/home/jovyan/.altastata/accounts/amazon.rsa.bob123";
const password = "your-account-password";

const authClient = new AuthServiceClient(endpoint, null, null);
const usersClient = new UsersServiceClient(endpoint, null, null);

function loginV2() {
  return new Promise((resolve, reject) => {
    const req = new LoginV2Request();
    req.setClientHint("grpcweb-users-example");
    req.setPassword(password);
    req.setUserAccountDirectory(accountDirectory);

    authClient.loginV2(req, {}, (err, resp) => {
      if (err) {
        reject(err);
        return;
      }
      resolve({
        sessionToken: resp.getSessionToken(),
        expiresAt: resp.getExpiresAt(),
      });
    });
  });
}

function getMyAccount(sessionToken) {
  return new Promise((resolve, reject) => {
    const req = new GetMyAccountRequest();
    const metadata = { authorization: `Bearer ${sessionToken}` };
    usersClient.getMyAccount(req, metadata, (err, resp) => {
      if (err) {
        reject(err);
        return;
      }
      resolve({
        userName: resp.getUserName(),
        initialized: resp.getInitialized(),
        accessKey: resp.getAccessKey(),
      });
    });
  });
}

async function run() {
  try {
    const login = await loginV2();
    console.log("LoginV2 session:", login.sessionToken);

    const account = await getMyAccount(login.sessionToken);
    console.log("my account:", account);
  } catch (err) {
    console.error("grpc-web call failed:", err.message || err);
  }
}

run();
