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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link GrpcUserRegistry#installFromLoginV2}. They
 * lock in two contracts (see altastata-grpc/SESSION_AND_EVENTS_DESIGN.md
 * §6 / §7.3):
 * <ul>
 *   <li>A wrong password cannot corrupt the live
 *       {@link AltaStataFileSystem} of another session for the same user.</li>
 *   <li>A successful re-Login does not replace or duplicate the live
 *       {@link AltaStataFileSystem} but does refresh its in-memory
 *       password, so altastata-core's 15-minute password timeout cannot
 *       leave the live fs permanently dead.</li>
 * </ul>
 *
 * <p>Mocks are injected via {@link AccountRegistry#putForTesting}, with the
 * mock stubbed to return a matching {@link AccountId}.
 */
class GrpcUserRegistryTest {

    private static final AccountId BOB = new AccountId(
            "altastata-org-bob-", "bob", "amazon-s3-secure");

    private static final AccountId ALICE = new AccountId(
            "altastata-org-alice-", "alice", "amazon-s3-secure");

    private static final String PROPS_BOB =
            "acccontainer-prefix=altastata-org-bob-\n"
          + "myuser=bob\n"
          + "accounttype=amazon-s3-secure\n";

    /** HSM-style: still has the three identity fields, plus key-protection/metadata-encryption. */
    private static final String PROPS_ALICE_HSM =
            "acccontainer-prefix=altastata-org-alice-\n"
          + "myuser=alice\n"
          + "accounttype=amazon-s3-secure\n"
          + "metadata-encryption=HSM\n"
          + "key-protection=HPCS\n";

    /** Stub {@code getAccountId()} so {@code invalidate(fs)} can locate the entry, then install. */
    private static void prePopulate(AccountId id, AltaStataFileSystem mockFs) {
        when(mockFs.getAccountId()).thenReturn(id);
        AccountRegistry.putForTesting(id, mockFs);
    }

    @BeforeEach
    void setUp() {
        AccountRegistry.clearForTesting();
    }

    @AfterEach
    void tearDown() {
        AccountRegistry.clearForTesting();
    }

    @Test
    void firstLoginInstallsFileSystemAndAccessKey() {
        AltaStataFileSystem fs = mock(AltaStataFileSystem.class);
        prePopulate(BOB, fs);
        GrpcUserRegistry registry = new GrpcUserRegistry();
        GrpcUserData data = registry.installFromLoginV2("bob", PROPS_BOB, "key", "correct", () -> fs);

        Assertions.assertSame(fs, data.getAltaStataFileSystem(),
                "first successful login must install the cached fs into the registry");
        Assertions.assertNotNull(data.getAccessKey());
        Assertions.assertFalse(data.getAccessKey().isEmpty(), "accessKey must be allocated on first login");
        Assertions.assertNotNull(data.getSecretKey());
        Assertions.assertFalse(data.getSecretKey().isEmpty(), "secretKey must be allocated on first login");
        verify(fs, times(1)).setPassword("correct");
    }

    @Test
    void wrongPasswordDoesNotCorruptLiveFileSystem() {
        // Re-Login no longer creates a transient AltaStataFileSystem
        // ("probe") — that probe used to start a parasite SQS-poller
        // thread which silently consumed cloud SHARE/DELETE events with
        // no listener attached. Instead, the password is checked against
        // the encrypted PEM directly. We simulate the BC PEM-decryption
        // failure here with a PasswordValidator that throws on "wrong".
        AltaStataFileSystem live = mock(AltaStataFileSystem.class);
        prePopulate(BOB, live);
        GrpcUserRegistry.PasswordValidator validator = (pem, pwd) -> {
            if (new String(pwd).equals("wrong")) {
                throw new RuntimeException("invalid password");
            }
        };
        GrpcUserRegistry registry = new GrpcUserRegistry(userName -> null, validator);
        registry.installFromLoginV2("bob", PROPS_BOB, "key", "correct", () -> live);
        GrpcUserData data = registry.getByAccountKey("bob");
        Assertions.assertSame(live, data.getAltaStataFileSystem(),
                "preconditions: first login installed the live fs");

        // Second login with a wrong password (e.g. another browser tab, or a
        // malicious caller that knows the userName) must throw and must not
        // touch the live fs.
        Assertions.assertThrows(RuntimeException.class,
                () -> registry.installFromLoginV2("bob", PROPS_BOB, "key", "wrong", () -> live));

        Assertions.assertSame(live, data.getAltaStataFileSystem(),
                "wrong password from another caller must not replace the live fs");
        verify(live, times(1)).setPassword("correct");
        verify(live, never()).setPassword("wrong");
    }

    @Test
    void secondLoginWithCorrectPasswordRefreshesLivePasswordAndKeepsTheSameFileSystemInstance() {
        // Locks in the post-fix contract: a second successful login must
        // NOT replace the live fs (so its cache, connection pools, and
        // SQS-poller thread survive) and must NOT spawn a transient
        // AltaStataFileSystem ("probe") that would start a parasite
        // SQS-poller of its own. It MUST still push the validated
        // password into the live fs.setPassword(...). Otherwise re-Login
        // after altastata-core's 15-minute password timeout
        // (SecureCloudEventProcessor.run() clears Account.accountPassword
        // when accountPasswordNextExpiredTime elapses) is silently a no-op
        // and the live fs stays "dead" until the JVM restarts.
        AltaStataFileSystem live = mock(AltaStataFileSystem.class);
        prePopulate(BOB, live);
        GrpcUserRegistry registry = new GrpcUserRegistry();
        registry.installFromLoginV2("bob", PROPS_BOB, "key", "correct", () -> live);
        GrpcUserData data = registry.getByAccountKey("bob");
        AltaStataFileSystem before = data.getAltaStataFileSystem();
        Assertions.assertSame(live, before);

        registry.installFromLoginV2("bob", PROPS_BOB, "key", "correct", () -> live);

        Assertions.assertSame(before, data.getAltaStataFileSystem(),
                "second successful login must not replace the live fs (its cache and connection pools must survive)");
        // First call: install path (live.setPassword). Second call:
        // refresh path (live.setPassword again). The AccountRegistry
        // does not allow a second construction for the same AccountId,
        // so no extra fs ever appears.
        verify(live, times(2)).setPassword("correct");
    }

    @Test
    void reLoginAfterSimulatedPasswordExpiryRefreshesLiveFileSystem() {
        // Black-box reproduction of the altastata-core 15-minute password
        // timeout: SecureCloudEventProcessor.run() ends up calling
        // account.setPassword(null) on the live fs, after which every
        // storage operation throws "Password is null". The user's UI then
        // sends a fresh AuthService/LoginV2, which lands here. The contract
        // under test is: that re-login must
        // call setPassword(correct) on the SAME live fs instance, so the
        // live fs is revived without losing its cache.
        AltaStataFileSystem live = mock(AltaStataFileSystem.class);
        prePopulate(BOB, live);
        GrpcUserRegistry registry = new GrpcUserRegistry();
        registry.installFromLoginV2("bob", PROPS_BOB, "key", "correct", () -> live);

        Assertions.assertSame(live, registry.getByAccountKey("bob").getAltaStataFileSystem());
        verify(live, times(1)).setPassword("correct");

        // The user notices the failures, the frontend triggers LoginV2 again.
        registry.installFromLoginV2("bob", PROPS_BOB, "key", "correct", () -> live);

        Assertions.assertSame(live, registry.getByAccountKey("bob").getAltaStataFileSystem(),
                "the live fs instance must not change across the timeout-recovery re-login");
        verify(live, times(2)).setPassword("correct");
    }

    @Test
    void firstLoginWithWrongPasswordLeavesRegistryClean() {
        // The mocked fs is pre-populated; production code retrieves it
        // from the AccountRegistry and calls setPassword on it. The mock
        // is configured to throw, modelling a corrupt PEM / wrong
        // password. GrpcUserRegistry must (a) propagate the exception,
        // (b) NOT install the fs into the per-user GrpcUserData, and
        // (c) invalidate(fs) the AccountRegistry entry so the next
        // attempt gets a fresh fs (modelled here by asserting that the
        // registry no longer contains the BOB id).
        AltaStataFileSystem probe = mock(AltaStataFileSystem.class);
        doThrow(new RuntimeException("invalid password"))
                .when(probe).setPassword(anyString());
        prePopulate(BOB, probe);
        GrpcUserRegistry registry = new GrpcUserRegistry();

        Assertions.assertThrows(RuntimeException.class,
                () -> registry.installFromLoginV2("bob", PROPS_BOB, "key", "wrong", () -> probe));

        GrpcUserData data = registry.getByAccountKey("bob");
        Assertions.assertNull(data.getAltaStataFileSystem(),
                "failed first login must not install a corrupt fs");
        Assertions.assertNull(data.getAccessKey(),
                "failed first login must not allocate an accessKey");
        Assertions.assertNull(data.getSecretKey(),
                "failed first login must not allocate a secretKey");
        Assertions.assertNull(AccountRegistry.get(BOB),
                "failed first login must drop the fs from the AccountRegistry, so a retry can get a fresh one");
    }

    @Test
    void hsmBackedBootstrapSucceedsWithoutPrivateKeyAndEmptyPassword() {
        // HSM-backed accounts (e.g. key-protection=HPCS in user_properties)
        // never send a PEM and never need a password: AltaStataFunctions
        // .from_credentials(user_properties, "") works fine over Py4J and
        // must work the same way through gRPC LoginV2 bootstrap. No private
        // key PEM is uploaded; the password supplied to LoginV2 is the empty
        // string. Account.setPassword(emptyCharArray) then takes the HSM
        // branch (Account.scala:711-715) and the registry must NOT block
        // that flow with an extra "private key required" precondition.
        //
        // Note: real HSM user_properties still carry the three identity
        // fields (acccontainer-prefix, myuser, accounttype) — HSM only
        // affects key handling, not account identity. PROPS_ALICE_HSM
        // exercises that realistic shape.
        AltaStataFileSystem fs = mock(AltaStataFileSystem.class);
        prePopulate(ALICE, fs);
        GrpcUserRegistry registry = new GrpcUserRegistry();
        GrpcUserData data = registry.installFromLoginV2("alice", PROPS_ALICE_HSM, "", "", () -> fs);

        Assertions.assertSame(fs, data.getAltaStataFileSystem(),
                "HSM-backed bootstrap must install the fs returned by the factory");
        verify(fs, times(1)).setPassword("");
    }
}
