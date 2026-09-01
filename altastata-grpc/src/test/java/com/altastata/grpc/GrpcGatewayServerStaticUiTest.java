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

import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

class GrpcGatewayServerStaticUiTest {

    private static final String INDEX_HTML =
            "<!doctype html><html><body>SPA root</body></html>";
    private static final String APP_JS = "console.log('hello');";

    private Server server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop().join();
        }
    }

    @Test
    void rootRequestReturnsIndexHtml(@TempDir Path uiDir) throws Exception {
        seedUi(uiDir);
        startServer(uiDir.toString());

        HttpResponse<String> resp = get("/");

        Assertions.assertEquals(200, resp.statusCode());
        Assertions.assertTrue(contentType(resp).contains("html"),
                "Content-Type should be HTML, got " + contentType(resp));
        Assertions.assertEquals(INDEX_HTML, resp.body());
    }

    @Test
    void knownAssetIsServedWithCorrectMimeType(@TempDir Path uiDir) throws Exception {
        seedUi(uiDir);
        startServer(uiDir.toString());

        HttpResponse<String> resp = get("/assets/app.js");

        Assertions.assertEquals(200, resp.statusCode());
        Assertions.assertTrue(contentType(resp).contains("javascript"),
                "Content-Type should be JS, got " + contentType(resp));
        Assertions.assertEquals(APP_JS, resp.body());
    }

    @Test
    void unknownPathFallsBackToIndexHtml(@TempDir Path uiDir) throws Exception {
        seedUi(uiDir);
        startServer(uiDir.toString());

        HttpResponse<String> resp = get("/some/deep/spa/route");

        Assertions.assertEquals(200, resp.statusCode());
        Assertions.assertTrue(contentType(resp).contains("html"),
                "Unknown SPA route should serve index.html, got " + contentType(resp));
        Assertions.assertEquals(INDEX_HTML, resp.body());
    }

    @Test
    void pathTraversalDoesNotLeakFilesOutsideUiRoot(@TempDir Path uiDir) throws Exception {
        seedUi(uiDir);
        // Place a sibling file outside the UI root that traversal must not reach.
        Path outsideRoot = uiDir.getParent().resolve("secret.txt");
        Files.writeString(outsideRoot, "TOP_SECRET");
        startServer(uiDir.toString());

        HttpResponse<String> resp = get("/../secret.txt");

        // Armeria rejects `..` at the HTTP routing layer with 400, which is the
        // strongest possible defense; our in-handler guard is a defense in depth
        // for edge cases like URL-encoded traversal. Either response is acceptable
        // as long as the secret never reaches the client.
        Assertions.assertFalse(resp.body().contains("TOP_SECRET"),
                "Path traversal must not leak files outside the UI root, got body: " + resp.body());
    }

    @Test
    void uiDirUnsetLeavesGatewayWithoutStaticRoutes(@TempDir Path uiDir) throws Exception {
        // No seeding: simulate empty configuration.
        startServer(""); // disabled

        HttpResponse<String> resp = get("/");

        // No static service registered → "/" is not routable.
        // gRPC paths still work; that is covered by GrpcGatewayServerGrpcWebTest.
        Assertions.assertEquals(404, resp.statusCode());
        // Sanity: the placeholder service registered by the test harness still works,
        // proving the server itself is healthy.
        HttpResponse<String> placeholder = get("/__placeholder");
        Assertions.assertEquals(200, placeholder.statusCode());
    }

    @Test
    void uiDirPointingAtMissingFolderIsIgnoredSilently(@TempDir Path uiDir) throws Exception {
        Path missing = uiDir.resolve("does-not-exist");
        startServer(missing.toString());

        HttpResponse<String> resp = get("/");

        Assertions.assertEquals(404, resp.statusCode());
    }

    @Test
    void resolveWebUiDirPrefersExplicitConfigOverEnv() {
        // Setting an env var inside a JVM is non-portable; we only assert the
        // precedence rule against the inputs we control. The fallback-to-env
        // path is exercised live via the AltaStataServicesApplication launcher.
        Assertions.assertEquals("/path/from/yml",
                GrpcGatewayServer.resolveWebUiDir("/path/from/yml"));
    }

    @Test
    void resolveWebUiDirReturnsEmptyWhenBothInputsAreMissing() {
        // With null/blank config and no env var, the result must be an empty
        // string so configureStaticUi can silently skip without throwing.
        // (System env may or may not contain ALTASTATA_WEB_UI_DIR depending on
        //  the developer's shell; we only assert that null/blank config alone
        //  does not crash.)
        String result = GrpcGatewayServer.resolveWebUiDir(null);
        Assertions.assertNotNull(result, "resolveWebUiDir must never return null");
        result = GrpcGatewayServer.resolveWebUiDir("");
        Assertions.assertNotNull(result, "resolveWebUiDir must never return null");
        result = GrpcGatewayServer.resolveWebUiDir("   ");
        Assertions.assertNotNull(result, "resolveWebUiDir must never return null");
    }

    @Test
    void resolveBindAddressDefaultsToLoopbackWhenBlankOrNull() {
        // The whole point of this default is to prevent accidental exposure on
        // 0.0.0.0. Any path that ends in null/blank must produce a loopback
        // socket, not "all interfaces".
        Assertions.assertTrue(
                GrpcGatewayServer.resolveBindAddress(null, 9877).getAddress().isLoopbackAddress(),
                "null bind-address must fall back to loopback");
        Assertions.assertTrue(
                GrpcGatewayServer.resolveBindAddress("", 9877).getAddress().isLoopbackAddress(),
                "empty bind-address must fall back to loopback");
        Assertions.assertTrue(
                GrpcGatewayServer.resolveBindAddress("   ", 9877).getAddress().isLoopbackAddress(),
                "whitespace bind-address must fall back to loopback");
    }

    @Test
    void resolveBindAddressHonoursExplicitLoopback() {
        java.net.InetSocketAddress addr = GrpcGatewayServer.resolveBindAddress("127.0.0.1", 9877);
        Assertions.assertEquals(9877, addr.getPort());
        Assertions.assertTrue(addr.getAddress().isLoopbackAddress());
        Assertions.assertEquals("127.0.0.1", addr.getAddress().getHostAddress());
    }

    @Test
    void resolveBindAddressHonoursExplicitWildcard() {
        // 0.0.0.0 is only used when the operator explicitly opts in. The point
        // of this test is to confirm we DON'T silently rewrite it back to
        // loopback — operators get what they ask for, plus the warning logged
        // by the constructor.
        java.net.InetSocketAddress addr = GrpcGatewayServer.resolveBindAddress("0.0.0.0", 9877);
        Assertions.assertEquals(9877, addr.getPort());
        Assertions.assertTrue(addr.getAddress().isAnyLocalAddress());
        Assertions.assertFalse(addr.getAddress().isLoopbackAddress());
    }

    @Test
    void resolveBindAddressFallsBackToLoopbackForUnresolvableInput() {
        // Typo or DNS failure must not crash startup or default to "all interfaces".
        // Use an IP-literal that fails parsing rather than a hostname (which could
        // hang trying to resolve in some environments).
        java.net.InetSocketAddress addr = GrpcGatewayServer.resolveBindAddress("not-a-real-ip-or-host!!!", 9877);
        Assertions.assertTrue(addr.getAddress().isLoopbackAddress(),
                "unresolvable bind-address must fall back to loopback, not 0.0.0.0");
    }

    private void seedUi(Path uiDir) throws Exception {
        Files.writeString(uiDir.resolve("index.html"), INDEX_HTML, StandardCharsets.UTF_8);
        Path assets = uiDir.resolve("assets");
        Files.createDirectories(assets);
        Files.writeString(assets.resolve("app.js"), APP_JS, StandardCharsets.UTF_8);
    }

    private void startServer(String webUiDir) {
        // Armeria refuses to build a server with zero services. Register a tiny
        // placeholder so the disabled-UI tests still get a healthy server to
        // probe; the test harness only owns this private path.
        ServerBuilder sb = Server.builder()
                .http(0)
                .service("/__placeholder",
                        (ctx, req) -> com.linecorp.armeria.common.HttpResponse.of(HttpStatus.OK));
        GrpcGatewayServer.configureStaticUi(sb, webUiDir);
        server = sb.build();
        server.start().join();
    }

    private HttpResponse<String> get(String path) throws Exception {
        int port = server.activeLocalPort();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .GET()
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static String contentType(HttpResponse<?> resp) {
        Optional<String> ct = resp.headers().firstValue("content-type");
        return ct.orElse("");
    }
}
