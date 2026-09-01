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

import com.altastata.filesystem.common.CloudFile;
import com.altastata.filesystem.common.FileSystemHandler;
import com.altastata.filesystem.common.VersionAttributes;
import com.altastata.filesystem.securecloud.SecureCloudStream.AltaStataChunkedInputStream;
import com.altastata.filesystem.securecloud.SecureCloudStream.AltaStataChunkedOutputStream;
import com.altastata.utils.Account;
import com.altastata.utils.Constants;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scala.Tuple2;

import java.io.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.Map;
import java.util.Base64;
import org.json.JSONObject;
import scala.collection.JavaConverters;

/**
 * @author AltaStata
 *
 */
public class AltaStataFileSystem {

	private Account account = new Account();
	
	private Logger LOGGER = LoggerFactory.getLogger(AltaStataFileSystem.class);
	
	public enum OperationState {
	    NONE, UPLOADING, UPLOADED, DOWNLOADING, DOWNLOADED, SHARING, DELETING, DELETED, RENAMING,
	    ERROR, DONE
	}
	
	// TODO: check that file or directory name does not have this symbol at the end
	// Twelve Pointed Black Star (https://unicode-table.com/en/#2739)
	public static String FILE_MARK_SIGN = "\u2739"; // + "/";

	/**
	 * Package-private. External callers must construct via
	 * {@link AccountRegistry#getOrCreateForAccount(Account)} so that
	 * the resulting filesystem is registered and its per-account
	 * caches/pools dedupe across services. See
	 * {@code ALTASTATA_SERVICES_UBER_DESIGN.md} §4.
	 */
	AltaStataFileSystem(Account account) {
		this.account = account;
	}

	/**
	 * Creates a new AltaStata File system instance.
	 *
	 * <p>Package-private. External callers must construct via
	 * {@link AccountRegistry#getOrCreate(String, String)} so that
	 * the resulting filesystem is registered and its per-account
	 * caches/pools dedupe across services. See
	 * {@code ALTASTATA_SERVICES_UBER_DESIGN.md} §4.
	 *
	 * @param userProperties Java properties content as a string
	 * @param privateKeyEncrypted Encrypted private key in PEM format
	 */
	AltaStataFileSystem(String userProperties, String privateKeyEncrypted) {
		String[] errors = account.loadAccountPropertiesFromText(userProperties, privateKeyEncrypted);
		
		if (errors.length > 0) {
			String joinedString = StringUtils.join(errors, "\n");
			
			LOGGER.error(joinedString);
		}
	}

	/**
	 * Create AltaStata File system from a directory containing
	 * user_properties and an encrypted private-key PEM.
	 *
	 * <p>Package-private. External callers must construct via
	 * {@link AccountRegistry#getOrCreateFromDir(String)} so that
	 * the resulting filesystem is registered and its per-account
	 * caches/pools dedupe across services. See
	 * {@code ALTASTATA_SERVICES_UBER_DESIGN.md} §4.
	 *
	 * @param accountDirPath Directory that contains userProperties and privateKey files
	 */
	AltaStataFileSystem(String accountDirPath) {
		// RSA/PQC: event loop starts from setPassword. HSM/HPCS: auto-starts at end of load.
		String[] errors = account.loadAccountProperties(accountDirPath);
		
		if (errors.length > 0) {
			String joinedString = StringUtils.join(errors, "\n");
			
			LOGGER.error(joinedString);
		}
	}

	/**
	 * The underlying {@link Account} that holds this filesystem's user
	 * properties, caches and cloud handlers. Exposed primarily so callers
	 * can pass it to lower-level cloud APIs (e.g.
	 * {@code SecureCloudStream.AltaStataChunkedInputStream}) without
	 * reloading user properties.
	 */
	public Account getAccount() {
		return account;
	}

	/**
	 * Identity tuple {@code (acccontainer-prefix, myuser, accounttype)}
	 * used as the {@link AccountRegistry} key.
	 */
	public AccountId getAccountId() {
		return AccountId.fromAccount(account);
	}

	/**
	 * Sets the decryption password for the account and initializes cryptographic handlers.
	 * This must be called before performing any I/O operations.
	 * On success, {@link Account#setPassword} also starts the change-queue event loop.
	 * 
	 * @param accountPassword The user's account password
	 * @return The current AltaStataFileSystem instance for method chaining
	 */
	public AltaStataFileSystem setPassword(String accountPassword) {
		account.setPassword(accountPassword.toCharArray());
		return this;
	}
	
	/**
	 * Sets the AWS Cognito password if Cognito is used as an identity provider.
	 * The username should be provided as part of the userProperties configuration.
	 * 
	 * @param userPassword The user's Cognito password
	 * @return The current AltaStataFileSystem instance
	 */
	public AltaStataFileSystem setCognitoPassword(String userPassword) {
		account.setCognitoPassword(userPassword);
		
		return this;
	}
	
	/**
	 * Sets the time interval for polling new cloud notifications (e.g. sharing events).
	 * 
	 * @param timeInterval The time interval in seconds
	 * @return The current AltaStataFileSystem instance
	 */
	public AltaStataFileSystem setNotificationsTimeInterval(String timeInterval) {
		account.checkNotifications(timeInterval);
		
		return this;
	}
	
