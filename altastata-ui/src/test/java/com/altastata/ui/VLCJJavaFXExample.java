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

import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import uk.co.caprica.vlcj.factory.MediaPlayerFactory;
import uk.co.caprica.vlcj.javafx.videosurface.ImageViewVideoSurface;
import uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer;

public class VLCJJavaFXExample extends Application {

    /*
    static {
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("mac")) {
            System.setProperty("jna.library.path", "/Applications/VLC.app/Contents/MacOS/lib");
        } else if (os.contains("linux")) {
            System.setProperty("jna.library.path", "/usr/lib/vlc"); // Adjust to your Linux setup
        } else if (os.contains("win")) {
            System.setProperty("jna.library.path", "C:\\Program Files\\VideoLAN\\VLC");
        }

        System.setProperty("vlcj.log", "INFO"); // Optional, for logging
    }
     */

    private MediaPlayerFactory mediaPlayerFactory;
    private EmbeddedMediaPlayer mediaPlayer;

    @Override
    public void start(Stage primaryStage) {
        BorderPane root = new BorderPane();
        Scene scene = new Scene(root, 800, 600);

        mediaPlayerFactory = new MediaPlayerFactory();
        mediaPlayer = mediaPlayerFactory.mediaPlayers().newEmbeddedMediaPlayer();

        ImageView videoImageView = new ImageView();
        ImageViewVideoSurface videoSurface = new ImageViewVideoSurface(videoImageView);

        mediaPlayer.videoSurface().set(videoSurface);

        // Create a StackPane to hold the video
        StackPane videoPane = new StackPane();
        videoPane.getChildren().add(videoImageView);

        // Apply margin (padding) of 5 pixels to all sides
        videoPane.setPadding(new javafx.geometry.Insets(5));

        // Bind ImageView size to StackPane size, considering padding
        videoImageView.fitWidthProperty().bind(videoPane.widthProperty().subtract(10)); // Subtract padding from width
        videoImageView.fitHeightProperty().bind(videoPane.heightProperty().subtract(10)); // Subtract padding from height
        videoImageView.setPreserveRatio(true); // Optional: maintain aspect ratio

        root.setCenter(videoPane);

        // Add control buttons
        Button playButton = new Button("Play");
        Button pauseButton = new Button("Pause");
        Button stopButton = new Button("Stop");

        // Set button actions
        playButton.setOnAction(e -> mediaPlayer.controls().play());
        pauseButton.setOnAction(e -> mediaPlayer.controls().pause());
        stopButton.setOnAction(e -> mediaPlayer.controls().stop());

        // Arrange buttons horizontally
        HBox controls = new HBox(10, playButton, pauseButton, stopButton);
        controls.setStyle("-fx-padding: 10; -fx-alignment: center;");

        root.setBottom(controls);

        primaryStage.setTitle("VLCJ JavaFX Player");
        primaryStage.setScene(scene);
        primaryStage.show();

        java.util.List<String> params = getParameters().getRaw();
        if (params.isEmpty()) {
            System.err.println("Usage: VLCJJavaFXExample <video-file>");
            System.exit(1);
        }
        mediaPlayer.media().play(params.get(0));
    }

    @Override
    public void stop() {
        mediaPlayer.release();
        mediaPlayerFactory.release();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
