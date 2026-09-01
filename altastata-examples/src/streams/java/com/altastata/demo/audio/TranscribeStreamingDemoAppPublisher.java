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

package com.altastata.demo.audio;

import com.altastata.api.AltaStataFileSystem;
import com.altastata.filesystem.securecloud.SecureCloudStream;
import com.altastata.utils.Account;
import com.altastata.utils.Constants;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.transcribestreaming.TranscribeStreamingAsyncClient;

import javax.sound.sampled.*;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;

public class TranscribeStreamingDemoAppPublisher {
    private static final Region REGION = Region.US_WEST_2;
    private static TranscribeStreamingAsyncClient client;

    static List<String> textAsList = new ArrayList<String>();

    /**
     * Main execution entry point for the TranscribeStreamingDemoAppPublisher, which copies audio wav streams
     * securely into AltaStata chunked filesystems.
     *
     * @param args command-line arguments (args[0] can be the path to the WAV file)
     * @throws URISyntaxException if URI configuration schemas are invalid
     * @throws InterruptedException if thread operations get interrupted
     * @throws LineUnavailableException if audio recording line is unavailable
     * @throws ExecutionException if execution errors occur
     * @throws UnsupportedAudioFileException if the target audio format is unsupported
     * @throws IOException if file access or input-output fails
     */
    public static void main(String args[]) throws URISyntaxException, InterruptedException, LineUnavailableException, ExecutionException, UnsupportedAudioFileException, IOException {

        ProgressBarTraditional pb2 = new ProgressBarTraditional();
        pb2.start();

        String filePath = "audio";
        String fileName = "CallRecording.wav";

        Account account = new Account();

        account.loadAccountProperties(Account.ALTASTATA_ACCOUNTS_HOME() + File.separator + "amazon.rsa.bob123");

        AltaStataFileSystem altaStataFileSystem = com.altastata.api.AccountRegistry.getOrCreateForAccount(account);

        altaStataFileSystem.setPassword("123");

        String wavPath = System.getProperty("user.home") + "/Desktop/CallRecording.wav";
        if (args.length > 0) {
            wavPath = args[0];
        }
        File wavFile = new File(wavPath);
        if (!wavFile.exists()) {
            System.err.println("WAV file not found: " + wavFile.getAbsolutePath());
            System.err.println("Please provide a path to a WAV file as the first argument, e.g.:");
            System.err.println("  ./gradlew :altastata-examples:runStreamsApp --args=\"/path/to/file.wav\"");
            System.exit(1);
        }

        AudioInputStream audioInputStream =
                AudioSystem.getAudioInputStream(wavFile);

        OutputStream outputStream =
                new SecureCloudStream.AltaStataChunkedOutputStream(filePath + "/" + fileName,
                                                                    System.currentTimeMillis(),
                                                                    true, account);

        // copy InputStream to OutputStream via buffer
        byte[] buf = new byte[Constants.PLAIN_CHUNK_MAX_SIZE()];
        int length;
        while ((length = audioInputStream.read(buf)) > 0) {
            outputStream.write(buf, 0, length);
            outputStream.flush();
        }

        outputStream.close();

        Thread.sleep(500000);

        pb2.showProgress = false;
    }

    /**
     * Initializes and returns an audio input stream captured directly from the local microphone.
     *
     * @return the captured microphone audio input stream
     * @throws LineUnavailableException if target recording hardware is unavailable
     */
    private static InputStream getStreamFromMic() throws LineUnavailableException {

        // Signed PCM AudioFormat with 16,000 Hz, 16 bit sample size, mono
        int sampleRate = 16000;
        AudioFormat format = new AudioFormat(sampleRate, 16, 1, true, false);
        DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);

        if (!AudioSystem.isLineSupported(info)) {
            System.out.println("Line not supported");
            System.exit(0);
        }

        TargetDataLine line = (TargetDataLine) AudioSystem.getLine(info);
        line.open(format);
        line.start();

        InputStream audioStream = new AudioInputStream(line);
        return audioStream;
    }
}

class ProgressBarTraditional extends Thread {
    boolean showProgress = true;
    /**
     * Executes the background animation thread to display progress feedback.
     */
    @Override
    public void run() {
        String anim  = "=====================";
        int x = 0;
        while (showProgress) {
            System.out.print("\r Streaming via AltaStata Data Lake "
                    + anim.substring(0, x++ % anim.length())
                    + " ");
            try { Thread.sleep(100); }
            catch (Exception e) {};
        }
    }
}