	/**
	 * Stores a list of local files or directories to the secure cloud directory.
	 * 
	 * The method walks local directories, maps them to cloud paths based on the 
	 * provided prefixes, encrypts their contents and metadata, and uploads them.
	 *
	 * @param localFilesOrDirectories List of absolute local file or directory paths (e.g. /tmp/myfile.txt, /tmp/dir1)
	 * @param localFSPrefix The local path prefix to be stripped before uploading
	 * @param cloudPathPrefix The cloud path prefix to prepend to the resulting cloud file
	 * @param waitUntilDone If true, blocks until all uploads finish. If false, returns immediately with UPLOADING status.
	 * @return A list of {@link CloudFileOperationStatus} tracking the state of each file
	 */
	public List<CloudFileOperationStatus> store(List<String> localFilesOrDirectories, String localFSPrefix, String cloudPathPrefix, boolean waitUntilDone) {
		
		List<File> selectedFiles = new ArrayList<File>();
		for (String fileName: localFilesOrDirectories) {
			selectedFiles.add(new File(fileName));
		}
		
		// we need only files, no dirs
		List<Tuple2<File, CloudFile>> listForSubTree = account.getFileSystemHandler().mapFilesTreeToCloudFileList(
				selectedFiles, localFSPrefix, cloudPathPrefix,
				System.currentTimeMillis());
		
		CloudFileOperationStatus[] storeResults = account.fileSystemModel()
				.uploadLocalFilesToCloud(listForSubTree, waitUntilDone);

		for (int i = 0; i < storeResults.length; i++) {
			if (storeResults[i].getOperationState().equals(OperationState.DONE)) {
				LOGGER.info("store: " + storeResults[i].getCloudFileVersionPath());
			}
			else {
				LOGGER.info("store " + storeResults[i].getCloudFileVersionPath() + 
						((storeResults[i].getErrorTrace() != null)?("error: " + storeResults[i].getError() + "\n" + stackTraceToString(storeResults[i].getErrorTrace())):""));
			}										
		}
				
		return Arrays.asList(storeResults);
	}

	/**
	 * List all files that match the cloudPathPrefix and timeInterval
	 * 
	 * @param cloudPathPrefix Prefix that matches all the cloud files for this function call. For example for a file "/My Files/file1.txt" it can be "/My Fi"
	 * @param includingSubdirectories Run only for the top matching directory or also for sub-directories 
	 * @param timeIntervalStart Filter in all the files with creation time larger or equal than that value. Use null to ignore it.
	 * @param timeIntervalEnd Filter in all the files with creation time smaller or equal than that value. Use null to ignore it.
	 * @return The list of files version names including tags and creation times. Use "\u2739" as delimiter to extract the version part.
	 */
	public Iterator<String[]> listCloudFilesVersions(String cloudPathPrefix, Boolean includingSubdirectories, String timeIntervalStart, String timeIntervalEnd) {
		if (!includingSubdirectories && cloudPathPrefix.endsWith("/") == false && !cloudPathPrefix.equals("")) {
			cloudPathPrefix += "/";
		}

		Iterator<CloudFile> it = 
				account.fileSystemModel().listCloudFiles(cloudPathPrefix, includingSubdirectories, timeIntervalStart, timeIntervalEnd, false);

		return new FacadeIterator(it);
	}
	
	/**
	 * Retrieves files or directories from the secure cloud to a local directory.
	 * 
	 * @param outputDir The absolute path of the local directory to download files into
	 * @param cloudPathPrefix The prefix (path) of the cloud file or directory to download
	 * @param includingSubdirectories True to download recursively, false for flat listing
	 * @param snapshotTime Epoch timestamp to resolve historical file versions. If null, gets the latest.
	 * @param isStreaming If true, optimizes chunk prefetching for sequential stream processing
	 * @param waitUntilDone If true, blocks until downloading completes. If false, runs asynchronously.
	 * @return A list of {@link CloudFileOperationStatus} tracking the download states
	 */
	public List<CloudFileOperationStatus> retrieve(String outputDir, String cloudPathPrefix, Boolean includingSubdirectories, Long snapshotTime, boolean isStreaming, boolean waitUntilDone) {
		if (!includingSubdirectories && cloudPathPrefix.endsWith("/") == false) {
			cloudPathPrefix += "/";
		}
		
		Iterator<CloudFile> it = 
				account.fileSystemModel().listCloudFiles(cloudPathPrefix, includingSubdirectories, null, null, true);
		
		List<CloudFile> objectsToDownload = new ArrayList<CloudFile>();
		Set<Long> timestampFilter = new TreeSet<Long>();
				
		while (it.hasNext()) {
			CloudFile cf = it.next();
			
			//System.out.println("cf: " + cf);
			
			VersionAttributes foundVersion = null;
			for (VersionAttributes version : cf.getVersions()) {
				//System.out.println("	version.getCreateTime(): " + version.getCreateTime() + " snapshotTime: " + snapshotTime);
				
				if (version.getCreateTime() <= snapshotTime) {
					foundVersion = version;
				}
			}
			
			if (foundVersion != null) {
				cf.getVersions().clear();
				cf.addVersion(foundVersion);
				
				objectsToDownload.add(cf);
				timestampFilter.add(foundVersion.getCreateTime());
			}
		}
		
		if (objectsToDownload.size() > 0) {
		
			CloudFileOperationStatus[] retrieveResults = account.fileSystemModel()
					.retrieveCloudFilesToLocalDirectory(objectsToDownload.toArray(new CloudFile[objectsToDownload.size()]),
							outputDir, new ArrayList<Long>(timestampFilter), isStreaming, waitUntilDone, false);
	
			for (int i = 0; i < retrieveResults.length; i++) {
				if (retrieveResults[i].getOperationState().equals(OperationState.DONE)) {
					LOGGER.info("download: " + retrieveResults[i].getCloudFileVersionPath());
				}
				else {
					LOGGER.info("download: " + retrieveResults[i].getCloudFileVersionPath() + 
							((retrieveResults[i].getErrorTrace() != null)?("error: " + retrieveResults[i].getError() + "\n" + stackTraceToString(retrieveResults[i].getErrorTrace())):""));
				}										
			}
			
			return Arrays.asList(retrieveResults);
		}
		else {
			return new ArrayList<CloudFileOperationStatus>();
		}
				
	}

