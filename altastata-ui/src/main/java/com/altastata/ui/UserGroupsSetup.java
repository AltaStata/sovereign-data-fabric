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

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import com.altastata.ui.theme.UITheme;
import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javafx.application.Application;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.event.ActionEvent;
import javafx.geometry.HPos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Modality;
import javafx.stage.Stage;

/**
 * JavaFX Application / Controller responsible for managing user grouping configurations.
 * 
 * Allows users to create user groups, delete groups, and manage group memberships by moving 
 * user handles between 'Users' (candidates) and 'Selected' lists. All group definitions 
 * are stored as line-delimited files within the active Account's `groups/` directory.
 */
public class UserGroupsSetup extends Application {

	private String selectedGroupName = null;
	private List<String> fullUsersList = null;
	private ObservableList<String> groups = FXCollections.observableArrayList();
	private ObservableList<String> candidates = FXCollections.observableArrayList();
	private ObservableList<String> selected = FXCollections.observableArrayList();

	private static Logger LOGGER = LoggerFactory.getLogger(UserGroupsSetup.class);

	/**
	 * Standard JavaFX Application lifecycle entry point used to initialize and display 
	 * the user groups setup modal window.
	 *
	 * @param primaryStage The primary {@link Stage} container.
	 * @throws IOException If file loading or initialization errors occur.
	 */
	@Override
	public void start(Stage primaryStage) throws IOException {
		primaryStage.initModality(Modality.WINDOW_MODAL);

		Scene scene = new Scene(createPane(), 250, 250, Color.WHITE);
		primaryStage.setScene(scene);
		primaryStage.show();
	}

	/**
	 * Instantiates and configures the main layout container containing group listings 
	 * and membership selection grids.
	 *
	 * @return A styled {@link BorderPane} containing setup controls.
	 */
	public BorderPane createPane() {
		// convert iterator to list
		fullUsersList = new ArrayList<String>();
		try {
			Iterator<String> it = AltaStataApp.account.fileSystemModel().listUsers();
			while (it.hasNext()) {
				fullUsersList.add(it.next());
			}

			fullUsersList.remove(AltaStataApp.account.MY_USER());
			fullUsersList.remove(AltaStataApp.account.CUSTODIAN_USER());
		} catch (NullPointerException ex) {
			LOGGER.error("createPane", ex);
		}

		BorderPane root = new BorderPane();
		root.setTop(listGroups());
		root.setCenter(configureGroup());
		root.setStyle("	 -fx-border-color: grey;" + "	 -fx-border-width: 2px;" + "	 -fx-padding: 13;"
				+ "	 -fx-spacing: 15;" + "    -fx-background-color: #f3f3f3;\n"
				+ "    -fx-background-insets: 0, 1, 2;\n" + "    -fx-background-radius: 5 5 5 5;");

		return root;
	}

	/**
	 * Builds the top-level horizontal panel containing the list of defined groups, 
	 * along with action buttons to Add or Delete groups.
	 *
	 * @return An {@link HBox} containing the groups list view and control buttons.
	 */
	private HBox listGroups() {
		candidates.setAll(fullUsersList);

		ListView<String> list = new ListView<>(groups);
		list.setMaxHeight(80);

		list.setOnMousePressed(me -> handleSelectGroup(list));
		list.setOnKeyReleased(me -> handleSelectGroup(list));

		File groupDir = new File(AltaStataApp.account.getAccountDir() + File.separator + "groups");
		if (groupDir.exists()) {
			for (String groupName : groupDir.list()) {
				groups.add(groupName);
			}
		}

		TextField groupNameField = new TextField();
		groupNameField.setMinWidth(100);

		Button btnAdd = new Button();
		btnAdd.setText("Add");
		btnAdd.setOnAction((ActionEvent event) -> {
			if (!groupNameField.getText().equals("")) {
				list.getItems().add(list.getItems().size(), groupNameField.getText() + ".group");
				FXCollections.sort(groups);
				groupNameField.clear();
			}
		});

		Button btnDelete = new Button();
		btnDelete.setText("Del");
		btnDelete.setOnAction((ActionEvent event) -> {
			selectedGroupName = list.getSelectionModel().getSelectedItem();

			File file = new File(AltaStataApp.account.getAccountDir() + File.separator + "groups" + File.separator
					+ selectedGroupName);
			if (file.exists()) {
				file.delete();
			}

			groups.remove(selectedGroupName);
		});

		HBox buttons = new HBox(btnAdd, btnDelete);
		buttons.setPadding(UITheme.PADDING_MINIMAL);
		buttons.setSpacing(10);

		VBox newGroup = new VBox(groupNameField, buttons);
		newGroup.setSpacing(5);

		HBox root = new HBox(list, newGroup);
		root.setPadding(UITheme.PADDING_FORM);
		root.setSpacing(10);

		return root;
	}

