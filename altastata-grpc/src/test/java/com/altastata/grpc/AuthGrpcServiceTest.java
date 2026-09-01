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

import com.altastata.api.AccountId;
import com.altastata.api.AccountRegistry;
import com.altastata.api.AltaStataFileSystem;
import com.altastata.grpc.proto.LoginV2Request;
import com.altastata.grpc.proto.LoginV2Response;
import com.altastata.grpc.proto.LoginV2Upload;
import com.altastata.grpc.proto.LogoutRequest;
import com.altastata.grpc.proto.LogoutResponse;
import com.altastata.grpc.proto.RefreshRequest;
import com.altastata.grpc.proto.RefreshResponse;
import com.google.protobuf.ByteString;
import io.grpc.Context;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Unit tests for {@link AuthGrpcService} ({@code LoginV2}, logout, refresh). */
class AuthGrpcServiceTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    @BeforeEach
    void setUp() {
        AccountRegistry.clearForTesting();
    }

    @AfterEach
    void tearDown() {
        AccountRegistry.clearForTesting();
    }

    private static AccountId idFor(String userName) {
        return new AccountId(
                "altastata-test-" + userName + "-",
                userName,
                "amazon-s3-secure");
    }

    private static String propsFor(String userName) {
        AccountId id = idFor(userName);
        return "acccontainer-prefix=" + id.getContainerPrefix() + "\n"
             + "myuser=" + id.getMyUser() + "\n"
             + "accounttype=" + id.getAccountType() + "\n";
    }

    private static GrpcUserRegistry registryForLoginV2Upload(String userName, AltaStataFileSystem fileSystem) {
        if (fileSystem != null) {
            AccountId id = idFor(userName);
            when(fileSystem.getAccountId()).thenReturn(id);
            AccountRegistry.putForTesting(id, fileSystem);
        }
        return new GrpcUserRegistry(un -> null, (pem, pwd) -> { });
    }

    private static SessionRegistry sessions(Instant fixedNow) {
        return new SessionRegistry(Duration.ofHours(8),
                Clock.fixed(fixedNow, ZoneOffset.UTC), false);
    }

    private static EventBus bus(Instant fixedNow) {
        return new EventBus(EventBus.DEFAULT_RING_SIZE,
                Clock.fixed(fixedNow, ZoneOffset.UTC));
    }

    private static LoginV2Request uploadLogin(String userName, String password, String clientHint) {
        return LoginV2Request.newBuilder()
                .setPassword(password)
                .setClientHint(clientHint)
                .setUpload(LoginV2Upload.newBuilder()
                        .setUserProperties(propsFor(userName))
                        .putAccountFiles("private.key", ByteString.copyFromUtf8("encrypted-pem"))
                        .build())
                .build();
    }

    private static final class Capture<T> implements StreamObserver<T> {
        T value;
        Throwable error;
        boolean completed;

        @Override
        public void onNext(T v) {
            value = v;
        }

        @Override
        public void onError(Throwable t) {
            error = t;
        }

        @Override
        public void onCompleted() {
            completed = true;
        }

        Status statusCode() {
            return ((StatusRuntimeException) error).getStatus();
        }
    }

    @Test
    void loginV2UploadIssuesSessionTokenWithoutAccessKey() {
        AltaStataFileSystem live = mock(AltaStataFileSystem.class);
        GrpcUserRegistry users = registryForLoginV2Upload("bob", live);
        SessionRegistry sessions = sessions(T0);
        AuthGrpcService svc = new AuthGrpcService(users, sessions, bus(T0));

        Capture<LoginV2Response> obs = new Capture<>();
        svc.loginV2(uploadLogin("bob", "correct", "Chrome"), obs);

        Assertions.assertNull(obs.error);
        Assertions.assertTrue(obs.completed);
        Assertions.assertTrue(obs.value.getSessionToken().startsWith("sess-"));
        Assertions.assertEquals(T0.plus(Duration.ofHours(8)).getEpochSecond(),
                obs.value.getExpiresAt().getSeconds());
        Assertions.assertEquals(1, sessions.size());
        Assertions.assertSame(live, users.getByAccountKey(idFor("bob").key()).getAltaStataFileSystem());
    }

    @Test
    void loginV2RejectsMissingAccountSourceAsInvalidArgument() {
        AuthGrpcService svc = new AuthGrpcService(
                new GrpcUserRegistry(), sessions(T0), bus(T0));

        Capture<LoginV2Response> obs = new Capture<>();
        svc.loginV2(LoginV2Request.newBuilder().setPassword("p").build(), obs);

        Assertions.assertEquals(Status.Code.INVALID_ARGUMENT, obs.statusCode().getCode());
        Assertions.assertEquals(0, sessions(T0).size());
    }

    @Test
    void loginV2UploadWithWrongPasswordReturnsUnauthenticatedAndIssuesNoSession() {
        AltaStataFileSystem probe = mock(AltaStataFileSystem.class);
        doThrow(new RuntimeException("invalid password")).when(probe).setPassword(anyString());
        GrpcUserRegistry users = registryForLoginV2Upload("bob", probe);
        SessionRegistry sessions = sessions(T0);
        AuthGrpcService svc = new AuthGrpcService(users, sessions, bus(T0));

        Capture<LoginV2Response> obs = new Capture<>();
        svc.loginV2(uploadLogin("bob", "wrong", ""), obs);

        Assertions.assertEquals(Status.Code.UNAUTHENTICATED, obs.statusCode().getCode());
        Assertions.assertEquals("Invalid credentials", obs.statusCode().getDescription());
        Assertions.assertEquals(0, sessions.size());
    }

    @Test
    void loginV2DirectoryIssuesSessionToken(@TempDir Path tempDir) throws Exception {
        Path accountsRoot = tempDir.resolve("accounts");
        Path accountDir = accountsRoot.resolve("amazon.rsa.bob123");
        Files.createDirectories(accountDir);
        Files.writeString(accountDir.resolve("bob.user.properties"), propsFor("bob"));
        Files.writeString(accountDir.resolve("private.key"), "encrypted-pem");

        AltaStataFileSystem live = mock(AltaStataFileSystem.class);
        AccountId id = idFor("bob");
        when(live.getAccountId()).thenReturn(id);
        AccountRegistry.putForTesting(id, live);

        LoginV2Support loginV2Support = new LoginV2Support(
                new LoginV2DirectoryPolicy(accountsRoot, true));
        GrpcUserRegistry users = new GrpcUserRegistry(un -> null, (pem, pwd) -> { });
        SessionRegistry sessions = sessions(T0);
        AuthGrpcService svc = new AuthGrpcService(users, sessions, bus(T0), loginV2Support);

        Capture<LoginV2Response> obs = new Capture<>();
        svc.loginV2(LoginV2Request.newBuilder()
                .setPassword("correct")
                .setUserAccountDirectory(accountDir.toString())
                .build(), obs);

        Assertions.assertNull(obs.error);
        Assertions.assertTrue(obs.completed);
        Assertions.assertTrue(obs.value.getSessionToken().startsWith("sess-"));
        Assertions.assertEquals(1, sessions.size());
        Assertions.assertSame(live, users.getByAccountKey(idFor("bob").key()).getAltaStataFileSystem());
    }

    @Test
    void loginV2DirectoryRejectsPathOutsideAccountsRoot(@TempDir Path tempDir) throws Exception {
        Path accountsRoot = tempDir.resolve("accounts");
        Files.createDirectories(accountsRoot);
        Path outsider = tempDir.resolve("outside");
        Files.createDirectories(outsider);

        LoginV2Support loginV2Support = new LoginV2Support(
                new LoginV2DirectoryPolicy(accountsRoot, true));
        AuthGrpcService svc = new AuthGrpcService(
                new GrpcUserRegistry(), sessions(T0), bus(T0), loginV2Support);

        Capture<LoginV2Response> obs = new Capture<>();
        svc.loginV2(LoginV2Request.newBuilder()
                .setPassword("p")
                .setUserAccountDirectory(outsider.toString())
                .build(), obs);

        Assertions.assertEquals(Status.Code.PERMISSION_DENIED, obs.statusCode().getCode());
        Assertions.assertEquals(0, sessions(T0).size());
    }

    @Test
    void logoutInvalidatesOnlyTheCallingSession() {
        AltaStataFileSystem live = mock(AltaStataFileSystem.class);
        GrpcUserRegistry users = registryForLoginV2Upload("bob", live);
        SessionRegistry sessions = sessions(T0);
        AuthGrpcService svc = new AuthGrpcService(users, sessions, bus(T0));

        Capture<LoginV2Response> a = new Capture<>();
        svc.loginV2(uploadLogin("bob", "p", "tab-a"), a);
        Session sessionA = sessions.resolve(a.value.getSessionToken()).get();

        Capture<LoginV2Response> b = new Capture<>();
        svc.loginV2(uploadLogin("bob", "p", "tab-b"), b);
        Session sessionB = sessions.resolve(b.value.getSessionToken()).get();

        Capture<LogoutResponse> logoutA = new Capture<>();
        Context.current()
                .withValue(GrpcGatewayAuthContext.SESSION, sessionA)
                .run(() -> svc.logout(LogoutRequest.getDefaultInstance(), logoutA));

        Assertions.assertNull(logoutA.error);
        Assertions.assertTrue(logoutA.completed);
        Assertions.assertTrue(sessions.resolve(sessionA.token()).isEmpty());
        Assertions.assertTrue(sessions.resolve(sessionB.token()).isPresent());
    }

    @Test
    void logoutWithoutSessionContextIsIdempotentNoOp() {
        AuthGrpcService svc = new AuthGrpcService(
                registryForLoginV2Upload("bob", mock(AltaStataFileSystem.class)),
                sessions(T0), bus(T0));

        Capture<LogoutResponse> obs = new Capture<>();
        svc.logout(LogoutRequest.getDefaultInstance(), obs);

        Assertions.assertNull(obs.error);
        Assertions.assertTrue(obs.completed);
    }

    @Test
    void refreshReturnsCurrentExpiryFromContextSession() {
        AuthGrpcService svc = new AuthGrpcService(
                registryForLoginV2Upload("bob", mock(AltaStataFileSystem.class)),
                sessions(T0), bus(T0));
        Session s = new Session("sess-test", "bob", "", T0, T0.plus(Duration.ofHours(2)));

        Capture<RefreshResponse> obs = new Capture<>();
        Context.current()
                .withValue(GrpcGatewayAuthContext.SESSION, s)
                .run(() -> svc.refresh(RefreshRequest.getDefaultInstance(), obs));

        Assertions.assertNull(obs.error);
        Assertions.assertEquals(s.expiresAt().getEpochSecond(), obs.value.getExpiresAt().getSeconds());
    }

    @Test
    void refreshWithoutSessionContextReturnsUnauthenticated() {
        AuthGrpcService svc = new AuthGrpcService(
                registryForLoginV2Upload("bob", mock(AltaStataFileSystem.class)),
                sessions(T0), bus(T0));

        Capture<RefreshResponse> obs = new Capture<>();
        svc.refresh(RefreshRequest.getDefaultInstance(), obs);

        Assertions.assertEquals(Status.Code.UNAUTHENTICATED, obs.statusCode().getCode());
    }

    @Test
    void loginV2EvictsPriorSessionForSameClientHint() {
        AltaStataFileSystem live = mock(AltaStataFileSystem.class);
        GrpcUserRegistry users = registryForLoginV2Upload("bob", live);
        SessionRegistry sessions = sessions(T0);
        EventBus events = bus(T0);
        AuthGrpcService svc = new AuthGrpcService(users, sessions, events);

        Capture<LoginV2Response> first = new Capture<>();
        svc.loginV2(uploadLogin("bob", "p", "same-tab"), first);
        String firstToken = first.value.getSessionToken();

        Capture<LoginV2Response> second = new Capture<>();
        svc.loginV2(uploadLogin("bob", "p", "same-tab"), second);

        Assertions.assertTrue(sessions.resolve(firstToken).isEmpty(),
                "second LoginV2 with same clientHint must evict the first session");
        Assertions.assertTrue(sessions.resolve(second.value.getSessionToken()).isPresent());
    }

    @Test
    void loginV2DifferentClientHintsCoexistForSameUser() {
        AltaStataFileSystem live = mock(AltaStataFileSystem.class);
        GrpcUserRegistry users = registryForLoginV2Upload("bob", live);
        SessionRegistry sessions = sessions(T0);
        AuthGrpcService svc = new AuthGrpcService(users, sessions, bus(T0));

        Capture<LoginV2Response> ui = new Capture<>();
        svc.loginV2(uploadLogin("bob", "p", "altastata-console-web"), ui);
        String uiToken = ui.value.getSessionToken();

        Capture<LoginV2Response> py = new Capture<>();
        svc.loginV2(uploadLogin("bob", "p", "altastata-python-package"), py);
        String pyToken = py.value.getSessionToken();

        Assertions.assertTrue(sessions.resolve(uiToken).isPresent());
        Assertions.assertTrue(sessions.resolve(pyToken).isPresent());
        Assertions.assertEquals(2, sessions.size());
    }

    @Test
    void logoutRevokesIssuedS3Credentials() {
        AltaStataFileSystem live = mock(AltaStataFileSystem.class);
        GrpcUserRegistry users = registryForLoginV2Upload("bob", live);
        SessionRegistry sessions = sessions(T0);
        S3CredentialsRegistry credentials = new S3CredentialsRegistry(sessions, Clock.fixed(T0, ZoneOffset.UTC));
        AuthGrpcService svc = new AuthGrpcService(users, sessions, bus(T0), credentials, new LoginV2Support());

        Capture<LoginV2Response> login = new Capture<>();
        svc.loginV2(uploadLogin("bob", "correct", ""), login);
        String token = login.value.getSessionToken();

        S3CredentialsRegistry.IssuedCredential issued = credentials.issue(
                token, "bob", live, "etl");
        Assertions.assertTrue(credentials.resolveForS3(issued.accessKeyId()).isPresent());

        Capture<LogoutResponse> logout = new Capture<>();
        Context.current()
                .withValue(GrpcGatewayAuthContext.SESSION, sessions.resolve(token).orElseThrow())
                .run(() -> svc.logout(LogoutRequest.getDefaultInstance(), logout));

        Assertions.assertNull(logout.error);
        Assertions.assertTrue(credentials.resolveForS3(issued.accessKeyId()).isEmpty());
    }

    @Test
    void loginLogoutLoginAgainIssuesFreshSessionAndKillsPriorToken() {
        AltaStataFileSystem live = mock(AltaStataFileSystem.class);
        GrpcUserRegistry users = registryForLoginV2Upload("bob", live);
        SessionRegistry sessions = sessions(T0);
        AuthGrpcService svc = new AuthGrpcService(users, sessions, bus(T0));

        Capture<LoginV2Response> first = new Capture<>();
        svc.loginV2(uploadLogin("bob", "p", "python-kernel"), first);
        String firstToken = first.value.getSessionToken();
        Assertions.assertTrue(sessions.resolve(firstToken).isPresent());

        Capture<LogoutResponse> logout = new Capture<>();
        Context.current()
                .withValue(GrpcGatewayAuthContext.SESSION, sessions.resolve(firstToken).orElseThrow())
                .run(() -> svc.logout(LogoutRequest.getDefaultInstance(), logout));
        Assertions.assertNull(logout.error);
        Assertions.assertTrue(sessions.resolve(firstToken).isEmpty());

        Capture<LoginV2Response> second = new Capture<>();
        svc.loginV2(uploadLogin("bob", "p", "python-kernel"), second);
        String secondToken = second.value.getSessionToken();

        Assertions.assertNull(second.error);
        Assertions.assertNotEquals(firstToken, secondToken);
        Assertions.assertTrue(sessions.resolve(firstToken).isEmpty(),
                "logged-out token must stay dead after a later LoginV2");
        Assertions.assertTrue(sessions.resolve(secondToken).isPresent());
        Assertions.assertEquals(1, sessions.size());
        Assertions.assertSame(live, users.getByAccountKey(idFor("bob").key()).getAltaStataFileSystem(),
                "re-login must keep the same live filesystem for the account");
    }

    @Test
    void loginV2TwoUsersHaveIsolatedSessionsAndFilesystems() {
        AltaStataFileSystem bobFs = mock(AltaStataFileSystem.class);
        AltaStataFileSystem aliceFs = mock(AltaStataFileSystem.class);
        when(bobFs.getAccountId()).thenReturn(idFor("bob"));
        when(aliceFs.getAccountId()).thenReturn(idFor("alice"));
        AccountRegistry.putForTesting(idFor("bob"), bobFs);
        AccountRegistry.putForTesting(idFor("alice"), aliceFs);

        GrpcUserRegistry users = new GrpcUserRegistry(un -> null, (pem, pwd) -> { });
        SessionRegistry sessions = sessions(T0);
        AuthGrpcService svc = new AuthGrpcService(users, sessions, bus(T0));

        Capture<LoginV2Response> bobLogin = new Capture<>();
        svc.loginV2(uploadLogin("bob", "p", "cli"), bobLogin);
        Capture<LoginV2Response> aliceLogin = new Capture<>();
        svc.loginV2(uploadLogin("alice", "p", "cli"), aliceLogin);

        Assertions.assertNull(bobLogin.error);
        Assertions.assertNull(aliceLogin.error);
        String bobToken = bobLogin.value.getSessionToken();
        String aliceToken = aliceLogin.value.getSessionToken();

        Assertions.assertNotEquals(bobToken, aliceToken);
        Assertions.assertEquals(2, sessions.size());
        Assertions.assertEquals(idFor("bob").key(), sessions.resolve(bobToken).get().accountKey());
        Assertions.assertEquals(idFor("alice").key(), sessions.resolve(aliceToken).get().accountKey());
        Assertions.assertSame(bobFs, users.getByAccountKey(idFor("bob").key()).getAltaStataFileSystem());
        Assertions.assertSame(aliceFs, users.getByAccountKey(idFor("alice").key()).getAltaStataFileSystem());

        Capture<LogoutResponse> logoutBob = new Capture<>();
        Context.current()
                .withValue(GrpcGatewayAuthContext.SESSION, sessions.resolve(bobToken).orElseThrow())
                .run(() -> svc.logout(LogoutRequest.getDefaultInstance(), logoutBob));

        Assertions.assertTrue(sessions.resolve(bobToken).isEmpty());
        Assertions.assertTrue(sessions.resolve(aliceToken).isPresent(),
                "logging out bob must not touch alice's session");
        Assertions.assertSame(aliceFs, users.getByAccountKey(idFor("alice").key()).getAltaStataFileSystem());
    }

    @Test
    void loginV2ParallelDistinctClientHintsAllSucceed() throws Exception {
        AltaStataFileSystem live = mock(AltaStataFileSystem.class);
        GrpcUserRegistry users = registryForLoginV2Upload("bob", live);
        SessionRegistry sessions = sessions(T0);
        AuthGrpcService svc = new AuthGrpcService(users, sessions, bus(T0));

        final int n = 8;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(n);
        ConcurrentHashMap<Integer, String> tokens = new ConcurrentHashMap<>();
        AtomicInteger failures = new AtomicInteger();

        try {
            for (int i = 0; i < n; i++) {
                final int idx = i;
                pool.submit(() -> {
                    try {
                        start.await(5, TimeUnit.SECONDS);
                        Capture<LoginV2Response> obs = new Capture<>();
                        svc.loginV2(uploadLogin("bob", "p", "client-" + idx), obs);
                        if (obs.error != null || obs.value == null
                                || obs.value.getSessionToken().isEmpty()) {
                            failures.incrementAndGet();
                        } else {
                            tokens.put(idx, obs.value.getSessionToken());
                        }
                    } catch (Exception e) {
                        failures.incrementAndGet();
                    } finally {
                        done.countDown();
                    }
                });
            }

            start.countDown();
            Assertions.assertTrue(done.await(10, TimeUnit.SECONDS), "parallel LoginV2 timed out");
        } finally {
            pool.shutdownNow();
        }

        Assertions.assertEquals(0, failures.get(), "all parallel LoginV2 calls must succeed");
        Assertions.assertEquals(n, tokens.size());
        Set<String> unique = new HashSet<>(tokens.values());
        Assertions.assertEquals(n, unique.size(), "each LoginV2 must mint a distinct session token");
        Assertions.assertEquals(n, sessions.size());
        for (String token : unique) {
            Assertions.assertTrue(sessions.resolve(token).isPresent());
        }
        Assertions.assertSame(live, users.getByAccountKey(idFor("bob").key()).getAltaStataFileSystem(),
                "parallel logins must share one live filesystem per account");
    }
}
