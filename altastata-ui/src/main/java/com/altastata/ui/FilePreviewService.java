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

package com.altastata.ui;

import com.altastata.api.AltaStataFileSystem.OperationState;
import com.altastata.api.CloudFileOperationStatus;
import com.altastata.api.StreamStartedEvent;
import com.altastata.api.StreamStartedListener;
import com.altastata.filesystem.common.CloudFile;
import com.altastata.filesystem.common.VersionAttributes;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.media.Media;
import javafx.scene.media.MediaException;
import javafx.scene.media.MediaPlayer;
import javafx.scene.media.MediaView;
import javafx.scene.text.Text;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.tika.Tika;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.co.caprica.vlcj.factory.MediaPlayerFactory;
import uk.co.caprica.vlcj.javafx.videosurface.ImageViewVideoSurface;
import uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Service class responsible for handling file preview functionality.
 * Supports previewing text files, images, audio/video files, and PDF documents.
 * Extracted from NavigationPane to improve code organization and separation of concerns.
 */
public class FilePreviewService {

    private static final Logger LOGGER = LoggerFactory.getLogger(FilePreviewService.class);
    private static final Tika defaultTika = new Tika();
    private static EmbeddedMediaPlayer embeddedMediaPlayer = null;

    /**
     * Result of cloud I/O for preview. {@link #loadPreviewPayload} runs off the JavaFX thread;
     * {@link #displayPreview} must run on the JavaFX Application Thread (e.g. Task.setOnSucceeded).
     */
    public static class PreviewPayload {
        public enum Kind { TEXT, IMAGE, PDF, MEDIA, UNSUPPORTED, ERROR }

        private final Kind kind;
        private final String mimeType;
        private final byte[] data;
        private final Path mediaTempDirectory;
        private final CloudFileOperationStatus mediaOperationStatus;

        private PreviewPayload(Kind kind, String mimeType, byte[] data,
                               Path mediaTempDirectory, CloudFileOperationStatus mediaOperationStatus) {
            this.kind = kind;
            this.mimeType = mimeType;
            this.data = data;
            this.mediaTempDirectory = mediaTempDirectory;
            this.mediaOperationStatus = mediaOperationStatus;
        }

        /**
         * Creates a new PreviewPayload of kind TEXT with plain text data.
         *
         * @param data The text content as a byte array.
         * @return A configured PreviewPayload instance representing text.
         */
        public static PreviewPayload text(byte[] data) {
            return new PreviewPayload(Kind.TEXT, "text/plain", data, null, null);
        }

        /**
         * Creates a new PreviewPayload of kind IMAGE with image byte data and mime type.
         *
         * @param data     The image content as a byte array.
         * @param mimeType The image's resolved MIME content-type.
         * @return A configured PreviewPayload instance representing an image.
         */
        public static PreviewPayload image(byte[] data, String mimeType) {
            return new PreviewPayload(Kind.IMAGE, mimeType, data, null, null);
        }

        /**
         * Creates a new PreviewPayload of kind PDF containing binary PDF data.
         *
         * @param data The PDF file content as a byte array.
         * @return A configured PreviewPayload instance representing a PDF.
         */
        public static PreviewPayload pdf(byte[] data) {
            return new PreviewPayload(Kind.PDF, "application/pdf", data, null, null);
        }

        /**
         * Creates a new PreviewPayload of kind MEDIA specifying download coordinates for video or audio.
         *
         * @param tempDirectory The local temp directory path where media file blocks are buffered.
         * @param status        The background file operation tracker instance.
         * @return A configured PreviewPayload instance representing streaming media.
         */
        public static PreviewPayload media(Path tempDirectory, CloudFileOperationStatus status) {
            return new PreviewPayload(Kind.MEDIA, null, null, tempDirectory, status);
        }

        /**
         * Creates a new PreviewPayload for unsupported file formats.
         *
         * @param mimeType The resolved MIME type of the unsupported file.
         * @return A PreviewPayload representing an unsupported file format.
         */
        public static PreviewPayload unsupported(String mimeType) {
            return new PreviewPayload(Kind.UNSUPPORTED, mimeType, null, null, null);
        }

        /**
         * Creates a new PreviewPayload indicating an error occurred during retrieval or loading.
         *
         * @return A PreviewPayload indicating an operational error.
         */
        public static PreviewPayload error() {
            return new PreviewPayload(Kind.ERROR, null, null, null, null);
        }

