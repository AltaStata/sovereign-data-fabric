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

import com.altastata.filesystem.common.CloudFile;
import com.altastata.filesystem.common.FileSystemHandler;
import com.altastata.ui.theme.UITheme;
import com.altastata.ui.util.VersionUtils;
import com.altastata.utils.Account;
import com.altastata.utils.AltaStataConfig;
import javafx.animation.Interpolator;
import javafx.animation.ScaleTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.concurrent.Task;
import javafx.geometry.Pos;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.control.ScrollPane.ScrollBarPolicy;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Line;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Text;
import javafx.stage.*;
import javafx.util.Duration;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scala.Tuple2;

import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Main application entry point for the AltaStata JavaFX Desktop Client.
 * 
 * Coordinates global application state, primary UI layout (scene/stage initialization),
 * user configurations, service initialization, and background thread execution.
 */
public class AltaStataApp extends Application {

	public static AltaStataConfig altaStataConfig = new AltaStataConfig();

	// it should run before any logger is initialized
	static {
		altaStataConfig.initLogbackConfigPath("application");
	}

	private static final int NUMBER_OF_OBJECTS_TO_REFRESH_LIST = 1000;
	public static final int INITIAL_COLUMN_NUMBER_BEFORE_INCREMENTATION = -1;
	private static Logger LOGGER = LoggerFactory.getLogger(AltaStataApp.class);

	static NavigationPane container = null;

	// http://stackoverflow.com/questions/15827599/javafx-secondary-screen-always-on-top-of-all-applications
	static Stage primaryStage = null;
	static ScrollPane scrollPane = null;
	static BorderPane borderPane = new BorderPane();
	static final Popup popup = new Popup();

	static Scene scene = null;

	final Set<Long> selectedTimestamps = new TreeSet<Long>();

	public static Account account = new Account();

	public static String ACCOUNTS_DIRECTORY = account.ALTASTATA_ACCOUNTS_HOME();

	// Service for handling account management operations
	static AccountManagementService accountManagementService;
	
	// Service for handling file operations
	private static FileOperationService fileOperationService;
	
	// Service for handling search operations
	private static SearchService searchService;

	static Task<Void> userMessagesTask = null;
	static String accountDirPath = null;

	static Button uploadButton = new Button();
	static Button downloadButton = new Button();
	static Button shareButton = new Button();
	static Button revokeButton = new Button();
	static Button deleteButton = new Button();
	static Button appsButton = new Button();

	/**
	 * Main application entry point for the AltaStata JavaFX desktop client.
	 *
	 * @param args command-line arguments
	 * @throws UnsupportedEncodingException if encoding is unsupported during path resolution
	 */
	public static void main(String[] args) throws UnsupportedEncodingException {
		/*
		// discover the APP_HOME and update ACCOUNTS_DIRECTORY
		// https://stackoverflow.com/questions/17540942/how-to-get-the-path-of-running-java-program

		final File f = new File(AltaStataApp.class.getProtectionDomain().getCodeSource().getLocation().getPath());

		if (f.getAbsolutePath().endsWith(".jar")) {
			String appHome = URLDecoder.decode(f.getAbsoluteFile().getParentFile().getParentFile().getAbsolutePath(),
					"UTF-8");
			ACCOUNTS_DIRECTORY = appHome + File.separator + ACCOUNTS_DIRECTORY;
		}
		 */

		LOGGER.info("ACCOUNTS_DIRECTORY: " + ACCOUNTS_DIRECTORY);

		Application.launch(args);
	}

