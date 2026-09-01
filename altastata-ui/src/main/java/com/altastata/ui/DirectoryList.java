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
import com.altastata.api.OperationStateChangeEvent;
import com.altastata.api.OperationStateChangeListener;
import com.altastata.api.ProgressValueChangeEvent;
import com.altastata.api.ProgressValueChangeListener;
import com.altastata.filesystem.common.CloudFile;
import com.altastata.ui.theme.UITheme;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.SortedList;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.*;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.TextAlignment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scala.Tuple2;

import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.io.*;
import java.util.*;

/**
 * A customized {@link VerticalBox} view component representing a single directory's file list in AltaStata.
 * 
 * Supports multi-selection, drag-and-drop file operations (upload, rename, drag-outs), 
 * inline progress bars for active operations, and dynamic state-aware cell rendering with 
 * custom type indicators/icons.
 */
public class DirectoryList extends VerticalBox {

	private static Logger LOGGER = LoggerFactory.getLogger(DirectoryList.class);

	public static final Image resizeImage = new Image("images/Webp.net-resizeimage.png", true);
	public static final Image folderArrowDownIcon = new Image("images/Folder-Arrow-Down-icon.png", true);
	public static final Image folderArrowUpIcon = new Image("images/Folder-Arrow-Up-icon.png", true);
	public static final Image deleteIcon = new Image("images/Delete_Icon.png", true);
	public static final Image shareIcon = new Image("images/ShareIcon.png", true);
	public static final Image folderMacIcon = new Image("images/Folder-Mac-icon.png", true);
	public static final Image fileArrowDownIcon = new Image("images/File-Arrow-Down.png", true);
	public static final Image fileArrowUpIcon = new Image("images/File-Arrow-Up.png", true);
	public static final Image documentIcon = new Image("images/Document-icon.png", true);

	public static final Map<String, Image> classicFiletypesImages = new HashMap<String, Image>();

	Label headerTxt = null;
	CheckBox headerCheckBox = new CheckBox();

	CloudFile directory;

	ObservableList<CloudFile> olItems = FXCollections.synchronizedObservableList(FXCollections.observableArrayList());
	ListView<CloudFile> listView = new ListView<CloudFile>(createSortedItemsList(olItems));

	/**
	 * Creates a new DirectoryList instance to visualize a cloud directory.
	 *
	 * @param file        The directory's representing {@link CloudFile} metadata object.
	 * @param showEditBox Flag to control the inclusion of editing sub-panels.
	 * @param container   The active {@link NavigationPane} parent panel.
	 */
	public DirectoryList(CloudFile file, boolean showEditBox, NavigationPane container) {
		super(container);

		this.directory = file;

		listView.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);

		VBox.setVgrow(listView, Priority.ALWAYS);

		BorderPane headerPane = new BorderPane();
		headerPane.setPadding(UITheme.PADDING_TIGHT);
		headerPane.setPrefHeight(32);
		headerPane.setStyle("-fx-background-color: " + UITheme.toHexString(UITheme.HEADER_BACKGROUND) + ";");

		// create the header text
		headerTxt = new Label(file.getParent());
		headerTxt.setTranslateX(5);
		headerTxt.setTranslateY(3);
		headerTxt.setPrefWidth(listView.getWidth() - 5);
		headerTxt.setFont(UITheme.HEADER_MEDIUM);
		headerTxt.setTextFill(Color.WHITE);
		headerTxt.setTextAlignment(TextAlignment.LEFT);

		listView.widthProperty().addListener((observable, oldValue, newValue) -> {

			headerTxt.setPrefWidth((Double) newValue - 5);
			headerPane.setPrefWidth(listView.getWidth());
		});

		headerPane.setCenter(headerTxt);
		BorderPane.setAlignment(headerTxt, Pos.CENTER_LEFT);

		listView.getStylesheets().add("css/ListView.css");

		if (NavigationPane.isMobileSelect) {
			headerCheckBox.setPadding(UITheme.PADDING_COMPONENT);
			headerCheckBox.setFont(UITheme.HEADER_MEDIUM);

			headerPane.setRight(headerCheckBox);
			BorderPane.setAlignment(headerCheckBox, Pos.BOTTOM_RIGHT);

			// select/deselect this checkBoxs in case headerCheckBox is selected/deselected
			headerCheckBox.selectedProperty().addListener((obs, wasOn, isNowOn) -> {
				if (isNowOn) {
					for (CloudFile cf : olItems) {
						cf.setMobileSelectStatusValue(true);
					}
				} else {
					cleanMobileSelectedOlItems();
				}
			});

			listView.getSelectionModel().select(-1);
		}

