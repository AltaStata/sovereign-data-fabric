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
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;

import java.awt.image.BufferedImage;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

public class PDFViewer extends Application {

    @Override
    public void start(Stage primaryStage) {
        // Create a TextField for PDF file path input
        TextField pdfPathField = new TextField();
        pdfPathField.setPromptText("Enter PDF file path");

        // Create a Button to trigger PDF rendering
        Button renderButton = new Button("Render PDF");

        // Create a ProgressIndicator
        ProgressIndicator progressIndicator = new ProgressIndicator();
        progressIndicator.setVisible(false);

        // Create a StackPane for displaying PDF images
        StackPane pdfPane = new StackPane();

        // Wrap the StackPane in a ScrollPane for scrolling
        ScrollPane scrollPane = new ScrollPane(pdfPane);
        scrollPane.setFitToWidth(true); // Ensures the content fits to the width of the ScrollPane
        scrollPane.setFitToHeight(true); // Ensures the content fits to the height of the ScrollPane

        // Set up the layout with VBox
        VBox layout = new VBox(10, pdfPathField, renderButton, progressIndicator, scrollPane);
        Scene scene = new Scene(layout, 800, 600);
        primaryStage.setTitle("PDF Viewer");
        primaryStage.setScene(scene);
        primaryStage.show();

        // Button action to load and render the PDF
        renderButton.setOnAction(event -> {
            String pdfPath = pdfPathField.getText();
            if (!pdfPath.isEmpty()) {
                // Show the progress indicator while loading
                progressIndicator.setVisible(true);
                pdfPane.getChildren().clear(); // Clear previous image

                // Create a Task to load and render the PDF in the background
                Task<Image> task = new Task<>() {
                    @Override
                    protected Image call() throws Exception {
                        try (InputStream inputStream = new FileInputStream(pdfPath)) {
                            return renderPDFPage(inputStream, 0); // Render the first page
                        }
                    }
                };

                // When the task finishes, update the UI with the rendered image
                task.setOnSucceeded(event1 -> {
                    // Ensure UI updates are done on the JavaFX Application Thread
                    Platform.runLater(() -> {
                        Image pdfImage = task.getValue();
                        ImageView imageView = new ImageView(pdfImage);
                        imageView.setPreserveRatio(true);
                        imageView.setFitWidth(800);

                        // Replace progress indicator with image view
                        pdfPane.getChildren().clear();
                        pdfPane.getChildren().add(imageView);
                        progressIndicator.setVisible(false); // Hide the progress indicator
                    });
                });

                // Handle any errors that occur during the task
                task.setOnFailed(event1 -> {
                    task.getException().printStackTrace();
                    System.err.println("Error loading PDF.");
                    progressIndicator.setVisible(false); // Hide progress indicator if error occurs
                });

                // Run the task in a background thread
                Thread backgroundThread = new Thread(task);
                backgroundThread.setDaemon(true); // Ensures it doesn't block application shutdown
                backgroundThread.start();
            } else {
                System.err.println("Please enter a valid PDF path.");
            }
        });
    }

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

    public static void main(String[] args) {
        launch(args);
    }
}