	/**
	 * Starts the JavaFX application life-cycle and sets up the primary UI scene and services.
	 *
	 * @param primaryStage the primary application stage
	 */
	@Override
	public void start(Stage primaryStage) {
		// Assign to static variable for global access
		AltaStataApp.primaryStage = primaryStage;
		
		primaryStage.setTitle("AltaStata File Explorer - v" + VersionUtils.getVersion());

		VBox bottomBox = new VBox(createToolBar());
		bottomBox.getStylesheets().add("css/Toolbar.css");

		scene = new Scene(borderPane, 1060, 600);

		detectMode(scene);

		container = new NavigationPane(FileSystemHandler.INIT_DIR, scene.widthProperty());
		container.setLayoutX(10);
		container.setLayoutY(10);

		addContainerWithScollOrNot(borderPane);

		borderPane.setBottom(bottomBox);

		scene.widthProperty().addListener(sceneSizeChangedListener(scene));
		scene.heightProperty().addListener(sceneSizeChangedListener(scene));

		scene.getStylesheets().add("css/HeaderList.css");

		// Use only the primary stage as the main window
		primaryStage.setScene(scene);
		primaryStage.show();

		Platform.setImplicitExit(true);
		primaryStage.setOnCloseRequest((ae) -> {
			Platform.exit();
			System.exit(0);
		});

		// Initialize account management service
		accountManagementService = new AccountManagementService(account, ACCOUNTS_DIRECTORY, popup);
		
		// Initialize file operation service
		fileOperationService = new FileOperationService(account, container, primaryStage, popup, selectedTimestamps);
		
		// Initialize search service
		searchService = new SearchService(account, container);

		accountManagementService.selectAccount();
	}

	/**
	 * Gets the currently focused node within the main scene.
	 *
	 * @return currently focused UI node
	 */
	static public Node getCurrentlyFocusedNode() {
		return scene.getFocusOwner();
	}

	/**
	 * Configures and mounts the main navigation container with or without scrolling.
	 *
	 * @param borderPane root border pane
	 */
	private void addContainerWithScollOrNot(BorderPane borderPane) {
		if (NavigationPane.isMobileNavigation == false) {
			scrollPane = new ScrollPane();
			scrollPane.setContent(container);
			scrollPane.setFitToHeight(true);

			scrollPane.setHbarPolicy(ScrollBarPolicy.ALWAYS);
			scrollPane.setVbarPolicy(ScrollBarPolicy.NEVER);

			// listen for SplitPane width changes
			container.widthProperty().addListener((observable, oldValue, newValue) -> {
				scrollPane.setHvalue(scrollPane.getHmax());
			});
			borderPane.setCenter(scrollPane);
		} else {
			borderPane.setCenter(container);
		}
	}

	/**
	 * Creates a scene size change listener to adjust UI dimensions dynamically.
	 *
	 * @param scene the active scene
	 * @return a change listener for adjusting the navigation view
	 */
	private ChangeListener<? super Number> sceneSizeChangedListener(final Scene scene) {
		return (observable, oldValue, newValue) -> {
			if (detectMode(scene)) {
				container.createAndPopulateDirectoryList(INITIAL_COLUMN_NUMBER_BEFORE_INCREMENTATION,
						new CloudFile(FileSystemHandler.INIT_DIR, true));
				container.setCurrentDirectoryIndex(0);

				addContainerWithScollOrNot(borderPane);

				if (NavigationPane.isMobileNavigation) {
					container.mobileClickAndMoveForward(container.getCurrentDirectoryIndex());
				}
			}
		};
	}

	/**
	 * Detects mobile versus desktop layout modes based on current scene dimensions.
	 *
	 * @param scene the active UI scene
	 * @return true if layout mode changed
	 */
	private boolean detectMode(final Scene scene) {
		boolean wasChanged = false;

		if (scene.getWidth() != 0 && scene.getHeight() != 0) {
			if (scene.getWidth() < scene.getHeight()) {
				if (NavigationPane.isMobileNavigation == false)
					wasChanged = true;
				if (NavigationPane.isMobileSelect == false)
					wasChanged = true;

				NavigationPane.isMobileNavigation = true;
				NavigationPane.isMobileSelect = true;
			} else {
				if (NavigationPane.isMobileNavigation)
					wasChanged = true;
				if (NavigationPane.isMobileSelect)
					wasChanged = true;

				NavigationPane.isMobileNavigation = false;
				NavigationPane.isMobileSelect = false;
			}
		}

		return wasChanged;
	}

