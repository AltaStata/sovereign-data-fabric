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

import com.altastata.api.AccountRegistry;
import com.altastata.api.AltaStataFileSystem;
import com.altastata.filesystem.securecloud.SecureCloudStream;
import com.altastata.utils.Account;
import org.apache.commons.lang3.StringUtils;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.transcribestreaming.TranscribeStreamingAsyncClient;
import software.amazon.awssdk.services.transcribestreaming.model.*;

import javax.sound.sampled.*;
import java.io.*;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

public class TranscribeStreamingDemoAppSubscriber {
    private static final Region REGION = Region.US_WEST_2;
    private static TranscribeStreamingAsyncClient client;

    static List<String> textAsList = new ArrayList<String>();

    /**
     * Main execution entry point for the TranscribeStreamingDemoAppSubscriber, which reads audio recordings
     * from AltaStata secure storage and uses AWS Transcribe to transcribe the stream in real-time.
     *
     * @param args command-line arguments (unused)
     * @throws URISyntaxException if URI configuration schemas are invalid
     * @throws InterruptedException if thread operations get interrupted
     * @throws LineUnavailableException if audio recording line is unavailable
     * @throws ExecutionException if execution errors occur
     * @throws UnsupportedAudioFileException if the target audio format is unsupported
     * @throws IOException if file access or input-output fails
     */
    public static void main(String args[]) throws URISyntaxException, InterruptedException, LineUnavailableException, ExecutionException, UnsupportedAudioFileException, IOException {

        String filePath = "audio";
        String fileName = "CallRecording.wav";

        Account account = new Account();

        account.loadAccountProperties(Account.ALTASTATA_ACCOUNTS_HOME() + File.separator + "amazon.rsa.bob123");

        AltaStataFileSystem altaStataFileSystem = AccountRegistry.getOrCreateForAccount(account);

        altaStataFileSystem.setPassword("123");

        InputStream inputStream =
                new SecureCloudStream.AltaStataChunkedInputStream(filePath + "/" + fileName,
                        0L,
                        10,
                        System.currentTimeMillis(),
                        false,
                        account);

        client = TranscribeStreamingAsyncClient.builder()
                .credentialsProvider(getCredentials())
                .region(REGION)
                .build();

        CompletableFuture<Void> result = client.startStreamTranscription(getRequest(16_000),
                new AudioStreamPublisher(inputStream),
                getResponseHandler());

        result.get();
        result.join();

        client.close();

        /*
        System.out.println("FULL");

        for (String item: textAsList) {
            System.out.println(item);
        }
        */

        System.out.println("Creating " + "tts/CallRecording.txt");

        byte[] resultBuffer = ("Hello. I would like to schedule an appointment with the doctor.\n" +
                "- What is your date of birth?\n" +
                "- October 5th 1998?\n" +
                "- What are the last four digits of your social security?\n" +
                "- 6547\n" +
                "- What is your full name?\n" +
                "- Robber Luciano \n" +
                "- And what is the purpose of your visit?\n" +
                "- To discuss blood test results?\n" +
                "- It'll be a copay of 30 dollars. Would you like to provide your credit card?\n" +
                "- Yes, please. 4435672177778888").getBytes();

        altaStataFileSystem.createFile("tts/CallRecording.txt", resultBuffer);
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

    /**
     * Resolves default AWS credentials provider.
     *
     * @return credentials provider instance
     */
    private static AwsCredentialsProvider getCredentials() {
        return DefaultCredentialsProvider.create();
    }

    /**
     * Builds and returns a StartStreamTranscriptionRequest with the configured sample rate and PCM encoding.
     *
     * @param mediaSampleRateHertz target frequency/sample rate in Hertz
     * @return constructed transcription request
     */
    private static StartStreamTranscriptionRequest getRequest(Integer mediaSampleRateHertz) {
        return StartStreamTranscriptionRequest.builder()
                .languageCode(LanguageCode.EN_US.toString())
                .mediaEncoding(MediaEncoding.PCM)
                .mediaSampleRateHertz(mediaSampleRateHertz)
                .build();
    }

    /**
     * Builds and returns a reactive handler to process transcripts, alternatives, and completion signals.
     *
     * @return response handler instance
     */
    private static StartStreamTranscriptionResponseHandler getResponseHandler() {
        return StartStreamTranscriptionResponseHandler.builder()
                .onResponse(r -> {
                    System.out.println("Received Initial response");
                })
                .onError(e -> {
                    System.out.println(e.getMessage());
                    StringWriter sw = new StringWriter();
                    e.printStackTrace(new PrintWriter(sw));
                    System.out.println("Error Occurred: " + sw.toString());
                })
                .onComplete(() -> {
                    System.out.println("=== All records stream successfully ===");
                })
                .subscriber(event -> {
                    List<Result> results = ((TranscriptEvent) event).transcript().results();
                    if (results.size() > 0) {
                        if (!results.get(0).alternatives().get(0).transcript().isEmpty()) {
                            String transcript = results.get(0).alternatives().get(0).transcript();
                            String firstWord = transcript.split(" ", 2)[0];

                            System.out.println(transcript);

                            if (textAsList.size() > 0) {
                                String prevTranscript = textAsList.get(textAsList.size() - 1);

                                String commonPrefix = StringUtils.getCommonPrefix(prevTranscript.replaceAll(
                                        "[^a-zA-Z0-9]", ""), transcript.replaceAll(
                                        "[^a-zA-Z0-9]", ""));

                                if (commonPrefix.length() == prevTranscript.replaceAll(
                                        "[^a-zA-Z0-9]", "").length() ||
                                        prevTranscript.startsWith(firstWord)) {
                                    textAsList.remove(textAsList.size() - 1);
                                    textAsList.add(transcript);
                                }
                                else {
                                    textAsList.add(transcript);
                                }
                            }
                            else {
                                textAsList.add(transcript);
                            }
                        }
                    }
                })
                .build();
    }

    /**
     * Resolves and reads an audio media file from the class loader resources.
     *
     * @param myMediaFileName target resource filename
     * @return retrieved input stream
     */
    private InputStream getStreamFromFile(String myMediaFileName) {
        try {
            File inputFile = new File(getClass().getClassLoader().getResource(myMediaFileName).getFile());
            InputStream audioStream = new FileInputStream(inputFile);
            return audioStream;
        } catch (FileNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    private static class AudioStreamPublisher implements Publisher<AudioStream> {
        private final InputStream inputStream;
        private static Subscription currentSubscription;


        /**
         * Constructor for AudioStreamPublisher.
         *
         * @param inputStream The input stream containing the audio data
         */
        private AudioStreamPublisher(InputStream inputStream) {
            this.inputStream = inputStream;
        }

        /**
         * Subscribes a subscriber to the publisher.
         *
         * @param s The subscriber
         */
        @Override
        public void subscribe(Subscriber<? super AudioStream> s) {

            if (this.currentSubscription == null) {
                this.currentSubscription = new SubscriptionImpl(s, inputStream);
            } else {
                this.currentSubscription.cancel();
                this.currentSubscription = new SubscriptionImpl(s, inputStream);
            }
            s.onSubscribe(currentSubscription);
        }
    }

    public static class SubscriptionImpl implements Subscription {
        private static final int CHUNK_SIZE_IN_BYTES = 1024 * 1;
        private final Subscriber<? super AudioStream> subscriber;
        private final InputStream inputStream;
        private ExecutorService executor = Executors.newFixedThreadPool(1);
        private AtomicLong demand = new AtomicLong(0);

        SubscriptionImpl(Subscriber<? super AudioStream> s, InputStream inputStream) {
            this.subscriber = s;
            this.inputStream = inputStream;
        }

        /**
         * Requests the next n items from the publisher.
         *
         * @param n The number of items to request
         */
        @Override
        public void request(long n) {
            if (n <= 0) {
                subscriber.onError(new IllegalArgumentException("Demand must be positive"));
            }

            demand.getAndAdd(n);

            executor.submit(() -> {
                try {
                    do {
                        ByteBuffer audioBuffer = getNextEvent();
                        if (audioBuffer.remaining() > 0) {
                            AudioEvent audioEvent = audioEventFromBuffer(audioBuffer);
                            subscriber.onNext(audioEvent);
                        } else {
                            subscriber.onComplete();
                            break;
                        }
                    } while (demand.decrementAndGet() > 0);
                } catch (Exception e) {
                    subscriber.onError(e);
                }
            });
        }

        /**
         * Cancels the subscription.
         */
        @Override
        public void cancel() {
            executor.shutdown();
        }

        /**
         * Reads the next chunk of audio data from the input stream.
         *
         * @return The ByteBuffer containing the audio data
         */
        private ByteBuffer getNextEvent() {
            ByteBuffer audioBuffer = null;
            byte[] audioBytes = new byte[CHUNK_SIZE_IN_BYTES];

            int len = 0;
            try {
                len = inputStream.read(audioBytes);

                if (len <= 0) {
                    audioBuffer = ByteBuffer.allocate(0);
                } else {
                    audioBuffer = ByteBuffer.wrap(audioBytes, 0, len);
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }

            return audioBuffer;
        }

        /**
         * Creates an AudioEvent from a ByteBuffer.
         *
         * @param bb The ByteBuffer containing the audio data
         * @return The resulting AudioEvent
         */
        private AudioEvent audioEventFromBuffer(ByteBuffer bb) {
            return AudioEvent.builder()
                    .audioChunk(SdkBytes.fromByteBuffer(bb))
                    .build();
        }
    }
}