	/**
	 * Deletes files or directories from the cloud storage securely.
	 * 
	 * @param cloudPathPrefix The path prefix indicating what files to delete
	 * @param includingSubdirectories True to delete recursively, false to delete just the top level
	 * @param timeIntervalStart Deletes versions created on or after this timestamp (optional)
	 * @param timeIntervalEnd Deletes versions created on or before this timestamp (optional)
	 * @return A list of {@link CloudFileOperationStatus} detailing the operation state
	 */
	public List<CloudFileOperationStatus> delete(String cloudPathPrefix, Boolean includingSubdirectories, String timeIntervalStart, String timeIntervalEnd) {
		CloudFileSelection selection = new CloudFileSelection();
		collectCloudFilesForPrefix(cloudPathPrefix, includingSubdirectories, timeIntervalStart, timeIntervalEnd, selection);
		if (selection.cloudFiles.isEmpty()) {
			return Collections.emptyList();
		}
		return Arrays.asList(account.fileSystemModel().deleteCloudFiles(
				toCloudFileArray(selection.cloudFiles),
				new ArrayList<Long>(selection.snapshotTimes)));
	}

	/**
	 * Deletes an explicit list of cloud file paths in one call.
	 *
	 * <p>Resolves every path with {@code includingSubdirectories=true} (so a single
	 * file path matches directly), then invokes {@code deleteCloudFiles} once on
	 * the combined {@link CloudFile} set.
	 *
	 * @param cloudPaths cloud file paths to delete (must not be null or empty)
	 * @param timeIntervalStart Deletes versions created on or after this timestamp (optional)
	 * @param timeIntervalEnd Deletes versions created on or before this timestamp (optional)
	 * @return A list of {@link CloudFileOperationStatus} detailing the operation state
	 */
	public List<CloudFileOperationStatus> deletePaths(String[] cloudPaths, String timeIntervalStart, String timeIntervalEnd) {
		if (cloudPaths == null || cloudPaths.length == 0) {
			return Collections.emptyList();
		}
		CloudFileSelection selection = collectCloudFilesForPaths(cloudPaths, timeIntervalStart, timeIntervalEnd);
		if (selection.cloudFiles.isEmpty()) {
			return Collections.emptyList();
		}
		return Arrays.asList(account.fileSystemModel().deleteCloudFiles(
				toCloudFileArray(selection.cloudFiles),
				new ArrayList<Long>(selection.snapshotTimes)));
	}

	/**
	 * Shares files or directories securely with other AltaStata users.
	 *
	 * Uses public-key cryptography (RSA/PQC) to encrypt the AES keys of the specified files
	 * for the public keys of the requested users.
	 * 
	 * @param cloudPathPrefix The path prefix of the files to share
	 * @param includingSubdirectories True to share recursively
	 * @param timeIntervalStart Include files created on or after this timestamp
	 * @param timeIntervalEnd Include files created on or before this timestamp
	 * @param users The list of usernames (email addresses or User IDs) to share with
	 * @return A list of {@link CloudFileOperationStatus} detailing the operation state
	 */
	public List<CloudFileOperationStatus> share(String cloudPathPrefix, Boolean includingSubdirectories, String timeIntervalStart, String timeIntervalEnd, String[] users) {
		CloudFileSelection selection = new CloudFileSelection();
		collectCloudFilesForPrefix(cloudPathPrefix, includingSubdirectories, timeIntervalStart, timeIntervalEnd, selection);
		if (selection.cloudFiles.isEmpty()) {
			return Collections.emptyList();
		}
		return Arrays.asList(account.fileSystemModel().shareCloudFiles(
				toCloudFileArray(selection.cloudFiles),
				users,
				new ArrayList<Long>(selection.snapshotTimes)));
	}

	/**
	 * Shares an explicit list of cloud file paths in one call.
	 *
	 * <p>Resolves every path with {@code includingSubdirectories=true}, then invokes
	 * {@code shareCloudFiles} once on the combined {@link CloudFile} set.
	 */
	public List<CloudFileOperationStatus> sharePaths(String[] cloudPaths, String timeIntervalStart, String timeIntervalEnd, String[] users) {
		if (cloudPaths == null || cloudPaths.length == 0) {
			return Collections.emptyList();
		}
		CloudFileSelection selection = collectCloudFilesForPaths(cloudPaths, timeIntervalStart, timeIntervalEnd);
		if (selection.cloudFiles.isEmpty()) {
			return Collections.emptyList();
		}
		return Arrays.asList(account.fileSystemModel().shareCloudFiles(
				toCloudFileArray(selection.cloudFiles),
				users,
				new ArrayList<Long>(selection.snapshotTimes)));
	}