	// Cloud files loading method moved to AccountManagementService

	/**
	 * Creates and initializes the main application toolbar with action buttons.
	 *
	 * @return the fully configured ToolBar component
	 */
	private ToolBar createToolBar() {

		downloadButton.setPadding(UITheme.PADDING_TIGHT);
		ImageView downloadImageView = new ImageView(new Image("images/download_cloud.png"));
		downloadImageView.setFitWidth(UITheme.ICON_SIZE_TOOLBAR);
		downloadImageView.setFitHeight(UITheme.ICON_SIZE_TOOLBAR);
		downloadButton.setGraphic(downloadImageView);
		downloadButton.setId("downloadButton");
		downloadButton.setTooltip(new Tooltip("Download... "));
		downloadButton.setFocusTraversable(false);

		downloadButton.setOnMouseClicked(me -> {
			if (accountManagementService.checkOrInputPassword()) {
				fileOperationService.handleDownloadButton();
			}
		});

		uploadButton.setPadding(UITheme.PADDING_TIGHT);
		ImageView uploadImageView = new ImageView(new Image("images/upload_cloud.png"));
		uploadImageView.setFitWidth(UITheme.ICON_SIZE_TOOLBAR);
		uploadImageView.setFitHeight(UITheme.ICON_SIZE_TOOLBAR);
		uploadButton.setGraphic(uploadImageView);
		uploadButton.setId("uploadButton");
		uploadButton.setTooltip(new Tooltip("Upload... "));
		uploadButton.setFocusTraversable(false);

		uploadButton.setOnMouseClicked(me -> {
			if (accountManagementService.checkOrInputPassword()) {
				fileOperationService.handleUploadButton();
			}
		});

		shareButton.setPadding(UITheme.PADDING_TIGHT);
		ImageView shareImageView = new ImageView(new Image("images/share_key.png"));
		shareImageView.setFitWidth(UITheme.ICON_SIZE_TOOLBAR);
		shareImageView.setFitHeight(UITheme.ICON_SIZE_TOOLBAR);
		shareButton.setGraphic(shareImageView);
		shareButton.setId("shareButton");
		shareButton.setTooltip(new Tooltip("Share... "));
		shareButton.setFocusTraversable(false);

		shareButton.setOnMouseClicked(me -> {
			if (accountManagementService.checkOrInputPassword()) {
				fileOperationService.handleShareButton();
			}
		});

		revokeButton.setPadding(UITheme.PADDING_TIGHT);
		StackPane revokeStackPane = new StackPane();
		ImageView revokeImageView = new ImageView(new Image("images/share_key.png"));
		revokeImageView.setFitWidth(UITheme.ICON_SIZE_TOOLBAR);
		revokeImageView.setFitHeight(UITheme.ICON_SIZE_TOOLBAR);
		revokeStackPane.getChildren().add(revokeImageView);
		// X overlay: "do not share" / revoke access
		double revokeIconSize = UITheme.ICON_SIZE_TOOLBAR;
		double xMargin = revokeIconSize * 0.2;
		Line xLine1 = new Line(xMargin, xMargin, revokeIconSize - xMargin, revokeIconSize - xMargin);
		Line xLine2 = new Line(revokeIconSize - xMargin, xMargin, xMargin, revokeIconSize - xMargin);
		xLine1.setStroke(Color.DARKRED);
		xLine2.setStroke(Color.DARKRED);
		xLine1.setStrokeWidth(4.5);
		xLine2.setStrokeWidth(4.5);
		Group xOverlay = new Group(xLine1, xLine2);
		StackPane.setAlignment(xOverlay, Pos.CENTER);
		revokeStackPane.getChildren().add(xOverlay);
		revokeButton.setGraphic(revokeStackPane);
		revokeButton.setId("revokeButton");
		revokeButton.setTooltip(new Tooltip("Revoke access... "));
		revokeButton.setFocusTraversable(false);

		revokeButton.setOnMouseClicked(me -> {
			if (accountManagementService.checkOrInputPassword()) {
				fileOperationService.handleRevokeButton();
			}
		});

		StackPane filterStackPane = new StackPane();
		filterStackPane.setAlignment(Pos.CENTER_RIGHT);
		TextField editBox = new TextField();
		editBox.setMaxWidth(70);
		editBox.setMinHeight(20);
		editBox.setPromptText("Filter");
		editBox.setFocusTraversable(false);
		
		// for each symbol type
		// editBox.textProperty().addListener( (observable, oldVal, newVal) -> {
		// handleSearchByKey((String)newVal);
		// });

		editBox.setOnKeyPressed(ke -> {
			if (ke.getCode().equals(KeyCode.ENTER)) {
				searchService.handleSearchByKey(editBox.getText());
			}
		});

		double tbHeight = 20;// = bind original.boundsInLocal.height;
		double iconRadius = tbHeight * 0.15;

		Group searchIcon = new Group();
		searchIcon.setTranslateX(-5);
		searchIcon.setRotate(45);
		Circle cir = new Circle();
		cir.setRadius(iconRadius);
		cir.setFill(Color.WHITE);
		cir.setStroke(Color.GREY);
		cir.setStrokeWidth(iconRadius * 0.5);
		Rectangle rect = new Rectangle();
		rect.setTranslateX(iconRadius);
		rect.setTranslateY(0 - iconRadius * 0.25);
		rect.setWidth(iconRadius * 1.5);
		rect.setHeight(iconRadius * 0.5);
		rect.setFill(Color.GRAY);
		searchIcon.getChildren().setAll(cir, rect);
		filterStackPane.getChildren().addAll(editBox, searchIcon);

		Button createDirButton = new Button();
		createDirButton.setPadding(UITheme.PADDING_TIGHT);
		ImageView createDirView = new ImageView(new Image("images/create_dir.png"));
		createDirView.setFitWidth(UITheme.ICON_SIZE_TOOLBAR);
		createDirView.setFitHeight(UITheme.ICON_SIZE_TOOLBAR);
		createDirButton.setGraphic(createDirView);
		createDirButton.setId("createDirButton");
		createDirButton.setTooltip(new Tooltip("Create directory... "));
		createDirButton.setFocusTraversable(false);

		createDirButton.setOnMouseClicked(me -> fileOperationService.handleCreateButton());

		deleteButton.setPadding(UITheme.PADDING_TIGHT);
		ImageView deleteView = new ImageView(new Image("images/editing-delete-icon.png"));
		deleteView.setFitWidth(UITheme.ICON_SIZE_TOOLBAR);
		deleteView.setFitHeight(UITheme.ICON_SIZE_TOOLBAR);
		deleteButton.setGraphic(deleteView);
		deleteButton.setId("deleteButton");
		deleteButton.setTooltip(new Tooltip("Delete ... "));
		deleteButton.setFocusTraversable(false);

		deleteButton.setOnMouseClicked(me -> {
			if (accountManagementService.checkOrInputPassword()) {
				fileOperationService.handleDeleteButton();
			}
		});

		appsButton.setPadding(UITheme.PADDING_TIGHT);

		ImageView appsView = new ImageView(new Image("images/apps.png"));
		appsView.setFitWidth(UITheme.ICON_SIZE_TOOLBAR);
		appsView.setFitHeight(UITheme.ICON_SIZE_TOOLBAR);

		StackPane appsStackPane = new StackPane();

		ImageView bellView = new ImageView();
		bellView.setImage(new Image("images/bell.png"));
		bellView.setPreserveRatio(true);
		bellView.setSmooth(true);
		bellView.setCache(true);
		bellView.setFitWidth(20);
		bellView.setFitHeight(20);

		StackPane.setAlignment(bellView, Pos.CENTER);

		appsStackPane.getChildren().add(appsView);

		appsButton.setGraphic(appsStackPane);
		appsButton.setId("appsButton");
		appsButton.setTooltip(new Tooltip("Apps ... "));
		appsButton.setFocusTraversable(false);

		appsStackPane.setOnMouseClicked(me -> {
			if (appsStackPane.getChildren().contains(bellView)) {
				appsStackPane.getChildren().remove(bellView);
			}

			container.refreshAllDirectories();
		});

		userMessagesTask = new Task<Void>() {
			int lastSize = 0;

			@Override
			public Void call() {
				while (true) {
					if (account.getUserMsgs().size() > lastSize) {
						// TODO: check if there is SHARED
						Platform.runLater(new Runnable() {
							@Override
							public void run() {

								if (appsStackPane.getChildren().contains(bellView) == false) {
									// "ring the bell"
									appsStackPane.getChildren().add(bellView);
								}

								appsButton.setOpacity(0.6);
							}
						});
					} else {
						Platform.runLater(new Runnable() {
							@Override
							public void run() {
								if (appsButton.getOpacity() == 0.6) {
									appsButton.setOpacity(1);
								}
							}
						});
					}

					lastSize = account.getUserMsgs().size();

					try {
						Thread.sleep(1000);
					} catch (Exception ignore) {
					}
				}
			}
		};

		new Thread(userMessagesTask).start();

		new SetupUI().init(appsButton, primaryStage, borderPane, popup);

		// Push Apps to the right edge of the toolbar.
		HBox paneThatAlwaysGrows = new HBox();
		HBox.setHgrow(paneThatAlwaysGrows, Priority.ALWAYS);

		ToolBar toolBar = new ToolBar(createDirButton, deleteButton,
				// new Separator(Orientation.VERTICAL),
				filterStackPane,
				// new Separator(Orientation.VERTICAL),
				downloadButton, uploadButton, shareButton, revokeButton,
				paneThatAlwaysGrows, appsButton);

		return toolBar;
	}