	/**
	 * Callback handler executed when a group is selected in the list.
	 * Loads the group's current memberships from disk and partitions users into 
	 * candidate and selected list structures.
	 *
	 * @param list The group list view control.
	 */
	private void handleSelectGroup(ListView<String> list) {
		try {
			selectedGroupName = list.getSelectionModel().getSelectedItem();
			File file = new File(AltaStataApp.account.getAccountDir() + File.separator + "groups" + File.separator
					+ selectedGroupName);
			if (file.exists()) {
				List<String> existingUsers = FileUtils.readLines(file);

				candidates.setAll(fullUsersList);
				candidates.removeAll(existingUsers);
				selected.setAll(existingUsers);
			} else {
				candidates.setAll(fullUsersList);
				selected.clear();
			}
		} catch (Exception e) {
			LOGGER.error("handleSelectGroup", e);
		}
	}

	/**
	 * Builds the center-level membership modification panel (a side-by-side selection grid) 
	 * allowing users to add/remove members from the currently selected group.
	 *
	 * @return A configured {@link GridPane} selection utility.
	 */
	private GridPane configureGroup() {
		GridPane gridpane = new GridPane();
		gridpane.setPadding(UITheme.PADDING_SMALL);
		gridpane.setHgap(5);
		gridpane.setVgap(5);

		ColumnConstraints column1 = new ColumnConstraints(100, 100, Double.MAX_VALUE);
		ColumnConstraints column2 = new ColumnConstraints(30);
		ColumnConstraints column3 = new ColumnConstraints(100, 100, Double.MAX_VALUE);
		column1.setHgrow(Priority.ALWAYS);
		column3.setHgrow(Priority.ALWAYS);
		gridpane.getColumnConstraints().addAll(column1, column2, column3);

		Label candidatesLbl = new Label("Users");
		GridPane.setHalignment(candidatesLbl, HPos.CENTER);
		gridpane.add(candidatesLbl, 0, 0);

		Label selectedLbl = new Label("Selected");
		gridpane.add(selectedLbl, 2, 0);
		GridPane.setHalignment(selectedLbl, HPos.CENTER);

		// Candidates
		final ListView<String> candidatesListView = new ListView<>(candidates);
		gridpane.add(candidatesListView, 0, 1);

		// Selected
		final ListView<String> heroListView = new ListView<>(selected);
		gridpane.add(heroListView, 2, 1);

		FXCollections.sort(candidates);
		FXCollections.sort(selected);

		Button sendRightButton = new Button(">");
		sendRightButton.setOnAction((ActionEvent event) -> {
			String potential = candidatesListView.getSelectionModel().getSelectedItem();
			if (potential != null) {
				candidatesListView.getSelectionModel().clearSelection();
				candidates.remove(potential);
				selected.add(potential);

				FXCollections.sort(candidates);
				FXCollections.sort(selected);

				try {
					File file = new File(AltaStataApp.account.getAccountDir() + File.separator + "groups"
							+ File.separator + selectedGroupName);
					FileUtils.writeLines(file, selected);
				} catch (Exception e) {
					LOGGER.error("sendRightButton", e);
				}
			}
		});

		Button sendLeftButton = new Button("<");
		sendLeftButton.setOnAction((ActionEvent event) -> {
			String s = heroListView.getSelectionModel().getSelectedItem();
			if (s != null) {
				heroListView.getSelectionModel().clearSelection();
				selected.remove(s);
				candidates.add(s);

				FXCollections.sort(candidates);
				FXCollections.sort(selected);

				try {
					File file = new File(AltaStataApp.account.getAccountDir() + File.separator + "groups"
							+ File.separator + selectedGroupName);
					FileUtils.writeLines(file, selected);
				} catch (Exception e) {
					LOGGER.error("sendRLeftButton", e);
				}
			}
		});

		VBox vbox = new VBox(5);
		vbox.getChildren().addAll(sendRightButton, sendLeftButton);

		gridpane.add(vbox, 1, 1);
		return gridpane;
	}

	/**
	 * Standalone entry point to launch the user groups setup UI independently.
	 *
	 * @param args CommandLine arguments.
	 */
	public static void main(String[] args) {
		launch(args);
	}
}