	/**
	 * Revokes read access from users for specific files or directories.
	 * 
	 * Removes the encrypted data attributes that grant the specified users access
	 * to the encryption keys of the matching files.
	 * 
	 * @param cloudPathPrefix The path prefix of the files to revoke access from
	 * @param includingSubdirectories True to revoke recursively
	 * @param timeIntervalStart Include files created on or after this timestamp
	 * @param timeIntervalEnd Include files created on or before this timestamp
	 * @param readersToRevoke The list of usernames to revoke access from
	 * @return A list of {@link CloudFileOperationStatus} detailing the operation state
	 */
	public List<CloudFileOperationStatus> revokeReaderAccess(String cloudPathPrefix, Boolean includingSubdirectories, String timeIntervalStart, String timeIntervalEnd, String[] readersToRevoke) {
		CloudFileSelection selection = new CloudFileSelection();
		collectCloudFilesForPrefix(cloudPathPrefix, includingSubdirectories, timeIntervalStart, timeIntervalEnd, selection);
		if (selection.cloudFiles.isEmpty()) {
			return Collections.emptyList();
		}
		return Arrays.asList(account.fileSystemModel().revokeReaderAccess(
				toCloudFileArray(selection.cloudFiles),
				readersToRevoke,
				new ArrayList<Long>(selection.snapshotTimes)));
	}

	/**
	 * Revokes readers from an explicit list of cloud file paths in one call.
	 *
	 * <p>Resolves every path with {@code includingSubdirectories=true}, then invokes
	 * {@code revokeReaderAccess} once on the combined {@link CloudFile} set.
	 */
	public List<CloudFileOperationStatus> revokePaths(String[] cloudPaths, String timeIntervalStart, String timeIntervalEnd, String[] readersToRevoke) {
		if (cloudPaths == null || cloudPaths.length == 0) {
			return Collections.emptyList();
		}
		CloudFileSelection selection = collectCloudFilesForPaths(cloudPaths, timeIntervalStart, timeIntervalEnd);
		if (selection.cloudFiles.isEmpty()) {
			return Collections.emptyList();
		}
		return Arrays.asList(account.fileSystemModel().revokeReaderAccess(
				toCloudFileArray(selection.cloudFiles),
				readersToRevoke,
				new ArrayList<Long>(selection.snapshotTimes)));
	}
	
	/**
	 * Renames a cloud file or directory.
	 * 
	 * Note: Depending on the backend cloud provider and configuration, a rename might be 
	 * implemented as a copy-then-delete operation.
	 * 
	 * @param cloudPathPrefix The path prefix of the file or directory to rename
	 * @param includingSubdirectories True to rename recursively
	 * @param timeIntervalStart Include files created on or after this timestamp
	 * @param timeIntervalEnd Include files created on or before this timestamp
	 * @param oldNamePrefix The old prefix to match
	 * @param newNamePrefix The new prefix to replace it with
	 * @return A list of {@link CloudFileOperationStatus} detailing the operation state
	 */
	public List<CloudFileOperationStatus> rename(String cloudPathPrefix, Boolean includingSubdirectories, String timeIntervalStart, String timeIntervalEnd, String oldNamePrefix, String newNamePrefix) {
		if (!includingSubdirectories && cloudPathPrefix.endsWith("/") == false) {
			cloudPathPrefix += "/";
		}

		Iterator<CloudFile> it = 
				account.fileSystemModel().listCloudFiles(cloudPathPrefix, true, null, null, false);
		
		List<CloudFile> objectsToRename = new ArrayList<CloudFile>();
		TreeSet<Long> snapshotTimes = new TreeSet<Long>();

		while (it.hasNext()) {
			CloudFile cf = it.next();
			
			for (VersionAttributes version : cf.getVersions()) {
				Long createTime = version.getCreateTime();
				
				if ((timeIntervalStart == null || createTime.toString().compareTo(timeIntervalStart) >= 0) && 
						(timeIntervalEnd == null || createTime.toString().compareTo(timeIntervalEnd) <= 0)) {
					snapshotTimes.add(createTime);
				}
			}
			
			objectsToRename.add(cf);
		}
		
		CloudFile[] objectsToRenameArray = objectsToRename.toArray(new CloudFile[objectsToRename.size()]);

		return Arrays.asList(account.fileSystemModel().renameCloudFiles(objectsToRenameArray, oldNamePrefix, newNamePrefix, new ArrayList<Long>(snapshotTimes)));
	}
	
	/**
	 * Lists all users who share files with the current account, or who are in the 
	 * current account's address book.
	 * 
	 * @return A list of usernames (email addresses or User IDs)
	 */
	public List<String> listUsers() {
		Iterator<String> it = account.fileSystemModel().listUsers();
		
		List<String> users = new ArrayList<String>();
		while (it.hasNext()) {
			String next = it.next();
			if (!next.equals(account.CUSTODIAN_USER())) {
				users.add(next);
			}
		}
		
		return users;
	}
	
