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
import com.altastata.filesystem.common.CloudFile;
import com.altastata.filesystem.common.FileSystemHandler;
import com.altastata.filesystem.common.VersionAttributes;
// DataAttribute imports removed - now using simple String types
import com.altastata.ui.theme.UITheme;
import javafx.animation.Transition;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyDoubleProperty;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.event.EventHandler;
import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.*;
import javafx.scene.layout.*;
import javafx.scene.text.Text;
import javafx.scene.text.TextAlignment;
import javafx.util.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.DecimalFormat;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class NavigationPane extends SplitPane {
	final static private double SLIDE_DURATION_LEFT = 1000.0;
	final static private double SLIDE_DURATION_RIGHT = 800.0;
	final static private long DOUBLE_PRESS_THRESHOLD = 300; // Milliseconds

	// Single-click auto-previews latest version when it's <= this (cheap fetch) or cached.
	final static private long AUTO_PREVIEW_SIZE_BYTES = 512L * 1024L;

	boolean ignoreChanges = false;
	public static boolean isMobileNavigation = false;
	public static boolean isMobileSelect = false;

	private static Logger LOGGER = LoggerFactory.getLogger(NavigationPane.class);

	int currentDirectoryIndex = -1;

	//static {
	//	System.setProperty("vlcj.log", "DEBUG");
	//}

	/**
	 * Creates a new NavigationPane instance.
	 *
	 * @param initDir the initial directory to list
	 * @param sceneWidthProperty the read-only double property of the scene width
	 */
	public NavigationPane(String initDir, ReadOnlyDoubleProperty sceneWidthProperty) {
		createAndPopulateDirectoryList(AltaStataApp.INITIAL_COLUMN_NUMBER_BEFORE_INCREMENTATION,
				new CloudFile(initDir, true));

		setMinWidth(sceneWidthProperty.doubleValue());
		
		sceneWidthProperty.addListener((observable, oldValue, newValue) -> {
			setMinWidth(newValue.doubleValue());
		});
	}

	/**
	 * Handles mouse click events on a directory list.
	 *
	 * @param me mouse event context
	 * @param dlist target directory list
	 * @param clickCount mouse click count
	 */
	public void onListMousePressed(MouseEvent me, DirectoryList dlist, int clickCount) {
		onListPressed(me, dlist, clickCount);

		// Check for right button or Command-Button on OSX
		if (me instanceof MouseEvent
				&& (((MouseEvent) me).getButton() == MouseButton.SECONDARY || ((MouseEvent) me).isMetaDown())) {

			// do not open files -- Display file details
			// Desktop.getDesktop().open(file);
		}
	}

	/**
	 * Handles input events (keyboard/mouse) on a directory list with a single click count default.
	 *
	 * @param event input event
	 * @param dlist target directory list
	 */
	public void onListPressed(InputEvent event, DirectoryList dlist) {
		onListPressed(event, dlist, 1);
	}

	/**
	 * Handles input events (keyboard/mouse) on a directory list.
	 *
	 * @param event input event
	 * @param dlist target directory list
	 * @param clickCount click count
	 */
	public void onListPressed(InputEvent event, DirectoryList dlist, int clickCount) {
		LOGGER.trace("\tonListPressed: " + dlist);

		// if the OSX Command Key (right click simulation) was released, do not do
		// anything
		if (event instanceof KeyEvent && (((KeyEvent) event).getCode().equals(KeyCode.COMMAND))) {
			return;
		}

		setCurrentDirectoryIndex(findIndex(dlist));

		if (NavigationPane.isMobileSelect) {
			dlist.cleanMobileSelectedOlItems();
		}

		// Get the selected text within the dlist
		CloudFile lastClickedFile = dlist.getListView().getSelectionModel().getSelectedItem();
		if (lastClickedFile != null) {
			// check if double click
			createSubDirectoryAndNavigate(dlist, lastClickedFile, (clickCount == 2)?true:false);
		}
	}

	/**
	 * Navigates into a sub-directory with no file preview default.
	 *
	 * @param dlist active directory list
	 * @param lastClickedFile target directory to enter
	 * @return new directory list column populated
	 */
	public DirectoryList createSubDirectoryAndNavigate(DirectoryList dlist, CloudFile lastClickedFile) {
		return createSubDirectoryAndNavigate(dlist, lastClickedFile, false);
	}

	/**
	 * Navigates into a sub-directory or opens a file report detail pane.
	 *
	 * @param dlist active directory list
	 * @param lastClickedFile target cloud file
	 * @param showFilePreview whether to trigger file preview download/rendering
	 * @return new directory list column populated if folder entered, or null if file selected
	 */
	public DirectoryList createSubDirectoryAndNavigate(DirectoryList dlist, CloudFile lastClickedFile, boolean showFilePreview) {
		DirectoryList result = null;

		int index = findIndex(dlist);

		LOGGER.trace("\tcreateSubDirectoryAndNavigate dlist: " + dlist.getDirectoryCloudFile().getPath()
				+ " index: " + index + " file: " + lastClickedFile.getPath());

		if (lastClickedFile.isDirectory()) {
			result = createAndPopulateDirectoryList(index, lastClickedFile);
		} else {
			for (int i = getItems().size() - 1; i > index; i--) {
				getItems().remove(i);
			}

			VerticalBox fileReportBox = createFileReportBox(lastClickedFile, showFilePreview);
			
			// only I can resize it
			// TODO: its a work around work the navigation backwards and mouse click on
			// previous directories
			if (isMobileNavigation == false) {
				SplitPane.setResizableWithParent(fileReportBox, Boolean.FALSE);
			}
						
			getItems().add(fileReportBox);

			if (isMobileNavigation == false) {
				adjustGeometryProgressionForColumnsSizes();
			}
		}

		return result;
	}

	/**
	 * Creates and populates a search result list column.
	 *
	 * @param found matching cloud files set
	 * @return populated search list column
	 */
	public SearchList createAndPopulateSearchList(Set<CloudFile> found) {
		SearchList searchList = new SearchList(this);
		searchList.setItems(found);

		getItems().clear();
		getItems().add(searchList);

		return searchList;
	}

	/**
	 * Creates and populates a directory list column.
	 *
	 * @param prevIndex previous list column index
	 * @param file target directory to list
	 * @return populated directory list column
	 */
	public DirectoryList createAndPopulateDirectoryList(int prevIndex, CloudFile file) {
		LOGGER.trace("\tcreateAndPopulateDirectoryList: " + file.getPath() + " prevIndex: " + prevIndex);

		for (int i = getItems().size() - 1; i > prevIndex; i--) {
			getItems().remove(i);
		}

		DirectoryList dlist = new DirectoryList(file, true, this);
		dlist.getListView().getSelectionModel().clearSelection();

		populateDirectoryListAsync(dlist, file);

		// only I can resize it
		// TODO: its a work around for the navigation backwards and mouse click on
		// previous directories
		SplitPane.setResizableWithParent(dlist, Boolean.FALSE);

		LOGGER.trace(
				"\tcreateAndPopulateDirectoryList: Adding " + dlist.getHeader() + " to panel: " + getItems().size());
		getItems().add(dlist);

		if (isMobileNavigation == false) {
			adjustGeometryProgressionForColumnsSizes();
		}

		// TODO: for mobile navigation use PressAndHoldHandler as the keyboard in
		// sensitive
		// I tried the solution at
		// http://stackoverflow.com/questions/25601266/how-to-achieve-javafx-mouse-event-push-and-hold
		// but it did not work well on the phone
		dlist.getListView().setOnMousePressed(mousePressHandler(dlist));

		dlist.getListView().setOnKeyReleased(keyReleaseHandler(dlist));

		return dlist;
	}

	private long lastEnterPressTime = 0;

	/**
	 * Returns a key release event handler for navigating directory lists.
	 *
	 * @param dlist active directory list
	 * @return event handler for key release events
	 */
	private EventHandler<KeyEvent> keyReleaseHandler(DirectoryList dlist) {
		return ke -> {
			// select the first item of the right list in case that the Right arrow is
			// pressed
			if (isMobileNavigation == false) {
				setCurrentDirectoryIndex(findIndex(dlist));

				if (ke.getCode().equals(KeyCode.RIGHT)) {
					VerticalBox vb = getDirectoryForIndex(currentDirectoryIndex + 1);

					if (vb != null && vb instanceof DirectoryList) {
						setCurrentDirectoryIndex(currentDirectoryIndex + 1);

						DirectoryList currentDir = getCurrentDirectory();

						// make sure its before onListPressed() as we need an indication what to
						// populate
						currentDir.getListView().getSelectionModel().select(0);

						if (AltaStataApp.accountManagementService.checkOrInputPassword()) {
							onListPressed(ke, currentDir);
						}

						// move focus right
						currentDir.getListView().scrollTo(0);
						currentDir.getListView().requestFocus();
					}
				} else if (ke.getCode().equals(KeyCode.LEFT) || ke.getCode().equals(KeyCode.ESCAPE)) {
					if (currentDirectoryIndex > 0) {
						setCurrentDirectoryIndex(currentDirectoryIndex - 1);

						DirectoryList currentDir = getCurrentDirectory();

						// make sure its before onListPressed()
						// move focus left
						currentDir.getListView().requestFocus();

						onListPressed(ke, currentDir);
					} else if (currentDirectoryIndex == 0) {
						setCurrentDirectoryIndex(currentDirectoryIndex - 1);

						this.requestFocus();
					}
				} else if (ke.getCode().equals(KeyCode.ENTER)) {
					long currentTime = System.currentTimeMillis();
					if (currentTime - lastEnterPressTime <= DOUBLE_PRESS_THRESHOLD) {
						lastEnterPressTime = currentTime;
						onListPressed(ke, dlist, 2);
					}
					else {
						lastEnterPressTime = currentTime;
						onListPressed(ke, dlist, 1);
					}
				} else {
					LOGGER.debug("keyReleaseHandler: Unknown key pressed " + ke.getCode());
				}
			}
		};
	}

	/**
	 * Returns a mouse press event handler for directory list interaction.
	 *
	 * @param dlist active directory list
	 * @return event handler for mouse press events
	 */
	private EventHandler<MouseEvent> mousePressHandler(DirectoryList dlist) {
		return me -> {
			if (AltaStataApp.accountManagementService.checkOrInputPassword()) {
				onListMousePressed(me, dlist, me.getClickCount());

				if (isMobileNavigation) {
					mobileClickAndMoveForward(findIndex(dlist));
				}
			}
		};
	}

	/**
	 * Open the divider on index part of the SplitPane Let to user to make a choice
	 * Change focus to index + 1 part of the SplitPane
	 * 
	 * @param index
	 */
	public void mobileClickAndMoveForward(int index) {
		LOGGER.trace("\tmobileClickAndMoveForward: " + index);
		for (int i = 0; i < getDividers().size(); i++) {
			if (i <= index) {

				if (i == index) { // do animation
					// set the initial position for transition between 1.0 -> 0.0
					getDividers().get(i).setPosition(1);

					SlideTransition slideTransition = new SlideTransition(Duration.millis(SLIDE_DURATION_RIGHT),
							getDividers().get(i), 0.0);
					slideTransition.play();
				} else {
					getDividers().get(i).setPosition(0);
				}

				// If you do not want the animation: getDividers().get(i).setPosition(0);

				// change focus
				if (i == index) {
					Platform.runLater(new Runnable() {
						@Override
						public void run() {
							if (getItems().get(index + 1) instanceof DirectoryList) {
								setCurrentDirectoryIndex(index + 1);
							}

							getItems().get(index + 1).requestFocus();
						}
					});
				}
			} else {
				getDividers().get(i).setPosition(1.0);
			}
		}
	}

	/**
	 * Navigates backward in mobile layout mode.
	 *
	 * @param index index to navigate backward from
	 * @param keyCode keyboard key trigger
	 */
	public void mobileNavigationBackward(int index, KeyCode keyCode) {
		LOGGER.trace("\tmobileNavigationBackward to: " + index);

		setCurrentDirectoryIndex(index - 1);

		for (int i = 0; i < getDividers().size(); i++) {
			LOGGER.trace("\tmobileNavigationBackward: i = " + i + " index = " + index + " getItems().get(index) = "
					+ getItems().get(index));

			if (i < index) {
				getDividers().get(i).setPosition(0.0);
			} else {
				// set the initial position for transition between 0.0 -> 1.0
				getDividers().get(i).setPosition(0.0);

				SlideTransition slideTransition = new SlideTransition(Duration.millis(SLIDE_DURATION_LEFT),
						getDividers().get(i), 1.0);
				slideTransition.play();

				slideTransition.setOnFinished(ev -> {
					getItems().remove(getItems().size() - 1);
					getItems().get(getItems().size() - 1).requestFocus();
				});
			}
		}
	}

	/**
	 * Transition by animation
	 *
	 */
	private class SlideTransition extends Transition {
		Divider divider;
		double finalPosition;

		/**
		 * Creates a new SlideTransition.
		 *
		 * @param cycleDuration the duration of the cycle
		 * @param divider       the split pane divider to move
		 * @param finalPosition the final position of the divider
		 */
		public SlideTransition(final Duration cycleDuration, Divider divider, double finalPosition) {
			// LOGGER.trace("\tSlideTransition: " + cycleDuration + " divider: " +
			// divider.getPosition() + " finalPosition: " + finalPosition);
			setCycleDuration(cycleDuration);
			this.divider = divider;
			this.finalPosition = finalPosition;
		}

		/**
		 * Interpolates the animation position.
		 *
		 * @param d the interpolation value
		 */
		@Override
		protected void interpolate(double d) {
			if (finalPosition == 0.0) {
				if (divider.getPosition() > 0.0) {
					divider.setPosition(divider.getPosition() - d);
				} else {// DONE
					divider.setPosition(0.0);
					stop();
				}
			} else {
				// LOGGER.trace("\tinterpolate finalPosition == 1.0 d: " + d + "
				// divider.getPosition(): " + divider.getPosition());
				if (divider.getPosition() < 1.0) {
					divider.setPosition(divider.getPosition() + d);
				} else {// DONE
					divider.setPosition(1.0);
					stop();
				}
			}
		}
	}

	/**
	 * Computes the unified listing path for the given file.
	 *
	 * @param file cloud file directory
	 * @return absolute or relative directory listing path
	 */
	private static String computeListingPath(CloudFile file) {
		String parentPath = file.getParent();
		String dirname = file.getName();

		if (dirname != null) {
			if (parentPath.equals(FileSystemHandler.INIT_DIR) == false) {
				parentPath += "/";
			}
			parentPath += dirname;
		}

		return parentPath;
	}

	/**
	 * Applies the directory list header based on file name or parent.
	 *
	 * @param list directory list column
	 * @param file directory cloud file
	 */
	private void applyDirectoryListHeader(DirectoryList list, CloudFile file) {
		String dirname = file.getName();
		if (dirname != null) {
			list.setHeader(dirname);
		} else {
			list.setHeader(file.getParent());
		}
	}

	/**
	 * Load directory contents off the JavaFX thread so cloud/catalog I/O does not freeze the UI.
	 * Partial snapshots are painted as the cloud list pages in (first items ASAP).
	 */
	private void populateDirectoryListAsync(DirectoryList list, CloudFile file) {
		applyDirectoryListHeader(list, file);
		final String parentPath = computeListingPath(file);
		final boolean listInUse = file.getOperationState().equals(OperationState.DELETING);

		if (AltaStataApp.account == null) {
			return;
		}

		backgroundExecutor.submit(() -> {
			try {
				if (listInUse) {
					Collection<CloudFile> results =
							AltaStataApp.account.getFileSystemHandler().listDirectoryInUse(parentPath);
					publishDirectoryItems(list, parentPath,
							results == null ? new CloudFile[0] : results.toArray(new CloudFile[0]));
					return;
				}

				AltaStataApp.account.getFileSystemHandler().listDirectory(parentPath, items ->
						publishDirectoryItems(list, parentPath, items));
			} catch (Throwable ex) {
				LOGGER.warn("populateDirectoryListAsync failed for {}", parentPath, ex);
			}
		});
	}

	private void publishDirectoryItems(DirectoryList list, String parentPath, CloudFile[] items) {
		final CloudFile[] snapshot = items != null ? items : new CloudFile[0];
		Platform.runLater(() -> {
			if (parentPath.equals(computeListingPath(list.getDirectoryCloudFile())) == false) {
				return;
			}
			LOGGER.trace("\tpopulateDirectoryListAsync parentPath: {} size: {}", parentPath, snapshot.length);
			list.setItems(snapshot);
		});
	}

	DecimalFormat formatter = new DecimalFormat("#,###");

	// Track which version is currently expanded (-1 means none)
	private int expandedVersionIndex = -1;
	
	/**
	 * Simple method to handle magnifying glass clicks
	 */
	private void onMagnifyingGlassClick(int versionIndex, List<VersionAttributes> versionList, List<Task<String>> sizeTasks, GridPane fileVersionsPane, DecimalFormat formatter, CloudFile file, VerticalBox vbh) {
		if (AltaStataApp.accountManagementService.checkOrInputPassword()) {
			expandedVersionIndex = versionIndex;
			expandVersion(versionIndex, versionList, sizeTasks, fileVersionsPane, formatter, file, vbh);
		}
	}
	
	/**
	 * Show all versions in normal state with magnifying glasses
	 */
	private void showAllVersionsNormal(List<VersionAttributes> versionList, List<Task<String>> sizeTasks, GridPane fileVersionsPane, DecimalFormat formatter, CloudFile file, VerticalBox vbh) {
		// Clear all version elements
		List<Node> toRemove = new ArrayList<>();
		for (Node child : fileVersionsPane.getChildren()) {
			if (GridPane.getRowIndex(child) != null && GridPane.getRowIndex(child) > 0) {
				toRemove.add(child);
			}
		}
		fileVersionsPane.getChildren().removeAll(toRemove);
		
		// Rebuild all versions in normal state
		for (int i = 0; i < versionList.size(); i++) {
			VersionAttributes versionAttributes = versionList.get(i);
			String sizeText = sizeTasks.get(i).getValue();
			if (sizeText != null) {
				Label size = new Label(sizeText);
				size.setWrapText(true);
				fileVersionsPane.add(size, 0, 1 + i);
			}
			
			ImageView more = new ImageView();
			more.setImage(new Image("images/preview-icon.png", true));
			fileVersionsPane.add(more, 1, 1 + i);
			
			// Set up click handler
			final int index = i;
			more.setOnMouseClicked(me -> {
				onMagnifyingGlassClick(index, versionList, sizeTasks, fileVersionsPane, formatter, file, vbh);
			});
		}
	}
	
	/**
	 * Expand a specific version and show others in normal state
	 */
	private void expandVersion(int versionIndex, List<VersionAttributes> versionList, List<Task<String>> sizeTasks, GridPane fileVersionsPane, DecimalFormat formatter, CloudFile file, VerticalBox vbh) {
		// Create a task for loading the expanded version data
		Task<Map<String, String>> expandTask = new Task<Map<String, String>>() {
			@Override
			protected Map<String, String> call() throws Exception {
				VersionAttributes versionAttributes = versionList.get(versionIndex);
				Map<String, String> result = new HashMap<>();
				
				try {
					// Load size and readers data
					String dataSizeAttribute = versionAttributes.getVersionDataAttribute("size");
					String dataReadersAttribute = versionAttributes.getVersionDataAttribute("readers");
					
					result.put("size", dataSizeAttribute != null ? dataSizeAttribute : "");
					result.put("readers", dataReadersAttribute != null ? dataReadersAttribute : "");
					
					return result;
				} catch (Exception e) {
					LOGGER.error("Exception loading data for expanded version " + versionIndex, e);
					throw e;
				}
			}
		};
		
		// Set up success handler
		expandTask.setOnSucceeded(successEvent -> {
			try {
				Map<String, String> data = expandTask.getValue();
				if (data == null) {
					LOGGER.warn("No data loaded for expanded version " + versionIndex);
					return;
				}
				
				final String dataSizeAttribute = data.get("size");
				final String dataReadersAttribute = data.get("readers");
				final VersionAttributes versionAttributes = versionList.get(versionIndex);
				
				// Clear all version elements
				List<Node> toRemove = new ArrayList<>();
				for (Node child : fileVersionsPane.getChildren()) {
					if (GridPane.getRowIndex(child) != null && GridPane.getRowIndex(child) > 0) {
						toRemove.add(child);
					}
				}
				fileVersionsPane.getChildren().removeAll(toRemove);
				
				// Rebuild all versions
				for (int i = 0; i < versionList.size(); i++) {
					final int currentIndex = i;
					if (i == versionIndex) {
						// This is the expanded version
						String[] readers = dataReadersAttribute.isEmpty() ? new String[0] : dataReadersAttribute.split("\n");
						
						// Format the size properly
						String formattedSize;
						try {
							long previewSize = Long.parseLong(dataSizeAttribute);
							formattedSize = formatter.format(previewSize);
						} catch (NumberFormatException ex) {
							formattedSize = dataSizeAttribute;
						}
						
						Label expandedLabel = new Label(
								"Size: " + formattedSize + " | Created: " + CloudFile.DATEFORMAT.format(versionAttributes.getCreateTime()) + " by " + versionAttributes.getTag() + "\nReaders: " + String.join(", ", readers));
						expandedLabel.setWrapText(true);
						fileVersionsPane.add(expandedLabel, 0, 1 + i);
						
						// Cloud I/O in Task.call() (background); UI in setOnSucceeded (JavaFX thread).
						final VersionAttributes previewVersion = versionList.get(currentIndex);
						Task<FilePreviewService.PreviewPayload> previewTask = new Task<FilePreviewService.PreviewPayload>() {
							@Override
							protected FilePreviewService.PreviewPayload call() throws Exception {
								if (AltaStataApp.account.MY_USER().equals(AltaStataApp.account.CUSTODIAN_USER())) {
									return null;
								}
								return filePreviewService.loadPreviewPayload(file, previewVersion, dataSizeAttribute);
							}
						};

						previewTask.setOnSucceeded(event -> {
							FilePreviewService.PreviewPayload payload = previewTask.getValue();
							if (payload != null) {
								filePreviewService.displayPreview(vbh, file, previewVersion, payload);
							}
						});

						previewTask.setOnFailed(event -> {
							LOGGER.error("File preview task failed for version " + currentIndex, previewTask.getException());
						});

						backgroundExecutor.submit(previewTask);
					} else {
						// This is a normal version
						VersionAttributes otherVersion = versionList.get(i);
						String otherSizeText = sizeTasks.get(i).getValue();
						if (otherSizeText != null) {
							Label otherSize = new Label(otherSizeText);
							otherSize.setWrapText(true);
							fileVersionsPane.add(otherSize, 0, 1 + i);
						}
						
						ImageView otherMore = new ImageView();
						otherMore.setImage(new Image("images/preview-icon.png", true));
						fileVersionsPane.add(otherMore, 1, 1 + i);
						
						// Set up click handler
						otherMore.setOnMouseClicked(otherMe -> {
							onMagnifyingGlassClick(currentIndex, versionList, sizeTasks, fileVersionsPane, formatter, file, vbh);
						});
					}
				}
			} catch (Exception e) {
				LOGGER.error("Exception updating UI for expanded version " + versionIndex, e);
			}
		});
		
		// Set up failure handler
		expandTask.setOnFailed(failEvent -> {
			LOGGER.error("Failed to load data for expanded version " + versionIndex, expandTask.getException());
		});
		
		// Submit the task to the background executor
		backgroundExecutor.submit(expandTask);
	}

	/**
	 * Creates a vertical box pane representing a detailed file report, with optional file preview.
	 *
	 * @param file cloud file to report
	 * @param showFilePreview true to trigger preview rendering
	 * @return file report panel
	 */
	public VerticalBox createFileReportBox(CloudFile file, boolean showFilePreview) {
		// Cancel any existing size loading tasks
		for (Task<String> task : activeSizeTasks) {
			if (task.isRunning()) {
				task.cancel();
			}
		}
		activeSizeTasks.clear();
		
		// Also cancel any running wait tasks
		if (currentWaitTask != null && currentWaitTask.isRunning()) {
			currentWaitTask.cancel();
		}
		
		VerticalBox vbh = new VerticalBox(this);
		vbh.setPadding(UITheme.PADDING_FORM);
				
		Separator sep = new Separator(Orientation.HORIZONTAL);
		sep.setPadding(new Insets(vbh.getWidth(), 10, 10, 10));

		ColumnConstraints constraints1 = new ColumnConstraints();
		constraints1.setHalignment(HPos.LEFT);
		constraints1.setMinWidth(60);

		ColumnConstraints constraints2 = new ColumnConstraints();
		constraints2.setHalignment(HPos.LEFT);

		// Do not show versions and preview if not in one of these states
		if (file.getOperationState() == OperationState.NONE ||
				file.getOperationState() == OperationState.UPLOADED ||
				file.getOperationState() == OperationState.DOWNLOADING ||
				file.getOperationState() == OperationState.DOWNLOADED ||
				file.getOperationState() == OperationState.SHARING ||
				file.getOperationState() == OperationState.DONE) {

			// Report file properties
			GridPane fileVersionsPane = new GridPane();
			fileVersionsPane.setHgap(10);
			fileVersionsPane.setVgap(15); // Reduced vertical gap between rows
			fileVersionsPane.setPadding(UITheme.PADDING_FORM);
			fileVersionsPane.getColumnConstraints().addAll(constraints1, constraints2); // Need both columns for versions
			// Note: We now use 2 columns: size info (0) and magnifying glass (1)
			
			// Set minimum row height to prevent overlapping
			// We'll set row constraints after we know how many versions we have

			//		fileVersionsPane.setBorder(new Border(new BorderStroke(Color.GRAY,
			//				 BorderStrokeStyle.SOLID, CornerRadii.EMPTY, BorderWidths.DEFAULT)));

			Label name = new Label(file.getName());
			name.setWrapText(true);
			name.setTextAlignment(TextAlignment.LEFT);
			name.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;"); // Make it larger and bold

			fileVersionsPane.add(name, 0, 0, 2, 1); // Span 2 columns, 1 row

			// In case of double-click on file, we simulate a mouse click on the latest more button
			ImageView latestMoreButton = null;

			// First, load all size data in parallel
			List<VersionAttributes> versionList = new ArrayList<>(file.getVersions());
			List<Task<String>> sizeTasks = new ArrayList<>();
			// Raw plaintext byte size per version, populated by each size task; -1 = unknown.
			final long[] versionSizes = new long[versionList.size()];
			Arrays.fill(versionSizes, -1L);

			// Set row constraints for ALL rows: header (0) + all versions (1 to n)
			for (int row = 0; row <= versionList.size(); row++) {
				RowConstraints rowConstraint = new RowConstraints();
				rowConstraint.setMinHeight(35); // Reduced minimum height per row
				rowConstraint.setPrefHeight(40); // Reduced preferred height for better spacing
				fileVersionsPane.getRowConstraints().add(rowConstraint);
			}
			
			// Create tasks for all versions
			for (int i = 0; i < versionList.size(); i++) {
				final VersionAttributes versionAttributes = versionList.get(i);
				final int versionIndex = i;
				
				Task<String> loadSizeTask = new Task<String>() {
					@Override
					protected String call() throws Exception {
						try {
							// Check if task was cancelled before making the call
							if (isCancelled()) {
								return null;
							}
							
							// try to read the access properties
							String fileSizeDataAttribute = versionAttributes.getVersionDataAttribute("size");
							
							// Check if task was cancelled after the call
							if (isCancelled()) {
								return null;
							}
							
							// Check if we got a valid response
							if (fileSizeDataAttribute == null || fileSizeDataAttribute.trim().isEmpty()) {
								LOGGER.warn("Empty or null size data for version " + versionIndex + ", tag: " + versionAttributes.getTag());
								return "Size: | Created: " + CloudFile.DATEFORMAT.format(versionAttributes.getCreateTime()) + " by " + versionAttributes.getTag();
							}
							
							long fileSize = Long.parseLong(fileSizeDataAttribute);
							
							// Check for stub value (-1)
							if (fileSize == -1) {
								return "Size: | Created: " + CloudFile.DATEFORMAT.format(versionAttributes.getCreateTime()) + " by " + versionAttributes.getTag();
							}

							if (fileSize >= 0) versionSizes[versionIndex] = fileSize;

							String result = ((fileSize >= 0) ? "Size: " + formatter.format(fileSize) : "Size: 0") + " | Created: " + CloudFile.DATEFORMAT.format(versionAttributes.getCreateTime()) + " by " + versionAttributes.getTag();
							return result;
						} catch (NumberFormatException e) {
							LOGGER.warn("Invalid size format for version " + versionIndex + ": " + e.getMessage());
							return "Size: | Created: " + CloudFile.DATEFORMAT.format(versionAttributes.getCreateTime()) + " by " + versionAttributes.getTag();
						} catch (Exception e) {
							LOGGER.error("Exception loading size for version " + versionIndex, e);
							throw e; // Re-throw to trigger onFailed
						}
					}
				};
				
				sizeTasks.add(loadSizeTask);
				activeSizeTasks.add(loadSizeTask);
				backgroundExecutor.submit(loadSizeTask);
			}
			
			// Wait for all tasks to complete, then create UI
			currentWaitTask = new Task<Void>() {
				@Override
				protected Void call() throws Exception {
					try {
						// Wait for all size tasks to complete
						for (Task<String> task : sizeTasks) {
							if (isCancelled()) {
								return null; // Exit early if cancelled
							}
							task.get(); // This will wait for each task to complete
						}
						return null;
					} catch (InterruptedException e) {
						// Task was cancelled, exit gracefully
						if (isCancelled()) {
							return null;
						}
						throw e; // Re-throw if not cancelled
					}
				}
			};
			
			currentWaitTask.setOnSucceeded(successEvent -> {
				// All size data loaded, now create the complete UI
				ImageView finalLatestMoreButton = null;
				
				for (int i = 0; i < versionList.size(); i++) {
					VersionAttributes versionAttributes = versionList.get(i);
					String sizeText = sizeTasks.get(i).getValue();
					
					// Skip if task was cancelled
					if (sizeText == null) {
						continue;
					}
					
					// Create labels with complete data (size text now includes creation time)
					Label size = new Label(sizeText);
					size.setWrapText(true);
					fileVersionsPane.add(size, 0, 1 + i);

					ImageView more = new ImageView();
					more.setImage(new Image("images/preview-icon.png", true));
					fileVersionsPane.add(more, 1, 1 + i);
					
					// Set up the more button click handler
					final int j = i;
					more.setOnMouseClicked(me -> {
						// Call directly on JavaFX thread for immediate response
						onMagnifyingGlassClick(j, versionList, sizeTasks, fileVersionsPane, formatter, file, vbh);
					});

					finalLatestMoreButton = more;
				}
				
				// Auto-preview latest on single-click if small or already cached. Older
				// versions always keep their search icon - user clicks to expand.
				int latestIdx = versionList.size() - 1;
				boolean autoPreviewLatest = finalLatestMoreButton != null && latestIdx >= 0
						&& ((versionSizes[latestIdx] >= 0 && versionSizes[latestIdx] <= AUTO_PREVIEW_SIZE_BYTES)
								|| AltaStataApp.account.caches().hasChunk(
										file.getCloudObjectPathIncludingVersion(versionList.get(latestIdx))));

				if ((showFilePreview || autoPreviewLatest) && finalLatestMoreButton != null) {
					finalLatestMoreButton.fireEvent(new MouseEvent(
							MouseEvent.MOUSE_CLICKED,
							0, 0, 0, 0,
							MouseButton.PRIMARY,
							2, // Set clickCount to 2 for a double-click
							false, false, false, false,
							true, false, false, false, false, false,
							null
					));
				}
			});
			
			currentWaitTask.setOnFailed(failedEvent -> {
				// If loading fails, show error state
				LOGGER.error("Failed to load size data for file versions", currentWaitTask.getException());
				for (int i = 0; i < versionList.size(); i++) {
					VersionAttributes versionAttributes = versionList.get(i);
					
					Label size = new Label("Size: Error loading | Creator: " + versionAttributes.getTag() + " | Created: " + CloudFile.DATEFORMAT.format(versionAttributes.getCreateTime()));
					size.setWrapText(true);
					fileVersionsPane.add(size, 0, 1 + i);

					ImageView more = new ImageView();
					more.setImage(new Image("images/preview-icon.png", true));
					fileVersionsPane.add(more, 1, 1 + i);
				}
			});
			
			// Start the wait task
			backgroundExecutor.submit(currentWaitTask);

			// TODO: need to check the option to use the pane instead of Text, ImageView etc.
			vbh.getChildren().addAll(fileVersionsPane, sep, new Text());
		}

		return vbh;
	}

	// Service for handling file preview functionality
	private final FilePreviewService filePreviewService = new FilePreviewService();
	
	// Shared executor for background tasks
	private static final ExecutorService backgroundExecutor = Executors.newCachedThreadPool();
	
	/**
	 * Cleanup method to cancel all active size loading tasks
	 */
	public void cleanupSizeTasks() {
		for (Task<String> task : activeSizeTasks) {
			if (task.isRunning()) {
				task.cancel();
			}
		}
		activeSizeTasks.clear();
		LOGGER.debug("Cleaned up " + activeSizeTasks.size() + " active size tasks");
	}
	

	
	// Track active tasks for cancellation
	private final List<Task<String>> activeSizeTasks = new ArrayList<>();
	private Task<Void> currentWaitTask = null;

	/**
	 * Retrieves the array of last selected cloud files in the active directory list column.
	 *
	 * @return array of last selected cloud files
	 */
	public CloudFile[] getLastSelectedObjects() {
		Collection<CloudFile> lastSelectedObjects = null;

		// find last selected objects
		for (int i = getItems().size() - 1; i >= 0; i--) {
			if (getItems().get(i) instanceof DirectoryList) {
				lastSelectedObjects = ((DirectoryList) getItems().get(i)).getLastSelected();

				if (lastSelectedObjects.size() > 0) {
					return lastSelectedObjects.toArray(new CloudFile[0]);
				}
			}
		}

		return null;
	}

	/**
	 * Updates the internal tracking of cloud objects currently visible in the UI directories.
	 */
	public void cloudObjectsCurrentlyInDirectories() {
		Map<String, CloudFile> cloudFilesInUIUse = new HashMap<String, CloudFile>();

		ObservableList<Node> verticalBoxes = getItems();

		// find all objects in UI
		for (int i = 0; i < verticalBoxes.size(); i++) {
			VerticalBox vb = (VerticalBox) verticalBoxes.get(i);

			if (vb instanceof DirectoryList) {
				ObservableList<CloudFile> list = ((DirectoryList)vb).getItems();

				for (CloudFile cf: list) {
					cloudFilesInUIUse.put(cf.getPath(), cf);
				}
			}
		}
	}

	/**
	 * Finds the column index of the given vertical box in the navigation split pane.
	 *
	 * @param vb the vertical box column
	 * @return column index, or -1 if not found
	 */
	public int findIndex(VerticalBox vb) {
		int i = 0;
		for (Node node : getItems()) {
			if (node.equals(vb)) {
				return i;
			}

			i++;
		}
		return -1;
	}

	/**
	 * Finds the index of the directory containing the specified cloud file.
	 *
	 * @param file target cloud file
	 * @return directory index, or -1 if not found
	 */
	public int getDirectoryIndexForCloudFile(CloudFile file) {
		for (int i = getItems().size() - 1; i >= 0; i--) {
			if (getItems().get(i) instanceof DirectoryList) {
				if (((DirectoryList) getItems().get(i)).getItems().contains(file)) {
					return i;
				}
			}
		}

		return -1;
	}

	/**
	 * Sets the index of the currently active directory list column.
	 *
	 * @param currentDirectoryIndex the active directory index
	 */
	public void setCurrentDirectoryIndex(int currentDirectoryIndex) {
		LOGGER.trace(
				"\tsetCurrentDirectoryIndex was: " + this.currentDirectoryIndex + " now: " + currentDirectoryIndex);
		this.currentDirectoryIndex = currentDirectoryIndex;
	}

	/**
	 * Gets the index of the currently active directory list column.
	 *
	 * @return active directory index
	 */
	public int getCurrentDirectoryIndex() {
		return currentDirectoryIndex;
	}

	/**
	 * Gets the currently active directory list column.
	 *
	 * @return active directory list, or null if search list or empty
	 */
	public DirectoryList getCurrentDirectory() {
		while (getItems().size() <= getCurrentDirectoryIndex()) {
			setCurrentDirectoryIndex(getCurrentDirectoryIndex() - 1);
		}

		VerticalBox current = (VerticalBox) getItems().get(getCurrentDirectoryIndex());
		if (current instanceof DirectoryList) {
			return (DirectoryList) current;
		} else if (current instanceof SearchList) {
			return null;
		} else {
			currentDirectoryIndex--;
			return (DirectoryList) getItems().get(getCurrentDirectoryIndex());
		}
	}

	/**
	 * Gets the directory list column at the specified index.
	 *
	 * @param index target index
	 * @return directory list column
	 */
	public DirectoryList getDirectoryForIndex(int index) {
		VerticalBox dir = (VerticalBox) getItems().get(index);
		if (dir instanceof DirectoryList) {
			return (DirectoryList) dir;
		} else {
			return (DirectoryList) getItems().get(index - 1);
		}
	}

	/**
	 * Adjusts columns' widths following a geometric progression to maximize space for rightmost columns.
	 */
	public void adjustGeometryProgressionForColumnsSizes() {
		int columnsNumber = (currentDirectoryIndex < getItems().size() - 1) ? (currentDirectoryIndex + 1)
				: currentDirectoryIndex;

		double geometryProgressionElement = getWidth() / (Math.pow(2, columnsNumber) - 1);

		for (int i = 0; i < columnsNumber; i++) {
			// LOGGER.trace("\tadjustGeometryProgressionForColumnsSizes i: " + i + "
			// geometryProgressionElement: " + geometryProgressionElement + " out of: " +
			// getWidth());

			if (getItems().size() < i) {
				((VerticalBox) getItems().get(i)).setMaxWidth(geometryProgressionElement);
			}

			geometryProgressionElement = geometryProgressionElement * 2;
		}
	}

	/**
	 * Inserts a newly created file or folder into the appropriate directory list.
	 *
	 * @param parentDir parent directory cloud file
	 * @param newFile newly created cloud file
	 */
	public void insertNewFile(CloudFile parentDir, CloudFile newFile) {
		refreshDirectoryAndSelectCloudFile(parentDir, newFile);
	}

	/**
	 * Refreshes the active directory list column and selects its first selected cloud file if any.
	 */
	public void refreshCurrentDirectoryListAndSelectFirstCloudFile() {
		DirectoryList currentDirectoryList = getCurrentDirectory();
		if (currentDirectoryList != null) {
			CloudFile currentDirFile = currentDirectoryList.getDirectoryCloudFile();

			CloudFile[] selectedFiles = currentDirectoryList.getLastSelected()
					.toArray(new CloudFile[currentDirectoryList.getLastSelected().size()]);

			if (selectedFiles.length > 0) {
				CloudFile selectedFile = selectedFiles[0];

				refreshDirectoryAndSelectCloudFile(currentDirFile, selectedFile);
			}
		}
	}

	/**
	 * Compares two TreeSet instances of CloudFile for path equality.
	 *
	 * @param set1 first set
	 * @param set2 second set
	 * @return true if both sets have identical file paths
	 */
	boolean equalTreeSets(TreeSet<CloudFile> set1, TreeSet<CloudFile> set2) {
		// First, check if the sizes are equal
		if (set1.size() != set2.size()) {
			return false; // Sets are not equal if their sizes differ
		}

		// Compare the sets element by element
		Iterator<CloudFile> iterator1 = set1.iterator();
		Iterator<CloudFile> iterator2 = set2.iterator();

		while (iterator1.hasNext() && iterator2.hasNext()) {
			CloudFile cf1 = iterator1.next();
			CloudFile cf2 = iterator2.next();
			if (cf1.getPath().equals(cf2.getPath()) == false) {
				return false;
			}
		}

		return true;
	}

	/**
	 * Refresh the directories in UI using the data in the multimap. The files names
	 * got received within the SHARE messages or incremental list calls.
	 */
	public void refreshAllDirectories() {
		LOGGER.trace("\trefreshAllDirectories getCurrentDirectoryIndex(): " + getCurrentDirectoryIndex()
				+ " getItems().size(): " + getItems().size());

		for (int index = 0; index < getItems().size(); index++) {
			VerticalBox dir = (VerticalBox) getItems().get(index);

			if (dir instanceof DirectoryList) {
				String path = ((DirectoryList) dir).getDirectoryCloudFile().getPath();

				Task<Void> task = new Task<Void>() {
					@Override
					public Void call() {
						try {
							// get file listView
							Collection<CloudFile> results = AltaStataApp.account.getFileSystemHandler().listDirectory(path);
							if (results != null && results.size() > 0) {
								Platform.runLater(new Runnable() {
									@Override
									public void run() {

										TreeSet<CloudFile> currentList = new TreeSet(((DirectoryList) dir).getItems());
										TreeSet<CloudFile> newList = new TreeSet<>(results);

										if (equalTreeSets(currentList, newList) == false) {
											((DirectoryList) dir).setItems(results.toArray(new CloudFile[results.size()]));
										}
									}
								});
							}
						} catch (Throwable ex) {
							LOGGER.warn("refreshAllDirectories() Cannot access the storage", ex.getMessage());
						}

						return null;
					}
				};

				backgroundExecutor.submit(task);
			}
		}
	}

	/**
	 * Refreshes the specified parent directory and selects the targeted file/directory inside it.
	 *
	 * @param parentDirectory parent directory cloud file
	 * @param fileOrDirToSelect file or directory to select
	 */
	private void refreshDirectoryAndSelectCloudFile(CloudFile parentDirectory, CloudFile fileOrDirToSelect) {
		int parentDirectoryIndex = getDirectoryIndexForCloudFile(parentDirectory);

		LOGGER.trace("\trefreshDirectoryAndSelectCloudFile parentDirectory: " + parentDirectory.getPath()
				+ " fileOrDirToSelect: " + fileOrDirToSelect.getPath() + " parentDirectoryIndex: "
				+ parentDirectoryIndex);

		// recreate subdirectory for parent directory, otherwise it does not want to
		// refresh, TODO: understand why
		DirectoryList parentDirectoryList = null;

		if (parentDirectoryIndex == AltaStataApp.INITIAL_COLUMN_NUMBER_BEFORE_INCREMENTATION) {
			parentDirectoryList = createAndPopulateDirectoryList(
					AltaStataApp.INITIAL_COLUMN_NUMBER_BEFORE_INCREMENTATION,
					new CloudFile(FileSystemHandler.INIT_DIR, true));
		} else {
			parentDirectoryList = createSubDirectoryAndNavigate(getDirectoryForIndex(parentDirectoryIndex),
					parentDirectory);
		}

		// create subdirectory for fileOrDirToSelect within parentDirectory
		createSubDirectoryAndNavigate(parentDirectoryList, fileOrDirToSelect);

		setCurrentDirectoryIndex(parentDirectoryIndex + 1);

		if (NavigationPane.isMobileNavigation) {
			mobileClickAndMoveForward(getCurrentDirectoryIndex());
		} else {
			// request focus
			parentDirectoryList.getListView().requestFocus();
			parentDirectoryList.getListView().scrollTo(0);
			parentDirectoryList.getListView().getSelectionModel().select(fileOrDirToSelect);
		}
	}

	/**
	 * Cleans up and refreshes the navigation UI after a successful file/directory deletion.
	 *
	 * @param directoryIndex deleted file's directory column index
	 * @param selectFile fallback file to select in the list
	 */
	public void cleanAfterDelete(int directoryIndex, CloudFile selectFile) {
		// check if user chose to delete whole directory structure, not just a list of
		// files
		boolean deleteDirectory = false;
		if (getCurrentDirectoryIndex() < directoryIndex) {
			deleteDirectory = true;
		}

		// update the current directory if deleted everything
		if (getCurrentDirectory().getItems().size() == 0) {
			if (getCurrentDirectoryIndex() > 0) {
				setCurrentDirectoryIndex((int) directoryIndex - 1);
			}
		}

		if (NavigationPane.isMobileNavigation) {
			mobileNavigationBackward(getCurrentDirectoryIndex(), null);
		} else {
			// request focus - commented out by now
			//getCurrentDirectory().getListView().requestFocus();
			getCurrentDirectory().getListView().scrollTo(0);

			if (selectFile == null || deleteDirectory) {
				// do not select first element as the cursor can be on other one
				//getCurrentDirectory().getListView().getSelectionModel().clearAndSelect(0);
			} else {
				getCurrentDirectory().getListView().getSelectionModel().select(selectFile);
			}

			// remove the deleted subtree from UI and create a new subtree for the first
			// item in the list
			CloudFile lastClickedFile = getCurrentDirectory().getListView().getSelectionModel().getSelectedItem();
			if (lastClickedFile != null) {
				createSubDirectoryAndNavigate(getCurrentDirectory(), lastClickedFile);
			}

			// remove the second panel if the first is empty
			if (getCurrentDirectoryIndex() == 0) {
				DirectoryList firstDirectoryList = (DirectoryList) getItems().get(0);

				while (firstDirectoryList.getItems().size() == 0 && getItems().size() > 1) {
					getItems().remove(getItems().size() - 1);
				}
			}
		}
	}

	/**
	 * Uses the one selected file if its the directory or goes up to its parent
	 * 
	 * @return
	 */
	public CloudFile bestMatchingDirForSelection() {
		CloudFile[] selectedDirectories = getLastSelectedObjects();
		CloudFile matchingDir = null;

		// work around: if it was escape to the root directory, define
		// selectedDirectories as null
		if (getCurrentDirectoryIndex() == AltaStataApp.INITIAL_COLUMN_NUMBER_BEFORE_INCREMENTATION) {
			setCurrentDirectoryIndex(0);
			selectedDirectories = null;
		}

		if (selectedDirectories == null || selectedDirectories[0] == null) {
			// it occurs only on mobile phones or in root
			matchingDir = getDirectoryForIndex(getCurrentDirectoryIndex()).getDirectoryCloudFile();
		} else if (selectedDirectories.length != 1 || selectedDirectories[0].isDirectory() == false) {
			// user selected file instead of directory, go back in that case
			matchingDir = getDirectoryForIndex(getCurrentDirectoryIndex()).getDirectoryCloudFile();
		} else {
			matchingDir = selectedDirectories[0];
		}

		return matchingDir;
	}

}
