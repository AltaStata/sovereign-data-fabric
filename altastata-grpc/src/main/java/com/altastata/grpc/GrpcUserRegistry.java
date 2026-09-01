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
import com.altastata.api.AltaStataEventListener;
import com.altastata.api.AltaStataFileSystem;
import jakarta.inject.Singleton;
import org.bouncycastle.openssl.PEMEncryptedKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcePEMDecryptorProviderBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

@Singleton
public class GrpcUserRegistry {
    private static final Logger logger = LoggerFactory.getLogger(GrpcUserRegistry.class);
    /**
     * Test seam for the listener that gets attached to a freshly-installed
     * {@link AltaStataFileSystem}. Production wiring constructs an
     * {@link AltaStataEventBusAdapter} so cross-user "SHARE" / "DELETE"
     * events from altastata-core land on the typed {@link EventBus} for
     * consumption by {@code EventsService.Watch}. Tests can return
     * {@code null} to skip listener registration entirely or provide a
     * capturing double.
     *
     * <p>The factory is invoked exactly once per user, in the same
     * {@code synchronized (data)} block that installs the live filesystem,
     * so re-login (probe-then-install) does not double-register.
     */
    @FunctionalInterface
    interface EventListenerFactory {
        /** Returns the listener to attach, or {@code null} to attach none. */
        AltaStataEventListener create(String userName);
    }

    /**
     * Test seam for the lightweight password check that runs on re-Login
     * (when the live {@link AltaStataFileSystem} is already installed and
     * we just need to confirm the supplied password is the same one that
     * decrypts the user's encrypted private key). Production wiring is
     * {@link #validatePasswordAgainstEncryptedPem}, which uses BouncyCastle
     * to decrypt the PEM and throws on a wrong password.
     *
     * <p>Tests usually pass either a no-op validator (when they want all
     * passwords to be accepted) or a validator that throws on a specific
     * "wrong" password, so they can exercise the re-Login error path
     * without standing up a real encrypted PEM fixture.
     */
    @FunctionalInterface
    interface PasswordValidator {
        /** Throws (RuntimeException) if the password does not match the encrypted PEM. */
        void validate(String privateKeyPem, char[] password);
    }

    private final Map<String, GrpcUserData> byAccountKey = new ConcurrentHashMap<>();
    private final EventListenerFactory eventListenerFactory;
    private final PasswordValidator passwordValidator;

    /** Production constructor wired by Micronaut. */
    public GrpcUserRegistry(EventBus eventBus) {
        this(userName -> new AltaStataEventBusAdapter(userName, eventBus),
                GrpcUserRegistry::validatePasswordAgainstEncryptedPem);
    }

    /**
     * Test-only convenience: no event listener, permissive password
     * validator (re-Login with any password is accepted).
     */
    GrpcUserRegistry() {
        this(userName -> null, (pem, pwd) -> { });
    }

    GrpcUserRegistry(EventListenerFactory eventListenerFactory) {
        this(eventListenerFactory, (pem, pwd) -> { });
    }

    GrpcUserRegistry(EventListenerFactory eventListenerFactory,
                     PasswordValidator passwordValidator) {
        this.eventListenerFactory = eventListenerFactory;
        this.passwordValidator = passwordValidator;
    }