        /**
         * Gets the payload classification kind.
         *
         * @return The PreviewPayload Kind enum value.
         */
        public Kind getKind() { return kind; }

        /**
         * Gets the MIME classification type of the loaded payload.
         *
         * @return The MIME type string.
         */
        public String getMimeType() { return mimeType; }

        /**
         * Gets the raw retrieved binary file content array.
         *
         * @return The file payload as a byte array.
         */
        public byte[] getData() { return data; }

        /**
         * Gets the temporary local path where media file blocks are being downloaded.
         *
         * @return The local Path reference.
         */
        public Path getMediaTempDirectory() { return mediaTempDirectory; }
        /**
         * Gets the background operation tracker for active media stream downloads.
         *
         * @return The active {@link CloudFileOperationStatus} reference.
         */
        public CloudFileOperationStatus getMediaOperationStatus() { return mediaOperationStatus; }
    }

    /**
     * Executes non-blocking cloud retrieval tasks to load file data for a preview layout.
     * This method runs outside of the main JavaFX Application Thread, performing secure gRPC and
     * HTTP download requests on background thread pools.
     *
     * @param cloudFile               The representing cloud file object.
     * @param versionAttributes       Specific version attributes to resolve.
     * @param fileSizeDataAttribute   The size of the target file in bytes.
     * @return A resolved {@link PreviewPayload} containing either data buffers or temp streaming configurations.
     */
    public PreviewPayload loadPreviewPayload(CloudFile cloudFile, VersionAttributes versionAttributes,
                                             String fileSizeDataAttribute) {
        long fileSize = Long.parseLong(fileSizeDataAttribute);
        String type = resolveContentType(cloudFile);

        List<Long> timestamps = new ArrayList<>();
        timestamps.add(versionAttributes.getCreateTime());

        if (isTextRepresentable(type) || cloudFile.getName().contains("README")) {
            ByteBuffer buffer = ByteBuffer.allocate((fileSize > 1000) ? 1000 : (int) fileSize);
            CloudFileOperationStatus retrieveResults = AltaStataApp.account.fileSystemModel().retrieveCloudFileToByteBuffer(
                    buffer, cloudFile, timestamps, 0L, null, true, false);
            if (retrieveResults.getOperationState().equals(OperationState.ERROR)) {
                return PreviewPayload.error();
            }
            return PreviewPayload.text(buffer.array());
        } else if (type.startsWith("image/")) {
            ByteBuffer buffer = ByteBuffer.allocate((int) fileSize);
            CloudFileOperationStatus retrieveResults = AltaStataApp.account.fileSystemModel().retrieveCloudFileToByteBuffer(
                    buffer, cloudFile, timestamps, 0L, null, true, true);
            if (retrieveResults.getOperationState().equals(OperationState.ERROR)) {
                return PreviewPayload.error();
            }
            return PreviewPayload.image(buffer.array(), type);
        } else if (type.startsWith("audio/") || type.startsWith("video/")) {
            try {
                Path tempDirectoryPath = Files.createTempDirectory("myTempDir_");
                tempDirectoryPath.toFile().deleteOnExit();
                CloudFileOperationStatus status = startDownloadingMediaFileForPreview(
                        cloudFile, versionAttributes, tempDirectoryPath);
                if (status == null) {
                    return PreviewPayload.error();
                }
                return PreviewPayload.media(tempDirectoryPath, status);
            } catch (IOException e) {
                LOGGER.error("Failed to create temporary directory for media preview", e);
                return PreviewPayload.error();
            }
        } else if (type.equals("application/pdf")) {
            ByteBuffer buffer = ByteBuffer.allocate((int) fileSize);
            CloudFileOperationStatus retrieveResults = AltaStataApp.account.fileSystemModel().retrieveCloudFileToByteBuffer(
                    buffer, cloudFile, timestamps, 0L, null, true, true);
            if (retrieveResults.getOperationState().equals(OperationState.ERROR)) {
                return PreviewPayload.error();
            }
            return PreviewPayload.pdf(buffer.array());
        } else {
            LOGGER.info("loadPreviewPayload unsupported type: " + type);
            return PreviewPayload.unsupported(type);
        }
    }

