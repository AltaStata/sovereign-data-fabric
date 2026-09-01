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

import io.grpc.ServerInterceptors;
import io.grpc.ServerInterceptor;
import io.grpc.BindableService;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.cors.CorsService;
import com.linecorp.armeria.server.file.HttpFile;
import com.linecorp.armeria.server.grpc.GrpcService;
import com.linecorp.armeria.common.grpc.GrpcSerializationFormats;
import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.annotation.Value;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Gated by {@code altastata.services.grpc.enabled} (default {@code true}) so the
 * unified {@code altastata-services} JVM can host an S3-only deployment that
 * leaves Armeria (and the gRPC gateway) entirely unstarted. The
 * property is consulted at context startup; flipping it at runtime is not
 * supported.
 */
@Context
@Singleton
@Requires(property = "altastata.services.grpc.enabled", value = "true", defaultValue = "true")
public class GrpcGatewayServer {
    private static final Logger logger = LoggerFactory.getLogger(GrpcGatewayServer.class);

    private final Server server;

    public GrpcGatewayServer(
            @Value("${grpcgateway.port:9877}") int port,
            @Value("${grpcgateway.bind-address:127.0.0.1}") String bindAddressFromConfig,
            @Value("${grpcgateway.max-request-length-bytes:134217728}") long maxRequestLengthBytes,
            @Value("${grpcgateway.web-ui-dir:}") String webUiDirFromConfig,
            GrpcGatewayAuthInterceptor authInterceptor,
            AuthGrpcService authGrpcService,
            AccountSetupGrpcService accountSetupGrpcService,
            S3CredentialsGrpcService s3CredentialsGrpcService,
            UsersGrpcService usersGrpcService,
            SharingGrpcService sharingGrpcService,
            AttributesGrpcService attributesGrpcService,
            FileOpsGrpcService fileOpsGrpcService,
            EventsGrpcService eventsGrpcService
    ) {
        GrpcService grpcService = buildGrpcService(
                authInterceptor,
                authGrpcService,
                accountSetupGrpcService,
                s3CredentialsGrpcService,
                usersGrpcService,
                sharingGrpcService,
                attributesGrpcService,
                fileOpsGrpcService,
                eventsGrpcService
        );
        InetSocketAddress bindAddr = resolveBindAddress(bindAddressFromConfig, port);
        if (!bindAddr.getAddress().isLoopbackAddress()) {
            // Operator opted into network reachability. We don't try to wrap that
            // in self-signed TLS in-process — Phase A attempted that and it does
            // not work in Chrome for gRPC-Web POST subresources (the "Advanced
            // -> Proceed" cert override extends to top-frame and GET subresources
            // only). Production deployments must front the gateway with a
            // TLS-terminating proxy that holds a real CA-issued cert. Logging
            // here so the operator notices a misconfigured setup.
            logger.warn("gRPC gateway bound to non-loopback address {}: traffic will be in cleartext. " +
                            "For network-reachable deployments, front the gateway with a TLS-terminating " +
                            "reverse-proxy (Caddy / nginx / OpenShift route / K8s Ingress with cert-manager). " +
                            "See TLS_DESIGN.md.",
                    bindAddr.getAddress().getHostAddress());
        }
        ServerBuilder serverBuilder = Server.builder()
                // Armeria HTTP request-length limit is separate from gRPC message-size limit.
                // Keep this above maxRequestMessageLength to avoid early RST_STREAM on medium payloads.
                .maxRequestLength(maxRequestLengthBytes)
                .service(grpcService)
                .decorator(buildGrpcWebCorsDecorator())
                .http(bindAddr);
        configureStaticUi(serverBuilder, resolveWebUiDir(webUiDirFromConfig));
        this.server = serverBuilder.build();
        this.server.start().join();
        logger.info("gRPC gateway started on http://{}:{}",
                bindAddr.getAddress().getHostAddress(), port);
    }

    /**
     * Resolve the configured bind address. Falls back to loopback on any
     * error so a typo never silently exposes the gateway on all interfaces.
     */
    static InetSocketAddress resolveBindAddress(String bindAddressFromConfig, int port) {
        String requested = (bindAddressFromConfig == null || bindAddressFromConfig.isBlank())
                ? "127.0.0.1"
                : bindAddressFromConfig.trim();
        try {
            return new InetSocketAddress(InetAddress.getByName(requested), port);
        } catch (UnknownHostException e) {
            logger.warn("Could not resolve grpcgateway.bind-address={}, falling back to 127.0.0.1",
                    requested, e);
            return new InetSocketAddress(InetAddress.getLoopbackAddress(), port);
        }
    }

    /**
     * Effective UI directory: explicit Micronaut config wins over the
     * {@code ALTASTATA_WEB_UI_DIR} environment variable. Reading the env var
     * directly avoids Micronaut's automatic property-name conversion, which
     * does not flow through nested {@code ${...:${...:}}} defaults reliably.
     */
    static String resolveWebUiDir(String webUiDirFromConfig) {
        if (webUiDirFromConfig != null && !webUiDirFromConfig.isBlank()) {
            return webUiDirFromConfig;
        }
        String fromEnv = System.getenv("ALTASTATA_WEB_UI_DIR");
        return fromEnv == null ? "" : fromEnv;
    }

