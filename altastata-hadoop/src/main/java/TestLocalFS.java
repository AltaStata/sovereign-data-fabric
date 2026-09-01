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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

import org.apache.commons.io.IOUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.LocatedFileStatus;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.RemoteIterator;

/**
 * Simple test utility class to verify basic operations against the Hadoop Local FileSystem.
 */
public class TestLocalFS {

	/**
	 * Main execution entrypoint for testing standard Local Hadoop FileSystem interactions.
	 *
	 * @param args local directory to list and read from
	 * @throws IOException If any FileSystem or IO error occurs.
	 */
	public static void main(String[] args) throws IOException {
		if (args == null || args.length < 1) {
			System.err.println("Usage: TestLocalFS <local-directory>");
			System.exit(1);
		}

		Configuration conf = new Configuration();
		
		/**	    
		 * conf.addResource(new Path("/hadoop/projects/hadoop-1.0.4/conf/core-site.xml"));
		 * conf.addResource(new Path("/hadoop/projects/hadoop-1.0.4/conf/hdfs-site.xml"));
		 */
		
		Path fspath = new Path(args[0]);
		
		FileSystem fs = fspath.getFileSystem(conf);
		fs.setWorkingDirectory(fspath);
		
		RemoteIterator<LocatedFileStatus> locatedFiles = fs.listFiles(fspath, false);
				
		while (locatedFiles.hasNext()) {
			LocatedFileStatus locatedFile = locatedFiles.next();
			System.out.println(locatedFile.getPath() + " " + locatedFile.isFile());
		}
		
		FSDataInputStream inputStream = fs.open(new Path("java/TestLocalFS.java"));
		
		System.out.println("available: " + inputStream.available());
		
		String myString = IOUtils.toString(inputStream, "UTF-8");
		
		System.out.println("myString: " + myString);

		fs.close();

	}
}