    /**
     * Instantiates and mounts appropriate JavaFX UI layouts inside the preview column.
     * Must be invoked on the main JavaFX Application Thread.
     *
     * @param container         The column UI box where the preview node will be embedded.
     * @param cloudFile         The target file being previewed.
     * @param versionAttributes The resolved version parameters.
     * @param payload           The loaded {@link PreviewPayload} containing preview materials.
     */
    public void displayPreview(VerticalBox container, CloudFile cloudFile,
                               VersionAttributes versionAttributes, PreviewPayload payload) {
        if (payload == null) {
            return;
        }
        switch (payload.getKind()) {
            case ERROR:
                AltaStataApp.popupHandlerErrorAlert("Preview",
                        "Please, see the message box for the details.");
                return;
            case UNSUPPORTED:
                LOGGER.info("displayPreview unsupported type: " + payload.getMimeType());
                return;
            case TEXT:
                displayTextPreview(container, payload.getData());
                return;
            case IMAGE:
                displayImagePreview(container, payload.getData());
                return;
            case PDF:
                displayPdfPreview(container, payload.getData());
                return;
            case MEDIA:
                displayMediaPreview(container, cloudFile, payload);
                return;
            default:
                break;
        }
    }

    /**
     * Renders plain-text file preview layouts inside the vertical container panel.
     *
     * @param container The target vertical box layout.
     * @param data      The raw text content byte array.
     */
    private void displayTextPreview(VerticalBox container, byte[] data) {
        Text text = new Text(new String(data, Charset.forName("UTF-8")));
        text.setWrappingWidth(container.widthProperty().doubleValue() - 20);
        container.widthProperty().addListener((observable, oldValue, newValue) -> {
            text.setWrappingWidth(container.widthProperty().doubleValue() - 20);
        });
        container.getChildren().set(2, text);
    }

    /**
     * Renders image preview layouts (PNG, JPEG, WebP) inside the vertical container panel.
     *
     * @param container The target vertical box layout.
     * @param data      The raw image content byte array.
     */
    private void displayImagePreview(VerticalBox container, byte[] data) {
        Image img = new Image(new ByteArrayInputStream(data));
        ImageView imageView = new ImageView(img);
        imageView.setPreserveRatio(true);
        imageView.setFitWidth(container.widthProperty().doubleValue() - 20);
        container.widthProperty().addListener((observable, oldValue, newValue) -> {
            imageView.setFitWidth(container.widthProperty().doubleValue() - 20);
        });
        container.getChildren().set(2, imageView);
    }

    /**
     * Renders Adobe PDF document preview layouts inside the vertical container panel.
     * Renders document pages as high-quality JavaFX rasterized image nodes using PDFBox.
     *
     * @param container The target vertical box layout.
     * @param data      The raw PDF document byte array.
     */
    private void displayPdfPreview(VerticalBox container, byte[] data) {
        ProgressIndicator progressIndicator = new ProgressIndicator();
        progressIndicator.setVisible(false);
        StackPane pdfPane = new StackPane();
        VBox layout = new VBox(10, progressIndicator, pdfPane);
        container.getChildren().set(2, layout);
        progressIndicator.setVisible(true);
        pdfPane.getChildren().clear();

        Task<Image> task = new Task<>() {
            @Override
            protected Image call() throws Exception {
                try (InputStream inputStream = new ByteArrayInputStream(data)) {
                    return renderPDFPage(inputStream, 0);
                }
            }
        };

        task.setOnSucceeded(event1 -> {
            Image pdfImage = task.getValue();
            ImageView imageView = new ImageView(pdfImage);
            imageView.setPreserveRatio(true);
            imageView.setFitWidth(container.widthProperty().doubleValue() - 20);
            container.widthProperty().addListener((observable, oldValue, newValue) -> {
                imageView.setFitWidth(container.widthProperty().doubleValue() - 20);
            });
            pdfPane.getChildren().clear();
            pdfPane.getChildren().add(imageView);
            progressIndicator.setVisible(false);
        });

        task.setOnFailed(event1 -> {
            LOGGER.error("Error loading PDF", task.getException());
            progressIndicator.setVisible(false);
        });

        Thread backgroundThread = new Thread(task);
        backgroundThread.setDaemon(true);
        backgroundThread.start();
    }

