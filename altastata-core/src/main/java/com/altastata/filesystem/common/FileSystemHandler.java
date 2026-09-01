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

package com.altastata.filesystem.common;

import com.altastata.api.AltaStataEvent;
import com.altastata.api.AltaStataEventListener;
import com.altastata.api.AltaStataFileSystem;
import com.altastata.api.AltaStataFileSystem.OperationState;
import com.altastata.utils.Account;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.filefilter.HiddenFileFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scala.Tuple2;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;

/**
 * Core handler managing the lifecycle, resolution, and event dispatching for files within the file system.
 *
 * `FileSystemHandler` acts as the orchestrator connecting raw cloud object models to 
 * application-level behavior. It tracks files currently in use, manages files mid-upload, 
 * computes and filters file timestamps (versions), and resolves nested directory trees.
 *
 * It is also responsible for converting string paths and cloud object keys into `CloudFile` 
 * domain objects, handling path obfuscation logic behind the scenes.
 */
public class FileSystemHandler {

	public static final int TIMESTAMP_DETECTIONS_THREADS_POOL_SIZE = 100;

	private ExecutorService timeStampsExecutorService =
			Executors.newFixedThreadPool(TIMESTAMP_DETECTIONS_THREADS_POOL_SIZE);

	private Logger LOGGER = LoggerFactory.getLogger(FileSystemHandler.class);
	public Account account = null;

	public static String INIT_DIR = "";

	private List<AltaStataEventListener>  altaStataEventListeners = new ArrayList<AltaStataEventListener>();

	private ConcurrentMap<String, CloudFile> cloudFilesInUse = new ConcurrentHashMap<String, CloudFile>();

	// When the files are no in process anymore, they should be extracted from this data structure
	private ConcurrentMap<String, CloudFile> cloudFilesInUploadingProcess = new ConcurrentHashMap<>();

	/**
	 * Constructs a new FileSystemHandler associated with a specific user Account.
	 *
	 * @param account the user account context
	 */
	public FileSystemHandler(Account account) {
		this.account = account;
	}

	/**
	 * Initializes the file system handler, resetting and clearing all active file registries and event listeners.
	 */
	public void init() {
		altaStataEventListeners.clear();
		cloudFilesInUploadingProcess.clear();
		cloudFilesInUse.clear();
	}

	/**
	 * Extracts all unique timestamps (creation times) from the specified file trees.
	 *
	 * This is typically used to build historical version timelines. It scans the provided 
	 * directories concurrently, retrieving metadata to construct a comprehensive map of 
	 * file paths to their available versions.
	 *
	 * @param origFiles The list of root files or directories to scan.
	 * @param onlyLatestVersion If true, only the most recent timestamp for each file is returned.
	 * @return A sorted list of unique epoch timestamps representing file versions.
	 */
	public List<Long> detectTimestamps(CloudFile[] origFiles, boolean onlyLatestVersion) {
		Map<String, List<Long>> allTimestampsForTree = detectTimestampsPerFile(origFiles);

		return detectTimestamps(allTimestampsForTree, onlyLatestVersion);
	}

	/**
	 * Filters and sorts unique epoch timestamps from a map of file paths to their version timestamps.
	 *
	 * @param allTimestampsForTree A map representing cloud file paths and their associated list of version timestamps.
	 * @param onlyLatestVersion If true, filters down to only the most recent timestamp for each file path.
	 * @return A list of sorted, unique epoch timestamps.
	 */
	public List<Long> detectTimestamps(Map<String, List<Long>> allTimestampsForTree, boolean onlyLatestVersion) {
		Set<Long> resultingSet = new TreeSet<Long>();

		// Iterate over key-value pairs using entrySet
		for (Map.Entry<String, List<Long>> entry : allTimestampsForTree.entrySet()) {
			String cloudFilePath = entry.getKey();
			List<Long> timestampsList = entry.getValue();

			if (onlyLatestVersion == false) {
				resultingSet.addAll(timestampsList);
			}
			else {
				resultingSet.add(timestampsList.get(timestampsList.size() - 1));
			}
		}

		return new ArrayList<>(resultingSet);
	}

	/**
	 * Detects and maps all creation timestamps (versions) for each of the given files.
	 * Launches parallel tasks using the timestamps thread pool for efficient scanning.
	 *
	 * @param origFiles The array of target files.
	 * @return A map of cloud file paths to their sorted list of version timestamps.
	 */
	public Map<String, List<Long>> detectTimestampsPerFile(CloudFile[] origFiles) {
		LOGGER.trace("detectTimestamps origFiles[]: " + origFiles.length);

		List<Future<Map<String, List<Long>>>> futures = new ArrayList<>();

		// Submit tasks for each CloudFile
		for (CloudFile cloudFile : origFiles) {
			Future<Map<String, List<Long>>> future = timeStampsExecutorService.submit(new Callable<Map<String, List<Long>>>() {
				@Override
				public Map<String, List<Long>> call() {
					return detectTimestamps(cloudFile);
				}
			});
			futures.add(future);
		}

		// Collect results
		Map<String, List<Long>> allTimestamps = new TreeMap<String, List<Long>>();
		for (Future<Map<String, List<Long>>> future : futures) {
			try {
				allTimestamps.putAll(future.get());
			} catch (InterruptedException ex) {
				LOGGER.error("Task was interrupted", ex);
				Thread.currentThread().interrupt();
			} catch (ExecutionException ex) {
				LOGGER.error("Task execution failed", ex);
			}
		}

		return allTimestamps;
	}

