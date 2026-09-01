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

import com.altastata.api.AccountRegistry;
import com.altastata.api.AltaStataFileSystem;
import com.altastata.api.accountsetup.UserAccountSetupHandlerInterface;
import com.altastata.grpc.proto.AccountType;
import com.altastata.grpc.proto.ChangePasswordRequest;
import com.altastata.grpc.proto.DeleteAccountRequest;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Session-scoped account maintenance ({@code ChangePassword},
 * {@code ExportAccount}, {@code DeleteAccount}).
 */
@Singleton
public final class AccountMaintenanceService {

    private static final Logger logger = LoggerFactory.getLogger(AccountMaintenanceService.class);

    private final SessionRegistry sessionRegistry;
    private final S3CredentialsRegistry s3CredentialsRegistry;
    private final EventBus eventBus;
    private final GrpcUserRegistry userRegistry;
    private final GenerateKeysService.AccountSetupHandlerFactory handlerFactory;

    public AccountMaintenanceService(SessionRegistry sessionRegistry,
                              S3CredentialsRegistry s3CredentialsRegistry,
                              EventBus eventBus,
                              GrpcUserRegistry userRegistry) {
        this(sessionRegistry, s3CredentialsRegistry, eventBus, userRegistry,
                GenerateKeysService.AccountSetupHandlerFactory.DEFAULT);
    }

    AccountMaintenanceService(SessionRegistry sessionRegistry,
                              S3CredentialsRegistry s3CredentialsRegistry,
                              EventBus eventBus,
                              GrpcUserRegistry userRegistry,
                              GenerateKeysService.AccountSetupHandlerFactory handlerFactory) {
        this.sessionRegistry = sessionRegistry;
        this.s3CredentialsRegistry = s3CredentialsRegistry;
        this.eventBus = eventBus;
        this.userRegistry = userRegistry;
        this.handlerFactory = handlerFactory;
    }

    Map<String, byte[]> changePassword(Session session,
                                       GrpcUserData userData,
                                       ChangePasswordRequest request) {
        requireLoggedIn(session, userData);
        String currentPassword = requirePassword(request.getCurrentPassword(), "current_password");
        String newPassword = requirePassword(request.getNewPassword(), "new_password");

        AltaStataFileSystem fs = userData.getAltaStataFileSystem();
        String userProperties = userData.getUserProperties();
        AccountType accountType = AccountSetupSupport.accountTypeFromUserProperties(userProperties);
        UserAccountSetupHandlerInterface handler = handlerFactory.create(accountType);

        Path workingDir = null;
        boolean deleteWorkingDir = false;
        try {
            WorkingDirContext context = prepareWorkingDir(fs, userData, accountType);
            workingDir = context.path();
            deleteWorkingDir = context.deleteOnClose();

            verifyCurrentPassword(handler, fs, userData, workingDir, currentPassword, accountType);

            if (!handler.extractKeysFromFiles(workingDir.toString(), currentPassword)) {
                throw new SecurityException("Invalid current password");
            }
            handler.reencryptAndSavePrivateKey(newPassword, workingDir.toString());

            Map<String, byte[]> accountFiles = readKeyFiles(workingDir, accountType);
            String updatedPrivateKeyPem = readUpdatedPrivateKeyPem(workingDir, accountType);
            if (deleteWorkingDir) {
                userRegistry.refreshFromKeyMaterial(
                        session.accountKey(),
                        userProperties,
                        accountFiles,
                        updatedPrivateKeyPem,
                        newPassword);
            } else {
                userRegistry.refreshAfterPasswordChange(
                        session.accountKey(), newPassword, updatedPrivateKeyPem);
            }

            if (AccountRegistry.isGatewayMaterialDir(workingDir)) {
                copyKeyFilesInto(workingDir, accountFiles, accountType);
            }

            logger.warn("ChangePassword: updated keys for account={}", session.accountKey());
            return accountFiles;
        } catch (IOException e) {
            throw new IllegalStateException("Password change failed", e);
        } finally {
            if (deleteWorkingDir && workingDir != null) {
                deleteRecursively(workingDir);
            }
        }
    }

