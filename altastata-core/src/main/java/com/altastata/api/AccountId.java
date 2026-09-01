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

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.util.Objects;
import java.util.Properties;

/**
 * Immutable identity of an AltaStata account, used as the key of
 * {@link AccountRegistry}.
 *
 * <p>An AltaStata account is uniquely identified by the tuple of three
 * fields read from the user-properties file:
 *
 * <ul>
 *   <li>{@code acccontainer-prefix} — the storage container/bucket prefix
 *       (e.g. {@code altastata-myorgrsa444-}). Encodes the
 *       <i>datalake</i> / organisation.</li>
 *   <li>{@code myuser} — the AltaStata user inside that datalake
 *       (e.g. {@code bob123}).</li>
 *   <li>{@code accounttype} — the cloud backend &amp; encryption mode
 *       (e.g. {@code amazon-s3-secure}, {@code azure-secure},
 *       {@code ibm-cos-secure}).</li>
 * </ul>
 *
 * <p>Two callers that present the same triple are talking about the
 * <b>same</b> AltaStata account; sharing across different triples is
 * unsafe (different credentials, different keys, different containers).
 *
 * <p>Production code never needs to construct an {@code AccountId}
 * directly — pass the user-properties text / account dir / preloaded
 * {@link Account} to {@link AccountRegistry} and let it derive the key.
 */
public final class AccountId {

    private final String containerPrefix;
    private final String myUser;
    private final String accountType;

    /**
     * Construct an identity from its three components. All three must be
     * non-blank.
     *
     * <p>Production callers should prefer the {@link #fromUserProperties},
     * {@link #fromAccountDir}, or {@link #fromAccount} factories so the
     * identity is computed from the same source the
     * {@link AltaStataFileSystem} is built from.
     */
    public AccountId(String containerPrefix, String myUser, String accountType) {
        this.containerPrefix = requireNonBlank(containerPrefix, "acccontainer-prefix");
        this.myUser = requireNonBlank(myUser, "myuser");
        this.accountType = requireNonBlank(accountType, "accounttype");
    }

    /** @return the {@code acccontainer-prefix} field. */
    public String getContainerPrefix() {
        return containerPrefix;
    }

    /**
     * Stable identity key for use as a map / session key
     * ({@code prefix|myuser|accounttype}).
     *
     * <p>Distinct from {@link #toString()} on purpose: {@code toString()} is a
     * log-friendly rendering that may evolve, whereas {@code key()} is a
     * contract other components depend on to route sessions and gateway state.
     * Two callers with equal {@code AccountId}s always get an equal {@code key()}.
     */
    public String key() {
        return containerPrefix + "|" + myUser + "|" + accountType;
    }

    /** @return the {@code myuser} field. */
    public String getMyUser() {
        return myUser;
    }

    /** @return the {@code accounttype} field. */
    public String getAccountType() {
        return accountType;
    }

    /**
     * Parse the given user-properties text (same format as the
     * {@code *.user.properties} file on disk) and build the identity.
     *
     * @throws IllegalArgumentException if {@code userPropertiesText} is null,
     *         can't be parsed as Java {@link Properties}, or is missing
     *         one of the three identity fields.
     */
    public static AccountId fromUserProperties(String userPropertiesText) {
        if (userPropertiesText == null) {
            throw new IllegalArgumentException("userPropertiesText is null");
        }
        Properties p = new Properties();
        try (Reader r = new StringReader(userPropertiesText)) {
            p.load(r);
        } catch (IOException e) {
            // StringReader.load doesn't really do I/O, but Properties.load
            // declares IOException, so wrap defensively.
            throw new IllegalArgumentException("Can't parse user properties", e);
        }
        return fromProperties(p, "<inline user properties>");
    }