    /**
     * Prepares and initializes the media player download preview parameters.
     * Hooks a stream-started listener to display the media preview once data starts buffering.
     *
     * @param container The vertical box container layout.
     * @param cloudFile The cloud file object.
     * @param payload   The media payload details.
     */
    private void displayMediaPreview(VerticalBox container, CloudFile cloudFile, PreviewPayload payload) {
        Path tempDirectoryPath = payload.getMediaTempDirectory();
        Path tempFilePath = tempDirectoryPath.resolve(cloudFile.getName());
        CloudFileOperationStatus cloudFileOperationStatus = payload.getMediaOperationStatus();

        cloudFileOperationStatus.addStreamStartedListener(new StreamStartedListener() {
            /**
             * Handles the stream started event.
             *
             * @param evt the stream started event
             */
            @Override
            public void streamStarted(StreamStartedEvent evt) {
                Platform.runLater(() -> displayMediaPreviewOnFxThread(container, cloudFile, tempFilePath, cloudFileOperationStatus));
            }
        });
    }

    /**
     * Renders and plays media stream content inside the preview column layout.
     * Auto-detects and loads either VLCJ (if configured) or the native JavaFX MediaEngine.
     *
     * @param container                The parent container.
     * @param cloudFile                The file being previewed.
     * @param tempFilePath             The local temp file stream path.
     * @param cloudFileOperationStatus The operation state indicator tracking the stream.
     */
    private void displayMediaPreviewOnFxThread(VerticalBox container, CloudFile cloudFile,
                                               Path tempFilePath, CloudFileOperationStatus cloudFileOperationStatus) {
        if (AltaStataApp.account.getMediaPlayerName().equals("vlcj")) {
            if (embeddedMediaPlayer == null) {
                MediaPlayerFactory mediaPlayerFactory = new MediaPlayerFactory();
                embeddedMediaPlayer = mediaPlayerFactory.mediaPlayers().newEmbeddedMediaPlayer();
            }

            BorderPane root = new BorderPane();
            ImageView videoImageView = new ImageView();
            ImageViewVideoSurface videoSurface = new ImageViewVideoSurface(videoImageView);
            embeddedMediaPlayer.videoSurface().set(videoSurface);

            StackPane videoPane = new StackPane();
            videoPane.getChildren().add(videoImageView);
            videoPane.setPadding(new javafx.geometry.Insets(5));
            videoImageView.fitWidthProperty().bind(videoPane.widthProperty().subtract(10));
            videoImageView.fitHeightProperty().bind(videoPane.heightProperty().subtract(10));
            videoImageView.setPreserveRatio(true);
            root.setCenter(videoPane);

            Button playButton = new Button("Play");
            Button pauseButton = new Button("Pause");
            Button stopButton = new Button("Stop");
            playButton.setOnAction(e -> embeddedMediaPlayer.controls().play());
            pauseButton.setOnAction(e -> embeddedMediaPlayer.controls().pause());
            stopButton.setOnAction(e -> {
                embeddedMediaPlayer.controls().stop();
                cloudFileOperationStatus.doCancelOperation(true);
                cleanMediaFileStream(cloudFile, tempFilePath);
                cloudFile.setOperationStateValue(OperationState.NONE);
            });

            HBox controls = new HBox(10, playButton, pauseButton, stopButton);
            controls.setStyle("-fx-padding: 10; -fx-alignment: center;");
            root.setBottom(controls);
            container.getChildren().set(2, root);

            LOGGER.info("Play file: " + tempFilePath.toAbsolutePath());
            embeddedMediaPlayer.media().play(tempFilePath.toAbsolutePath().toString());
            scheduleStopEmbeddedMediaPlayer(embeddedMediaPlayer);
        } else {
            MediaView mv = new MediaView();
            mv.setPreserveRatio(true);
            mv.setFitWidth(container.widthProperty().doubleValue() - 20);
            container.widthProperty().addListener((observable, oldValue, newValue) -> {
                mv.setFitWidth(container.widthProperty().doubleValue() - 20);
            });

            Button playButton = new Button("Play");
            Button pauseButton = new Button("Pause");
            Button stopButton = new Button("Stop");
            HBox controls = new HBox(10, playButton, pauseButton, stopButton);
            controls.setStyle("-fx-padding: 10; -fx-alignment: center;");

            BorderPane root = new BorderPane();
            root.setCenter(mv);
            root.setBottom(controls);
            container.getChildren().set(2, root);

            try {
                LOGGER.info("Play file: " + tempFilePath.toUri().toURL().toExternalForm());
                Media media = new Media(tempFilePath.toUri().toURL().toExternalForm());
                MediaPlayer mediaPlayer = new MediaPlayer(media);
                mediaPlayer.setAutoPlay(true);
                mv.setMediaPlayer(mediaPlayer);
                playButton.setOnAction(e -> mediaPlayer.play());
                pauseButton.setOnAction(e -> mediaPlayer.pause());
                stopButton.setOnAction(e -> {
                    mediaPlayer.stop();
                    cloudFileOperationStatus.doCancelOperation(true);
                    cleanMediaFileStream(cloudFile, tempFilePath);
                    cloudFile.setOperationStateValue(OperationState.NONE);
                });
                scheduleStopMediaPlayer(mediaPlayer);
            } catch (MalformedURLException | MediaException e) {
                LOGGER.error("", e);
                root.setBottom(null);
                cloudFileOperationStatus.doCancelOperation(true);
                cleanMediaFileStream(cloudFile, tempFilePath);
                cloudFile.setOperationStateValue(OperationState.NONE);
                UIUtils.showErrorAlert("Media Player Error", "The media file is not supported, or the file cannot be streamed. " +
                        "To make the video available for preview as a stream, it should be reformatted. " +
                        "You can try using a command that inserts the [moov] Atom (Movie Box): \n\n" +
                        "ffmpeg -i input_file.mp4 -c copy -movflags faststart output_file.mp4");
            }
        }
    }