		// http://www.drdobbs.com/jvm/multi-columnselect-javafx-listview/229402139
		// TODO: move to ListView.css
		listView.setStyle(UITheme.CLEAN_LISTVIEW_STYLE);

		getChildren().add(headerPane);

		defineListCellForListView();

		getChildren().add(listView);

		// Define listeners for drop on Pane
		if (NavigationPane.isMobileSelect == false) {
			this.setOnDragOver(de -> {
				if (de.getDragboard().hasFiles() || de.getDragboard().hasString()) {
					de.acceptTransferModes(TransferMode.ANY);
				}
				de.consume();
			});
			this.setOnDragDropped(de -> {
				if (AltaStataApp.accountManagementService.checkOrInputPassword()) {
					dropFilesHandle(de, directory, false);
				}
			});

			// TODO: Its a good message to develop the right panes sizes
			widthProperty().addListener((observable, oldValue, newValue) -> {
				LOGGER.trace("\tDirectoryList widthProperty() " + headerTxt.getText() + " changed from " + oldValue
						+ " to " + newValue);
			});
		}
	}

	/**
	 * Gets the current directory's underlying {@link CloudFile} metadata.
	 *
	 * @return The directory {@link CloudFile}.
	 */
	public CloudFile getDirectoryCloudFile() {
		return directory;
	}

	/**
	 * Unchecks/deselects all files in this directory list when using mobile-mode select checkboxes,
	 * resetting the global header checkbox state as well.
	 */
	public void cleanMobileSelectedOlItems() {
		for (CloudFile cloudFile : olItems) {
			if (cloudFile.getMobileSelectStatusValue()) {
				cloudFile.setMobileSelectStatusValue(false);
			}
		}

		headerCheckBox.setSelected(false);
	}

	/**
	 * Configures the cell factory for the list view.
	 *
	 * Styles cells, registers progress bar and operation listeners, sets drag-and-drop cell interactions,
	 * and configures checkbox behaviors for mobile layouts.
	 */
	public void defineListCellForListView() {

		((ListView<CloudFile>) listView).setCellFactory(listView -> {
			ListCell<CloudFile> lc = new ListCell<CloudFile>() {
				private final ImageView imageView = new ImageView();
				private final Label textLabel = new Label("");
				private final ProgressBar progressBar = new ProgressBar();
				private final CheckBox checkBox = new CheckBox();

				// We need this list to unbind the other CloudFiles that used this ListCell
				// before.
				// Function updateItem is called periodically with the last CloudFile, but it
				// does not store the previous bindings, so we have to do it
				private final List<ChangeListener<Boolean>> checkBoxListeners = new ArrayList<ChangeListener<Boolean>>();

				private BorderPane pane = new BorderPane();

				{
					// make text more visible
					this.setFont(UITheme.LIST_ITEM);
					textLabel.setFont(UITheme.LIST_ITEM);

					imageView.setFitHeight(20);
					imageView.setFitWidth(32);
					imageView.setPreserveRatio(true);
					imageView.setCache(false);

					textLabel.setPadding(UITheme.PADDING_TEXT);

					// this.getStyleClass().add("check-box-list-cell");

					pane.setLeft(imageView);
					pane.setCenter(textLabel);
					pane.setBottom(progressBar);
					pane.setPadding(UITheme.PADDING_COMPONENT);
					BorderPane.setAlignment(textLabel, Pos.CENTER_LEFT);
					BorderPane.setAlignment(progressBar, Pos.BOTTOM_LEFT);

					if (NavigationPane.isMobileSelect) {
						// TODO: check on phone
						// textLabel.setWrapText(true);
						pane.setRight(checkBox);

						checkBox.setSelected(headerCheckBox.isSelected());
					}

					progressBar.setVisible(false);
				}

				/**
				 * Updates the visual state of the cell based on the given item.
				 *
				 * @param cloudFile the cloud file item to display
				 * @param empty     true if the cell is empty
				 */
				@Override
				public void updateItem(CloudFile cloudFile, boolean empty) {
					CloudFile previusCloudFile = getItem();

					super.updateItem(cloudFile, empty);

					if (empty || cloudFile == null) {
						setText(null);
						setGraphic(null);
					} else {
						// setText(cloudFile.getName());
						textLabel.setText(cloudFile.getName());

						// set -1 in case of 0
						if (cloudFile.getProgressValue() > 0) {
							progressBar.setProgress(cloudFile.getProgressValue());
						}

						LOGGER.trace("\tupdateItem " + cloudFile + " -> " + cloudFile.getOperationState()
								+ " progressValue: " + cloudFile.getProgressValue());

						if (previusCloudFile == null || !previusCloudFile.equals(cloudFile)) {
							setIcon(cloudFile, true);
						}
						else {
							setIcon(cloudFile, false);
						}

						setGraphic(pane);

						// TODO: Min and Max do not work
						// double maxWidth = 70 * cloudFile.getName().length();
						// DirectoryList.this.setPrefWidth(Math.min(maxWidth, getMaxWidth()));

						if (NavigationPane.isMobileSelect == false) {
							setDragAndDropCellListeners();
						}

						// unbind the previous cloud files
						for (ChangeListener<Boolean> changeListener : checkBoxListeners) {
							checkBox.selectedProperty().removeListener(changeListener);
						}

						ChangeListener<Boolean> checkBoxChangeListener = new ChangeListener<Boolean>() {
							@Override
							public void changed(ObservableValue<? extends Boolean> observable, Boolean oldValue,
									Boolean newValue) {
								cloudFile.setMobileSelectStatusValue(newValue);
							}
						};

						checkBox.selectedProperty().addListener(checkBoxChangeListener);

						// add the listener to the list
						checkBoxListeners.add(checkBoxChangeListener);

						setOperationStateListener(cloudFile);
						setProgressValueListener(cloudFile);
						setMobileSelectStatusValueChangeListener(cloudFile);
					}
				}

				/**
				 * Adds a listener for operation state changes on the cloud file.
				 *
				 * @param cloudFile the file to track for operation changes
				 */
				private void setOperationStateListener(CloudFile cloudFile) {
					// bind on cloudFile property
					cloudFile.addOperationStateValueChangeListener(new OperationStateChangeListener() {
						/**
						 * Handles operation state changes.
						 *
						 * @param evt the operation state change event
						 */
						@Override
						public void operationStateChange(OperationStateChangeEvent evt) {
							Platform.runLater(new Runnable() {
								@Override
								public void run() {
									OperationState opState = evt.getOperationState();
									if (NavigationPane.isMobileSelect) {
										// unselect everything on beginning of each operation like Download etc.
										if (opState.equals(OperationState.NONE) == false) {
											if (headerCheckBox.isSelected()) {
												headerCheckBox.setSelected(false);
											}

											checkBox.setSelected(false);
										}
									}

									LOGGER.trace("\tsetOperationStateListener " + cloudFile + " -> " + opState);

									if (cloudFile.getOperationState().equals(OperationState.DELETED) == false) {
										setIcon(cloudFile, true);
									}
									else { // handle DELETED status
										final int deletedFileDirIndex = cloudFile.getPath().length()
												- cloudFile.getPath().replace("/", "").length();

										container.setCurrentDirectoryIndex(deletedFileDirIndex);
										if (container.getItems().size() <= container.getCurrentDirectoryIndex()) {
											container
													.setCurrentDirectoryIndex(container.getCurrentDirectoryIndex() - 1);
										}

										// if not all the versions of the file were deleted
										if (cloudFile.isDirectory() == false && cloudFile.getVersions().size() > 0) {

											cloudFile.setOperationStateValue(OperationState.NONE);
											container.cleanAfterDelete(deletedFileDirIndex, cloudFile);

										} else {

											container.getCurrentDirectory().removeItem(cloudFile);
											container.cleanAfterDelete(deletedFileDirIndex, null);
										}
									}
								}
							});
						}
					});
				}

				/**
				 * Adds a listener for progress value changes on the cloud file.
				 *
				 * @param cloudFile the file to track for progress updates
				 */
				private void setProgressValueListener(CloudFile cloudFile) {
					cloudFile.addProgressValueChangeListener(new ProgressValueChangeListener() {
						/**
						 * Handles progress value changes.
						 *
						 * @param evt the progress value change event
						 */
						@Override
						public void progressValueChange(ProgressValueChangeEvent evt) {
							Platform.runLater(new Runnable() {
								@Override
								public void run() {
									progressBar.setProgress(evt.getProgressValue());
								}
							});
						}
					});
				}

				/**
				 * Adds a listener for mobile select status changes.
				 *
				 * @param cloudFile the file to track for selection changes
				 */
				private void setMobileSelectStatusValueChangeListener(CloudFile cloudFile) {
					cloudFile.addMobileSelectStatusValueChangeListener(new PropertyChangeListener() {
						/**
						 * Handles property changes for mobile select status.
						 *
						 * @param evt the property change event
						 */
						@Override
						public void propertyChange(PropertyChangeEvent evt) {
							Platform.runLater(new Runnable() {
								@Override
								public void run() {
									checkBox.setSelected((Boolean) evt.getNewValue());
								}
							});
						}
					});
				}

				/**
				 * Sets the appropriate icon based on the file state.
				 *
				 * @param cloudFile                the file to display
				 * @param operationStateHasChanged true if the operation state has changed
				 */
				private void setIcon(CloudFile cloudFile, boolean operationStateHasChanged) {

					if (imageView.getImage() == null || operationStateHasChanged) {

						if (cloudFile.isDirectory()) {

							if (NavigationPane.isMobileSelect == false) {
								if (pane.getRight() == null) {
									pane.setRight(new ImageView(resizeImage));
								}
							}

							switch (cloudFile.getOperationState()) {
								case DOWNLOADING:
									imageView.setImage(folderArrowDownIcon);
									progressBar.setVisible(true);
									break;
								case UPLOADING:
									imageView.setImage(folderArrowUpIcon);
									progressBar.setVisible(true);
									break;
								case DELETING:
									imageView.setImage(deleteIcon);
									progressBar.setVisible(true);
									break;
								case SHARING:
									imageView.setImage(shareIcon);
									progressBar.setVisible(true);
									break;
								case NONE:
								default:
									imageView.setImage(folderMacIcon);
									progressBar.setVisible(false);
									break;
							}
						} else {
							if (NavigationPane.isMobileSelect == false) {
								if (pane.getRight() != null) {
									pane.setRight(null);
								}
							}

							switch (cloudFile.getOperationState()) {
								case DOWNLOADING:
									imageView.setImage(fileArrowDownIcon);
									progressBar.setVisible(true);
									break;
								case UPLOADING:
									imageView.setImage(fileArrowUpIcon);
									progressBar.setVisible(true);
									break;
								case DELETING:
									imageView.setImage(deleteIcon);
									progressBar.setVisible(true);
									break;
								case DELETED:
									break;
								case SHARING:
									imageView.setImage(shareIcon);
									progressBar.setVisible(true);
									break;
								case NONE:
								default:
									try {
										int extentionIndex = cloudFile.getPath().lastIndexOf('.');
										String name = cloudFile.getPath().substring(extentionIndex + 1);

										if (classicFiletypesImages.containsKey(name.toLowerCase()) == false) {
											Image image =
													new Image("images/filetypes/classic/" + name.toLowerCase() + ".png", true);

											classicFiletypesImages.put(name.toLowerCase(), image);
										}

										Image image = classicFiletypesImages.get(name.toLowerCase());
										imageView.setImage(image);

									} catch (Exception ex) {
										imageView.setImage(documentIcon);
									}

									progressBar.setVisible(false);

									// bind on cloudFile property
									progressBar.setProgress(ProgressIndicator.INDETERMINATE_PROGRESS);
									break;
							}
						}
					}
				}

				/**
				 * Configures drag and drop event listeners for the cell.
				 */
				private void setDragAndDropCellListeners() {
					// define drag and drop listeners
					if (getItem().isDirectory()) {
						this.setOnDragOver(de -> handleOnDragOver(de));
						this.setOnDragEntered(de -> handleOnDragEntered(de));
						this.setOnDragExited(de -> handleOnDragExited(de));
						this.setOnDragDropped(de -> handleOnDragDropped(de));
					}

					// allows to drag the files outside of the directory
					this.setOnDragDetected(me -> handleOnDragDetected(me));
					this.setOnDragDone(de -> handleOnDragDone(de));
				}

				/**
				 * Handles the drag over event.
				 *
				 * @param de the drag event
				 */
				private void handleOnDragOver(DragEvent de) {
					if (de.getDragboard().hasFiles() || de.getDragboard().hasString()) {
						de.acceptTransferModes(TransferMode.ANY);
					}
					de.consume();
				}

				/**
				 * Handles the drag entered event.
				 *
				 * @param de the drag event
				 */
				private void handleOnDragEntered(DragEvent de) {
					LOGGER.trace("\thandleOnDragEntered: " + de);
					// entered the target region
					if (de.getGestureSource() != de.getSource()
							&& (de.getDragboard().hasFiles() || de.getDragboard().hasString())) {

						// Highlight the potential drop target
						CloudFile target = (CloudFile) this.getItem();

						if (target == null || target.isDirectory()) {
							textLabel.setUnderline(true);
							textLabel.setFont(UITheme.HEADER_SMALL);
						}
					}

					de.consume();
				}

				/**
				 * Handles the drag exited event.
				 *
				 * @param de the drag event
				 */
				private void handleOnDragExited(DragEvent de) {
					LOGGER.trace("\thandleOnDragExited: " + de);

					CloudFile target = (CloudFile) this.getItem();

					if (target == null || target.isDirectory()) {
						textLabel.setUnderline(false);
						textLabel.setFont(UITheme.LIST_ITEM);
					}

					de.consume();
				}

				/**
				 * Handles the drag dropped event.
				 *
				 * @param de the drag event
				 */
				private void handleOnDragDropped(DragEvent de) {
					LOGGER.trace("\thandleOnDragDropped from outside: " + de);

					CloudFile target = (CloudFile) this.getItem();

					if (target == null || target.isDirectory() == false) {
						target = container.bestMatchingDirForSelection();
					}

					dropFilesHandle(de, target, true);
				}

				/**
				 * Handles drag detected event, enabling move/rename capabilities.
				 *
				 * @param me the mouse event
				 */
				private void handleOnDragDetected(MouseEvent me) {
					LOGGER.trace("\thandleOnDragDetected setOnDragDetected to outside: " + me);

					// start a drag-and-drop gesture
					Dragboard db = this.startDragAndDrop(TransferMode.LINK);

					// Put the file contents or filename into the clipboard
					ClipboardContent content = new ClipboardContent();

					// serialize the List<CloudFile> to string
					try {
						List<CloudFile> listToSerialize = new ArrayList<CloudFile>();
						listToSerialize.addAll(listView.getSelectionModel().getSelectedItems());

						content.put(DataFormat.PLAIN_TEXT, serialize(listToSerialize));

						db.setContent(content);
					} catch (Exception ex) {
						LOGGER.error("handleOnDragDetected", ex);
					}

					db.setDragView(this.snapshot(null, null));

					me.consume();
				}

				/**
				 * Handles the completion of a drag event.
				 *
				 * @param de the drag event
				 */
				private void handleOnDragDone(DragEvent de) {
					LOGGER.trace("\thandleOnDragDone: " + de);

					Dragboard db = de.getDragboard();

					db.clear();
					de.consume();
				}

				/**
				 * Handles mouse click events on the cell.
				 *
				 * @param me the mouse event
				 */
				private void handleOnMouseClicked(MouseEvent me) {
					/*
					 * // only for right click or control down for OSX if (me.getButton() ==
					 * MouseButton.SECONDARY || me.isControlDown()) { CloudFile source =
					 * (CloudFile)this.getItem();
					 * 
					 * // Open in a new Stage/Scene graph with a TextFlow control Group grp = new
					 * Group(); Node node; if ( source.getAbsolutePath().endsWith("html") ) {
					 * HTMLEditor html = new HTMLEditor();
					 * html.setHtmlText(source.getAbsolutePath()); node = html; } else { TextFlow
					 * txtFlow = new TextFlow( new Text("File path: " + source.getAbsolutePath() +
					 * "\n"), new Text("File size: " + source.length())); node = txtFlow; }
					 * 
					 * grp.getChildren().add(node); Scene scene = new Scene(grp, 640, 480); Stage
					 * editStg = new Stage(); editStg.setScene(scene); editStg.show();
					 * editStg.centerOnScreen(); }
					 */
				}
			};

			lc.prefWidthProperty().bind(listView.widthProperty().subtract(4));

			return lc;
		});
	}

	/**
	 * Sets the text displayed in the header label of this directory column.
	 *
	 * @param text The new header string.
	 */
	public void setHeader(String text) {
		if (headerTxt != null)
			headerTxt.setText(text);
	}

	/**
	 * Retrieves the text currently displayed in the header label.
	 *
	 * @return The header text string.
	 */
	public String getHeader() {
		return headerTxt.getText();
	}

	/**
	 * Gets the observable collection of all items inside this directory list.
	 *
	 * @return The underlying {@link ObservableList} of {@link CloudFile} children.
	 */
	public ObservableList<CloudFile> getItems() {
		return olItems;
	}

	/**
	 * Updates the directory listing with a brand new set of file/directory objects.
	 * Handles incremental synchronization (removing old items and adding newly discovered ones).
	 *
	 * @param items An array of updated {@link CloudFile} elements.
	 */
	public void setItems(CloudFile[] items) {
		if (items != null) {
			// Convert the input array to a List for easier comparisons
			List<CloudFile> inputList = Arrays.asList(items);

			// Remove elements from olItems that are not in the input array
			olItems.removeIf(existingItem -> !inputList.contains(existingItem));

			// Add elements from the input array if they are not already in olItems
			for (CloudFile item : items) {
				if (!olItems.contains(item)) {
					olItems.add(item);
				}
			}
		} else {
			// If items is null, clear the list
			olItems.clear();
		}

		listView.setItems(createSortedItemsList(olItems));
	}

	/**
	 * Adds a single {@link CloudFile} item to this directory listing if it is not already present.
	 *
	 * @param item The {@link CloudFile} to add.
	 * @return Always returns {@code true}.
	 */
	public boolean addItem(CloudFile item) {
		if (olItems.contains(item) == false) {
			olItems.add(item);

			listView.setItems(createSortedItemsList(olItems));
		}

		return true;
	}

	/**
	 * Removes a single {@link CloudFile} item from this directory listing if it exists.
	 *
	 * @param item The {@link CloudFile} to remove.
	 * @return {@code true} if the item was found and removed, {@code false} otherwise.
	 */
	public boolean removeItem(CloudFile item) {
		if (olItems.contains(item)) {
			olItems.remove(item);

			listView.setItems(createSortedItemsList(olItems));

			return true;
		}

		return false;
	}

	/**
	 * Retrieves the JavaFX {@link ListView} showing files/directories in this directory list.
	 *
	 * @return The active {@link ListView} control.
	 */
	public ListView<CloudFile> getListView() {
		return listView;
	}

	/**
	 * Wraps the given observable collection of cloud files in a sorted list helper, 
	 * ordering them alphabetically.
	 *
	 * @param olItems The observable items collection.
	 * @return A {@link SortedList} containing sorted elements.
	 */
	private SortedList<CloudFile> createSortedItemsList(ObservableList<CloudFile> olItems) {
		return new SortedList<CloudFile>(olItems, new Comparator<CloudFile>() {
			/**
			 * Compares two cloud files by their paths alphabetically.
			 *
			 * @param arg0 the first file
			 * @param arg1 the second file
			 * @return a negative integer, zero, or a positive integer as the first argument is less than, equal to, or greater than the second
			 */
			@Override
			public int compare(CloudFile arg0, CloudFile arg1) {
				return arg0.getPath().compareToIgnoreCase(arg1.getPath());
			}
		});
	}

	/**
	 * Handles drag-and-drop dropping events on this directory column.
	 * Decodes file trees for storage uploads or deserializes metadata lists for move/rename transfers.
	 *
	 * @param de         The JavaFX {@link DragEvent} details.
	 * @param target     The target directory {@link CloudFile} where elements are dropped.
	 * @param isGoToNext Flag to direct navigation progression.
	 */
	private void dropFilesHandle(DragEvent de, CloudFile target, boolean isGoToNext) {
		// Get data from the dragboard
		Dragboard db = de.getDragboard();
		boolean success = false;

		if (db.hasFiles()) {
			List<File> listToProcess = db.getFiles();

			try {
				List<Tuple2<File, CloudFile>> listForSubTree = AltaStataApp.account.getFileSystemHandler()
						.mapFilesTreeToCloudFileList(listToProcess,
								listToProcess.get(0).getParent(), target.getPath(), System.currentTimeMillis());

				new Thread(AltaStataApp.storeTask(listForSubTree)).start();
				success = true;
			} catch (IllegalArgumentException ex) {
				LOGGER.error("dropFilesHandle: cannot map dropped files to cloud paths", ex);
				new Alert(Alert.AlertType.ERROR,
						"Cannot upload the dropped files:\n" + ex.getMessage()).showAndWait();
				de.setDropCompleted(false);
				de.consume();
				return;
			}
		} else if (db.hasString()) { // for RENAME
			List<CloudFile> listToProcess = new ArrayList<CloudFile>();

			try {
				// TODO: run findCloudFile() in the context of Thread and not JavaFX
				for (CloudFile file : deserialize(db.getString())) {
					listToProcess.add(AltaStataApp.account.getFileSystemHandler().findCloudFile((CloudFile) file));
				}

				if (listToProcess.size() > 0) {
					new Thread(AltaStataApp.renameTask(listToProcess, listToProcess.get(0).getParent(),
							target.getPath())).start();
				}
				success = true;

			} catch (ClassNotFoundException | IOException e) {
				e.printStackTrace();
			}
		}

		if (success == true) {
			// Update the target listView
			int index = container.findIndex(DirectoryList.this) - 1;

			if (isGoToNext) {
				index++;
			}

			container.createAndPopulateDirectoryList(index, target);

			// change selected item
			listView.getSelectionModel().clearSelection();
			listView.getSelectionModel().select(target);

			if (NavigationPane.isMobileNavigation) {
				container.mobileClickAndMoveForward(container.getCurrentDirectoryIndex());
			}
		}

		// Inform the source that the drag/drop is complete
		de.setDropCompleted(success);
		de.consume();
	}

	/**
	 * Retrieves the collection of currently selected file/directory items,
	 * supporting standard multi-selection models and mobile checked-state maps.
	 *
	 * @return A {@link Collection} of selected {@link CloudFile} elements.
	 */
	public Collection<CloudFile> getLastSelected() {
		if (NavigationPane.isMobileSelect) {

			List<CloudFile> selected = new ArrayList<CloudFile>();
			for (CloudFile cf : olItems) {
				if (cf.getMobileSelectStatus()) {
					selected.add(cf);
				}
			}

			return selected;

			// Java 8 implementation
			// return olItems.stream().
			// filter(cf ->
			// cf.getMobileSelectStatus().getValue()).collect(Collectors.toList());
		} else {
			return getListView().getSelectionModel().getSelectedItems();
		}
	}

	/**
	 * Deserializes a Base64-encoded string representing a list of serialized {@link CloudFile}s.
	 *
	 * @param serialized Base64 encoded string.
	 * @return Deserialized {@link List} of {@link CloudFile}s.
	 * @throws IOException            If communication errors occur.
	 * @throws ClassNotFoundException If the target class is missing.
	 */
	public static List<CloudFile> deserialize(String serialized) throws IOException, ClassNotFoundException {
		List<CloudFile> listToProcess;
		byte[] data = Base64.getDecoder().decode(serialized);
		ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(data));
		listToProcess = (List<CloudFile>) ois.readObject();
		ois.close();
		return listToProcess;
	}

	/**
	 * Serializes a list of {@link CloudFile} items to a Base64 string for system clipboard/drag-and-drop transfers.
	 *
	 * @param toSerialize The list of cloud files to serialize.
	 * @return A Base64-encoded serialization string.
	 * @throws IOException If serialization fails.
	 */
	public static String serialize(List<CloudFile> toSerialize) throws IOException {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		ObjectOutputStream oos = new ObjectOutputStream(baos);
		oos.writeObject(toSerialize);
		oos.close();
		String serializedText = Base64.getEncoder().encodeToString(baos.toByteArray());
		return serializedText;
	}

	/**
	 * Returns a string representation of this DirectoryList instance.
	 *
	 * @return Single quoted text representing column header title.
	 */
	public String toString() {
		return "\'" + headerTxt.getText() + "\'";
	}
}
