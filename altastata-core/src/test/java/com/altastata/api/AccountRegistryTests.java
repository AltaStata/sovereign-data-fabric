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

package com.altastata.api;

import com.altastata.utils.Account;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * JUnit 4 tests for {@link AccountRegistry}.
 *
 * <p>{@link AccountRegistry} keeps one map per JVM, so {@code @Before} /
 * {@code @After} call {@link AccountRegistry#clearForTesting()} for
 * isolation. Most tests use the {@link TestFs} subclass (in this package
 * to reach the package-private constructor) so they can exercise the
 * registry map semantics without spinning up real cloud handlers.
 * {@link #getOrCreate_realUserProperties_buildsFsAndCachesIt} is the
 * single full-stack test that actually builds a real
 * {@link AltaStataFileSystem}.
 */
public class AccountRegistryTests {

    @Before
    public void setUp() {
        AccountRegistry.clearForTesting();
    }

    @After
    public void tearDown() {
        AccountRegistry.clearForTesting();
    }

    // ─── Fixtures ──────────────────────────────────────────────────────────

    /** Skips Account-init; returns a caller-controlled {@link AccountId}. */
    private static final class TestFs extends AltaStataFileSystem {
        private final AccountId id;
        TestFs(AccountId id) {
            super(new Account()); // bare Account; getAccountId() is overridden
            this.id = id;
        }
        @Override
        public AccountId getAccountId() { return id; }
    }

    private static AccountId idFor(String suffix) {
        return new AccountId(
                "altastata-org-" + suffix + "-",
                "user-" + suffix,
                "amazon-s3-secure");
    }

    /** User-properties text whose {@link AccountId} matches {@code id}. */
    private static String userPropertiesFor(AccountId id) {
        return "acccontainer-prefix=" + id.getContainerPrefix() + "\n"
             + "myuser=" + id.getMyUser() + "\n"
             + "accounttype=" + id.getAccountType() + "\n";
    }

    // ─── Basics ────────────────────────────────────────────────────────────

    @Test
    public void emptyRegistry_size_isZero() {
        assertEquals(0, AccountRegistry.size());
    }

    @Test
    public void registryIsNotInstantiable() throws Exception {
        java.lang.reflect.Constructor<AccountRegistry> ctor =
                AccountRegistry.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        try {
            ctor.newInstance();
            fail("AccountRegistry must not be instantiable, even reflectively");
        } catch (java.lang.reflect.InvocationTargetException expected) {
            Throwable cause = expected.getCause();
            assertNotNull("expected the private ctor to throw", cause);
            assertTrue("expected an AssertionError-style guard, got: " + cause,
                    cause instanceof AssertionError);
        }
    }

    @Test
    public void putForTesting_thenGetOrCreate_returnsSameInstance_andDoesNotConstructNewFs() {
        AccountId bob = idFor("bob");
        TestFs cached = new TestFs(bob);

        AltaStataFileSystem prior = AccountRegistry.putForTesting(bob, cached);
        assertNull("first putForTesting for an id must return null", prior);

        AltaStataFileSystem returned = AccountRegistry.getOrCreate(userPropertiesFor(bob), "encrypted-key");

        assertSame("getOrCreate must return the pre-populated mock", cached, returned);
        assertEquals(1, AccountRegistry.size());
    }

    @Test
    public void getOrCreate_repeatedCalls_sameAccountIdFromDifferentPropertiesText_returnsSameInstance() {
        AccountId alice = idFor("alice");
        TestFs cached = new TestFs(alice);
        AccountRegistry.putForTesting(alice, cached);

        String props1 = userPropertiesFor(alice) + "extra-irrelevant-field=1\n";
        String props2 = userPropertiesFor(alice) + "extra-irrelevant-field=2\n";

        AltaStataFileSystem a = AccountRegistry.getOrCreate(props1, "k1");
        AltaStataFileSystem b = AccountRegistry.getOrCreate(props2, "k2");

        assertSame("AccountId identity is the registry key, not the literal text", cached, a);
        assertSame(cached, b);
        assertEquals(1, AccountRegistry.size());
    }

    @Test
    public void getOrCreate_differentAccountIds_returnIndependentInstances() {
        AccountId bob = idFor("bob");
        AccountId alice = idFor("alice");
        TestFs bobFs = new TestFs(bob);
        TestFs aliceFs = new TestFs(alice);

        AccountRegistry.putForTesting(bob, bobFs);
        AccountRegistry.putForTesting(alice, aliceFs);

        assertSame(bobFs, AccountRegistry.getOrCreate(userPropertiesFor(bob), null));
        assertSame(aliceFs, AccountRegistry.getOrCreate(userPropertiesFor(alice), null));
        assertEquals(2, AccountRegistry.size());
    }

    @Test
    public void getOrCreate_nullUserProperties_throwsIllegalArgumentException() {
        try {
            AccountRegistry.getOrCreate(null, "k");
            fail("expected IllegalArgumentException for null userProperties");
        } catch (IllegalArgumentException expected) {
            // ok
        }
        assertEquals(0, AccountRegistry.size());
    }

    @Test
    public void getOrCreate_userPropertiesMissingIdentityField_throwsIllegalArgumentException() {
        String incomplete = "acccontainer-prefix=altastata-x-\nmyuser=alice\n"; // accounttype missing
        try {
            AccountRegistry.getOrCreate(incomplete, null);
            fail("expected IllegalArgumentException when accounttype is missing");
        } catch (IllegalArgumentException expected) {
            assertTrue("error message must name the missing field, got: " + expected.getMessage(),
                    expected.getMessage().contains("accounttype"));
        }
        assertEquals(0, AccountRegistry.size());
    }

    // ─── Invalidate ────────────────────────────────────────────────────────

    @Test
    public void invalidateByFs_removesEntry_whenCachedInstanceMatches() {
        AccountId bob = idFor("bob");
        TestFs cached = new TestFs(bob);
        AccountRegistry.putForTesting(bob, cached);

        boolean removed = AccountRegistry.invalidate(cached);

        assertTrue("invalidate(fs) must succeed when the registry holds fs", removed);
        assertEquals(0, AccountRegistry.size());
    }

    @Test
    public void invalidateByFs_doesNotEvict_whenADifferentInstanceIsCachedNow() {
        AccountId bob = idFor("bob");
        TestFs originallyPoisoned = new TestFs(bob);
        TestFs concurrentlyReplaced = new TestFs(bob);

        // Race simulation: caller `A` had `originallyPoisoned` and decided to
        // invalidate after its setPassword failed, but caller `B` already
        // built `concurrentlyReplaced` for the same id. invalidate(A) must
        // not throw away B's freshly-initialised fs.
        AccountRegistry.putForTesting(bob, concurrentlyReplaced);

        boolean removed = AccountRegistry.invalidate(originallyPoisoned);

        assertFalse(removed);
        assertSame(concurrentlyReplaced, AccountRegistry.getOrCreate(userPropertiesFor(bob), null));
        assertEquals(1, AccountRegistry.size());
    }

    @Test
    public void invalidateByFs_nullFs_returnsFalseAndIsNoop() {
        assertFalse(AccountRegistry.invalidate((AltaStataFileSystem) null));
        assertEquals(0, AccountRegistry.size());
    }

    @Test
    public void invalidateById_existingAccountId_removesAndReturnsCached() {
        AccountId bob = idFor("bob");
        TestFs cached = new TestFs(bob);
        AccountRegistry.putForTesting(bob, cached);

        AltaStataFileSystem removed = AccountRegistry.invalidate(bob);

        assertSame(cached, removed);
        assertEquals(0, AccountRegistry.size());
    }

    @Test
    public void invalidateById_unknownAccountId_returnsNull() {
        assertNull(AccountRegistry.invalidate(idFor("ghost")));
    }

    @Test
    public void invalidateById_nullId_returnsNull() {
        assertNull(AccountRegistry.invalidate((AccountId) null));
    }

    @Test
    public void getOrCreate_afterInvalidate_buildsFreshInstance() {
        AccountId bob = idFor("bob");
        TestFs first = new TestFs(bob);
        TestFs second = new TestFs(bob);

        AccountRegistry.putForTesting(bob, first);
        AccountRegistry.invalidate(first);

        // Simulate the "fresh build" by another putForTesting (in production
        // this is the real `new AltaStataFileSystem(...)` inside getOrCreate).
        AccountRegistry.putForTesting(bob, second);
        AltaStataFileSystem returned = AccountRegistry.getOrCreate(userPropertiesFor(bob), null);

        assertSame(second, returned);
        assertNotSame(first, returned);
        assertEquals(1, AccountRegistry.size());
    }

    // ─── putForTesting / clearForTesting ───────────────────────────────────

    @Test(expected = NullPointerException.class)
    public void putForTesting_nullId_throwsNpe() {
        AccountRegistry.putForTesting(null, new TestFs(idFor("bob")));
    }

    @Test(expected = NullPointerException.class)
    public void putForTesting_nullFs_throwsNpe() {
        AccountRegistry.putForTesting(idFor("bob"), null);
    }

    @Test
    public void putForTesting_secondCall_returnsAlreadyCachedInstance_andDoesNotReplace() {
        AccountId bob = idFor("bob");
        TestFs first = new TestFs(bob);
        TestFs second = new TestFs(bob);

        assertNull(AccountRegistry.putForTesting(bob, first));
        AltaStataFileSystem prior = AccountRegistry.putForTesting(bob, second);

        assertSame("second putForTesting must return the prior cached instance",
                first, prior);
        assertSame("first instance must still be the cached one (putIfAbsent semantics)",
                first, AccountRegistry.getOrCreate(userPropertiesFor(bob), null));
        assertEquals(1, AccountRegistry.size());
    }

    @Test
    public void clearForTesting_emptiesTheRegistry() {
        AccountId bob = idFor("bob");
        AccountId alice = idFor("alice");
        AccountRegistry.putForTesting(bob, new TestFs(bob));
        AccountRegistry.putForTesting(alice, new TestFs(alice));
        assertEquals(2, AccountRegistry.size());

        AccountRegistry.clearForTesting();

        assertEquals(0, AccountRegistry.size());
        assertNull(AccountRegistry.get(bob));
        assertNull(AccountRegistry.get(alice));
    }

    // ─── End-to-end: real AltaStataFileSystem construction ─────────────────

    /**
     * Single full-stack happy path: a brand-new id triggers real
     * {@link AltaStataFileSystem} construction inside the registry; a
     * second call with the same id returns the same instance.
     *
     * <p>Uses {@code accounttype=localfs-secure} so {@code Account} does not
     * try to talk to a real cloud or start the SQS polling thread under
     * test (the SQS thread is also guarded by {@code !MY_USER == "admin"};
     * we pin {@code myuser=admin} as extra insurance against thread leaks).
     */
    @Test
    public void getOrCreate_realUserProperties_buildsFsAndCachesIt() {
        String props = "acccontainer-prefix=altastata-test-orgrsa-\n"
                     + "myuser=admin\n"
                     + "accounttype=localfs-secure\n"
                     + "sqs-interval=0\n";

        AltaStataFileSystem first = AccountRegistry.getOrCreate(props, null);
        assertNotNull(first);
        AccountId id = first.getAccountId();
        assertEquals("altastata-test-orgrsa-", id.getContainerPrefix());
        assertEquals("admin", id.getMyUser());
        assertEquals("localfs-secure", id.getAccountType());
        assertEquals(1, AccountRegistry.size());

        AltaStataFileSystem second = AccountRegistry.getOrCreate(props, null);
        assertSame("second getOrCreate with the same identity must be deduplicated",
                first, second);
        assertEquals(1, AccountRegistry.size());
    }

    @Test
    public void getOrCreateFromUpload_nullUserProperties_throwsIllegalArgumentException() {
        try {
            AccountRegistry.getOrCreateFromUpload(null, java.util.Collections.<String, byte[]>emptyMap());
            fail("expected IllegalArgumentException for null userProperties");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    @Test
    public void getOrCreateFromUpload_rsaMissingPrivateKey_throwsIllegalArgumentException() {
        String props = userPropertiesFor(idFor("rsa")) + "metadata-encryption=RSA\n";
        try {
            AccountRegistry.getOrCreateFromUpload(props, java.util.Collections.<String, byte[]>emptyMap());
            fail("expected IllegalArgumentException when private.key is missing");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("private.key"));
        }
    }

    @Test
    public void getOrCreateFromUpload_reusesPrepopulatedInstance() {
        AccountId bob = idFor("upload");
        TestFs cached = new TestFs(bob);
        AccountRegistry.putForTesting(bob, cached);

        String props = userPropertiesFor(bob) + "metadata-encryption=RSA\n";
        java.util.Map<String, byte[]> files = new java.util.HashMap<String, byte[]>();
        files.put("private.key", "pem".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        AltaStataFileSystem returned = AccountRegistry.getOrCreateFromUpload(props, files);

        assertSame(cached, returned);
        assertEquals(1, AccountRegistry.size());
    }

    @Test
    public void getOrCreateFromUpload_rejectsPathTraversalInAccountFilesKey() {
        String props = userPropertiesFor(idFor("pqc")) + "metadata-encryption=PQC\n";
        java.util.Map<String, byte[]> files = new java.util.HashMap<String, byte[]>();
        files.put("../kyber_private.key", "pem".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        try {
            AccountRegistry.getOrCreateFromUpload(props, files);
            fail("expected IllegalArgumentException for path traversal in account_files key");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("basename"));
        }
    }

    @Test
    public void materializeUploadDirectory_writesLicenseAndOrgCaForRsa() throws Exception {
        String props = userPropertiesFor(idFor("licensed")) + "metadata-encryption=RSA\n";
        java.util.Map<String, byte[]> files = new java.util.HashMap<String, byte[]>();
        files.put("private.key", "-----BEGIN PRIVATE KEY-----\nX\n-----END PRIVATE KEY-----"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        files.put("license.jwt", "jwt.payload.sig".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        files.put("org-ca.pem", "-----BEGIN PUBLIC KEY-----\nY\n-----END PUBLIC KEY-----"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));

        java.nio.file.Path dir = AccountRegistry.materializeUploadDirectory(props, files);
        try {
            assertTrue(java.nio.file.Files.isRegularFile(dir.resolve("account.user.properties")));
            assertTrue(java.nio.file.Files.isRegularFile(dir.resolve("private.key")));
            assertEquals("jwt.payload.sig",
                    new String(java.nio.file.Files.readAllBytes(dir.resolve("license.jwt")),
                            java.nio.charset.StandardCharsets.UTF_8));
            assertTrue(java.nio.file.Files.isRegularFile(dir.resolve("org-ca.pem")));
        } finally {
            java.nio.file.Files.walk(dir)
                    .sorted(java.util.Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            java.nio.file.Files.deleteIfExists(path);
                        } catch (java.io.IOException ignored) {
                        }
                    });
        }
    }

    // ─── Concurrency ───────────────────────────────────────────────────────

    /**
     * Many threads race for the same {@link AccountId} on a registry that
     * has a stub already pre-populated. The contract is: every caller
     * observes the same {@link AltaStataFileSystem} instance.
     */
    @Test
    public void concurrentGetOrCreate_sameAccountId_allSeeSameInstance() throws Exception {
        AccountId bob = idFor("bob");
        TestFs cached = new TestFs(bob);
        AccountRegistry.putForTesting(bob, cached);

        final int threadCount = 64;
        final CountDownLatch start = new CountDownLatch(1);
        final ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        try {
            List<Future<AltaStataFileSystem>> futures = new ArrayList<>(threadCount);
            for (int i = 0; i < threadCount; i++) {
                Callable<AltaStataFileSystem> task = () -> {
                    start.await();
                    return AccountRegistry.getOrCreate(userPropertiesFor(bob), "k");
                };
                futures.add(pool.submit(task));
            }
            start.countDown();

            Set<AltaStataFileSystem> distinct =
                    java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            for (Future<AltaStataFileSystem> f : futures) {
                AltaStataFileSystem fs = f.get(30, TimeUnit.SECONDS);
                assertNotNull(fs);
                distinct.add(fs);
            }

            assertEquals("all threads must observe the same AltaStataFileSystem instance",
                    1, distinct.size());
            assertSame(cached, distinct.iterator().next());
            assertEquals(1, AccountRegistry.size());
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    /**
     * Different ids under concurrent load: registry must end up with exactly
     * N entries, each id pointing to the pre-populated stub.
     */
    @Test
    public void concurrentGetOrCreate_disjointAccountIds_independentEntries() throws Exception {
        final int users = 16;
        final int hitsPerUser = 8;
        final int threadCount = users * hitsPerUser;

        final AccountId[] ids = new AccountId[users];
        final TestFs[] stubs = new TestFs[users];
        for (int u = 0; u < users; u++) {
            ids[u] = idFor("u" + u);
            stubs[u] = new TestFs(ids[u]);
            AccountRegistry.putForTesting(ids[u], stubs[u]);
        }

        final ConcurrentHashMap<String, AtomicInteger> getsByUser = new ConcurrentHashMap<>();
        for (int u = 0; u < users; u++) {
            getsByUser.put("u" + u, new AtomicInteger());
        }

        final CountDownLatch start = new CountDownLatch(1);
        final ExecutorService pool = Executors.newFixedThreadPool(Math.min(threadCount, 32));
        try {
            List<Future<AltaStataFileSystem>> futures = new ArrayList<>(threadCount);
            for (int i = 0; i < threadCount; i++) {
                final int idx = i % users;
                Callable<AltaStataFileSystem> task = () -> {
                    start.await();
                    getsByUser.get("u" + idx).incrementAndGet();
                    return AccountRegistry.getOrCreate(userPropertiesFor(ids[idx]), null);
                };
                futures.add(pool.submit(task));
            }
            start.countDown();

            for (int i = 0; i < threadCount; i++) {
                AltaStataFileSystem fs = futures.get(i).get(30, TimeUnit.SECONDS);
                assertSame(stubs[i % users], fs);
            }

            assertEquals(users, AccountRegistry.size());
            for (int u = 0; u < users; u++) {
                assertEquals(hitsPerUser, getsByUser.get("u" + u).get());
            }

            Set<AltaStataFileSystem> distinct =
                    java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            for (int u = 0; u < users; u++) {
                distinct.add(stubs[u]);
            }
            assertEquals(users, distinct.size());
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }
}