	/**
	 * Scans the cloud storage for all versions of a specific file or folder (root),
	 * collecting their creation epoch timestamps.
	 *
	 * @param root The root CloudFile to detect timestamps for.
	 * @return A map mapping the file path to its list of version timestamps.
	 */
	private Map<String, List<Long>> detectTimestamps(CloudFile root) {
		LOGGER.trace("detectTimestamps: " + root);

		Map<String, List<Long>> collectingResults = new TreeMap<String, List<Long>>();
		Iterator<CloudFile> it = account.fileSystemModel().listCloudFiles(root.getPath(), true, null, null, true);

		while (it.hasNext()) {
			CloudFile cf = it.next();
			List<Long> timestamps = new ArrayList<>();

			for (VersionAttributes versionAttributes : cf.getVersions()) {
				timestamps.add(versionAttributes.getCreateTime());
			}

			// Find if the same cloudFile is already in process
			CloudFile cfInUploadingProcess = cloudFilesInUploadingProcess.get(cf.getPath());
			if (cfInUploadingProcess != null) {
				for (VersionAttributes versionAttributes : cfInUploadingProcess.getVersions()) {
					if (!cf.getVersions().contains(versionAttributes)) {
						timestamps.add(versionAttributes.getCreateTime());
					}
				}
			}

			if (timestamps.isEmpty() == false) {
				collectingResults.put(cf.getPath(), timestamps);
			}
		}

		return collectingResults;
	}

	/**
	 * Resolves a list of files or directories into a complete set of individual `CloudFile` objects, 
	 * optionally flattening subdirectories.
	 *
	 * It intelligently decides whether to list each subtree individually or to issue a bulk `LIST` 
	 * operation against a common prefix (e.g., when deleting multiple files in the same folder).
	 *
	 * @param origFiles The selected input files/directories.
	 * @param setDirectoryOperationState The state to apply to directories (e.g., DOWNLOADING, DELETING).
	 * @param timestampsFilter Specifies which historical versions should be included.
	 * @return A flattened array of fully resolved `CloudFile` objects.
	 */
	public CloudFile[] getWithSubtrees(CloudFile[] origFiles, OperationState setDirectoryOperationState, List<Long> timestampsFilter) {
		Set<CloudFile> list = new TreeSet<CloudFile>();
		if (shouldListSubtreeInBulk(origFiles)) {
			collectSubtreeInBulk(origFiles, list, setDirectoryOperationState, timestampsFilter);
		} else {
			for (CloudFile cloudFile : origFiles) {
				getSubtree(cloudFile, list, setDirectoryOperationState, timestampsFilter);
			}
		}

		return list.toArray(new CloudFile[list.size()]);
	}

