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

import java.io.Console;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

/**
 * Example CLI client illustrating how a recipient user ("Alice") lists and retrieves/downloads
 * secure cloud files shared with her by another user.
 */
public class AliceRetrieve {
	
	static Account account = new Account();
	
	/**
	 * Main execution routine for Alice's secure retrieve demo.
	 * Loads properties, handles password input, awaits and parses shared catalog entries,
	 * and downloads shared contents locally.
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
		
		// wait for 10 seconds to check that all the shared files are valid
		try {
			Thread.sleep(10 * 1000);
		}
		catch (InterruptedException ignore) {}
		
		System.out.println("listCloudFiles: testdirectory");
		
		// list the files that were uploaded by bob123 and shared with me through the scala program
		List<CloudFile> toRetrieve = new ArrayList<CloudFile>(); 					
		
		Iterator<CloudFile> it = account.fileSystemModel().listCloudFiles("testdirectory", true, null, null, true);
		while (it.hasNext()) {
			CloudFile cloudFile = it.next();			
			System.out.println("\tList: " + cloudFile.getLastCloudObjectPathIncludingVersion());
			toRetrieve.add(cloudFile);
		}

		if (toRetrieve.size() > 0) {
			
			// download the shared files
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
		}
	}

}