    /**
     * Bootstrap / local-mode password change: re-encrypt private key file(s) in
     * {@code accountDir}. No session and no {@code *user.properties} required —
     * only proof of the current passphrase via successful PEM decrypt.
     */
    Map<String, byte[]> changePasswordInDirectory(String accountDirectory,
                                                  String currentPassword,
                                                  String newPassword) {
        String current = requirePassword(currentPassword, "current_password");
        String next = requirePassword(newPassword, "new_password");
        String canonical = new LoginV2DirectoryPolicy().validateAndCanonicalize(accountDirectory);
        Path workingDir = Paths.get(canonical);
        AccountType accountType = AccountSetupSupport.accountTypeFromKeyDirectory(workingDir);
        if (accountType == AccountType.HPCS) {
            throw new IllegalArgumentException(
                    "HPCS accounts do not use a local private-key passphrase");
        }
        new AccountSetupPolicy().requireAccountSetupPermitted();

        UserAccountSetupHandlerInterface handler = handlerFactory.create(accountType);
        try {
            if (!handler.extractKeysFromFiles(workingDir.toString(), current)) {
                throw new SecurityException("Invalid current password");
            }
            handler.reencryptAndSavePrivateKey(next, workingDir.toString());
            Map<String, byte[]> accountFiles = readKeyFiles(workingDir, accountType);
            logger.warn("ChangePassword (directory): updated keys in {}", workingDir);
            return accountFiles;
        } catch (IOException e) {
            throw new IllegalStateException("Password change failed", e);
        }
    }

    Map<String, byte[]> exportAccount(Session session, GrpcUserData userData) {
        requireLoggedIn(session, userData);
        AltaStataFileSystem fs = userData.getAltaStataFileSystem();
        String userProperties = userData.getUserProperties();
        AccountType accountType = AccountSetupSupport.accountTypeFromUserProperties(userProperties);

        try {
            Path sourceDir = resolveSourceDir(fs, userData, accountType);
            Map<String, byte[]> accountFiles = new HashMap<>();
            String propsFileName = Files.exists(sourceDir)
                    ? AccountSetupSupport.findUserPropertiesFileName(sourceDir)
                    : AccountSetupSupport.defaultUserPropertiesFileName(userProperties);
            accountFiles.put(propsFileName, userProperties.getBytes(StandardCharsets.UTF_8));
            for (String basename : AccountSetupSupport.keyFileBasenames(accountType)) {
                Path file = sourceDir.resolve(basename);
                if (Files.isRegularFile(file)) {
                    accountFiles.put(basename, Files.readAllBytes(file));
                }
            }
            if (accountFiles.size() <= 1) {
                throw new IllegalStateException("No account key files available to export");
            }
            logger.warn("ExportAccount: exported {} files for account={}",
                    accountFiles.size(), session.accountKey());
            return accountFiles;
        } catch (IOException e) {
            throw new IllegalStateException("Account export failed", e);
        }
    }