	/**
	 * Helper method to show an error alert from non-JavaFX background threads.
	 * Schedules the alert display on the JavaFX Application Thread.
	 *
	 * @param header  The title/header of the alert.
	 * @param content The error message content.
	 */
	public static void popupHandlerErrorAlert(String header, String content) {
		Platform.runLater(new Runnable() {
			@Override
			public void run() {
				UIUtils.showErrorAlert(header, content);
			}
		});
	}

	/**
	 * Creates a background task to process file uploads/stores.
	 *
	 * @param listForSubTree The list of files to transfer mapped to their cloud paths.
	 * @return An active {@link Task} for execution.
	 */
	public static Task<Void> storeTask(List<Tuple2<File, CloudFile>> listForSubTree) {
		return fileOperationService.createStoreTask(listForSubTree);
	}
	

	/**
	 * Creates a background task to process file renames/moves.
	 *
	 * @param listForSubTree The cloud files to rename.
	 * @param oldPrefix      The old absolute path prefix.
	 * @param newPrefix      The target path prefix.
	 * @return An active {@link Task} for execution.
	 */
	public static Task<Void> renameTask(List<CloudFile> listForSubTree, String oldPrefix, String newPrefix) {
		return fileOperationService.createRenameTask(listForSubTree, oldPrefix, newPrefix);
	}
	

