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

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;

import java.io.IOException;

/**
 * Simple test utility class to verify the connection and basic operations
 * of the custom AltaStata Hadoop FileSystem implementation.
 */
public class TestAltaStataFS {

	/**
	 * Main execution entrypoint for testing AltaStata Hadoop FileSystem integration.
	 *
	 * @param args CommandLine arguments (ignored).
	 * @throws IOException If any FileSystem or network error occurs.
	 */
	public static void main(String[] args) throws IOException {
		Configuration conf = new Configuration();
		
		conf.set("fs.altastata.impl", "org.apache.hadoop.fs.altastata.AltaStataHadoopFileSystem");
		conf.set("fs.defaultFS", "altastata://nameNode:9000");
		
		/**	    
		 * conf.addResource(new Path("/hadoop/projects/hadoop-1.0.4/conf/core-site.xml"));
		 * conf.addResource(new Path("/hadoop/projects/hadoop-1.0.4/conf/hdfs-site.xml"));
		 */
		
		FileSystem fs = FileSystem.get(conf);
				
		System.out.println(fs.getName());
		
		Path fspath = new Path("eee/aaa");
		
		fs.setWorkingDirectory(fspath);
		
//		RemoteIterator<LocatedFileStatus> locatedFiles = fs.listFiles(fspath, false);
//				
//		while (locatedFiles.hasNext()) {
//			LocatedFileStatus locatedFile = locatedFiles.next();
//			System.out.println(locatedFile.getPath() + " " + locatedFile.isFile());
//		}
//		
//		FSDataInputStream inputStream = fs.open(new Path("java/TestLocalFS.java"));
//		
//		System.out.println("available: " + inputStream.available());
//		
//		String myString = IOUtils.toString(inputStream, "UTF-8");
//		
//		System.out.println("myString: " + myString);

		fs.close();

	}
}
