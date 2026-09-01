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

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import com.altastata.filesystem.common.CloudFile;
import com.altastata.filesystem.common.FileSystemHandler;
import com.altastata.api.OperationStateChangeEvent;
import com.altastata.api.OperationStateChangeListener;
import com.altastata.api.AltaStataFileSystem.OperationState;

import com.altastata.ui.theme.UITheme;
import javafx.beans.property.Property;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.TextAlignment;
import javafx.scene.layout.BorderPane;

/**
 * A specialized {@link VerticalBox} view that displays search results within the file explorer.
 * 
 * Includes custom list cell styling, dynamic icon rendering depending on file operation state 
 * (uploading, downloading, or default), and interactive double-click / navigation logic to locate
 * selected items inside their parent directories in the primary navigation panel.
 */
public class SearchList extends VerticalBox {

	Label headerTxt = null;

	ObservableList<CloudFile> olItems = FXCollections.synchronizedObservableList(FXCollections.observableArrayList());
	ListView<CloudFile> listView = new ListView<CloudFile>(olItems);

	/**
	 * Creates a new SearchList container.
	 *
	 * @param container The parent navigation pane workspace.
	 */
	public SearchList(NavigationPane container) {
		super(container);

		listView.getSelectionModel().setSelectionMode(SelectionMode.SINGLE);

		VBox.setVgrow(listView, Priority.ALWAYS);

		BorderPane headerPane = new BorderPane();
		headerPane.setPadding(UITheme.PADDING_TIGHT);
		headerPane.setPrefHeight(32);
		headerPane.setStyle("-fx-background-color: " + UITheme.toHexString(UITheme.HEADER_BACKGROUND) + ";");

		// create the header text
		headerTxt = new Label("Search");
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

		// http://www.drdobbs.com/jvm/multi-columnselect-javafx-listview/229402139
		listView.setStyle(UITheme.CLEAN_LISTVIEW_STYLE);

		getChildren().add(headerPane);

		defineListCellForListView();

		getChildren().add(listView);
	}