    /**
     * Resolves the MIME content-type of a cloud file using Apache Tika detection 
     * and fallback rules for common extensions.
     *
     * @param cloudFile The cloud file whose extension is examined.
     * @return A MIME-type string representing content format.
     */
    private String resolveContentType(CloudFile cloudFile) {
        String type = defaultTika.detect(cloudFile.getName());
        String fileName = cloudFile.getName().toLowerCase();
        if (fileName.endsWith(".txt") || fileName.endsWith(".log") || fileName.endsWith(".md") ||
            fileName.endsWith(".json") || fileName.endsWith(".xml") || fileName.endsWith(".csv") ||
            fileName.endsWith(".sql") || fileName.endsWith(".js") || fileName.endsWith(".html") ||
            fileName.endsWith(".css") || fileName.endsWith(".py") || fileName.endsWith(".java") ||
            fileName.endsWith(".scala") || fileName.endsWith(".sh") || fileName.endsWith(".bat") ||
            fileName.endsWith(".conf") || fileName.endsWith(".properties") || fileName.endsWith(".yml") ||
            fileName.endsWith(".yaml") || fileName.endsWith(".ini") || fileName.endsWith(".cfg")) {
            type = "text/plain";
        }
        return type;
    }

    /**
     * Legacy method to trigger immediate file preview rendering.
     * 
     * @param container             The vertical container.
     * @param cloudFile             The cloud file object.
     * @param versionAttributes     The active file version attributes.
     * @param fileSizeDataAttribute The file size in bytes.
     * @deprecated Use {@link #loadPreviewPayload} on a background thread and {@link #displayPreview} on JavaFX thread.
     */
    @Deprecated
    public void showPreview(VerticalBox container, CloudFile cloudFile, VersionAttributes versionAttributes, String fileSizeDataAttribute) {
        displayPreview(container, cloudFile, versionAttributes, loadPreviewPayload(cloudFile, versionAttributes, fileSizeDataAttribute));
    }

    /**
     * Renders a specific page of a PDF document as a JavaFX Image.
     * 
     * @param inputStream The PDF document input stream
     * @param pageIndex The page index to render (0-based)
     * @return The rendered page as a JavaFX Image
     * @throws IOException If an error occurs during PDF rendering
     */
    private Image renderPDFPage(InputStream inputStream, int pageIndex) throws IOException {
        // Load PDF document from InputStream
        try (PDDocument document = PDDocument.load(inputStream)) {
            PDFRenderer pdfRenderer = new PDFRenderer(document);
            
            // Render the specified page (pageIndex starts at 0)
            BufferedImage bufferedImage = pdfRenderer.renderImageWithDPI(pageIndex, 150);
            
            // Convert BufferedImage to JavaFX Image
            return SwingFXUtils.toFXImage(bufferedImage, null);
        }
    }
    
