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

package com.altastata.restapi.spark;

import com.altastata.api.AccountRegistry;
import com.altastata.api.AltaStataFileSystem;
import org.apache.commons.io.IOUtils;

import javax.servlet.MultipartConfigElement;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.NoSuchElementException;

import static spark.Spark.*;

public class VPCRestAPI {

	/**
	 * Main execution entry point for the VPCRestAPI server. Sets up routes and handler endpoints
	 * for secure cloud uploading, downloading, and authentication.
	 *
	 * @param args command-line arguments (args[0] must be the AltaStata account directory path)
	 */
	public static void main(String[] args) {
		String accountDirPath = args[0];

		System.out.println("VPCRestAPI: " + accountDirPath);

		/* */
		AltaStataFileSystem altaStataFileSystem =
				AccountRegistry.getOrCreateFromDir(accountDirPath);

		altaStataFileSystem.setPassword("123");
		/* */

		post("/upload_file", (req, res) -> {

			System.out.println("upload_file req: " + req.queryParams("file_path"));

			String filePath = req.queryParams("file_path");

			System.out.println("upload_file filePath: " + filePath);

			req.attribute("org.eclipse.jetty.multipartConfig", new MultipartConfigElement("/temp"));
			try (InputStream is = req.raw().getPart(filePath).getInputStream()) {
				byte[] bytes = IOUtils.toByteArray(is);
				/* */
				filePath = filePath.replace(System.getProperty("user.home") + "/simulator_files", "Public/Documents");

				altaStataFileSystem.createFile(filePath, bytes);
				/* */

				/*
				Path realFilePath = Paths.get(filePath);
				Files.write(realFilePath, bytes, StandardOpenOption.CREATE);
				 */
			}

			return "File uploaded";
		});

		get("/get_file", (req, res) -> {

			System.out.println("get_file req: " + req.queryParams("file_path"));

			String filePath = req.queryParams("file_path");

			System.out.println("get_file filePath: " + filePath);

			try {
				/* */
				filePath = filePath.replace(System.getProperty("user.home") + "/simulator_files", "Public/Documents");

				InputStream is =
						altaStataFileSystem.getFileInputStream(filePath, System.currentTimeMillis(), 0L, 10);
				/* */

				/*
				InputStream is = new FileInputStream(filePath);
				 */

				res.type("application/octet-stream");
				res.header("Content-Disposition", "attachment; filename=\"" + filePath + "\"");

				String result = IOUtils.toString(is, StandardCharsets.UTF_8);

				return result;
			}
			catch (FileNotFoundException | NoSuchElementException ex) {
				return halt(404, "File Not Found");
			}
		});

				/*
		get("/list", (req, res) -> {

			System.out.println("req: " + req.queryParams("with_subdirectories"));
			System.out.println("res: " + res.toString());

			boolean includingSubdirectories = false;
			if (req.queryParams("with_subdirectories") != null &&
					Boolean.valueOf(req.queryParams("with_subdirectories")) == true) {
				includingSubdirectories = true;
			}

			System.out.println("includingSubdirectories: " + includingSubdirectories);

			JSONArray allDataArray = new JSONArray();

			Iterator<String[]> itFiles =
							altaStataFileSystem.listCloudFilesVersions("",
									includingSubdirectories,
									null,
									null);

			while (itFiles.hasNext()) {
				String[] list = itFiles.next();

				allDataArray.put(list[0]);
			}

			JSONObject root = new JSONObject();
			try {
				root.put("list", allDataArray);
			} catch (JSONException e) {
				e.printStackTrace();
			}

			return root.toString();
		});
		 */
	}
}