    /**
     * Completely deletes a user account, revoking active sessions/S3 credentials, invalidating local states,
     * and removing the user profile from the gateway registry.
     *
     * @param session the user session instance
     * @param userData the gRPC user data context
     * @param request the account deletion request
     */
    void deleteAccount(Session session, GrpcUserData userData, DeleteAccountRequest request) {
        requireLoggedIn(session, userData);
        String currentPassword = requirePassword(request.getCurrentPassword(), "current_password");

        AltaStataFileSystem fs = userData.getAltaStataFileSystem();
        String userProperties = userData.getUserProperties();
        AccountType accountType = AccountSetupSupport.accountTypeFromUserProperties(userProperties);
        UserAccountSetupHandlerInterface handler = handlerFactory.create(accountType);

        Path workingDir = null;
        boolean deleteWorkingDir = false;
        try {
            WorkingDirContext context = prepareWorkingDir(fs, userData, accountType);
            workingDir = context.path();
            deleteWorkingDir = context.deleteOnClose();

            verifyCurrentPassword(handler, fs, userData, workingDir, currentPassword, accountType);

            String accountKey = session.accountKey();
            for (String token : sessionRegistry.tokensForAccount(accountKey)) {
                eventBus.evictSession(token, "Account deleted");
                s3CredentialsRegistry.revokeAllForSession(token);
            }
            s3CredentialsRegistry.revokeAllForUser(accountKey);
            sessionRegistry.invalidateAccount(accountKey);
            AccountRegistry.invalidate(fs);
            userRegistry.removeAccount(accountKey);

            logger.warn("DeleteAccount: removed gateway state for account={}", accountKey);
        } catch (IOException e) {
            throw new IllegalStateException("Account deletion failed", e);
        } finally {
            if (deleteWorkingDir && workingDir != null) {
                deleteRecursively(workingDir);
            }
        }
    }

    /**
     * Enforces that the session is valid and active on this gateway.
     *
     * @param session the user session instance
     * @param userData the gRPC user data context
     */
    private static void requireLoggedIn(Session session, GrpcUserData userData) {
        if (session == null || userData == null) {
            throw new SecurityException("Bearer session required");
        }
        if (userData.getAltaStataFileSystem() == null) {
            throw new IllegalStateException("Account is not logged in on this gateway");
        }
        if (userData.getUserProperties() == null || userData.getUserProperties().isEmpty()) {
            throw new IllegalStateException("Account user properties are not available");
        }
    }

