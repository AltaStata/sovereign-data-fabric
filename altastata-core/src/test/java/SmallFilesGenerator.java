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

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

public class SmallFilesGenerator {

	public static void writeFile(String outputFolderPath, String fileName, String contents) throws IOException {

	    BufferedWriter bw = null;

	    try {
	        File folder = new File( String.format("%1$s", outputFolderPath) );

	        if (! folder.exists()) {
	            folder.mkdirs();
	        } 

	        File file = new File( String.format("%1$s/%2$s", folder.getAbsolutePath(), fileName) );

	        // if file doesnt exists, then create it
	        if (!file.exists()) {
	            file.createNewFile();
	            FileWriter fw = new FileWriter(file.getAbsoluteFile());
	            bw = new BufferedWriter(fw);
	            bw.write(contents);
	            bw.close();
	        }
	    } catch (IOException e) {
	        e.printStackTrace();
	    } finally {
	        try {
	            if (bw != null) bw.close();
	        } catch (IOException ex) {
	            ex.printStackTrace();
	        }
	    }
	}
	
	public static void main(String[] args) throws IOException {
        String outputFolderPath = "/tmp/many/smallfiles";
        
        for (int i = 0; i < 10; i++) {
        	for (int j = 0; j < 5000; j++) {
        		writeFile(outputFolderPath + i, String.format("%03d", j) + "file.txt", "stam");
        	}
        }
	}

}