	/**
	 * Returns JSON with FileAttribute like file size or readers
	 * 
	 * @param cloudFilePath The file path on the cloud
	 * @param createTime File versions creation time should match this snapshot
	    * @param name Can be "size", "readers", "eTag", "s3metadata"
	 * @return JSON
	 */
	public String getFileAttribute(String cloudFilePath, Long createTime, String name) {
		// Use getFileAttributes with a single attribute name
		List<String> names = Arrays.asList(name);
		Map<String, String> attributesMap = getFileAttributes(cloudFilePath, createTime, names);
		
		if (attributesMap != null) {
			return attributesMap.get(name);
		}
		return null;
	}

	/**
	 * Returns JSON with multiple FileAttributes like file size, readers, eTag, s3metadata
	 * 
	 * @param cloudFilePath The file path on the cloud
	 * @param createTime File versions creation time should match this snapshot
	    * @param names List of attribute names like ["size", "readers", "eTag", "s3metadata"]
	 * @return JSON object with all requested attributes
	 */
	public Map<String, String> getFileAttributes(String cloudFilePath, Long createTime, List<String> names) {
		// Check if we already have the full path with version (contains file mark)
		if (cloudFilePath.contains(FILE_MARK_SIGN)) {
			// Fast path: Direct attributes retrieval without expensive file listing
			LOGGER.debug("Fast path: Direct attribute retrieval for {}", cloudFilePath);

			CloudFile cloudFile = account.getFileSystemHandler().parseObjectPathIncludingVersion(cloudFilePath);

			// Get all data attributes in parallel
			Map<String, String> attributesMap = account.fileSystemModel().getDataAttributesForCloudFile(cloudFile, createTime, names);

			// Return the Map directly
			return attributesMap;
		}
		else {
			// Standard path: Need to list files and resolve versions
			LOGGER.debug("Standard path: Resolving version for {}", cloudFilePath);
			Iterator<CloudFile> it =
					account.fileSystemModel().listCloudFiles(cloudFilePath, true, null, null, true);

			if (it.hasNext()) {
				CloudFile cloudFile = it.next();

				if (createTime == null) {
					createTime = cloudFile.getVersions().last().getCreateTime();
				}

				// Get all data attributes in parallel
				Map<String, String> attributesMap = account.fileSystemModel().getDataAttributesForCloudFile(cloudFile, createTime, names);

				// Return the Map directly
				return attributesMap;
			}
			else {
				return null;
			}
			}
	}

	/**
	 * Set a custom attribute for a cloud file
	 * 
	 * @param cloudFilePath The file path on the cloud
	 * @param createTime File versions creation time. Use null for latest version.
	 * @param name The attribute name
	 * @param value The attribute value as a String (can be JSON, Long, or plain String)
	 */
	public void setFileAttribute(String cloudFilePath, Long createTime, String name, String value) {
		Iterator<CloudFile> it = 
				account.fileSystemModel().listCloudFiles(cloudFilePath, true, null, null, true);

		if (it.hasNext()) {
			CloudFile cloudFile = it.next();

			// Always use the latest version for attribute setting
			createTime = cloudFile.getVersions().last().getCreateTime();

			// Pass the string value directly - the Scala implementation will handle DataAttribute creation
			account.fileSystemModel().setDataAttributeForCloudFile(cloudFile, createTime, name, value);
		}
	}

	/**
	 * Delete a custom attribute for a cloud file
	 * 
	 * @param cloudFilePath The file path on the cloud
	 * @param createTime File versions creation time. Use null for latest version.
	 * @param name The attribute name
	 */
	public void deleteFileAttribute(String cloudFilePath, Long createTime, String name) {
		Iterator<CloudFile> it = 
				account.fileSystemModel().listCloudFiles(cloudFilePath, true, null, null, true);

		if (it.hasNext()) {
			CloudFile cloudFile = it.next();

			// Always use the latest version for attribute deletion
			createTime = cloudFile.getVersions().last().getCreateTime();

			account.fileSystemModel().deleteDataAttributeForCloudFile(cloudFile, createTime, name);
		}
	}
	
	/**
	 * Create and return InputStream for AltaStata file
	 * 
	 * @param cloudFilePath The file path on the cloud
	 * @param snapshotTime File versions creation time should match this snapshot or be last nearest. Use current time for default.
	 * @param startPosition Starting position in the stream
	 * @param howManyChunksInParallel The number of chunks that can be retrieved in parallel
	 * @return InputStream to read the file content
	 */
	public InputStream getFileInputStream(String cloudFilePath, Long snapshotTime, Long startPosition, int howManyChunksInParallel) {
		return getFileInputStream(cloudFilePath, snapshotTime, startPosition, howManyChunksInParallel, false);
	}

	/**
	 * Same as {@link #getFileInputStream(String, Long, Long, Long, int)} but with an opt-in
	 * {@code trustCachedSize} flag. When {@code true}, the caller declares this file's content is
	 * immutable (write-once, no appends), so the per-open fresh cloud GET of the {@code size}
	 * attribute is skipped and the cached value is trusted — a big win for read-many workloads such
	 * as ML dataset epochs. On a cold cache it still fetches {@code size} fresh, so correctness is
	 * preserved. Default ({@code false}): always re-fetch {@code size} (mutable-file safe behavior).
	 */
	public InputStream getFileInputStream(String cloudFilePath, Long snapshotTime, Long startPosition, int howManyChunksInParallel, boolean trustCachedSize) {
		if (snapshotTime == null) {
			snapshotTime = System.currentTimeMillis();
		}
		return new AltaStataChunkedInputStream(cloudFilePath, startPosition, howManyChunksInParallel, snapshotTime, trustCachedSize, account);
	}