    /**
     * @param accountKey {@link com.altastata.api.AccountId#key()} — not bare {@code myuser}
     */
    public GrpcUserData installFromLoginV2(String accountKey,
                                           String userProperties,
                                           String privateKeyPemForValidator,
                                           String password,
                                           Supplier<AltaStataFileSystem> fileSystemFactory) {
        GrpcUserData data = byAccountKey.computeIfAbsent(accountKey, GrpcUserData::new);

        synchronized (data) {
            AltaStataFileSystem currentFs = data.getAltaStataFileSystem();

            if (currentFs != null) {
                // Already live — password only. Ignore uploaded props / key (Console vs Python race).
                passwordValidator.validate(
                        data.getPrivateKeyEncrypted(), password.toCharArray());
                currentFs.setPassword(password);
            } else {
                AltaStataFileSystem fs = fileSystemFactory.get();
                try {
                    fs.setPassword(password);
                } catch (Exception e) {
                    AccountRegistry.invalidate(fs);
                    throw (e instanceof RuntimeException) ? (RuntimeException) e : new RuntimeException(e);
                }
                data.setUserProperties(userProperties);
                if (privateKeyPemForValidator != null) {
                    data.setPrivateKeyEncrypted(privateKeyPemForValidator);
                }
                data.setAltaStataFileSystem(fs);
                AltaStataEventListener listener = eventListenerFactory.create(accountKey);
                if (listener != null) {
                    fs.addAltaStataEventListener(listener);
                }
            }
            if (data.getAccessKey() == null || data.getAccessKey().isEmpty()) {
                data.setAccessKey("ak-temp-user-" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 8));
            }
            if (data.getSecretKey() == null || data.getSecretKey().isEmpty()) {
                data.setSecretKey("sk-temp-user-" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 8));
            }
        }
        return data;
    }

    /**
     * Drop gateway state for {@code accountKey} after account deletion.
     */
    public void removeAccount(String accountKey) {
        if (accountKey != null) {
            byAccountKey.remove(accountKey);
        }
    }

    /**
     * Refresh the live filesystem after a successful password change so the
     * session keeps working with the re-encrypted key material.
     */
    public void refreshAfterPasswordChange(String accountKey,
                                           String newPassword,
                                           String updatedPrivateKeyPem) {
        GrpcUserData data = byAccountKey.get(accountKey);
        if (data == null) {
            return;
        }
        synchronized (data) {
            AltaStataFileSystem prior = data.getAltaStataFileSystem();
            String priorAccountDir = prior != null ? prior.getAccount().getAccountDir() : null;
            if (prior != null) {
                AccountRegistry.invalidate(prior);
            }
            if (updatedPrivateKeyPem != null) {
                data.setPrivateKeyEncrypted(updatedPrivateKeyPem);
            }
            data.setAltaStataFileSystem(null);

            AltaStataFileSystem fs;
            java.util.Optional<java.nio.file.Path> materialDir = prior != null
                    ? AccountRegistry.materialDirFor(prior.getAccountId())
                    : java.util.Optional.empty();
            if (materialDir.isPresent()) {
                fs = AccountRegistry.getOrCreateFromDir(materialDir.get().toString());
            } else if (priorAccountDir != null) {
                fs = AccountRegistry.getOrCreateFromDir(priorAccountDir);
            } else {
                fs = AccountRegistry.getOrCreate(
                        data.getUserProperties(), data.getPrivateKeyEncrypted());
            }
            installRefreshedFilesystem(accountKey, data, fs, newPassword);
        }
    }

    /**
     * Rebuild the live filesystem from updated in-memory key material (used
     * when password change ran against a temp copy of a co-located account dir).
     */
    public void refreshFromKeyMaterial(String accountKey,
                                       String userProperties,
                                       java.util.Map<String, byte[]> keyFiles,
                                       String updatedPrivateKeyPem,
                                       String newPassword) {
        GrpcUserData data = byAccountKey.get(accountKey);
        if (data == null) {
            return;
        }
        synchronized (data) {
            AltaStataFileSystem prior = data.getAltaStataFileSystem();
            if (prior != null) {
                AccountRegistry.invalidate(prior);
            }
            data.setUserProperties(userProperties);
            if (updatedPrivateKeyPem != null) {
                data.setPrivateKeyEncrypted(updatedPrivateKeyPem);
            }
            data.setAltaStataFileSystem(null);

            AltaStataFileSystem fs;
            if (updatedPrivateKeyPem != null) {
                fs = AccountRegistry.getOrCreate(userProperties, updatedPrivateKeyPem);
            } else {
                fs = AccountRegistry.getOrCreateFromUpload(userProperties, keyFiles);
            }
            installRefreshedFilesystem(accountKey, data, fs, newPassword);
        }
    }

    private void installRefreshedFilesystem(String accountKey,
                                            GrpcUserData data,
                                            AltaStataFileSystem fs,
                                            String newPassword) {
        try {
            fs.setPassword(newPassword);
        } catch (RuntimeException e) {
            AccountRegistry.invalidate(fs);
            throw e;
        }
        data.setAltaStataFileSystem(fs);
        AltaStataEventListener listener = eventListenerFactory.create(accountKey);
        if (listener != null) {
            fs.addAltaStataEventListener(listener);
        }
    }

    /**
     * Lightweight password check: decrypt the encrypted RSA private-key PEM
     * with the supplied password using BouncyCastle. Throws on a bad
     * password the same way {@link AltaStataFileSystem#setPassword} would
     * (PEMException / IOException), which {@link AuthGrpcService#loginV2}
     * already maps to {@code UNAUTHENTICATED "Invalid credentials"}.
     *
     * <p>Used on re-Login to avoid constructing a transient
     * {@code AltaStataFileSystem} (which would also start an unwanted
     * background SQS-poller thread on this user's cloud).
     *
     * <p>If the PEM is not actually encrypted (older test fixtures), the
     * call is a no-op — it just confirms the PEM parses, which is a
     * useful sanity check anyway.
     */
    static void validatePasswordAgainstEncryptedPem(
            String privateKeyPem, char[] password) {
        // HSM-backed accounts never registered a PEM in the first place.
        // Account.setPassword on the live fs is still called and will fail
        // loudly for the HSM branch if user_properties is misconfigured.
        if (privateKeyPem == null || privateKeyPem.isEmpty()) {
            return;
        }
        try (PEMParser parser = new PEMParser(new StringReader(privateKeyPem))) {
            Object obj = parser.readObject();
            if (obj instanceof PEMEncryptedKeyPair) {
                ((PEMEncryptedKeyPair) obj).decryptKeyPair(
                        new JcePEMDecryptorProviderBuilder().build(password));
            }
            // else: not encrypted; AltaStataFileSystem.setPassword would
            // also accept it. Nothing to validate cryptographically.
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            // Wrap checked exceptions (IOException) as runtime so
            // AuthGrpcService.loginV2's generic catch maps them to
            // UNAUTHENTICATED.
            throw new RuntimeException(e);
        }
    }

    /**
     * Resolves gateway profile by registry key ({@link com.altastata.api.AccountId#key()}).
     */
    public GrpcUserData getByAccountKey(String accountKey) {
        return byAccountKey.get(accountKey);
    }

    /**
     * Org peer lookup: same datalake ({@code prefix} + {@code accounttype}) as {@code inLake},
     * different {@code myUser}. Avoids colliding amazon/azure logins that share a myuser.
     */
    public GrpcUserData findLiveInSameLake(AccountId inLake, String myUser) {
        if (inLake == null || myUser == null || myUser.isEmpty()) {
            return null;
        }
        return byAccountKey.get(new AccountId(
                inLake.getContainerPrefix(), myUser, inLake.getAccountType()).key());
    }

    /**
     * Lists all registered account keys ({@link com.altastata.api.AccountId#key()}).
     *
     * @return list of account key strings
     */
    public List<String> listAccountKeys() {
        return new ArrayList<>(byAccountKey.keySet());
    }
}