	/**
	 * Configures and registers a custom cell factory for the search results {@link ListView}.
	 *
	 * Styles each cell with a clean layout, resolves relevant type/operation icons on state changes,
	 * and listens for selection and click events to trigger structural exploration navigation.
	 */
	public void defineListCellForListView() {

		((ListView<CloudFile>) listView).setCellFactory(listView -> {
			ListCell<CloudFile> lc = new ListCell<CloudFile>() {
				private final ImageView imageView = new ImageView();
				private final Label textLabel = new Label("");

				Property<OperationState> operationState = new SimpleObjectProperty<OperationState>();

				private BorderPane pane = new BorderPane();

				{
					// make text more visible
					this.setFont(UITheme.LIST_ITEM);
					textLabel.setFont(UITheme.LIST_ITEM);

					imageView.setFitHeight(20);
					imageView.setFitWidth(32);
					imageView.setPreserveRatio(true);

					textLabel.setPadding(UITheme.PADDING_TEXT);

					// this.getStyleClass().add("check-box-list-cell");

					pane.setLeft(imageView);
					pane.setCenter(textLabel);
					pane.setPadding(UITheme.PADDING_COMPONENT);
					BorderPane.setAlignment(textLabel, Pos.CENTER_LEFT);
				}

				/**
				 * Updates the visual state of the cell based on the given item.
				 *
				 * @param cloudFile the cloud file item to display
				 * @param empty     true if the cell is empty
				 */
				@Override
				public void updateItem(CloudFile cloudFile, boolean empty) {
					super.updateItem(cloudFile, empty);

					if (empty) {
						setText(null);
						setGraphic(null);
					} else {
						// setText(cloudFile.getName());
						textLabel.setText(cloudFile.getPath());
						textLabel.setWrapText(true);
						textLabel.setTextAlignment(TextAlignment.JUSTIFY);

						setIcon(cloudFile);

						setGraphic(pane);

						setOperationStateListeners(cloudFile);

						setOnMouseClicked(me -> handleOnMouseClicked(cloudFile));
					}
				}

				/**
				 * Adds a listener for operation state changes on the cloud file.
				 *
				 * @param cloudFile the file to track for operation changes
				 */
				private void setOperationStateListeners(CloudFile cloudFile) {
					// bind on cloudFile property
					cloudFile.addOperationStateValueChangeListener(new OperationStateChangeListener() {
						/**
						 * Handles operation state changes.
						 *
						 * @param evt the operation state change event
						 */
						@Override
						public void operationStateChange(OperationStateChangeEvent evt) {
							operationState.setValue(evt.getOperationState());
						}
					});

					// add listener to change the image and unselect headerCheckBox and this
					// checkBox
					operationState.addListener((obs, wasOn, isNowOn) -> {
						setIcon(cloudFile);
					});
				}

				/**
				 * Sets the appropriate icon based on the file state.
				 *
				 * @param cloudFile the file to display
				 */
				private void setIcon(CloudFile cloudFile) {
					if (cloudFile.isDirectory()) {
						switch (cloudFile.getOperationState()) {
						case DOWNLOADING:
							imageView.setImage(new Image("images/Folder-Arrow-Down-icon.png", true));
							break;
						case UPLOADING:
							imageView.setImage(new Image("images/Folder-Arrow-Up-icon.png", true));
							break;
						case SHARING:
						case NONE:
						default:
							imageView.setImage(new Image("images/Folder-Mac-icon.png", true));
							break;
						}
					} else {
						switch (cloudFile.getOperationState()) {
						case DOWNLOADING:
							imageView.setImage(new Image("images/File-Arrow-Down.png", true));
							break;
						case UPLOADING:
							imageView.setImage(new Image("images/File-Arrow-Up.png", true));
							break;
						case SHARING:
						case NONE:
						default:
							imageView.setImage(new Image("images/Document-icon.png", true));
							break;
						}
					}
				}

				/**
				 * Handles mouse click events on the cell.
				 *
				 * @param cloudFile the cloud file associated with the clicked cell
				 */
				private void handleOnMouseClicked(CloudFile cloudFile) {
					CloudFile lastFile = new CloudFile(FileSystemHandler.INIT_DIR, true);
					DirectoryList lastDirList = container.createAndPopulateDirectoryList(
							AltaStataApp.INITIAL_COLUMN_NUMBER_BEFORE_INCREMENTATION, lastFile);

					int lastSlashIndex = 0;
					while ((lastSlashIndex = cloudFile.getPath().indexOf("/",
							lastFile.getPath().length() + 1)) >= 0) {
						lastFile = new CloudFile(cloudFile.getPath().substring(0, lastSlashIndex), true);

						// select file within the directory
						((DirectoryList) lastDirList).getListView().getSelectionModel().select(lastFile);

						lastDirList = container.createSubDirectoryAndNavigate(lastDirList, lastFile);
					}

					if (NavigationPane.isMobileNavigation) {
						int parentDirIndex = container.getDirectoryIndexForCloudFile(lastFile);

						container.setCurrentDirectoryIndex(parentDirIndex);
						container.mobileClickAndMoveForward(container.getCurrentDirectoryIndex());
					} else {
						// request focus
						((DirectoryList) lastDirList).getListView().requestFocus();
						((DirectoryList) lastDirList).getListView().scrollTo(0);
						((DirectoryList) lastDirList).getListView().getSelectionModel().select(cloudFile);

						// TODO: does not work correct
						// Platform.runLater(new Runnable() {
						// @Override
						// public void run() {
						// AltaStataApp.scrollPane.setHvalue(AltaStataApp.scrollPane.getHmin());
						// }
						// });
					}
				}
			};

			lc.prefWidthProperty().bind(listView.widthProperty().subtract(4));

			return lc;
		});
	}

	/**
	 * Retrieves the observable collection of search items currently managed by this list.
	 *
	 * @return The underlying {@link ObservableList} of {@link CloudFile} matches.
	 */
	public ObservableList<CloudFile> getItems() {
		return olItems;
	}

	/**
	 * Populates the list with a new set of cloud file search results.
	 *
	 * @param items The set of found {@link CloudFile}s to display.
	 */
	public void setItems(Set<CloudFile> items) {
		if (items != null) {
			for (CloudFile item : items) {
				olItems.addAll(item);
			}

			listView.setItems(olItems);
		}
	}

	/**
	 * Gets the internal JavaFX {@link ListView} control displaying the search results.
	 *
	 * @return The active {@link ListView} instance.
	 */
	public ListView<CloudFile> getListView() {
		return listView;
	}

	/**
	 * Retrieves all selected search result objects. Handles both multi-selection checks
	 * on standard desktop displays and mobile checked states.
	 *
	 * @return A {@link Collection} of currently selected {@link CloudFile} items.
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
}
