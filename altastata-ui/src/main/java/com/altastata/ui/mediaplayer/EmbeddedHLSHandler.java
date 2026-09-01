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

package com.altastata.ui.mediaplayer;

import com.altastata.api.AltaStataFileSystem.OperationState;
import com.altastata.api.CloudFileOperationStatus;
import com.altastata.filesystem.common.CloudFile;
import com.altastata.utils.Account;
import com.altastata.utils.Constants;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.*;
import java.util.Base64.Encoder;

/**
 * Custom {@link HttpHandler} that hosts a micro loopback HTTP Server to stream secure 
 * encrypted video files as segments on-the-fly directly into native HTML5 or JavaFX 
 * media players, bypassing local disk storage.
 * 
 * <p>Implements on-demand chunk buffering using background producer threads.</p>
 * 
 * <p>See: https://stackoverflow.com/questions/19702543/play-a-video-without-a-file-on-disk-java</p>
 * 
 * <p>See also: https://stackoverflow.com/questions/28427339/how-to-implement-http-byte-range-requests-in-spring-mvc</p>
 */
public class EmbeddedHLSHandler implements HttpHandler {

	private static final int HOW_MANY_CHUNKS_IN_BUFFER = 3;
	private static final int DELAY_TO_WAIT = 50;
	private static final int HOW_MANY_BUFFERS_TO_PRODUCE = 3;

	static HttpServer server = null;

	static Account account = new Account();

	private static Logger LOGGER = LoggerFactory.getLogger(EmbeddedHLSHandler.class);

	List<ByteBuffer> buffersList = Collections.synchronizedList(new ArrayList<ByteBuffer>());
	
	CloudFile cloudFile = null;

	boolean finished = false;

	static {
		try {
			server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 8093), 0);
			server.setExecutor(null); // creates a default executor
			server.start();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	/**
	 * Creates a new EmbeddedHLSHandler instance to stream the specified cloud file.
	 * Starts a background producer thread to buffer decryptable file segments.
	 *
	 * @param cloudFile The target {@link CloudFile} metadata object to stream.
	 */
	public EmbeddedHLSHandler(CloudFile cloudFile) {
		this.cloudFile = cloudFile;

		new ProducerThread().start();
	}
	
	class ProducerThread extends Thread {

	    public void run(){
			long chunkNumber = 0;
			while (true) {
				if (buffersList.size() < HOW_MANY_BUFFERS_TO_PRODUCE) {
											
					readBuffer(cloudFile, chunkNumber);
					
					chunkNumber += HOW_MANY_CHUNKS_IN_BUFFER;
				}
				
				try {
					Thread.sleep(DELAY_TO_WAIT);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
			}
	    }
	}

	/**
	 * Checks whether the cloud file segment downloading process has fully completed or failed.
	 *
	 * @return {@code true} if downloading is finished, {@code false} otherwise.
	 */
	public boolean isFinished() {
		return finished;
	}

	/**
	 * Sets the complete state indicator.
	 *
	 * @param finished The finished flag value to apply.
	 */
	public void setFinished(boolean finished) {
		this.finished = finished;
	}

	/**
	 * Fetches contiguous blocks of chunks from the secure cloud backend and caches 
	 * the decrypted raw payloads inside a memory-mapped ByteBuffer list.
	 *
	 * @param cloudFile   The representing cloud file.
	 * @param chunkNumber The absolute chunk index boundary.
	 */
	private void readBuffer(CloudFile cloudFile, long chunkNumber) {
		ByteBuffer buffer = ByteBuffer.allocate(Constants.PLAIN_CHUNK_MAX_SIZE() * HOW_MANY_CHUNKS_IN_BUFFER);

		List<Long> timestamps = new ArrayList<Long>();
		timestamps.add(cloudFile.getVersions().last().getCreateTime());

		CloudFileOperationStatus retrieveResult = account.fileSystemModel()
				.retrieveCloudFileToByteBuffer(buffer, cloudFile, timestamps, chunkNumber, null, true, false);

		if (retrieveResult.getOperationState().equals(OperationState.DONE)) {
			System.out.println("EmbeddedHLSHandler retrieve right: " + retrieveResult.getCloudFileVersionPath());
			buffersList.add(buffer);
		}
		else {
			System.out.println("EmbeddedHLSHandler retrieve left: " + retrieveResult.getCloudFileVersionPath() + "error: " + retrieveResult.getError());
			
			LOGGER.error("EmbeddedHLSHandler retrieve left: " + retrieveResult.getError());
			
			buffersList.add(buffer);
			
			setFinished(true);
		}										
	}

	/**
	 * Receives incoming HTTP requests from the player, responds with active chunk 
	 * stream headers, and writes segmented block payloads down the connection channel.
	 *
	 * @param t The target {@link HttpExchange} context.
	 * @throws IOException If network or streaming failures occur.
	 */
	public void handle(HttpExchange t) throws IOException {
		InputStream is = t.getRequestBody();
		System.out.println(IOUtils.toString(is)); // .. read the request body

		t.sendResponseHeaders(200, 0);
		t.getResponseHeaders().set("Content-Type", "application/vnd.apple.mpegurl"); 

		OutputStream os = t.getResponseBody();

		while (buffersList.size() > 0 || isFinished() == false) {
			if (buffersList.size() > 0) {
				ByteBuffer currentBuffer = buffersList.get(0);
			
				LOGGER.info("Write first buffer to OutputStream");
					
				os.write(currentBuffer.array());

				buffersList.remove(currentBuffer);
			}
			else {
				//LOGGER.info("Wait until finished");
				
				try {
					Thread.sleep(DELAY_TO_WAIT);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
			}
		}
		
		os.close();
	}

	/**
	 * Generates a high-entropy cryptographically secure session URL path token.
	 * Used to register temporary routes on the embedded HTTP loopback server.
	 *
	 * @return A URL-safe Base64 encoded token string.
	 */
	public static String generateRandomToken() {
		byte bytes[] = new byte[16];
		new SecureRandom().nextBytes(bytes);
		Encoder encoder = Base64.getUrlEncoder().withoutPadding();
		String token = encoder.encodeToString(bytes);
		return token;
	}

	/**
	 * Standalone execution entrypoint used to verify HLS streaming functionality 
	 * locally against sample accounts.
	 *
	 * @param args CommandLine arguments.
	 * @throws IOException If server initialization or file loading fails.
	 */
	public static void main(String[] args) throws IOException {
		if (args == null || args.length < 2) {
			System.err.println("Usage: EmbeddedHLSHandler <account-dir-or-name> <cloud-path> [password]");
			System.err.println("Password may also be set via ALTASTATA_PASSWORD.");
			System.exit(1);
		}
		String accountArg = args[0].trim();
		File accountFile = new File(accountArg);
		String accountDir = accountFile.isAbsolute()
				? accountArg
				: account.ALTASTATA_ACCOUNTS_HOME() + File.separator + accountArg;
		String password = args.length > 2 ? args[2] : System.getenv("ALTASTATA_PASSWORD");
		if (password == null || password.isEmpty()) {
			System.err.println("Password is required (argument or ALTASTATA_PASSWORD).");
			System.exit(1);
		}
		account.loadAccountProperties(accountDir);
		account.setPassword(password.toCharArray());

		String token = generateRandomToken();
		System.out.println("Try: http://127.0.0.1:8093/media/" + token);

		Iterator<CloudFile> it = account.fileSystemModel().listCloudFiles(args[1], true, null, null, true);
		CloudFile cf = it.next();

		server.createContext("/media/" + token, new EmbeddedHLSHandler(cf));
	}
}