    /**
     * Enforces that a non-null, non-empty password was supplied.
     *
     * @param password the password to check
     * @param fieldName the associated request parameter label
     * @return the verified password string
     */
    private static String requirePassword(String password, String fieldName) {
        if (password == null || password.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        return password;
    }

    private void verifyCurrentPassword(UserAccountSetupHandlerInterface handler,
                                       AltaStataFileSystem fs,
                                       GrpcUserData userData,
                                       Path workingDir,
                                       String currentPassword,
                                       AccountType accountType) {
        if (accountType == AccountType.RSA) {
            GrpcUserRegistry.validatePasswordAgainstEncryptedPem(
                    userData.getPrivateKeyEncrypted(), currentPassword.toCharArray());
        } else if (!handler.checkPasswordUsingEncryptedPrivateKey(
                currentPassword, workingDir.toString())) {
            throw new SecurityException("Invalid current password");
        }
        try {
            fs.setPassword(currentPassword);
        } catch (Exception e) {
            throw new SecurityException("Invalid current password", e);
        }
    }

    private WorkingDirContext prepareWorkingDir(AltaStataFileSystem fs,
                                                GrpcUserData userData,
                                                AccountType accountType) throws IOException {
        Path sourceDir = resolveSourceDir(fs, userData, accountType);
        if (AccountRegistry.isGatewayMaterialDir(sourceDir)) {
            return new WorkingDirContext(sourceDir, false);
        }
        Path tempDir = Files.createTempDirectory("altastata-maint-");
        copyDirectory(sourceDir, tempDir);
        return new WorkingDirContext(tempDir, true);
    }

    private Path resolveSourceDir(AltaStataFileSystem fs,
                                  GrpcUserData userData,
                                  AccountType accountType) throws IOException {
        String accountDir = fs.getAccount().getAccountDir();
        if (accountDir != null && Files.isDirectory(Paths.get(accountDir))) {
            return Paths.get(accountDir);
        }
        Optional<Path> materialDir = AccountRegistry.materialDirFor(fs.getAccountId());
        if (materialDir.isPresent()) {
            return materialDir.get();
        }
        if (accountType == AccountType.RSA) {
            return materializeRsaWorkingDir(userData);
        }
        throw new IllegalStateException("Account key files are not available on this gateway");
    }

    /**
     * Materializes an RSA account user properties and private key into a temporary folder to serve as a working directory.
     *
     * @param userData the gRPC user data context
     * @return path to the newly created working directory
     * @throws IOException if folder creation or file writing fails
     */
    private static Path materializeRsaWorkingDir(GrpcUserData userData) throws IOException {
        Path tempDir = Files.createTempDirectory("altastata-maint-");
        String userProperties = userData.getUserProperties();
        String propsFileName = AccountSetupSupport.defaultUserPropertiesFileName(userProperties);
        Files.write(tempDir.resolve(propsFileName),
                userProperties.getBytes(StandardCharsets.UTF_8));
        String privateKey = userData.getPrivateKeyEncrypted();
        if (privateKey == null || privateKey.isEmpty()) {
            deleteRecursively(tempDir);
            throw new IllegalStateException("RSA private key is not available");
        }
        Files.write(tempDir.resolve("private.key"), privateKey.getBytes(StandardCharsets.UTF_8));
        return tempDir;
    }

    /**
     * Reads key files from the given account directory.
     *
     * @param accountDir The directory to read files from
     * @param accountType The type of the account
     * @return A map of filenames to file contents
     * @throws IOException If reading fails
     */
    private static Map<String, byte[]> readKeyFiles(Path accountDir, AccountType accountType)
            throws IOException {
        Map<String, byte[]> files = new HashMap<>();
        for (String basename : AccountSetupSupport.keyFileBasenames(accountType)) {
            Path file = accountDir.resolve(basename);
            if (Files.isRegularFile(file)) {
                files.put(basename, Files.readAllBytes(file));
            }
        }
        if (files.isEmpty()) {
            throw new IllegalStateException("Password change produced no account files");
        }
        return files;
    }

    private static void copyKeyFilesInto(Path targetDir,
                                         Map<String, byte[]> accountFiles,
                                         AccountType accountType) throws IOException {
        for (String basename : AccountSetupSupport.keyFileBasenames(accountType)) {
            byte[] content = accountFiles.get(basename);
            if (content != null) {
                Files.write(targetDir.resolve(basename), content);
            }
        }
    }

    /**
     * Reads the updated private key in PEM format.
     *
     * @param accountDir The account directory
     * @param accountType The account type
     * @return The updated private key PEM string, or null if not an RSA account or the file does not exist
     * @throws IOException If reading fails
     */
    private static String readUpdatedPrivateKeyPem(Path accountDir, AccountType accountType)
            throws IOException {
        if (accountType != AccountType.RSA) {
            return null;
        }
        Path privateKey = accountDir.resolve("private.key");
        if (!Files.isRegularFile(privateKey)) {
            return null;
        }
        return Files.readString(privateKey);
    }

    /**
     * Recursively copies a directory tree from source to destination.
     *
     * @param source source directory path
     * @param target destination directory path
     * @throws IOException if folder or file copy operations fail
     */
    private static void copyDirectory(Path source, Path target) throws IOException {
        Files.walk(source).forEach(path -> {
            try {
                Path relative = source.relativize(path);
                Path destination = target.resolve(relative);
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else {
                    Files.createDirectories(destination.getParent());
                    Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) {
                throw new IllegalStateException("Failed to copy account directory", e);
            }
        });
    }

    /**
     * Recursively deletes a directory or file path.
     *
     * @param root directory or file path to delete
     */
    private static void deleteRecursively(Path root) {
        try {
            Files.walk(root)
                    .sorted((a, b) -> b.compareTo(a))
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException ignored) {
                            // best-effort cleanup
                        }
                    });
        } catch (IOException ignored) {
            // best-effort cleanup
        }
    }

    private static final class WorkingDirContext {
        private final Path path;
        private final boolean deleteOnClose;

        WorkingDirContext(Path path, boolean deleteOnClose) {
            this.path = path;
            this.deleteOnClose = deleteOnClose;
        }

        Path path() {
            return path;
        }

        boolean deleteOnClose() {
            return deleteOnClose;
        }
    }
}