    /**
     * Locate the single {@code *user.properties} file inside
     * {@code accountDirPath}, load it, and build the identity.
     *
     * <p>Matches the lookup that {@code Account.loadAccountProperties}
     * performs at filesystem-construction time, so the resulting id is
     * guaranteed to be the same as the one a freshly-constructed
     * {@link AltaStataFileSystem} from the same directory would report.
     *
     * @throws IllegalArgumentException if {@code accountDirPath} is null
     *         or doesn't resolve to a directory containing exactly one
     *         {@code *user.properties} file with all three identity fields.
     */
    public static AccountId fromAccountDir(String accountDirPath) {
        if (accountDirPath == null) {
            throw new IllegalArgumentException("accountDirPath is null");
        }
        File dir = new File(accountDirPath);
        if (!dir.isDirectory()) {
            throw new IllegalArgumentException("Not a directory: " + accountDirPath);
        }
        File[] propsFiles = dir.listFiles((d, name) -> name.endsWith("user.properties"));
        if (propsFiles == null || propsFiles.length == 0) {
            throw new IllegalArgumentException("No *user.properties file in: " + accountDirPath);
        }
        if (propsFiles.length > 1) {
            throw new IllegalArgumentException(
                    "More than one *user.properties file in: " + accountDirPath);
        }
        Properties p = new Properties();
        try (Reader r = new FileReader(propsFiles[0])) {
            p.load(r);
        } catch (IOException e) {
            throw new IllegalArgumentException(
                    "Can't read " + propsFiles[0].getAbsolutePath(), e);
        }
        return fromProperties(p, propsFiles[0].getAbsolutePath());
    }

    /**
     * Read the identity from an already-loaded {@link Account}. Pull
     * fields through the accessors so the same Scala-side logic that
     * decides them (including {@code addDefaultProps}) is honoured.
     *
     * @throws IllegalArgumentException if any of the three identity
     *         properties is missing on the {@code Account}.
     */
    public static AccountId fromAccount(Account account) {
        if (account == null) {
            throw new IllegalArgumentException("account is null");
        }
        return new AccountId(
                account.ACCOUNT_CONTAINER_PREFIX(),
                account.MY_USER(),
                account.ACCOUNT_TYPE());
    }

    /**
     * Parses an AccountId from a Java Properties collection.
     *
     * @param p the properties object containing the account properties
     * @param sourceLabel the origin of the properties (used in error message context)
     * @return the parsed AccountId instance
     * @throws IllegalArgumentException if any required property is missing or blank
     */
    private static AccountId fromProperties(Properties p, String sourceLabel) {
        String prefix = p.getProperty("acccontainer-prefix");
        String user = p.getProperty("myuser");
        String type = p.getProperty("accounttype");
        if (prefix == null || prefix.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "Missing 'acccontainer-prefix' in " + sourceLabel);
        }
        if (user == null || user.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "Missing 'myuser' in " + sourceLabel);
        }
        if (type == null || type.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "Missing 'accounttype' in " + sourceLabel);
        }
        return new AccountId(prefix, user, type);
    }

    /**
     * Asserts that a given string value is not null or empty.
     *
     * @param s the string to validate
     * @param fieldName the name of the field for context in potential error exceptions
     * @return the trimmed, non-blank string
     * @throws IllegalArgumentException if the string is null, empty, or whitespace-only
     */
    private static String requireNonBlank(String s, String fieldName) {
        if (s == null || s.trim().isEmpty()) {
            throw new IllegalArgumentException(fieldName + " is blank");
        }
        return s;
    }

    /**
     * Compares this AccountId with another object for value equality.
     *
     * @param o the object to compare against
     * @return true if the other object is an AccountId with equivalent fields; false otherwise
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AccountId)) return false;
        AccountId other = (AccountId) o;
        return containerPrefix.equals(other.containerPrefix)
                && myUser.equals(other.myUser)
                && accountType.equals(other.accountType);
    }

    /**
     * Calculates a hash code based on the identifying fields.
     *
     * @return a composite hash code value
     */
    @Override
    public int hashCode() {
        return Objects.hash(containerPrefix, myUser, accountType);
    }

    /**
     * Returns a stable, log-friendly string representation of the account identity.
     *
     * @return a serialized string representation
     */
    @Override
    public String toString() {
        // Format intentionally stable & log-friendly — used in error
        // messages from AccountRegistry and exceptions thrown by
        // AltaStataFileSystem when a registry mismatch is detected.
        return "AccountId{" + containerPrefix + "|" + myUser + "|" + accountType + "}";
    }
}
