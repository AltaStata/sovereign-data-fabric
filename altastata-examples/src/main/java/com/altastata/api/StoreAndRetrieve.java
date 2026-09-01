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

import com.altastata.api.AltaStataFileSystem.OperationState;
import com.altastata.filesystem.common.CloudFile;
import com.altastata.utils.Account;
import scala.Tuple2;

import java.io.Console;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;


/**
 * Example CLI client demonstrating basic upload (store), download (retrieve),
 * and subsequent deletion of a secure cloud file.
 */
public class StoreAndRetrieve {

	static Account account = new Account();
	
	/**
	 * Main execution routine for the StoreAndRetrieve demo.
	 * Loads account properties, prompts/resolves master password, lists active catalog elements,
	 * uploads a local file, downloads it, and finally deletes it from cloud storage.
	 *
	 * @param args command-line arguments, where args[0] is the master account decryption password
	 * @throws Exception if an error occurs during properties resolution or decryption
	 */
	public static void main(String[] args) throws Exception {
		String outputDir = "tmp/";
				
		account.loadAccountProperties(account.ALTASTATA_ACCOUNTS_HOME() + File.separator + "amazon.rsa.alice222");

		Console console = System.console();
        if (console == null) {
    		account.setPassword(args[0].toCharArray());
        }
        else {
        	char passwordArray[] = console.readPassword("Enter your secret password: ");
        	account.setPassword(passwordArray);
        }

		// list all files
		Iterator<CloudFile> it = account.fileSystemModel().listCloudFiles("", true, null, null, true);
		while (it.hasNext()) {
			System.out.println("\tList: " + it.next().getLastCloudObjectPathIncludingVersion());
		}

		// store one file
		List<Tuple2<File, CloudFile>> list = new ArrayList<Tuple2<File, CloudFile>>(); 
		
		File file = new File("altastata-examples/files/video_streaming.png");
		CloudFile cloudFile = account.getFileSystemHandler().createCloudFileVersion(file.getPath().replace("files/", ""), file.isDirectory(), System.currentTimeMillis());
		
		list.add(new Tuple2<File, CloudFile>(file, cloudFile));

		List<CloudFile> toRetrieve = new ArrayList<CloudFile>(); 
		
		CloudFileOperationStatus[] storeResults = 
				account.fileSystemModel().uploadLocalFilesToCloud(list, true);	
		
		for (int i = 0; i < storeResults.length; i++) {
			if (storeResults[i].getOperationState().equals(OperationState.DONE)) {
				toRetrieve.add(account.getFileSystemHandler().parseObjectPathIncludingVersion(storeResults[i].getCloudFileVersionPath()));
				System.out.println("store right: " + storeResults[i].getCloudFileVersionPath());
			}
			else {
				System.out.println("store left: " + storeResults[i].getCloudFileVersionPath() + "error: " + storeResults[i].getError());
			}										
		}

		Thread.sleep(2000);
		
		// retrieve stored file
		List<Long> timestampFilter = Arrays.asList(System.currentTimeMillis());

		CloudFileOperationStatus[] retrieveResults = 
				account.fileSystemModel().retrieveCloudFilesToLocalDirectory(toRetrieve.toArray(new CloudFile[toRetrieve.size()]), outputDir, timestampFilter, false, true, false);
		
		for (int i = 0; i < retrieveResults.length; i++) {
			if (retrieveResults[i].getOperationState().equals(OperationState.DONE)) {
				System.out.println("retrieve right: " + retrieveResults[i].getCloudFileVersionPath());
			}
			else {
				System.out.println("retrieve left: " + retrieveResults[i].getCloudFileVersionPath() + "error: " + retrieveResults[i].getError());
			}										
		}

		Thread.sleep(5000);

		// delete stored file
		CloudFileOperationStatus[] deleteResults = 
				account.fileSystemModel().deleteCloudFiles(toRetrieve.toArray(new CloudFile[toRetrieve.size()]), timestampFilter);
		
		for (int i = 0; i < deleteResults.length; i++) {
			if (deleteResults[i].getOperationState().equals(OperationState.DONE)) {
				System.out.println("delete right: " + deleteResults[i].getCloudFileVersionPath());
			}
			else {
				System.out.println("delete left: " + deleteResults[i].getCloudFileVersionPath() + " error: " + deleteResults[i].getError() + "\n" + deleteResults[i].getError());
			}						
		}
	}

}