	/**
	 * Checks if the given popup stage wrapper is currently visible.
	 *
	 * @param popup The target {@link Popup}.
	 * @return {@code true} if showing, {@code false} otherwise.
	 */
	public static boolean isPopupShowing(Popup popup) {
		// Get the stage from the popup properties
		Stage popupStage = (Stage) popup.getProperties().get("stage");
		return popupStage != null && popupStage.isShowing();
	}

	/**
	 * Safely hides/closes the given popup stage wrapper if it is showing.
	 *
	 * @param popup The target {@link Popup}.
	 */
	public static void hidePopup(Popup popup) {
		// Get the stage from the popup properties
		Stage popupStage = (Stage) popup.getProperties().get("stage");
		if (popupStage != null && popupStage.isShowing()) {
			popupStage.close();
		}
	}

	/**
	 * Emulates a popup/modal dialog behavior by creating a customized transparent {@link Stage}
	 * on top of the primary window. Leverages modally-focused wrappers and smooth scale zoom transitions.
	 *
	 * @param popup      The reference {@link Popup} tracker.
	 * @param pane       The layout pane content to mount.
	 * @param isAutoHide If {@code true}, clicking outside of the pane bounds will close the window.
	 */
	public static void showPopup(Popup popup, Pane pane, boolean isAutoHide) {
		// Check if there's already a stage associated with this popup and close it first
		hidePopup(popup);
		
		// Use Stage instead of Popup for consistent cross-platform behavior
		Stage popupStage = new Stage();
		popupStage.initOwner(primaryStage);
		popupStage.initModality(Modality.WINDOW_MODAL);
		popupStage.initStyle(StageStyle.TRANSPARENT);
		
		// Simple wrapper for the content
		StackPane wrapper = new StackPane(pane);
		wrapper.setStyle("-fx-background-color: transparent;");
		
		Scene popupScene = new Scene(wrapper);
		popupScene.setFill(javafx.scene.paint.Color.TRANSPARENT);
		popupStage.setScene(popupScene);
		popupStage.setResizable(true);
		
		// Click-outside to close (if auto-hide enabled)
		if (isAutoHide) {
			wrapper.setOnMouseClicked(event -> {
				if (event.getTarget() == wrapper) {
					popupStage.close();
				}
			});
		}
		
		// ESC key handling
		popupScene.setOnKeyPressed(event -> {
			if (event.getCode() == KeyCode.ESCAPE) {
				popupStage.close();
				event.consume();
			}
		});
		
		wrapper.setFocusTraversable(true);
		
		// Store stage reference for compatibility
		popup.getProperties().put("stage", popupStage);
		
		// Handle timing and animation
		popupStage.setOnShown(windowEvent -> {
			popupStage.sizeToScene();
			centerPopupStage(popupStage);
			
			// Scale animation
			ScaleTransition scaleTransition = new ScaleTransition(Duration.seconds(0.4), pane);
			scaleTransition.setInterpolator(Interpolator.EASE_BOTH);
			scaleTransition.setToX(1);
			scaleTransition.setToY(1);
			scaleTransition.setFromX(0);
			scaleTransition.setFromY(0);
			scaleTransition.play();
			
			wrapper.requestFocus();
		});
		
		// Dynamic resizing - single listener on pane bounds
		pane.layoutBoundsProperty().addListener((observable, oldBounds, newBounds) -> {
			if (popupStage.isShowing() && !oldBounds.equals(newBounds)) {
				Platform.runLater(() -> {
					popupStage.sizeToScene();
					centerPopupStage(popupStage);
				});
			}
		});
		
		// Cleanup on close
		popupStage.setOnCloseRequest(event -> {
			popup.getProperties().remove("stage");
		});
		
		popupStage.show();
	}

	/**
	 * Centers the popover Stage relative to the parent primary stage coordinates.
	 *
	 * @param popupStage The target {@link Stage} to position.
	 */
	private static void centerPopupStage(Stage popupStage) {
		// Center the popup stage on the parent stage
		double centerX = primaryStage.getX() + (primaryStage.getWidth() / 2) - (popupStage.getWidth() / 2);
		double centerY = primaryStage.getY() + (primaryStage.getHeight() / 2) - (popupStage.getHeight() / 2);
		
		popupStage.setX(centerX);
		popupStage.setY(centerY);
	}
}