	/**
	 * Stream cloud file content directly to a local file without loading into heap.
	 * Java reads chunks from the cloud via AltaStataChunkedInputStream and writes
	 * them straight to disk. The caller is responsible for reading and deleting the file.
	 *
	 * @param outputFilePath  local file path to write to
	 * @param cloudFilePath   cloud object path
	 * @param snapshotTime    version timestamp (null = current time)
	 * @param startPosition   byte offset to start reading from
	 * @param howManyChunksInParallel chunk pre-fetch window
	 * @return number of bytes written
	 * @throws IOException on I/O error
	 */
	public long streamToFile(String outputFilePath, String cloudFilePath, Long snapshotTime, Long startPosition, int howManyChunksInParallel) throws IOException {
		return streamToFile(outputFilePath, cloudFilePath, snapshotTime, startPosition, howManyChunksInParallel, false);
	}

	/**
	 * Same as {@link #streamToFile(String, String, Long, Long, int)} but with the opt-in
	 * {@code trustCachedSize} flag (see {@link #getFileInputStream(String, Long, Long, int, boolean)}).
	 */
	public long streamToFile(String outputFilePath, String cloudFilePath, Long snapshotTime, Long startPosition, int howManyChunksInParallel, boolean trustCachedSize) throws IOException {
		try (InputStream in = getFileInputStream(cloudFilePath, snapshotTime, startPosition, howManyChunksInParallel, trustCachedSize);
			 java.io.FileOutputStream out = new java.io.FileOutputStream(outputFilePath)) {
			byte[] buf = new byte[Constants.PLAIN_CHUNK_MAX_SIZE()];
			long total = 0;
			int n;
			while ((n = in.read(buf)) != -1) {
				out.write(buf, 0, n);
				total += n;
			}
			return total;
		}
	}

	/**
	 * Read the buffer from InputStream, as py4j and some other frameworks do not support Java InputStream
	 *
	 * @param inputStream
	 * @param size
	 * @return
	 * @throws IOException
	 */
	public byte[] readBufferFromInputStream(InputStream inputStream, int size) throws IOException {
		if (size != -1) {
			byte[] buffer = new byte[size];

			// Read data into the buffer (returns the number of bytes read)
			int bytesRead = IOUtils.read(inputStream, buffer);

			if (bytesRead < size) {
				// If fewer bytes are read, reduce the size of the buffer
				byte[] actualData = new byte[bytesRead];
				System.arraycopy(buffer, 0, actualData, 0, bytesRead);
				buffer = actualData;
			}

			return buffer;
		}
		else
			return IOUtils.toByteArray(inputStream);
	}

	/**
	 * Read a chunk from the InputStream and return it as a Base64-encoded String.
	 *
	 * Py4J transfers {@code String} values ~10x faster than {@code byte[]}
	 * because byte arrays are serialized element-by-element.  This method
	 * lets Python stream large files chunk-by-chunk without touching the
	 * local filesystem while still avoiding slow {@code byte[]} transfers.
	 *
	 * Returns {@code null} when the stream is exhausted (0 bytes read).
	 *
	 * @param inputStream  the InputStream to read from
	 * @param size         maximum number of bytes to read in this chunk
	 * @return Base64-encoded chunk, or null at end-of-stream
	 * @throws IOException on I/O error
	 */
	public String readBufferFromInputStreamAsBase64(InputStream inputStream, int size) throws IOException {
		byte[] buffer = new byte[size];
		int bytesRead = IOUtils.read(inputStream, buffer);

		if (bytesRead <= 0) {
			return null;
		}

		if (bytesRead < size) {
			byte[] actualData = new byte[bytesRead];
			System.arraycopy(buffer, 0, actualData, 0, bytesRead);
			buffer = actualData;
		}

		return Base64.getEncoder().encodeToString(buffer);
	}

	/**
	 * Get byte buffer from AltaStata file
	 * 
	 * @param cloudFilePath The file path on the cloud
	 * @param snapshotTime File versions creation time should match this snapshot or be last nearest. Use current time for default.
	 * @param startPosition Starting position in the stream
	 * @param howManyChunksInParallel The number of chunks that can be retrieved in parallel
	 * @param size The size of the buffer, -1L if need to take the entire file
	 * @return The buffer
	 * @throws IOException
	 */
	public byte[] getBuffer(String cloudFilePath, Long snapshotTime, Long startPosition, int howManyChunksInParallel, int size) throws IOException {
		return getBuffer(cloudFilePath, snapshotTime, startPosition, howManyChunksInParallel, size, false);
	}

	/**
	 * Same as {@link #getBuffer(String, Long, Long, int, int)} but with the opt-in
	 * {@code trustCachedSize} flag (see {@link #getFileInputStream(String, Long, Long, int, boolean)}).
	 */
	public byte[] getBuffer(String cloudFilePath, Long snapshotTime, Long startPosition, int howManyChunksInParallel, int size, boolean trustCachedSize) throws IOException {
		InputStream inputStream = getFileInputStream(cloudFilePath, snapshotTime, startPosition, howManyChunksInParallel, trustCachedSize);
		return readBufferFromInputStream(inputStream, size);
	}