    /**
     * Starts downloading a media file for preview purposes.
     * 
     * @param cloudFile The file to download
     * @param versionAttributes Version information for the file
     * @param tempDirectoryPath Temporary directory to store the file
     * @return CloudFileOperationStatus representing the download operation
     */
    private CloudFileOperationStatus startDownloadingMediaFileForPreview(CloudFile cloudFile, VersionAttributes versionAttributes, Path tempDirectoryPath) {
        
        // Directly create a file path in the temporary directory
        Path tempFilePath = tempDirectoryPath.resolve(cloudFile.getName());
        
        try {
            LOGGER.debug("Start streaming file to: " + tempFilePath);
            
            CloudFile[] toRetrieve = new CloudFile[] {cloudFile};
            
            List<Long> timestamps = new ArrayList<>();
            timestamps.add(versionAttributes.getCreateTime());
            
            CloudFileOperationStatus [] cloudFileOperationStatusArray =
                    AltaStataApp.account.fileSystemModel().retrieveCloudFilesToLocalDirectory(
                            toRetrieve,
                            tempDirectoryPath.toString(),
                            timestamps, true, false, true);
            
            return cloudFileOperationStatusArray[0];
            
        } catch (Throwable ex) {
            LOGGER.error("showPreview: " + cloudFile.getPath(), ex);
        }
        finally {
            scheduleCleanMediaFile(cloudFile, tempFilePath);
        }
        
        return null;
    }
    
    /**
     * Schedules cleanup of temporary media files after a delay.
     * 
     * @param cloudFile The cloud file being previewed
     * @param tempFilePath Path to the temporary file to clean up
     */
    private void scheduleCleanMediaFile(CloudFile cloudFile, Path tempFilePath) {
        
        TimerTask cleanMediaFileTask = new TimerTask() {
            @Override
            public void run() {
                cleanMediaFileStream(cloudFile, tempFilePath);
            }
        };
        
        new Timer().schedule(cleanMediaFileTask, 22000);
    }
    
    /**
     * Cleans up temporary media files and resets file operation state.
     * 
     * @param cloudFile The cloud file being previewed
     * @param tempFilePath Path to the temporary file to clean up
     */
    private static void cleanMediaFileStream(CloudFile cloudFile, Path tempFilePath) {
        LOGGER.info("cleanMediaFileStream: " + cloudFile + " " + cloudFile.getOperationState());
        
        // Just makes sure that its not in DOWNLOADING state anymore
        cloudFile.setOperationStateValue(OperationState.NONE);
        
        try {
            if (Files.exists(tempFilePath)) {
                Files.delete(tempFilePath);
                Files.delete(tempFilePath.getParent());
                
                LOGGER.info("Delete tempDirectoryPath: " + tempFilePath.getParent());
            }
        }
        catch (IOException e) {
            LOGGER.warn("Failed to delete temporary file: " + tempFilePath + " " + e.getMessage());
        }
        
    }
    
    /**
     * Schedules automatic stop of JavaFX MediaPlayer after a timeout.
     * 
     * @param mediaPlayer The MediaPlayer to stop
     */
    private void scheduleStopMediaPlayer(MediaPlayer mediaPlayer) {
        
        TimerTask stopMediaPlayerTask = new TimerTask() {
            @Override
            public void run() {
                // free all resources associated with player
                mediaPlayer.stop();
                mediaPlayer.dispose();
            }
        };
        
        new Timer().schedule(stopMediaPlayerTask, 20000);
    }
    
    /**
     * Schedules automatic stop of VLCJ EmbeddedMediaPlayer after a timeout.
     * 
     * @param mediaPlayer The EmbeddedMediaPlayer to stop
     */
    private void scheduleStopEmbeddedMediaPlayer(EmbeddedMediaPlayer mediaPlayer) {
        
        TimerTask stopMediaPlayerTask = new TimerTask() {
            @Override
            public void run() {
                
                mediaPlayer.controls().stop();
            }
        };
        
        new Timer().schedule(stopMediaPlayerTask, 20000);
    }
    
    /**
     * Determines if a MIME type represents text that can be displayed as a preview.
     * 
     * @param mimeType The MIME type to check
     * @return true if the MIME type represents text content
     */
    private static boolean isTextRepresentable(String mimeType) {
        return mimeType.startsWith("text/") || mimeType.equals("application/json")
                || mimeType.equals("application/xml") || mimeType.equals("application/xhtml+xml")
                || mimeType.equals("application/csv") || mimeType.equals("application/sql")
                || mimeType.equals("application/javascript") || mimeType.equals("application/x-javascript")
                || mimeType.equals("application/octet-stream");
    }
}
