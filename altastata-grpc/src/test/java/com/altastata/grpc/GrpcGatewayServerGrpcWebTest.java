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

package com.altastata.grpc;

import com.altastata.grpc.proto.AccountSetupServiceGrpc;
import com.altastata.grpc.proto.GetSupportedAccountTypesRequest;
import com.altastata.grpc.proto.GetSupportedAccountTypesResponse;
import com.linecorp.armeria.server.Server;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Optional;

class GrpcGatewayServerGrpcWebTest {
    private Server server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop().join();
        }
    }

    @Test
    void corsPreflightAllowsGrpcWebHeaders() throws Exception {
        startTestServer();
        int port = server.activeLocalPort();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port
                        + "/altastata.v1.AccountSetupService/GetSupportedAccountTypes"))
                .header("Origin", "http://localhost:3000")
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "content-type,x-grpc-web,authorization")
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .build();

        HttpResponse<byte[]> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofByteArray());

        Assertions.assertTrue(response.statusCode() == 200 || response.statusCode() == 204);
        Optional<String> allowOrigin = response.headers().firstValue("access-control-allow-origin");
        Assertions.assertTrue(allowOrigin.isPresent(), "CORS allow-origin header is missing");
    }

    @Test
    void grpcWebBinaryRequestIsAccepted() throws Exception {
        startTestServer();
        int port = server.activeLocalPort();

        byte[] emptyMessageFrame = new byte[] {0, 0, 0, 0, 0};
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port
                        + "/altastata.v1.AccountSetupService/GetSupportedAccountTypes"))
                .header("Content-Type", "application/grpc-web+proto")
                .header("x-grpc-web", "1")
                .header("x-user-agent", "grpc-web-javascript/0.1")
                .POST(HttpRequest.BodyPublishers.ofByteArray(emptyMessageFrame))
                .build();

        HttpResponse<byte[]> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofByteArray());

        Assertions.assertEquals(200, response.statusCode());
        Optional<String> contentType = response.headers().firstValue("content-type");
        Assertions.assertTrue(contentType.isPresent() && contentType.get().contains("grpc-web"));
    }

    private void startTestServer() {
        ServerInterceptor passThrough = new ServerInterceptor() {
            @Override
            public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
                    ServerCall<ReqT, RespT> call,
                    Metadata headers,
                    ServerCallHandler<ReqT, RespT> next
            ) {
                return next.startCall(call, headers);
            }
        };

        AccountSetupServiceGrpc.AccountSetupServiceImplBase accountSetup =
                new AccountSetupServiceGrpc.AccountSetupServiceImplBase() {
                    @Override
                    public void getSupportedAccountTypes(
                            GetSupportedAccountTypesRequest request,
                            StreamObserver<GetSupportedAccountTypesResponse> responseObserver) {
                        responseObserver.onNext(GetSupportedAccountTypesResponse.getDefaultInstance());
                        responseObserver.onCompleted();
                    }
                };

        server = Server.builder()
                .http(0)
                .service(GrpcGatewayServer.buildGrpcService(passThrough, accountSetup))
                .decorator(GrpcGatewayServer.buildGrpcWebCorsDecorator())
                .build();
        server.start().join();
    }
}