	/**
	 * Get file content as a Base64-encoded String.
	 *
	 * Py4J transfers String values far more efficiently than byte[] because
	 * byte arrays are serialized element-by-element in the Py4J protocol,
	 * whereas Strings use standard UTF encoding.  The ~33% Base64 size
	 * overhead is far outweighed by the faster wire transfer.
	 *
	 * @param cloudFilePath    cloud object path
	 * @param snapshotTime     version timestamp
	 * @param startPosition    byte offset to start reading
	 * @param howManyChunksInParallel  chunk pre-fetch window
	 * @param size             total bytes to read (-1 for entire file)
	 * @return Base64-encoded file content
	 * @throws IOException on I/O error
	 */
	public String getBufferAsBase64(String cloudFilePath, Long snapshotTime, Long startPosition, int howManyChunksInParallel, int size) throws IOException {
		return getBufferAsBase64(cloudFilePath, snapshotTime, startPosition, howManyChunksInParallel, size, false);
	}

	/**
	 * Same as {@link #getBufferAsBase64(String, Long, Long, int, int)} but with the opt-in
	 * {@code trustCachedSize} flag (see {@link #getFileInputStream(String, Long, Long, int, boolean)}).
	 */
	public String getBufferAsBase64(String cloudFilePath, Long snapshotTime, Long startPosition, int howManyChunksInParallel, int size, boolean trustCachedSize) throws IOException {
		byte[] data = getBuffer(cloudFilePath, snapshotTime, startPosition, howManyChunksInParallel, size, trustCachedSize);
		return Base64.getEncoder().encodeToString(data);
	}

	/**
	 * Create a new file version on cloud and add the buffer (may be empty)
	 * It adds the buffer very fast, but does not guarantee the streaming order
	 * If you want to append the buffer as a stream, create the empty file, and use appendBufferToFile
	 * 
	 * @param cloudFilePath The file path on the cloud
	 * @return The CloudFileOperationStatus
	 */
	public CloudFileOperationStatus createFile(String cloudFilePath, byte[] buffer) {
		CloudFile newCloudFile = account.getFileSystemHandler().createCloudFileVersion(cloudFilePath, false, System.currentTimeMillis());
		
		return account.fileSystemModel().storeByteBufferToCloudFile(ByteBuffer.wrap(buffer), newCloudFile, true);
	}

	/**
	 * Append the buffer as a output stream to the File version
	 * 
	 * @param cloudFilePath The file path on the cloud
	 * @param snapshotTime File versions creation time should match this snapshot or be last nearest. Use current time for default.
	 * @param buffer The buffer to append
	 * 
	 * @throws IOException
	 */
	public void appendBufferToFile(String cloudFilePath, Long snapshotTime, byte[] buffer) throws IOException {
		OutputStream outputStream = new AltaStataChunkedOutputStream(cloudFilePath, snapshotTime, true, account);
		
		try(InputStream in = new ByteArrayInputStream(buffer)) {
		    IOUtils.copy(in, outputStream);
		    
		    outputStream.close();
		}
	}
	
	/**
	 * Obtain OutputStream to write to the file
	 * 
	 * @param cloudFilePath The file path on the cloud
	 * @param snapshotTime File version creation time should match this snapshot or be last nearest. Use current time for default.
	 * @param isAppendingMode add to the existing content or start from the beginning
	 * @return OutputStream to write or append the file content
	 */
	public OutputStream getFileOutputStream(String cloudFilePath, Long snapshotTime, Boolean isAppendingMode) {
		return new AltaStataChunkedOutputStream(cloudFilePath, snapshotTime, isAppendingMode, account);
	}
	
	/**
	 * Add event listener for file share/delete, etc.
	 * 
	 * @param altaStataEventListener
	 */
	public void addAltaStataEventListener(AltaStataEventListener altaStataEventListener) {
		account.getFileSystemHandler().addAltaStataEventListener(altaStataEventListener);
    }

	/**
	 * Remove event listener for file share/delete, etc.
	 * 
	 * @param altaStataEventListener
	 */
	public void removeAltaStataEventListener(AltaStataEventListener altaStataEventListener) {
		account.getFileSystemHandler().removeAltaStataEventListener(altaStataEventListener);
    }
	
	/**
	 * Copy a file from one cloud path to another
	 * 
	 * @param fromCloudFilePath The source file path on the cloud
	 * @param toCloudFilePath The destination file path on the cloud
	 * @return The CloudFileOperationStatus of the copy operation
	 */
	public CloudFileOperationStatus copyFile(String fromCloudFilePath, String toCloudFilePath) {
		try {
			// Get input stream from source file
			InputStream inputStream = getFileInputStream(fromCloudFilePath, System.currentTimeMillis(), 0L, 4);
			
			// Create output stream for destination file (creates file if doesn't exist)
			OutputStream outputStream = getFileOutputStream(toCloudFilePath, System.currentTimeMillis(), false);
			
			try {
				// Copy data from input to output stream with explicit buffer
				byte[] buffer = new byte[Constants.PLAIN_CHUNK_MAX_SIZE()];
				int bytesRead;
				long totalBytesCopied = 0;
				
				while ((bytesRead = inputStream.read(buffer)) != -1) {
					outputStream.write(buffer, 0, bytesRead);
					totalBytesCopied += bytesRead;
					
					// Log progress for large files
					if (totalBytesCopied % (Constants.PLAIN_CHUNK_MAX_SIZE() * 25) == 0) { // Every 100MB
						LOGGER.info("AltaStata: Copied {} MB of {}/{}", totalBytesCopied / (1024 * 1024), fromCloudFilePath, toCloudFilePath);
					}
				}
				
				outputStream.flush();
				LOGGER.info("AltaStata: Successfully copied {} bytes from {} to {}", totalBytesCopied, fromCloudFilePath, toCloudFilePath);
				
				// Return success status
				CloudFileOperationStatus status = new CloudFileOperationStatus(toCloudFilePath, OperationState.DONE);
				return status;
				
			} finally {
				inputStream.close();
				outputStream.close();
			}
			
		} catch (Exception e) {
			LOGGER.error("AltaStata: Failed to copy file from {} to {}: {}", fromCloudFilePath, toCloudFilePath, e.getMessage(), e);
			
			// Return error status
			CloudFileOperationStatus status = new CloudFileOperationStatus(toCloudFilePath, OperationState.ERROR);
			status.setError(e);
			return status;
		}
	}