	/**
	 * Many individual files (after a prefix catalog LIST): one LIST under their common
	 * directory prefix resolves merged versions — not one LIST per file path.
	 */
	private static boolean shouldListSubtreeInBulk(CloudFile[] origFiles) {
		if (origFiles == null || origFiles.length <= 1) {
			return false;
		}
		for (CloudFile cf : origFiles) {
			if (cf.isDirectory()) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Computes the longest common directory prefix of a list of cloud files.
	 *
	 * @param files The array of CloudFiles.
	 * @return The longest common prefix string.
	 */
	static String longestCommonDirectoryPrefix(CloudFile[] files) {
		if (files == null || files.length == 0) {
			return "";
		}
		String prefix = files[0].getPath();
		for (int i = 1; i < files.length; i++) {
			String path = files[i].getPath();
			while (prefix.length() > 0 && !path.startsWith(prefix)) {
				int slash = prefix.lastIndexOf('/');
				prefix = slash > 0 ? prefix.substring(0, slash) : "";
			}
		}
		return prefix;
	}

	/**
	 * Recursively resolves and filters the subtrees of multiple files in a single bulk operation.
	 */
	private void collectSubtreeInBulk(CloudFile[] origFiles, Set<CloudFile> collectingResults,
			OperationState setDirectoryOperationState, List<Long> timestampsFilter) {
		Set<String> targetPaths = new HashSet<String>();
		for (CloudFile cf : origFiles) {
			targetPaths.add(cf.getPath());
		}
		Set<Long> timestampFilterSet = toTimestampSet(timestampsFilter);
		Long downloadingUpperBound = toDownloadingUpperBound(setDirectoryOperationState, timestampsFilter);

		String dirPrefix = longestCommonDirectoryPrefix(origFiles);
		String searchPattern = dirPrefix.isEmpty() ? "" : dirPrefix + "/";
		CloudFile root = dirPrefix.isEmpty() ? null : new CloudFile(dirPrefix, true);
		if (root != null) {
			root.setOperationStateValue(setDirectoryOperationState);
		}

		Iterator<CloudFile> it =
				account.fileSystemModel().listCloudFiles(searchPattern, true, null, null, true);

		while (it.hasNext()) {
			CloudFile cf = it.next();
			if (!targetPaths.contains(cf.getPath())) {
				continue;
			}
			processListedCloudFile(
					cf,
					root,
					collectingResults,
					setDirectoryOperationState,
					timestampFilterSet,
					downloadingUpperBound);
		}
	}

	/**
	 * Populates the `collectingResults` set with all CloudFiles nested under the specified root directory.
	 */
	private void getSubtree(CloudFile root, Set<CloudFile> collectingResults, OperationState setDirectoryOperationState, List<Long> timestampsFilter) {
		String searchPattern = root.getPath();
		Set<Long> timestampFilterSet = toTimestampSet(timestampsFilter);
		Long downloadingUpperBound = toDownloadingUpperBound(setDirectoryOperationState, timestampsFilter);

		if (root.isDirectory()) {
			root.setOperationStateValue(setDirectoryOperationState);

			searchPattern += "/";
		}

		Iterator<CloudFile> it =
				account.fileSystemModel().listCloudFiles(searchPattern, true, null, null, true);

		while (it.hasNext()) {
			CloudFile cf = it.next();
			// Prefix listing of a file path also returns siblings (`file` vs `file2`).
			if (!root.isDirectory() && !cf.getPath().equals(root.getPath())) {
				continue;
			}
			processListedCloudFile(
					cf,
					root,
					collectingResults,
					setDirectoryOperationState,
					timestampFilterSet,
					downloadingUpperBound);
		}
	}

	/**
	 * Processes a listed CloudFile: updates its cached versions based on real data, merges
	 * in-process versions, filters by timestamp, and registers its parent directory hierarchy if matched.
	 */
	private void processListedCloudFile(CloudFile cf, CloudFile root, Set<CloudFile> collectingResults,
			OperationState setDirectoryOperationState, Set<Long> timestampFilterSet, Long downloadingUpperBound) {
		cf = updateCachedVersionsBasedOnRealData(cf.getPath(), cf);
		cf = mergeInProcessVersions(cf);

		boolean matchTimestamps = matchesTimestampFilter(
				cf,
				setDirectoryOperationState,
				timestampFilterSet,
				downloadingUpperBound);

		if (matchTimestamps) {
			collectingResults.add(cf.setOperationStateValue(setDirectoryOperationState));

			if (root != null) {
				String parent = cf.getParent();
				while (parent != null && parent.startsWith(root.getPath())) {
					CloudFile parentDir = cloudFilesInUse.computeIfAbsent(parent, p -> new CloudFile(p, true));
					parentDir.setOperationStateValue(setDirectoryOperationState);

					parent = parentDir.getParent();
				}
			}
		}
	}

	/**
	 * Merges any active in-process uploads (mid-transfer versions) for the given CloudFile
	 * into its resolved versions list to ensure real-time consistency.
	 */
	private CloudFile mergeInProcessVersions(CloudFile cloudFile) {
		CloudFile cfInProcess = cloudFilesInUploadingProcess.get(cloudFile.getPath());
		if (cfInProcess == null) {
			return cloudFile;
		}
		for (VersionAttributes version : cfInProcess.getVersions()) {
			if (!cloudFile.getVersions().contains(version)) {
				cloudFile.getVersions().add(version);
			}
		}
		return cloudFile;
	}

	/**
	 * Converts a list of Long timestamps into a Set for O(1) lookups.
	 */
	private Set<Long> toTimestampSet(List<Long> timestampsFilter) {
		if (timestampsFilter == null) {
			return null;
		}
		return new HashSet<Long>(timestampsFilter);
	}

	/**
	 * Resolves the downloading upper bound timestamp from a filter list.
	 */
	private Long toDownloadingUpperBound(OperationState operationState, List<Long> timestampsFilter) {
		if (!OperationState.DOWNLOADING.equals(operationState) || timestampsFilter == null || timestampsFilter.isEmpty()) {
			return null;
		}
		long max = Long.MIN_VALUE;
		for (Long ts : timestampsFilter) {
			if (ts != null && ts > max) {
				max = ts;
			}
		}
		return max == Long.MIN_VALUE ? null : max;
	}

	/**
	 * Determines whether the CloudFile matches the active version filters.
	 */
	private boolean matchesTimestampFilter(
			CloudFile cloudFile,
			OperationState operationState,
			Set<Long> timestampFilterSet,
			Long downloadingUpperBound) {
		if (timestampFilterSet == null) {
			return true;
		}

		if (OperationState.DOWNLOADING.equals(operationState)) {
			if (downloadingUpperBound == null) {
				return false;
			}
			for (VersionAttributes version : cloudFile.getVersions()) {
				if (version.getCreateTime() <= downloadingUpperBound) {
					return true;
				}
			}
			return false;
		}

		for (VersionAttributes version : cloudFile.getVersions()) {
			if (timestampFilterSet.contains(version.getCreateTime())) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Registers a cloud file as currently undergoing an uploading process.
	 * Merges any new version attributes into an existing cached CloudFile if already present.
	 *
	 * @param cf The CloudFile being uploaded.
	 * @return The synchronized CloudFile instance mapped in use.
	 */
	public CloudFile addCloudFileInUploadingProcess(CloudFile cf) {
		// find if the same cloudFile is already cached
		CloudFile cfInUse = cloudFilesInUse.compute(cf.getPath(), (key, existingCf) -> {
			if (existingCf != null) {
				for (VersionAttributes version : cf.getVersions()) {
					if (!existingCf.getVersions().contains(version)) {
						// update a new version
						existingCf.addVersion(version);
					}
				}
				return existingCf;
			} else {
				return cf;
			}
		});

		cloudFilesInUploadingProcess.put(cf.getPath(), cfInUse);

		return cfInUse;
	}

	/**
	 * Removes a cloud file from the active uploading registry upon completion or cancellation.
	 *
	 * @param cf The CloudFile to remove.
	 */
	public void removeCloudFileInUploadingProcess(CloudFile cf) {
		cloudFilesInUploadingProcess.remove(cf.getPath());
	}

	/**
	 * Finds and resolves the canonical, synchronized CloudFile for the given input file.
	 * If the file is not a directory, queries the cloud storage to obtain the real metadata 
	 * and version attributes, updating the memory cache.
	 *
	 * @param cloudFile The template CloudFile containing at least the target path.
	 * @return The populated, canonical CloudFile, or null if the file does not exist on the cloud.
	 */
	public CloudFile findCloudFile(CloudFile cloudFile) {
		if (cloudFile.isDirectory()) {
			if (cloudFilesInUse.containsKey(cloudFile.getPath())) {
				return cloudFilesInUse.get(cloudFile.getPath());
			}
			else {
				return null;
			}
		}
		else {
			Iterator<CloudFile> it =
					account.fileSystemModel().listCloudFiles(cloudFile.getPath(), true, null, null, true);

			if (it.hasNext()) {
				CloudFile cf = it.next();

				if (!cf.getPath().equals(cloudFile.getPath())) {
					return null;
				}

				cf = updateCachedVersionsBasedOnRealData(cf.getPath(), cf);

				return cf;
			} else {
				return null;
			}
		}
	}

	/**
	 * Synchronizes and updates the cached set of versions in the in-memory cache
	 * using the real, authoritative metadata fetched from the cloud.
	 */
	private CloudFile updateCachedVersionsBasedOnRealData(String fileName, CloudFile realCloudFile) {

		CloudFile cachedCloudFile = cloudFilesInUse.putIfAbsent(fileName, realCloudFile);
		if (cachedCloudFile == null) {
			return realCloudFile;
		}
		else {
			//System.out.println("cachedCloudFile: " + cachedCloudFile + " realCloudFile: " + realCloudFile);

			Set<Long> cachedVersionsCreateTimes = new HashSet<Long>();
			for (VersionAttributes cachedVersion: cachedCloudFile.getVersions()) {
				cachedVersionsCreateTimes.add(cachedVersion.getCreateTime());
			}

			Set<Long> realVersionsCreateTimes = new HashSet<Long>();
			for (VersionAttributes realVersion: realCloudFile.getVersions()) {
				realVersionsCreateTimes.add(realVersion.getCreateTime());
			}

			// add real version to the cached file
			for (VersionAttributes realVersion: realCloudFile.getVersions()) {
				if (!cachedVersionsCreateTimes.contains(realVersion.getCreateTime())) {
					cachedCloudFile.addVersion(realVersion);
				}
			}

			// remove the cached version if it's not real
			for (VersionAttributes cachedVersion: cachedCloudFile.getVersions()) {

				//System.out.println("Times: " + realVersionsCreateTimes);
				//System.out.println("cachedVersion.getCreateTime(): " + cachedVersion.getCreateTime());

				/*
				 TODO: Theoretically it may be race condition, but I think ConcurrentMap for cloudFilesInUploadingProcess is ok
				 */
				if (!realVersionsCreateTimes.contains(cachedVersion.getCreateTime()) &&
						cloudFilesInUploadingProcess.get(fileName) == null) {
					//System.out.println("RemoveVersion: " + cachedVersion.getCreateTime());
					cachedCloudFile.removeVersion(cachedVersion.getCreateTime());
				}
			}

			return cachedCloudFile;
		}
	}

	/**
	 * Searches the entire secure cloud filesystem recursively for files whose names or paths
	 * match the given case-insensitive query string.
	 *
	 * @param filter The substring to filter file paths.
	 * @return A sorted set of matching {@link CloudFile} instances.
	 */
	public Set<CloudFile> searchFiles(String filter) {
		Set<CloudFile> foundList = new TreeSet<CloudFile>();

		Iterator<CloudFile> it =
				account.fileSystemModel().listCloudFiles("", true, null, null, true);

		while (it.hasNext()) {
			CloudFile cf = it.next();

			if (cf.getPath().toLowerCase().contains(filter.toLowerCase())) {
				foundList.add(cf);
			}
		}

		return foundList;
	}

	
	/**
	 * Cleans up local data structures (like `cloudFilesInUse` and `cloudFilesInUploadingProcess`) 
	 * after a file deletion completes.
	 *
	 * It intelligently traverses the directory tree upwards. If a file still has versions remaining 
	 * (i.e., not fully deleted), it ensures parent directories are kept alive. If the file is fully 
	 * deleted, it removes it from local caches and recursively removes empty parent directories 
	 * that were also marked for deletion.
	 *
	 * @param cloudFile The file that was just deleted or had versions removed.
	 */
	public synchronized void tryToDeleteFile(CloudFile cloudFile) {
	   // if only some versions were deleted
	   if (cloudFile.getVersions().size() > 0) {
		   			   			   
	       // recursively update ancestors operation states from DELETING to NONE as their subtrees are exist
		   CloudFile ancestor = getParent(cloudFile);				
		   while (ancestor != null && ancestor.getOperationState().equals(OperationState.DELETING)) {				        	
				ancestor.setOperationStateValue(OperationState.NONE);
					
				// check the upper directory if its empty
				ancestor = getParent(ancestor);
		   }		   
	   }
	   else {				
			while (cloudFile != null) {
				// delete cloud file from the cloudFilesInUploadingProcess
				cloudFilesInUploadingProcess.remove(cloudFile.getPath());

				cloudFilesInUse.remove(cloudFile.getPath());

				CloudFile parent = getParent(cloudFile);
			    
			    if (parent == null || !parent.getOperationState().equals(OperationState.DELETING)) {
			    		break;
			    }

				// check cloudFile's siblings
				for (CloudFile cf: cloudFilesInUse.values()) {
					// if one of the siblings files is still processing, stop trying
					if (cf.getParent().equals(cloudFile.getParent()) &&
							cf.getOperationState() == OperationState.DELETING) {
						return;
					}
				}

				parent.setOperationStateValue(OperationState.DELETED);

				cloudFile = parent;
			}
	   }
	   	    
	   // delete the file, version or the most upper ancestor
	   cloudFile.setOperationStateValue(OperationState.DELETED);
	}

	/**
	 * Checks recursively if a local directory is completely empty or contains only other empty directories.
	 */
	private static boolean isDirectoryEmpty(File dir) {
		// Check if the directory itself is empty
		File[] files = dir.listFiles();
		if (files == null || files.length == 0) {
			return true;
		}

		// Check if all contents are empty directories
		for (File file : files) {
			if (file.isFile() || !isDirectoryEmpty(file)) {
				return false; // Found a file or a non-empty directory
			}
		}

		return true; // All contents are empty directories
	}

	/**
	 * Recursively walks a list of local files or directories and maps them to their
	 * respective target cloud destination paths, returning a structured list of file pairs.
	 *
	 * Supports multi-level nesting. Strips a local base via {@link Path#relativize} (not
	 * {@link String#replace}) so path mapping cannot accidentally rewrite mid-string.
	 * When roots come from different folders, each root falls back to its own parent as base.
	 *
	 * @param selectedLocalFilesOrDirs The root local files or directories to map.
	 * @param baseLocalDir The local path prefix to strip when roots share that folder.
	 * @param targetCloudDir The cloud path prefix to substitute in place of baseLocalDir.
	 * @param createTime The epoch timestamp to assign to the new file versions.
	 * @return A list of Scala {@link Tuple2} mapping each local File to its destination {@link CloudFile}.
	 */
	public List<Tuple2<File, CloudFile>> mapFilesTreeToCloudFileList(List<File> selectedLocalFilesOrDirs, String baseLocalDir, String targetCloudDir, long createTime) {
		List<Tuple2<File, CloudFile>> files = new ArrayList<Tuple2<File, CloudFile>>();

		if (selectedLocalFilesOrDirs == null || selectedLocalFilesOrDirs.isEmpty()) {
			return files;
		}

		String sharedBase = baseLocalDir != null ? baseLocalDir : "";
		String target = targetCloudDir != null ? targetCloudDir : "";

		for (File root : selectedLocalFilesOrDirs) {
			if (root == null) {
				continue;
			}

			String effectiveBase = sharedBase;
			String rootPath = root.getAbsolutePath();
			if (!isPathUnderBase(rootPath, effectiveBase)) {
				File parent = root.getParentFile();
				effectiveBase = parent != null ? parent.getAbsolutePath() : "";
			}

			List<File> filesAndDirsList = new ArrayList<File>();

			if (root.isDirectory()) {
				Collection<File> filesAndDirs = FileUtils.listFilesAndDirs(
						root,
						HiddenFileFilter.VISIBLE,
						HiddenFileFilter.VISIBLE);

				filesAndDirs.removeIf(file -> file.isDirectory() && isDirectoryEmpty(file));
				filesAndDirsList.addAll(filesAndDirs);
			} else {
				filesAndDirsList.add(root);
			}

			for (File fileOrDir : filesAndDirsList) {
				String cloudFilePath = toCloudPath(fileOrDir.getAbsolutePath(), effectiveBase, target);
				files.add(new Tuple2<File, CloudFile>(fileOrDir,
						createCloudFileVersion(cloudFilePath, fileOrDir.isDirectory(), createTime)));
			}
		}

		return files;
	}

	/** True if {@code absolutePath} is {@code base} or a descendant (separator-safe). */
	private static boolean isPathUnderBase(String absolutePath, String base) {
		if (absolutePath == null) {
			return false;
		}
		if (base == null || base.isEmpty()) {
			return true;
		}
		if (absolutePath.equals(base)) {
			return true;
		}
		String prefix = base.endsWith(File.separator) ? base : base + File.separator;
		return absolutePath.startsWith(prefix);
	}

	/**
	 * Maps an absolute local path to a cloud path by relativizing against {@code localBase}
	 * and joining under {@code targetCloudDir}.
	 */
	private static String toCloudPath(String absolutePath, String localBase, String targetCloudDir) {
		Path abs = Paths.get(absolutePath).toAbsolutePath().normalize();
		String relUnix;
		if (localBase == null || localBase.isEmpty()) {
			relUnix = abs.getFileName() != null ? abs.getFileName().toString() : abs.toString();
		} else {
			Path base = Paths.get(localBase).toAbsolutePath().normalize();
			Path rel;
			try {
				rel = base.relativize(abs);
			} catch (IllegalArgumentException ex) {
				throw new IllegalArgumentException(absolutePath + " does not contain path: " + localBase, ex);
			}
			if (rel.startsWith("..")) {
				throw new IllegalArgumentException(absolutePath + " does not contain path: " + localBase);
			}
			relUnix = FilenameUtils.separatorsToUnix(rel.toString());
		}

		String target = FilenameUtils.separatorsToUnix(targetCloudDir != null ? targetCloudDir : "");
		if (target.isEmpty()) {
			return relUnix;
		}
		if (relUnix.isEmpty()) {
			return target;
		}
		if (target.endsWith("/")) {
			return target + relUnix;
		}
		return target + "/" + relUnix;
	}

	/**
	 * Instantiates a fresh {@link CloudFile} model object and configures it with an initial
	 * {@link VersionAttributes} version using the given timestamp if it is not a directory.
	 *
	 * @param cloudFilePath The target cloud path.
	 * @param isDirectory True if the file represents a directory.
	 * @param createTime The epoch creation timestamp for the version.
	 * @return The constructed CloudFile instance.
	 */
	public CloudFile createCloudFileVersion(String cloudFilePath, boolean isDirectory, long createTime) {
		CloudFile cloudFile = new CloudFile(cloudFilePath, isDirectory);

		if (isDirectory == false) {
	        VersionAttributes versionAttributes = new VersionAttributes(cloudFile, createTime, account);
	        cloudFile.addVersion(versionAttributes);
		}
		
		return cloudFile;
	}

	/**
	 * Lists all cloud files located inside the specified directory path that are currently in use
	 * or active (e.g. uploading or downloading).
	 *
	 * @param path The parent directory path.
	 * @return An ordered collection of active CloudFiles.
	 */
	public Collection<CloudFile> listDirectoryInUse(String path) {
		Set<CloudFile> collection = new TreeSet<CloudFile>();

		for (CloudFile cf: cloudFilesInUse.values()) {
			if (cf.getParent().equals(path)) {
				collection.add(cf);
			}
		}

		return collection;
	}
	
	/**
	 * Lists all cloud files located inside the specified directory path.
	 * Merges active/in-progress versions with the real versions stored in cloud storage,
	 * ensuring a unified real-time representation of directories.
	 *
	 * @param path The parent directory path.
	 * @return An ordered collection of visible CloudFiles.
	 */
	public Collection<CloudFile> listDirectory(String path) {
		TreeSet<CloudFile> last = new TreeSet<>();
		listDirectory(path, batch -> {
			last.clear();
			if (batch != null) {
				last.addAll(Arrays.asList(batch));
			}
		});
		return last;
	}

	/**
	 * Lists a directory and invokes {@code onPartial} with growing snapshots so the UI can
	 * paint the first results before the full cloud list finishes.
	 *
	 * @param path parent directory path
	 * @param onPartial called with a sorted snapshot (first item ASAP, then ~every 100 items / 150ms, then final)
	 */
	public void listDirectory(String path, java.util.function.Consumer<CloudFile[]> onPartial) {
		LOGGER.trace("listDirectory: " + path);

		if (account.MY_USER() == null) {
			if (onPartial != null) {
				onPartial.accept(new CloudFile[0]);
			}
			return;
		}

		Set<CloudFile> collection = new TreeSet<CloudFile>();
		final int batchSize = 100;
		final long minFlushMs = 150L;
		int sinceFlush = 0;
		long lastFlushMs = 0L;

		Runnable flush = () -> {
			if (onPartial != null) {
				onPartial.accept(collection.toArray(new CloudFile[0]));
			}
		};

		Iterator<CloudFile> it =
				account.fileSystemModel().listCloudFiles(path.equals("") ? path : path + "/", false, null, null, true);

		if (it != null) {
			while (it.hasNext()) {
				CloudFile cf = it.next();

				cf = updateCachedVersionsBasedOnRealData(cf.getPath(), cf);

				if (cf.getPath().startsWith(".")) {
					continue;
				}

				cf = mergeInProcessVersions(cf);
				collection.add(cf);
				sinceFlush++;

				long now = System.currentTimeMillis();
				if (collection.size() == 1 || sinceFlush >= batchSize || now - lastFlushMs >= minFlushMs) {
					flush.run();
					sinceFlush = 0;
					lastFlushMs = now;
				}
			}
		}

		for (CloudFile cfInUploadingProcess : cloudFilesInUploadingProcess.values()) {
			if (cfInUploadingProcess.getParent().equals(path) && !collection.contains(cfInUploadingProcess)) {
				collection.add(cfInUploadingProcess);
			}
		}

		addStandardDirectories(path, collection);
		flush.run();
	}

	/**
	 * Populates standard, structural platform directory entries (e.g. Public, Users)
	 * depending on the current browsing location path.
	 */
	private void addStandardDirectories(String path, Set<CloudFile> collection) {
		// Add standard directories
		switch (path) {
			case "":
				addStandardDirectory(collection, "Public");
				addStandardDirectory(collection, "Users");

				break;

			case "Public":
				addStandardDirectory(collection, "Public/Projects");
				addStandardDirectory(collection, "Public/Documents");

				break;

			case "Users":
				addStandardDirectory(collection, "Users/" + account.MY_USER());

				break;
		}
	}

	/**
	 * Resolves or caches a standard system directory, then adds it to the target collection.
	 */
	private void addStandardDirectory(Set<CloudFile> collection, String aPublic) {
		CloudFile cloudFilePublic = cloudFilesInUse.computeIfAbsent(aPublic, p -> new CloudFile(p, true));
		collection.add(cloudFilePublic);
	}

	/**
	 * Locates or builds a parent directory CloudFile reference for the given CloudFile.
	 */
	private synchronized CloudFile getParent(CloudFile cloudFile) {
		if (cloudFile.getParent().equals("")) {
			return null;
		}
		else if (cloudFilesInUse.containsKey(cloudFile.getParent())) {
			return cloudFilesInUse.get(cloudFile.getParent());
		}
		else if (cloudFilesInUploadingProcess.containsKey(cloudFile.getParent())) {
			return cloudFilesInUploadingProcess.get(cloudFile.getParent());
		}
		else {
			return new CloudFile(cloudFile.getParent(), true);
		}
	}
	
	/**
	 * Recursively resets ancestor directory operation states to {@link OperationState#NONE} 
	 * once all sibling transfers/operations nested under them have finished successfully.
	 *
	 * This prevents parent folders from remaining in transient/busy states (such as DOWNLOADING 
	 * or UPLOADING) in the UI or client logic when the actual work is completed.
	 *
	 * @param cloudFile The child {@link CloudFile} that has completed its active operation.
	 */
	public synchronized void attemptToSetAncestorsOperationStateAsNONE(CloudFile cloudFile) {
		CloudFile parentCloudFile = cloudFilesInUse.get(cloudFile.getParent());
		if (parentCloudFile == null || parentCloudFile.getOperationState().equals(OperationState.NONE)) {
			return;
		}

		for (CloudFile sibling: cloudFilesInUse.values()) {
			// if one of the sibling's files is still processing, stop attempting
			if (sibling.getParent().equals(cloudFile.getParent()) && sibling.getOperationState() != OperationState.NONE) {

				if (sibling.isDirectory()) {
					// check its children, maybe they are all NONE, and sibling should be updated
					for (CloudFile nephew : cloudFilesInUse.values()) {
						if (sibling.getPath().equals(nephew.getParent()) &&
								nephew.getOperationState() != OperationState.NONE) {
							return;
						}
					}

					sibling.setOperationStateValue(OperationState.NONE);
				}
				else {
					return;
				}
			}
		}

		// check my siblings, maybe there are directories that should be NONE
		for (CloudFile cf: cloudFilesInUse.values()) {
			// if one of the siblings files is still processing, stop attempting
			if (cf.getParent().equals(cloudFile.getParent()) && cf.getOperationState() != OperationState.NONE) {
				return;
			}
		}

		// set parent Operation Value as NONE
		parentCloudFile.setOperationStateValue(OperationState.NONE);

		attemptToSetAncestorsOperationStateAsNONE(parentCloudFile);
	}
	
	/**
	 * Parses a storage file path tuple (consisting of the file path and creation date)
	 * and returns a populated {@link CloudFile} instance with corresponding {@link VersionAttributes}.
	 *
	 * @param t Tuple of storage file path and its creation date.
	 * @return A constructed and version-configured CloudFile.
	 */
	public CloudFile parseStorageFilePath(Tuple2<String, Date> t) {
		CloudFile cf = new CloudFile(t._1(), false);
		
		VersionAttributes versionAttributes = new VersionAttributes(cf, t._2().getTime(), account);

		cf.addVersion(versionAttributes);
		
		return cf;
	}
	
	/**
	 * Parses a raw cloud object key including its unique version suffix tag (e.g. `path/to/file✣tag_timestamp`)
	 * and returns the decoded {@link CloudFile} along with its populated {@link VersionAttributes}.
	 *
	 * @param objectPathIncludingVersion The raw cloud object path string.
	 * @return The parsed CloudFile with populated version attributes.
	 */
	public CloudFile parseObjectPathIncludingVersion(String objectPathIncludingVersion) {
		try {
			CloudFile cf = null;

			int lastSlashIndex = objectPathIncludingVersion.lastIndexOf(AltaStataFileSystem.FILE_MARK_SIGN);
			if (lastSlashIndex > 0) {
				cf = new CloudFile(objectPathIncludingVersion.substring(0, lastSlashIndex), false);
				
				try {
					String versionPart = objectPathIncludingVersion.substring(lastSlashIndex + AltaStataFileSystem.FILE_MARK_SIGN.length());
					if (versionPart != null) {								
						String [] versionParts = versionPart.split("_");

						VersionAttributes versionAttributes = new VersionAttributes(cf, Long.parseLong(versionParts[1]), account);

						versionAttributes.setTag(versionParts[0]);
						
						cf.addVersion(versionAttributes);
					}
				}
				catch (IndexOutOfBoundsException ex1) {
					LOGGER.trace("parseObjectPathIncludingVersion - cannot read the version: " + objectPathIncludingVersion);				
				}
			}
			else { // its directory path
				String dirPath = objectPathIncludingVersion;
				
				// remove last '/' if exists
				if (dirPath.lastIndexOf('/') == dirPath.length() - 1) {
					dirPath = dirPath.substring(0, dirPath.length() - 1);
				}
				
				cf = new CloudFile(dirPath, true);
			}
						
			return cf;
		}
		catch (Throwable ex) {
			LOGGER.error("parseObjectPathIncludingVersion - cannot read the file: " + objectPathIncludingVersion, ex);
			return null;
		}
	}
		
	/**
	 * Registers an event listener to receive system, operation, and metadata notifications.
	 *
	 * @param altaStataEventListener The event listener to register.
	 */
	public void addAltaStataEventListener(AltaStataEventListener altaStataEventListener) {
		altaStataEventListeners.add(altaStataEventListener);
    }

	/**
	 * Removes a previously registered event listener.
	 *
	 * @param altaStataEventListener The event listener to unregister.
	 */
	public void removeAltaStataEventListener(AltaStataEventListener altaStataEventListener) {
		altaStataEventListeners.remove(altaStataEventListener);
    }

	/**
	 * Publishes and dispatches a system event to all registered listeners.
	 *
	 * @param altaStataEvent The system event to dispatch.
	 */
	public void fetchAltaStataEvent(AltaStataEvent altaStataEvent) {
		for (AltaStataEventListener altaStataEventListener : altaStataEventListeners) {
			altaStataEventListener.notify(altaStataEvent);
		}
	}

	/**
	 * Resolves a relative cloud object name under {@code outputDir}.
	 * Rejects absolute paths and {@code ..} escapes so downloads cannot write outside the chosen directory.
	 */
	public static String resolveDownloadPathInsideOutputDir(String outputDir, String relativePath) {
		if (relativePath == null || relativePath.trim().isEmpty()) {
			throw new SecurityException("Download relative path is empty");
		}
		if (relativePath.indexOf(0) >= 0) {
			throw new SecurityException("Download relative path contains NUL");
		}

		Path relative = Paths.get(relativePath.replace('\\', '/'));
		if (relative.isAbsolute()) {
			throw new SecurityException("Download path must be relative: " + relativePath);
		}

		Path root = Paths.get(outputDir).toAbsolutePath().normalize();
		Path candidate = root.resolve(relative).normalize();
		if (!candidate.startsWith(root)) {
			throw new SecurityException("Download path escapes output directory: " + relativePath);
		}
		return candidate.toString();
	}
}
