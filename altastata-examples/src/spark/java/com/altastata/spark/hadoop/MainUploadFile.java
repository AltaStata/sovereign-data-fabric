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

package com.altastata.spark.hadoop;

import com.altastata.utils.Account;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;

public class MainUploadFile {

	/**
	 * Main execution entry point for uploading a file using the custom Apache Hadoop FileSystem implementation
	 * integrated with AltaStata.
	 *
	 * @param args {@code password [localPath [destPath]]}
	 *             Default dest is {@code /Applications/britannica.txt} when a local path is given,
	 *             otherwise {@code /sample_video.mp4}.
	 */
	public static void main(String[] args) throws IOException, URISyntaxException {
		String accountDir = Account.ALTASTATA_ACCOUNTS_HOME() + File.separator + "amazon.rsa.bob123";
		String password = args[0];
		String localPath = args.length > 1 ? args[1] : System.getenv("ALTASTATA_UPLOAD_LOCAL");
		if (localPath == null || localPath.isEmpty()) {
			localPath = System.getProperty("user.home") + "/Desktop/sample_video.mp4";
		}
		String destPath = args.length > 2 ? args[2] : System.getenv("ALTASTATA_UPLOAD_DEST");
		if (destPath == null || destPath.isEmpty()) {
			destPath = "/Applications/britannica.txt";
		}

		Configuration hadoopConfig = new Configuration();
		hadoopConfig.set("fs.altastata.impl", org.apache.hadoop.fs.altastata.AltaStataHadoopFileSystem.class.getName());
		hadoopConfig.set("altastata.account.home", accountDir);
		hadoopConfig.set("altastata.account.password", password);

		System.out.println("copyFromLocalFile " + localPath + " -> altastata://" + destPath);

		try (FileSystem fs = FileSystem.get(new URI("altastata:///"), hadoopConfig)) {
			fs.copyFromLocalFile(false, true, new Path(localPath), new Path(destPath));
			System.out.println("done, length=" + fs.getFileStatus(new Path(destPath)).getLen());
		}
	}
}