	/**
	 * Get AltaStata Key Service based on cloudFilePath and snapshotTime.
	 * If file does not exist, it will be created.
	 * 
	 * @param cloudFilePath The file path on the cloud
	 * @param snapshotTime File versions creation time should match this snapshot or be last nearest. Use current time for default.
	 */
	public AltaStataChannelEncryptionService getAltaStataEncryptionService(String cloudFilePath, Long snapshotTime) {
		return new AltaStataChannelEncryptionService(cloudFilePath, snapshotTime, account);
	}
		
	/**
	 * Converts an exception's stack trace into a detailed, newline-separated string.
	 *
	 * @param e the Throwable exception to convert
	 * @return a string containing the serialized stack trace
	 */
	private String stackTraceToString(Throwable e) {
	    StringBuilder sb = new StringBuilder();
	    for (StackTraceElement element : e.getStackTrace()) {
	        sb.append(element.toString());
	        sb.append("\n");
	    }
	    return sb.toString();
	}

	private static final class CloudFileSelection {
		private final List<CloudFile> cloudFiles = new ArrayList<CloudFile>();
		private final TreeSet<Long> snapshotTimes = new TreeSet<Long>();
	}

	private void collectCloudFilesForPrefix(
			String cloudPathPrefix,
			boolean includingSubdirectories,
			String timeIntervalStart,
			String timeIntervalEnd,
			CloudFileSelection selection) {
		String prefix = cloudPathPrefix;
		if (!includingSubdirectories && !prefix.endsWith("/")) {
			prefix += "/";
		}

		Iterator<CloudFile> it = account.fileSystemModel().listCloudFiles(
				prefix, includingSubdirectories, timeIntervalStart, timeIntervalEnd, false);

		while (it.hasNext()) {
			CloudFile cf = it.next();
			addCloudFileVersions(cf, timeIntervalStart, timeIntervalEnd, selection);
		}
	}

	private CloudFileSelection collectCloudFilesForPaths(
			String[] cloudPaths,
			String timeIntervalStart,
			String timeIntervalEnd) {
		CloudFileSelection selection = new CloudFileSelection();
		for (String path : cloudPaths) {
			collectCloudFilesForPrefix(path, true, timeIntervalStart, timeIntervalEnd, selection);
		}
		return selection;
	}

	private static void addCloudFileVersions(
			CloudFile cf,
			String timeIntervalStart,
			String timeIntervalEnd,
			CloudFileSelection selection) {
		for (VersionAttributes version : cf.getVersions()) {
			Long createTime = version.getCreateTime();
			if ((timeIntervalStart == null || createTime.toString().compareTo(timeIntervalStart) >= 0) &&
					(timeIntervalEnd == null || createTime.toString().compareTo(timeIntervalEnd) <= 0)) {
				selection.snapshotTimes.add(createTime);
			}
		}
		selection.cloudFiles.add(cf);
	}

	private static CloudFile[] toCloudFileArray(List<CloudFile> cloudFiles) {
		return cloudFiles.toArray(new CloudFile[cloudFiles.size()]);
	}
	
	private class FacadeIterator implements Iterator<String[]> {
	    private final Iterator<CloudFile> delegate;

	    /**
	     * Constructs a FacadeIterator wrapping a CloudFile iterator.
	     *
	     * @param delegate base CloudFile iterator
	     */
	    public FacadeIterator(Iterator<CloudFile> delegate) {
	    		this.delegate = delegate;
	    }
	    
	    /**
	     * Returns the serialized representations of the next item.
	     *
	     * @return array of version strings for file, or a single path string for directory
	     */
	    public String[] next() {
	    		List<String> versions = new ArrayList<String>();
	    		
	    		CloudFile cf = delegate.next();
	    		
	    		// if File
	    		if (cf.isDirectory() == false) {
	    			for (VersionAttributes version : cf.getVersions()) {
	    				versions.add(version.toString());
	    			}
	    			
	    			return versions.toArray(new String[versions.size()]);
	    		}
	    		// if Directory
	    		else {
	    			return new String[] {cf.getPath()};
	    		}
	    	}

		/**
		 * Checks if the iterator has more elements to process.
		 *
		 * @return true if there are more elements
		 */
		@Override
		public boolean hasNext() {
			return delegate.hasNext();
		}
	}
}