    /**
     * Builds and configures the Armeria GrpcService, wiring the authentication interceptor around every registered gRPC service.
     *
     * @param authInterceptor the ServerInterceptor for auth validation
     * @param services list of bindable services to publish
     * @return constructed GrpcService instance
     */
    static GrpcService buildGrpcService(ServerInterceptor authInterceptor, BindableService... services) {
        var builder = GrpcService.builder()
                .maxRequestMessageLength(64 * 1024 * 1024)
                // Enable gRPC-Web (binary and base64 text) for browser clients.
                .supportedSerializationFormats(
                        GrpcSerializationFormats.PROTO,
                        GrpcSerializationFormats.PROTO_WEB,
                        GrpcSerializationFormats.PROTO_WEB_TEXT
                );

        for (BindableService service : services) {
            builder.addService(ServerInterceptors.intercept(service, authInterceptor));
        }
        return builder.build();
    }

    /**
      * Builds the CORS decorator for grpc-web requests.
      * @return the CORS decorator function
      */
    static java.util.function.Function<? super com.linecorp.armeria.server.HttpService, ? extends com.linecorp.armeria.server.HttpService> buildGrpcWebCorsDecorator() {
        return CorsService.builderForAnyOrigin()
                .allowRequestMethods(HttpMethod.POST, HttpMethod.OPTIONS)
                .allowRequestHeaders(
                        HttpHeaderNames.CONTENT_TYPE.toString(),
                        "x-grpc-web",
                        "x-user-agent",
                        "grpc-timeout",
                        "authorization",
                        "x-accept-response-streaming",
                        "x-grpc-web-client-version"
                )
                .exposeHeaders(
                        "grpc-status",
                        "grpc-message",
                        "grpc-status-details-bin"
                )
                .newDecorator();
    }

    /**
     * Mount the AltaStata Console SPA on the same port as the gRPC gateway when a
     * UI directory is configured. The directory may be set via Micronaut config
     * {@code grpcgateway.web-ui-dir} or environment variable
     * {@code ALTASTATA_WEB_UI_DIR}; if neither is set or the directory is invalid,
     * static serving is silently skipped (gRPC continues to work as before).
     *
     * Routing: gRPC service paths (e.g. {@code /altastata.v1.UsersService/*}) are
     * registered explicitly and take priority over the static prefix mount, so
     * static files are only served for non-gRPC paths. Unknown paths fall back
     * to {@code index.html} to keep client-side SPA routing working.
     */
    static void configureStaticUi(ServerBuilder serverBuilder, String webUiDir) {
        if (webUiDir == null || webUiDir.isBlank()) {
            return;
        }
        Path uiRoot;
        try {
            uiRoot = Paths.get(webUiDir).toAbsolutePath().normalize();
        } catch (Exception e) {
            logger.warn("ALTASTATA_WEB_UI_DIR={} is invalid; skipping static UI", webUiDir, e);
            return;
        }
        if (!Files.isDirectory(uiRoot)) {
            logger.warn("ALTASTATA_WEB_UI_DIR={} is not a directory; skipping static UI", webUiDir);
            return;
        }
        Path indexFile = uiRoot.resolve("index.html");
        if (!Files.isRegularFile(indexFile)) {
            logger.warn("ALTASTATA_WEB_UI_DIR={} contains no index.html; skipping static UI", webUiDir);
            return;
        }

        Path uiRootFinal = uiRoot;
        HttpService spaService = (ctx, req) -> {
            HttpMethod method = req.method();
            if (method != HttpMethod.GET && method != HttpMethod.HEAD) {
                return HttpResponse.of(HttpStatus.METHOD_NOT_ALLOWED);
            }
            String requestPath = ctx.path();
            if (requestPath.startsWith("/")) {
                requestPath = requestPath.substring(1);
            }
            Path target = indexFile;
            if (!requestPath.isEmpty()) {
                Path candidate;
                try {
                    candidate = uiRootFinal.resolve(requestPath).normalize();
                } catch (Exception e) {
                    return HttpResponse.of(HttpStatus.BAD_REQUEST);
                }
                // Path traversal guard + SPA fallback: anything not under the UI
                // root or not a regular file is served as index.html so that
                // client-side routing (and `/foo/bar` deep refreshes) still work.
                if (candidate.startsWith(uiRootFinal) && Files.isRegularFile(candidate)) {
                    target = candidate;
                }
            }
            return HttpFile.of(target).asService().serve(ctx, req);
        };

        serverBuilder.serviceUnder("/", spaService);
        logger.info("Serving static UI from {} on the gRPC gateway port", uiRoot);
    }

    /**
     * Triggers the graceful shutdown of the Armeria gateway server, blocking until stop completes.
     */
    @PreDestroy
    public void shutdown() {
        logger.info("Shutting down gRPC gateway");
        server.stop().join();
    }
}
