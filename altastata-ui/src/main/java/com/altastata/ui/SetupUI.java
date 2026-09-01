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

import com.altastata.api.accountsetup.HPCSUserAccountSetupHandler;
import com.altastata.api.accountsetup.PQCUserAccountSetupHandler;
import com.altastata.api.accountsetup.RSAUserAccountSetupHandler;
import com.altastata.api.accountsetup.UserAccountSetupHandlerInterface;
import com.altastata.cloud.ibm.HpcsGrep11KeyGenerator;
import com.altastata.ui.theme.UITheme;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.HPos;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.*;
import javafx.scene.text.Text;
import javafx.stage.Popup;
import javafx.stage.Stage;
import javafx.util.Callback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.Properties;
import java.util.stream.Stream;

import com.altastata.filesystem.UserMetadata;
import com.altastata.ui.util.VersionUtils;

/**
 * Orchestrates JavaFX UI layout generation, modal dialogs, configuration dialogs,
 * and onboarding/setup screens for setting up accounts (RSA, PQC, HSM, HPCS).
 */
public class SetupUI {

	private static Logger LOGGER = LoggerFactory.getLogger(SetupUI.class);

	/**
	 * Heuristic: does a user message describe an error or a rejected/ignored change?
	 * Such messages are rendered in a muted "pale red" in the messages list.
	 */
	private static boolean isErrorMessage(String msg) {
		if (msg == null) {
			return false;
		}
		String m = msg.toLowerCase();
		return m.contains("error")
			|| m.contains("strange change")
			|| m.contains("deleted without")
			|| m.contains("does not exist")
			|| m.contains("does not match")
			|| m.contains("not found")
			|| m.contains("missing")
			|| m.contains("timeout")
			|| m.contains("failed")
			|| m.contains("was ignored");
	}

	/** Key protection type - explicit user choice (not inferred only from account name). */
	private enum AccountKeyType {
		RSA("RSA"),
		PQC("PQC"),
		HSM("HSM"),
		HPCS("HPCS");

		private final String label;

		AccountKeyType(String label) {
			this.label = label;
		}

		@Override
		public String toString() {
			return label;
		}
	}
	
	static Stage primaryStage;
	static Popup appsPopup;
	static TilePane appsTile;

	/**
	 * Show the main 2x2 apps menu (accounts / groups / messages / status).
	 */
	public static void showAppsMenu() {
		if (appsPopup == null || appsTile == null) {
			return;
		}
		AltaStataApp.showPopup(appsPopup, appsTile, true);
	}

	/**
	 * Initializes the SetupUI action button controllers, layouts, and menus.
	 * Registers event click handlers for Accounts management, Group sharing configurations,
	 * system Messages, and the Dashboard status buttons inside the main Application popup menu.
	 *
	 * @param appsButton   The main toolbar button triggering the apps layout.
	 * @param primaryStage The main JavaFX {@link Stage}.
	 * @param borderPane   The main window's {@link BorderPane}.
	 * @param popup        The shared {@link Popup} used to display dialog menus.
	 */
	static public void init(Button appsButton, Stage primaryStage, BorderPane borderPane, Popup popup) {
		
		SetupUI.primaryStage = primaryStage;
		SetupUI.appsPopup = popup;

		TilePane tile = new TilePane();
		SetupUI.appsTile = tile;
		tile.setPadding(UITheme.PADDING_TILE);
		tile.setVgap(6);
		tile.setHgap(6);
		tile.setPrefColumns(2);
		tile.getStylesheets().add("css/Toolbar.css");
		tile.setStyle(UITheme.TRANSPARENT_TILE_STYLE);
		// tile.setOpacity(0.6);

		appsButton.setOnMouseClicked(me -> {
			if (AltaStataApp.account.MY_USER() == null || AltaStataApp.accountManagementService.checkOrInputPassword()) {
				if (AltaStataApp.isPopupShowing(popup)) {
					try {
						AltaStataApp.hidePopup(popup);
					}
					catch (NullPointerException ex) {
						// Do nothing, we just hide the popup
					}
				} else {
					AltaStataApp.showPopup(popup, tile, true);
				}
			}
		});

		Button accountsButton = new Button();
		accountsButton.setPadding(UITheme.PADDING_TIGHT);
		ImageView accountsView = new ImageView(new Image("images/multiple-account-icon-green.png"));
		accountsView.setFitWidth(60);
		accountsView.setFitHeight(60);
		accountsButton.setGraphic(accountsView);
		accountsButton.setId("accountsButton");
		accountsButton.setTooltip(new Tooltip("Accounts ... "));
		accountsButton.setFocusTraversable(false);

		tile.getChildren().add(accountsButton);

		accountsButton.setOnMouseClicked(me -> {
			// TODO: implement settings
			AltaStataApp.hidePopup(popup);

			AltaStataApp.accountManagementService.selectAccount();
		});

		Button groupsConfigButton = new Button();
		groupsConfigButton.setPadding(UITheme.PADDING_TIGHT);
		ImageView groupsConfigView = new ImageView(new Image("images/community_icon.png"));
		groupsConfigView.setFitWidth(60);
		groupsConfigView.setFitHeight(60);
		groupsConfigButton.setGraphic(groupsConfigView);
		groupsConfigButton.setId("groupsConfigButton");
		groupsConfigButton.setTooltip(new Tooltip("Groups Config ... "));
		groupsConfigButton.setFocusTraversable(false);

		tile.getChildren().add(groupsConfigButton);

		groupsConfigButton.setOnMouseClicked(me -> {
			// TODO: implement settings
			AltaStataApp.hidePopup(popup);
			/*
			 * final VBox vBox = createHttpSettingsBox();
			 * 
			 * final StackPane stackPane = new StackPane();
			 * //stackPane.setAlignment(Pos.CENTER); stackPane.getChildren().add(vBox);
			 */

			final BorderPane userGroupsSetupBorderPane = new UserGroupsSetup().createPane();
			AltaStataApp.showPopup(popup, userGroupsSetupBorderPane, true);

			userGroupsSetupBorderPane.setOnKeyReleased(ke -> {
				if (ke.getCode().equals(KeyCode.ESCAPE)) {
					AltaStataApp.hidePopup(popup);
				}
			});
		});

		Button messagesButton = new Button();
		messagesButton.setPadding(UITheme.PADDING_TIGHT);
		ImageView messagesView = new ImageView(new Image("images/messages.png"));
		messagesView.setFitWidth(60);
		messagesView.setFitHeight(60);
		messagesButton.setGraphic(messagesView);
		messagesButton.setId("messagesButton");
		messagesButton.setTooltip(new Tooltip("Messages ... "));
		messagesButton.setFocusTraversable(false);

		tile.getChildren().add(messagesButton);

		// observe AltaStataApp.account.userMsgs
		ObservableList<String> observableUserMsgsList = FXCollections.observableList(AltaStataApp.account.getUserMsgs());

		messagesButton.setOnMouseClicked(me -> {

			ListView<String> msgsListView = new ListView<String>(observableUserMsgsList);

			// Wider/taller so multi-line messages are readable without truncation.
			msgsListView.setPrefWidth(720);
			msgsListView.setMinWidth(560);
			msgsListView.setPrefHeight(380);

			msgsListView.setCellFactory(new Callback<ListView<String>, ListCell<String>>() {
				/**
				 * Called to provide a custom list cell.
				 *
				 * @param list the list view
				 * @return a custom list cell
				 */
				@Override
				public ListCell<String> call(final ListView<String> list) {
					return new ListCell<String>() {
						private final Text text = new Text();
						{
							text.wrappingWidthProperty().bind(list.widthProperty().subtract(30));
							setPrefWidth(0);
						}

						@Override
						protected void updateItem(String item, boolean empty) {
							super.updateItem(item, empty);
							if (empty || item == null) {
								setGraphic(null);
							} else {
								text.setText(item);
								// Errors/rejections stand out in a muted "pale red".
								text.setFill(isErrorMessage(item) ? UITheme.MESSAGE_ERROR : UITheme.ALMOST_BLACK);
								setGraphic(text);
							}
						}
					};
				}
			});

			// scroll to the bottom
			final int size = msgsListView.getItems().size();
			if (size > 0) {
				msgsListView.scrollTo(size - 1);
			}

			AltaStataApp.hidePopup(popup);

			VBox vbh = new VBox();
			vbh.setPadding(UITheme.PADDING_LARGE_DIALOG);

			vbh.setStyle(UITheme.COMPACT_DIALOG_BOX_STYLE);

			vbh.setOpacity(0.9);
			vbh.getChildren().add(msgsListView);

			final StackPane stackPane = new StackPane();
			stackPane.getChildren().add(vbh);

			AltaStataApp.showPopup(popup, stackPane, true);

			stackPane.setOnKeyReleased(ke -> {
				if (ke.getCode().equals(KeyCode.ESCAPE)) {
					AltaStataApp.hidePopup(popup);
				}
			});

		});

		Button statusButton = new Button();
		statusButton.setPadding(UITheme.PADDING_TIGHT);
		ImageView statusView = new ImageView(new Image("images/dashboard.png"));
		statusView.setFitWidth(60);
		statusView.setFitHeight(60);
		statusButton.setGraphic(statusView);
		statusButton.setId("statusButton");
		statusButton.setTooltip(new Tooltip("Status ... "));
		statusButton.setFocusTraversable(false);

		tile.getChildren().add(statusButton);

		statusButton.setOnMouseClicked(me -> {
			// TODO: implement settings
			AltaStataApp.hidePopup(popup);

			final VBox vBox = new VBox();

			Label titleLabel = new Label(VersionUtils.getVersionInfo());
			titleLabel.setStyle(UITheme.LARGE_HEADER_STYLE);
			
			Label buildLabel = new Label("Built: " + VersionUtils.getBuildTimestamp());
			buildLabel.setStyle(UITheme.MEDIUM_HEADER_STYLE);
			
			Label copyrightLabel = new Label("All rights reserved by " + VersionUtils.getVendor());
			copyrightLabel.setStyle(UITheme.MEDIUM_HEADER_STYLE);
			
			vBox.getChildren().addAll(titleLabel, buildLabel, copyrightLabel);
			vBox.setPadding(UITheme.PADDING_LARGE_DIALOG);
			vBox.setStyle(UITheme.DIALOG_BOX_STYLE +
					"    -fx-effect: dropshadow(three-pass-box, rgba(0, 0, 0, 0.6), 8, 0.0, 0, 0);");

			vBox.setOpacity(0.6);
			final StackPane stackPane = new StackPane();
			// stackPane.setAlignment(Pos.CENTER);
			stackPane.getChildren().add(vBox);

			AltaStataApp.showPopup(popup, stackPane, true);

		});

	}


	/**
	 * Finds the first file matching *.properties in the directory.
	 *
	 * @param dirPath target directory path
	 * @return name of the properties file or default
	 */
	static private String findPropertiesFileName(String dirPath) {
		File directory = new File(dirPath);

		if (!directory.exists() || !directory.isDirectory()) {
			throw new IllegalArgumentException("Invalid directory path: " + dirPath);
		}

		File[] files = directory.listFiles((dir, name) -> name.endsWith(".properties"));
		if (files != null && files.length > 0) {
			for (File file : files) {
				return file.getName();
			}
		}

		return "my.user.properties";
	}

	/**
	 * Checks if the specified directory contains a private key file, hpcs marker, or HPCS private key blob.
	 *
	 * @param dirPath target directory path
	 * @return true if private key or HPCS files are present
	 */
	static boolean containsPrivateKeyFile(String dirPath) {
		Path directory = Paths.get(dirPath);

		if (!Files.isDirectory(directory)) {
			return false;
		}

		try (Stream<Path> files = Files.list(directory)) {
			return files.anyMatch(file -> {
				String name = file.getFileName().toString();
				return name.endsWith("private.key")
						|| name.equals("hpcs.marker")
						|| name.equals("hpcs-privkey.blob");
			});
		} catch (IOException e) {
			return false;
		}
	}

	/**
	 * Creates and configures the account name text field.
	 *
	 * @param name The account name (null for new accounts)
	 * @return Configured TextField for account name
	 */
	private static TextField createAccountNameField(String name) {
		TextField nameTextField = new TextField();
		nameTextField.setPrefColumnCount(15);

		if (name != null) {
			nameTextField.setEditable(false);
			nameTextField.setFocusTraversable(false);
			nameTextField.setText(name);
		}

		return nameTextField;
	}

	/**
	 * Creates a list cell with specialized rendering for AccountKeyType dropdown.
	 *
	 * @return ListCell configured with the correct labels
	 */
	private static ListCell<AccountKeyType> createKeyTypeListCell() {
		return new ListCell<>() {
			/**
			 * Updates the visual state of the cell based on the given item.
			 *
			 * @param item  the account key type item
			 * @param empty true if the cell is empty
			 */
			@Override
			protected void updateItem(AccountKeyType item, boolean empty) {
				super.updateItem(item, empty);
				setText(empty || item == null ? "" : item.label);
			}
		};
	}

	/**
	 * Creates a ComboBox populated with the key types (RSA, HPCS, HSM, PQC).
	 *
	 * @param accountName current account name to pre-select type
	 * @return ComboBox for key type selection
	 */
	private static ComboBox<AccountKeyType> createAccountTypeComboBox(String accountName) {
		ComboBox<AccountKeyType> combo = new ComboBox<>(
				FXCollections.observableArrayList(AccountKeyType.values()));
		combo.setButtonCell(createKeyTypeListCell());
		combo.setCellFactory(listView -> createKeyTypeListCell());
		combo.setValue(inferAccountKeyType(accountName));
		combo.setPrefWidth(280);
		if (accountName != null) {
			combo.setDisable(true);
		}
		return combo;
	}

	/**
	 * Infers the account key type based on keywords in the account name.
	 *
	 * @param accountName name of the account
	 * @return inferred AccountKeyType enum
	 */
	private static AccountKeyType inferAccountKeyType(String accountName) {
		if (accountName == null || accountName.isEmpty()) {
			return AccountKeyType.RSA;
		}
		String lower = accountName.toLowerCase();
		if (lower.contains("hpcs")) {
			return AccountKeyType.HPCS;
		}
		if (lower.contains("hsm")) {
			return AccountKeyType.HSM;
		}
		if (lower.contains("pqc")) {
			return AccountKeyType.PQC;
		}
		if (lower.contains("rsa")) {
			return AccountKeyType.RSA;
		}
		return AccountKeyType.RSA;
	}

	/**
	 * Creates and configures the password settings pane with password fields.
	 *
	 * @param accountDir The account directory
	 * @param keyType    selected key protection type
	 * @param name       account folder name
	 * @return Configured GridPane with password fields and OK button
	 */
	private static GridPane createPasswordSettingsPane(String accountDir, AccountKeyType keyType, String name) {
		GridPane passwordSettingsPane = new GridPane();
		passwordSettingsPane.setHgap(10);
		passwordSettingsPane.setVgap(10);
		passwordSettingsPane.setPadding(UITheme.PADDING_FORM);
		Button okPasswordPropertiesButton = new Button("OK");
		populatePasswordSettingsPane(passwordSettingsPane, accountDir, keyType, name, okPasswordPropertiesButton);
		return passwordSettingsPane;
	}

	/** Rebuild hint/password rows when account type or name changes. */
	private static void refreshPasswordSettingsPane(GridPane passwordSettingsPane,
			String accountDir, AccountKeyType keyType, String accountName) {
		Button okButton = null;
		for (javafx.scene.Node child : passwordSettingsPane.getChildren()) {
			if (child instanceof Button) {
				okButton = (Button) child;
				break;
			}
		}
		if (okButton == null) {
			okButton = new Button("OK");
		}
		passwordSettingsPane.getChildren().clear();
		populatePasswordSettingsPane(passwordSettingsPane, accountDir, keyType, accountName, okButton);
	}

	private static void populatePasswordSettingsPane(GridPane passwordSettingsPane,
			String accountDir, AccountKeyType keyType, String name, Button okPasswordPropertiesButton) {
		boolean hpcsAccount = keyType == AccountKeyType.HPCS;
		boolean hsmAccount = keyType == AccountKeyType.HSM;
		boolean newAccountKeys = name == null || name.isEmpty()
				|| !containsPrivateKeyFile(accountDir + File.separator + name);

		int row = 0;
		if (hsmAccount) {
			Label hsmHint = new Label(
					"HSM: keys are managed by your hardware security module. "
							+ "Click OK to continue account setup.");
			hsmHint.setWrapText(true);
			hsmHint.setMaxWidth(420);
			hsmHint.setStyle("-fx-text-fill: #555555; -fx-font-size: 13px;");
			passwordSettingsPane.add(hsmHint, 0, row++);
		} else if (hpcsAccount) {
			Label hpcsHint = new Label("HPCS: click OK to generate keys (API key is in grep11client.yaml).");
			hpcsHint.setWrapText(true);
			hpcsHint.setPrefWidth(520);
			hpcsHint.setStyle("-fx-text-fill: #555555; -fx-font-size: 13px;");
			passwordSettingsPane.add(hpcsHint, 0, row++);
		} else {
			PasswordField passwordField = new PasswordField();
			PasswordField passwordFieldConfirm = new PasswordField();
			passwordField.setPromptText("Password");
			passwordFieldConfirm.setPromptText("Confirm Password");
			passwordSettingsPane.add(passwordField, 0, row++);
			if (newAccountKeys) {
				passwordSettingsPane.add(passwordFieldConfirm, 0, row++);
			}
		}

		passwordSettingsPane.add(okPasswordPropertiesButton, 0, row);
	}

	/**
	 * Creates the account setup handlers for RSA, PQC, and HPCS accounts.
	 *
	 * @return Array containing RSA, PQC, and HPCS handlers
	 */
	private static UserAccountSetupHandlerInterface[] createAccountHandlers() {
		final RSAUserAccountSetupHandler rsaHandler = new RSAUserAccountSetupHandler();
		final PQCUserAccountSetupHandler pqcHandler = new PQCUserAccountSetupHandler();
		final HPCSUserAccountSetupHandler hpcsHandler = new HPCSUserAccountSetupHandler();
		return new UserAccountSetupHandlerInterface[]{rsaHandler, pqcHandler, hpcsHandler};
	}
	
	/**
	 * Finds the first password field in a grid pane.
	 *
	 * @param pane target grid pane
	 * @return the first PasswordField found, or null
	 */
	private static PasswordField findFirstPasswordField(GridPane pane) {
		for (javafx.scene.Node child : pane.getChildren()) {
			if (child instanceof PasswordField) {
				return (PasswordField) child;
			}
		}
		return null;
	}

	/**
	 * Finds the second password field in a grid pane.
	 *
	 * @param pane target grid pane
	 * @return the second PasswordField found, or null
	 */
	private static PasswordField findSecondPasswordField(GridPane pane) {
		boolean foundFirst = false;
		for (javafx.scene.Node child : pane.getChildren()) {
			if (child instanceof PasswordField) {
				if (foundFirst) {
					return (PasswordField) child;
				}
				foundFirst = true;
			}
		}
		return null;
	}

	/**
	 * Gets password text or empty string if field is null.
	 *
	 * @param field target password field
	 * @return password text or empty string
	 */
	private static String passwordTextOrEmpty(PasswordField field) {
		return field == null ? "" : field.getText();
	}

	/**
	 * Determines the account setup handler from the selected key protection type.
	 */
	private static UserAccountSetupHandlerInterface getAccountConfigParameters(
			AccountKeyType keyType, UserAccountSetupHandlerInterface[] handlers) {
		switch (keyType) {
			case HPCS:
				return handlers[2];
			case PQC:
				return handlers[1];
			case RSA:
				return handlers[0];
			case HSM:
			default:
				return null;
		}
	}

	/**
	 * Configures the final dialog setup including key release handler.
	 *
	 * @param stackPane The main stack pane
	 * @param popup The popup for escape key handling
	 */
	private static void configureAccountDialog(StackPane stackPane, Popup popup) {
		stackPane.setOnKeyReleased(ke -> {
			if (ke.getCode().equals(KeyCode.ESCAPE)) {
				popup.hide();
			}
		});
	}

	/**
	 * Creates and returns the stack pane for account configuration/setup UI.
	 *
	 * @param accountDir path to the account directory
	 * @param popup parent popup component
	 * @param name name of the account
	 * @return StackPane UI root component
	 */
	static public StackPane accountConfig(String accountDir, Popup popup, String name) {
		StackPane stackPane = new StackPane();
		stackPane.setAlignment(Pos.CENTER);

		VBox vbh = new VBox();
		vbh.setPadding(UITheme.PADDING_LARGE_DIALOG);
		vbh.setStyle(UITheme.DIALOG_BOX_STYLE);
		vbh.setOpacity(0.9);

		// Create account name field and key-type selector
		TextField nameTextField = createAccountNameField(name);
		ComboBox<AccountKeyType> accountTypeCombo = createAccountTypeComboBox(name);
		final boolean[] keyTypeChosenByUser = {name != null};

		String defaultYaml = HpcsGrep11KeyGenerator.resolveYamlPath(null);
		Label hpcsYamlLabel = new Label("grep11client.yaml:");
		TextField hpcsYamlField = new TextField(defaultYaml != null ? defaultYaml : "");
		hpcsYamlField.setPrefColumnCount(50);
		hpcsYamlField.setPrefWidth(520);
		hpcsYamlField.setPromptText("Path to grep11client.yaml");
		Runnable refreshHpcsYamlVisibility = () -> {
			boolean hpcs = accountTypeCombo.getValue() == AccountKeyType.HPCS;
			hpcsYamlLabel.setVisible(hpcs);
			hpcsYamlLabel.setManaged(hpcs);
			hpcsYamlField.setVisible(hpcs);
			hpcsYamlField.setManaged(hpcs);
		};

		// Create password settings pane using helper method
		GridPane passwordSettingsPane = createPasswordSettingsPane(
				accountDir, accountTypeCombo.getValue(), name);
		Button okPasswordPropertiesButton = (Button) passwordSettingsPane.getChildren().get(
			passwordSettingsPane.getChildren().size() - 1);

		Runnable refreshKeysPane = () -> refreshPasswordSettingsPane(
				passwordSettingsPane, accountDir, accountTypeCombo.getValue(), nameTextField.getText());

		accountTypeCombo.valueProperty().addListener((obs, oldType, newType) -> {
			if (oldType != null && newType != null && oldType != newType) {
				keyTypeChosenByUser[0] = true;
			}
			refreshKeysPane.run();
			refreshHpcsYamlVisibility.run();
		});

		if (name == null) {
			nameTextField.textProperty().addListener((obs, oldName, newName) -> {
				if (!keyTypeChosenByUser[0]) {
					accountTypeCombo.setValue(inferAccountKeyType(newName));
				}
				refreshKeysPane.run();
			});
		}
		refreshKeysPane.run();
		refreshHpcsYamlVisibility.run();

		vbh.getChildren().setAll(
				new Label("Account Name:"), nameTextField,
				new Label("Key protection:"), accountTypeCombo,
				hpcsYamlLabel, hpcsYamlField,
				passwordSettingsPane);

		// Create account handlers using helper method
		UserAccountSetupHandlerInterface[] handlers = createAccountHandlers();
		final RSAUserAccountSetupHandler rsaUserAccountSetupHandler = (RSAUserAccountSetupHandler) handlers[0];
		final PQCUserAccountSetupHandler pqcUserAccountSetupHandler = (PQCUserAccountSetupHandler) handlers[1];
		final HPCSUserAccountSetupHandler hpcsUserAccountSetupHandler = (HPCSUserAccountSetupHandler) handlers[2];

		okPasswordPropertiesButton.setOnAction(event -> {

			PasswordField passwordField = findFirstPasswordField(passwordSettingsPane);
			PasswordField passwordFieldConfirm = findSecondPasswordField(passwordSettingsPane);

			// Get appropriate handler from key protection type
			AccountKeyType keyType = accountTypeCombo.getValue();
			UserAccountSetupHandlerInterface accountConfigParameters =
				getAccountConfigParameters(keyType, handlers);

			String dirPath = accountDir + File.separator + nameTextField.getText();
			final String passwordOrApiKey = passwordTextOrEmpty(passwordField);

			if (nameTextField.getText().trim().isEmpty()) {
				UIUtils.showErrorAlert("Account Name Error", "Enter an account name (e.g. amazon.rsa.hpcs.hpcsdev).");
				return;
			}

			Path path = Paths.get(dirPath);
			// if directory exists?
			if (!Files.exists(path)) {
				LOGGER.info("okPasswordPropertiesButton Creating directory: " + path);
				try {
					Files.createDirectories(path);
				} catch (IOException e1) {
					// TODO Auto-generated catch block
					LOGGER.error("okPasswordPropertiesButton Cannot create directory: " + path, e1);
				}
			}

			TilePane paneToExport = new TilePane();
			paneToExport.setPadding(UITheme.PADDING_SMALL);
			paneToExport.setVgap(6);
			paneToExport.setHgap(6);
			paneToExport.setPrefColumns(3);

			if (accountConfigParameters != null) {
				boolean noPasswordKeyType = keyType == AccountKeyType.HPCS || keyType == AccountKeyType.HSM;
				boolean passwordsMatch = passwordFieldConfirm == null
						|| passwordOrApiKey.equals(passwordFieldConfirm.getText());

				if (keyType == AccountKeyType.HPCS) {
					try {
						HpcsGrep11KeyGenerator.requireYamlPath(hpcsYamlField.getText());
						hpcsUserAccountSetupHandler.setGrep11YamlPath(hpcsYamlField.getText());
					} catch (RuntimeException ex) {
						UIUtils.showErrorAlert("HPCS Configuration", ex.getMessage());
						return;
					}
				}

				/*
				 * The main scenario of RSA and PQC user account setup
				 */
				if (accountConfigParameters.ifKeysFilesExist(dirPath) == false) {
					if (passwordsMatch || noPasswordKeyType) {
						try {
							accountConfigParameters.generateAndSaveKeys(dirPath, passwordOrApiKey);
							okPasswordPropertiesButton.setText("OK");
						} catch (RuntimeException ex) {
							LOGGER.error("Key generation failed", ex);
							UIUtils.showErrorAlert("Key Generation Error", ex.getMessage());
							okPasswordPropertiesButton.setText("Retry");
						}
					} else {
						okPasswordPropertiesButton.setText("Try again");
					}
				} else if (accountConfigParameters.ifKeysInitialized() == false) {
					if (accountConfigParameters.extractKeysFromFiles(dirPath, passwordOrApiKey)) {
						okPasswordPropertiesButton.setText("OK");
					} else {
						okPasswordPropertiesButton.setText("Retry");
					}
				} else {
					if (passwordsMatch || noPasswordKeyType) {
						accountConfigParameters.reencryptAndSavePrivateKey(passwordOrApiKey, dirPath);

						okPasswordPropertiesButton.setText("OK");
					} else {
						okPasswordPropertiesButton.setText("Try again");
					}
				}

				if (noPasswordKeyType
						|| accountConfigParameters.checkPasswordUsingEncryptedPrivateKey(passwordOrApiKey, dirPath)) {

					Button publicKeyExportButton = new Button("Public Key");
					publicKeyExportButton.setPadding(UITheme.PADDING_SMALL);
					publicKeyExportButton.setOnAction(e -> {
						try {
							String publicKeyPEMs = accountConfigParameters.publicKeysToCopy(dirPath);

							Clipboard clipboard = Clipboard.getSystemClipboard();
							ClipboardContent content = new ClipboardContent();
							content.putString(publicKeyPEMs);
							clipboard.setContent(content);

							UIUtils.showInfoAlert("Public Key", "Public key copied to clipboard");
						} catch (IOException ex) {
							LOGGER.error("Error copying public key", ex);
							UIUtils.showErrorAlert("Export Error", "Failed to copy public key: " + ex.getMessage());
						}
					});

					paneToExport.getChildren().add(publicKeyExportButton);

					Button privateKeyExportButton = new Button("Private Key");
					privateKeyExportButton.setPadding(UITheme.PADDING_SMALL);
					privateKeyExportButton.setOnAction(e -> {
						try {
							String privateKeyPEMs = accountConfigParameters.privateKeysToCopy(dirPath);

							Clipboard clipboard = Clipboard.getSystemClipboard();
							ClipboardContent content = new ClipboardContent();
							content.putString(privateKeyPEMs);
							clipboard.setContent(content);

							UIUtils.showInfoAlert("Private Key", "Private key copied to clipboard");
						} catch (IOException ex) {
							LOGGER.error("Error copying private key", ex);
							UIUtils.showErrorAlert("Export Error", "Failed to copy private key: " + ex.getMessage());
						}
					});

					paneToExport.getChildren().add(privateKeyExportButton);

					if (accountConfigParameters.ifKeysInitialized()) {
						accountConfigParameters.extractKeysFromFiles(dirPath, passwordOrApiKey);
					}
				} else {
					UIUtils.showErrorAlert("Password Error", "Password does not match");
				}
			}

			CheckBox propertiesCheckBox = new CheckBox("Account Properties:");

			nameTextField.setEditable(false);
			nameTextField.setFocusTraversable(false);

			vbh.getChildren().setAll(new Label("Account Name:"), nameTextField, paneToExport,
					propertiesCheckBox);

			saveFileUI(stackPane,
						nameTextField.getText(),
						dirPath,
						findPropertiesFileName(accountDir + File.separator + nameTextField.getText()),
						vbh,
						propertiesCheckBox,
						accountConfigParameters,
						(accountConfigParameters != null) ? passwordOrApiKey : null);

			if (passwordField != null) {
				passwordField.setText("");
			}
			if (passwordFieldConfirm != null) {
				passwordFieldConfirm.setText("");
			}
		});

		stackPane.getChildren().add(vbh);

		// Configure dialog using helper method
		configureAccountDialog(stackPane, popup);

		return stackPane;
	}
	
	
	private static void saveFileUI(StackPane stackPane, String suggestedUsername, String dirPath, String fileName, VBox vbh,
								   CheckBox mainPanelCheckBox, UserAccountSetupHandlerInterface accountConfigParameters, String password) {

		String filePath = dirPath + System.getProperty("file.separator") + fileName;
		
		// Create account properties pane using helper method
		GridPane accountPropertiesPane = createAccountPropertiesPane();
		TextArea accountPropertiesTextArea = (TextArea) accountPropertiesPane.getChildren().get(0);
		TilePane paneToExport = (TilePane) accountPropertiesPane.getChildren().get(1);
		
		// Create and configure buttons using helper method
		Button[] buttons = createAccountPropertiesButtons(stackPane, suggestedUsername, accountPropertiesTextArea,
			filePath, accountConfigParameters, password, dirPath);
		Button saveAccountPropertiesButton = buttons[0];
		Button cognitoAccountPropertiesButton = buttons[1];
		
		paneToExport.getChildren().addAll(saveAccountPropertiesButton, cognitoAccountPropertiesButton);
		
		// Setup text area event handling using helper method
		setupAccountPropertiesTextArea(accountPropertiesTextArea, saveAccountPropertiesButton, cognitoAccountPropertiesButton);

		// Setup checkbox behavior using helper method
		setupMainPanelCheckBox(mainPanelCheckBox, vbh, accountPropertiesPane, accountPropertiesTextArea, 
			filePath, cognitoAccountPropertiesButton);

		// Setup save button action using helper method
		setupSaveAccountPropertiesButton(saveAccountPropertiesButton, accountPropertiesTextArea, 
			accountConfigParameters, password, dirPath, filePath);
	}

	/**
	 * Creates and configures the account properties pane with text area and export tile pane.
	 *
	 * @return Configured GridPane with text area and export pane
	 */
	private static GridPane createAccountPropertiesPane() {
		GridPane accountPropertiesPane = new GridPane();
		accountPropertiesPane.setHgap(10);
		accountPropertiesPane.setVgap(10);
		accountPropertiesPane.setPadding(UITheme.PADDING_FORM);

		TextArea accountPropertiesTextArea = new TextArea();
		accountPropertiesTextArea.setPrefColumnCount(18);
		accountPropertiesTextArea.setPrefHeight(100);
		accountPropertiesPane.add(accountPropertiesTextArea, 0, 0);
		
		TilePane paneToExport = new TilePane();
		paneToExport.setPadding(UITheme.PADDING_SMALL);
		paneToExport.setVgap(6);
		paneToExport.setHgap(6);
		paneToExport.setPrefColumns(3);
		
		accountPropertiesPane.add(paneToExport, 0, 1);
		
		return accountPropertiesPane;
	}

	/**
	 * Creates and configures the save and cognito buttons for account properties.
	 *
	 * @param stackPane The main stack pane
	 * @param suggestedUsername The suggested username
	 * @param accountPropertiesTextArea The text area for properties
	 * @param filePath The file path for properties file
	 * @param accountConfigParameters The account configuration parameters
	 * @param rsaPassword local private.key password (null for HSM)
	 * @param dirPath account directory
	 * @return Array containing [saveButton, cognitoButton]
	 */
	private static Button[] createAccountPropertiesButtons(StackPane stackPane, String suggestedUsername,
			TextArea accountPropertiesTextArea, String filePath,
			UserAccountSetupHandlerInterface accountConfigParameters, String rsaPassword, String dirPath) {

		Button saveAccountPropertiesButton = new Button("Save");

		Button cognitoAccountPropertiesButton = new Button("Cognito");
		cognitoAccountPropertiesButton.setDisable(true);

		cognitoAccountPropertiesButton.setOnAction(e -> {
			createCognitoId(stackPane, suggestedUsername, accountPropertiesTextArea, filePath,
					accountConfigParameters, rsaPassword, dirPath);
		});

		return new Button[]{saveAccountPropertiesButton, cognitoAccountPropertiesButton};
	}

	/**
	 * Sets up event handling for the account properties text area.
	 *
	 * @param accountPropertiesTextArea The text area to configure
	 * @param saveAccountPropertiesButton The save button to enable on text changes
	 * @param cognitoAccountPropertiesButton The cognito button to enable/disable based on content
	 */
	private static void setupAccountPropertiesTextArea(TextArea accountPropertiesTextArea, 
			Button saveAccountPropertiesButton, Button cognitoAccountPropertiesButton) {
		
		// Enable save button when the user starts editing the text
		accountPropertiesTextArea.textProperty().addListener((observable, oldValue, newValue) -> {
			saveAccountPropertiesButton.setDisable(false);
		});
		
		// Check for cognito configuration on any key event
		accountPropertiesTextArea.addEventFilter(javafx.scene.input.KeyEvent.ANY, event -> {			
			checkAccountPropertyTextForCognito(accountPropertiesTextArea, cognitoAccountPropertiesButton);
		});
	}

	/**
	 * Sets up the behavior for the main panel checkbox.
	 *
	 * @param mainPanelCheckBox The checkbox to configure
	 * @param vbh The main VBox container
	 * @param accountPropertiesPane The account properties pane to show/hide
	 * @param accountPropertiesTextArea The text area to load content into
	 * @param filePath The file path to load properties from
	 * @param cognitoAccountPropertiesButton The cognito button to update
	 */
	private static void setupMainPanelCheckBox(CheckBox mainPanelCheckBox, VBox vbh, GridPane accountPropertiesPane,
			TextArea accountPropertiesTextArea, String filePath, Button cognitoAccountPropertiesButton) {
		
		mainPanelCheckBox.setOnAction(e -> {
			if (mainPanelCheckBox.isSelected()) {
				vbh.getChildren().add(vbh.getChildren().indexOf(mainPanelCheckBox) + 1, accountPropertiesPane);

				try {
					String propertiesText = new String(Files.readAllBytes(Paths.get(filePath)));
					accountPropertiesTextArea.setText(propertiesText);
				} catch (IOException ex) {
					accountPropertiesTextArea.setText("");
				}
				
				checkAccountPropertyTextForCognito(accountPropertiesTextArea, cognitoAccountPropertiesButton);
			} else {
				vbh.getChildren().remove(accountPropertiesPane);
			}
		});
	}

	/**
	 * Sets up the save account properties button action with all the save logic.
	 *
	 * @param saveAccountPropertiesButton The button to configure
	 * @param accountPropertiesTextArea The text area with properties content
	 * @param accountConfigParameters The account configuration parameters
	 * @param password The account password
	 * @param dirPath The directory path
	 * @param filePath The properties file path
	 */
	private static void setupSaveAccountPropertiesButton(Button saveAccountPropertiesButton, 
			TextArea accountPropertiesTextArea, UserAccountSetupHandlerInterface accountConfigParameters, 
			String password, String dirPath, String filePath) {
		
		saveAccountPropertiesButton.setOnAction(event -> {
			try {
				try {
					Properties properties = new Properties();
					properties.load(new StringReader(accountPropertiesTextArea.getText()));

					if (accountConfigParameters != null) { // not HSM
						properties = accountConfigParameters.enhancePropertiesIfNeeded(properties, AltaStataApp.account);
					}

					// save properties file
					StringWriter writer = new StringWriter();
					properties.store(new PrintWriter(writer), "My Properties");
					Files.write(Paths.get(filePath), writer.getBuffer().toString().getBytes());

					// load the initial properties
					AltaStataApp.account.loadAccountProperties(dirPath);
					if (password != null) { // not HSM
						AltaStataApp.account.setPassword(password.toCharArray());
					}

					UserMetadata userMetadata = null;

					try {
						userMetadata = AltaStataApp.account.fileSystemModel().retrieveUserdata(AltaStataApp.account.MY_USER());
					}
					catch (com.altastata.filesystem.RetrieveCloudObjectException ex) {
						if (accountConfigParameters != null) {
							userMetadata = accountConfigParameters.createUserMetadata(AltaStataApp.account);
						}
						else {
							userMetadata = new UserMetadata(AltaStataApp.account.MY_USER(),
									"user",
									AltaStataApp.account.ORGANIZATION());

							userMetadata.metadataEncryption_$eq(scala.Option.apply("HSM"));
						}
					}

					if (userMetadata != null) {
						AltaStataApp.account.fileSystemModel().shareUserdataWithCustodian(userMetadata);
					}
					else {
						UIUtils.showInfoAlert("No user metadata was sent to custodian", "The user: " + AltaStataApp.account.MY_USER() + " is already exist in the system. Please, request your custodian to remove it if you need to reregister.");
					}

					saveAccountPropertiesButton.setDisable(true);
				}
				catch (SecurityException ex) {
					UIUtils.showErrorAlert("License Error", ex.getMessage());
				}
				catch(IllegalStateException ex) {
					// For Azure, Google, LocalFS CloudUserCreatingHandler is not implemented yet,
					// so we just write the text as it was given by the user
					Files.write(Paths.get(filePath), accountPropertiesTextArea.getText().getBytes());

					UIUtils.showErrorAlert("Error Dialog", "" + ex.getMessage());
				}
			} catch (Exception e1) {
				LOGGER.error("saveAccountPropertiesButton Cannot write to the file or send UserData to custodian: ", e1);

				UIUtils.showErrorAlert("Error Dialog", "Cannot write to the file or send UserData to custodian: " + getStackTraceAsString(e1));
			} finally {
				// Always stop enrollment poller (HSM auto-start or RSA/PQC setPassword).
				AltaStataApp.account.cleanUserProperties();
			}
		});
	}

	/**
	 * Configures and prompts the user to create a Cognito identity setup.
	 *
	 * @param stackPane UI stack pane
	 * @param suggestedUsername suggested user identifier
	 * @param accountPropertiesTextArea target text area
	 * @param filePath configuration file path
	 * @param accountConfigParameters account config setup handler
	 * @param rsaPassword local private.key password for enrollment signing (null for HSM)
	 * @param dirPath account directory
	 */
	private static void createCognitoId(StackPane stackPane, String suggestedUsername,
			TextArea accountPropertiesTextArea, String filePath,
			UserAccountSetupHandlerInterface accountConfigParameters, String rsaPassword, String dirPath) {
		VBox vbh = new VBox();
		vbh.setPadding(UITheme.PADDING_LARGE_DIALOG);

		vbh.setStyle(UITheme.DIALOG_BOX_STYLE);

		vbh.setOpacity(1);

 		GridPane userIdSettingPane = new GridPane();
 		userIdSettingPane.setHgap(10);
 		userIdSettingPane.setVgap(10);
 		userIdSettingPane.setPadding(UITheme.PADDING_FORM);
 		userIdSettingPane.requestFocus();

		TextField nameTextField = new TextField();
		nameTextField.setPromptText("User Name");
		nameTextField.setText(suggestedUsername);
		nameTextField.setPrefColumnCount(18);

 		PasswordField passwordField = new PasswordField();
 		passwordField.setPromptText("Password");

 		PasswordField passwordFieldConfirm = new PasswordField();
 		passwordFieldConfirm.setPromptText("Confirm Password");

		TextField emailField = new TextField();
		emailField.setPromptText("User Email");

		TextField phoneNumberField = new TextField();
		phoneNumberField.setPromptText("User Phone Number");
		phoneNumberField.setText("+");

		Button backButton = new Button("Back");

		backButton.setOnAction(event -> {
			stackPane.getChildren().remove(vbh);
		});

 		Button okButton = new Button("OK");

 		userIdSettingPane.add(nameTextField, 0, 0);
 		userIdSettingPane.add(passwordField, 0, 1);
 		userIdSettingPane.add(passwordFieldConfirm, 0, 2);
 		userIdSettingPane.add(emailField, 0, 3);
 		userIdSettingPane.add(phoneNumberField, 0, 4);

 		TilePane buttonsTile = new TilePane();
 		buttonsTile.setPadding(UITheme.PADDING_TILE);
 		buttonsTile.setVgap(6);
 		buttonsTile.setHgap(6);
 		buttonsTile.setPrefColumns(2);

 		buttonsTile.getChildren().addAll(backButton, okButton);

 		userIdSettingPane.add(buttonsTile, 0, 5);

 		vbh.getChildren().setAll(userIdSettingPane);

 		okButton.setOnAction(event -> {

 			if (emailField.getText().isEmpty()) {
				UIUtils.showErrorAlert("Error Dialog", "Your email address must be provided");
			}			
 			else if (passwordField.getText().equals(passwordFieldConfirm.getText())) {

	 	 		try {
					// Bind account folder so license.jwt is found for PQC/HSM Cognito signup
					bindAccountDirFromPropertiesFile(filePath);
					AltaStataApp.account.loadUserProperties(accountPropertiesTextArea.getText());

					AltaStataApp.account.cognitoClient().signUpUser(nameTextField.getText(),
		 					passwordField.getText(), emailField.getText(), phoneNumberField.getText());

		 			LOGGER.info("cognitoHelper.SignUpUser");

					 // show the verification parameters
					userIdSettingPane.getChildren().removeAll(nameTextField, passwordField, passwordFieldConfirm, emailField, phoneNumberField, buttonsTile);

					Label nameLabel = new Label("User: " + nameTextField.getText());

					TextField codeVerificationField = new TextField();
					codeVerificationField.setPromptText("Code");
					codeVerificationField.setFocusTraversable(false);

					Button verifyButton = new Button("Verify");

					userIdSettingPane.add(nameLabel, 0, 2);
					userIdSettingPane.add(codeVerificationField, 0, 4);
					userIdSettingPane.add(verifyButton, 0, 5);

					verifyButton.setOnAction(eventVerify -> {
						try {
							LOGGER.info("Starting verification process for user: " + nameTextField.getText());
							
							AltaStataApp.account.cognitoClient().verifyAccessCode(nameTextField.getText(), codeVerificationField.getText());
							LOGGER.info("cognitoHelper.VerifyAccessCode passed");

							AltaStataApp.account.setCognitoPassword(passwordField.getText());
							LOGGER.info("Cognito password set");

							String acountSettingsText = accountPropertiesTextArea.getText();

							LOGGER.info("Getting identity ID for user: " + nameTextField.getText());
							String identityId = AltaStataApp.account.cognitoClient().getIdentityId(nameTextField.getText(), passwordField.getText());
							LOGGER.info("Identity ID retrieved: " + identityId);

							if (!acountSettingsText.contains("cognito-identity-id")) {
								acountSettingsText += "\ncognito-identity-id=" + identityId;
							}

							accountPropertiesTextArea.setText(acountSettingsText);

							try {
								Files.write(Paths.get(filePath), acountSettingsText.getBytes());
								LOGGER.info("Account properties saved to file");
							} catch (IOException e1) {
								LOGGER.error("createCognitoId Cannot write to the file: ", e1);
								UIUtils.showErrorAlert("File Error", "Cannot write to properties file: " + e1.getMessage());
								return;
							}

							// Ensure the myuser property is set to the Cognito username before loading
							if (!acountSettingsText.contains("myuser=")) {
								acountSettingsText += "\nmyuser=" + nameTextField.getText();
								accountPropertiesTextArea.setText(acountSettingsText);
								
								// Re-save the updated properties
								try {
									Files.write(Paths.get(filePath), acountSettingsText.getBytes());
									LOGGER.info("Updated account properties with myuser");
								} catch (IOException e1) {
									LOGGER.error("Failed to update properties with myuser", e1);
								}
							}

							bindAccountDirFromPropertiesFile(filePath);
							try {
								AltaStataApp.account.loadAccountProperties(dirPath);
								if (rsaPassword != null) {
									AltaStataApp.account.initForEnrollment(rsaPassword.toCharArray());
								}

								LOGGER.info("Creating Cognito user metadata with params: username=" + nameTextField.getText() + ", email=" + emailField.getText() + ", identityId=" + identityId);
								LOGGER.info("AccountConfigParameters type: " + accountConfigParameters.getClass().getName());
								
								UserMetadata userMetadata = null;
								try {
									userMetadata = accountConfigParameters.createCognitoUserMetadata(nameTextField.getText(), emailField.getText(), identityId, AltaStataApp.account);
									LOGGER.info("User metadata created successfully: " + userMetadata);
								} catch (Exception createEx) {
									LOGGER.error("Failed to create Cognito user metadata", createEx);
									throw createEx;
								}

								LOGGER.info("Sharing user metadata with custodian");
								AltaStataApp.account.fileSystemModel().shareUserdataWithCustodian(userMetadata);

								LOGGER.info("Cognito setup completed successfully");

								UIUtils.showInfoAlert("Cognito Success", "Cognito user setup completed successfully!\nIdentity ID: " + identityId);
								
								stackPane.getChildren().remove(vbh);
							} finally {
								// Always stop enrollment poller after Cognito verify path.
								AltaStataApp.account.cleanUserProperties();
							}
						}
						catch (SecurityException ex) {
							LOGGER.error("License gate failed during Cognito verification", ex);
							UIUtils.showErrorAlert("License Error", ex.getMessage());
						}
						catch (Exception ex) {
							LOGGER.error("Error during Cognito verification process", ex);
							UIUtils.showErrorAlert("Verification Error", "Error during verification: " + ex.getMessage());
						}

						stackPane.getChildren().remove(0);
					});
	 	 		}
				catch (SecurityException ex) {
					LOGGER.error("License gate failed during Cognito signup", ex);
					UIUtils.showErrorAlert("License Error", ex.getMessage());
				}
	 	 		catch (Exception ex) {
					LOGGER.error(ex.getMessage(), ex);
					String message = ex.getMessage();
					if (message != null && message.contains("(")) {
						message = message.substring(0, message.indexOf("("));
					}
					UIUtils.showErrorAlert("Error Dialog", message != null ? message : ex.toString());
	 	 		}
 			} else {
				UIUtils.showErrorAlert("Error Dialog", "Password is not confirmed");
 			}
 		});
 		
 		stackPane.getChildren().add(vbh);
	}

	/**
	 * Configures the active Account directory location based on the path of the properties file.
	 * 
	 * Ensures that license keys, token blobs, and related account properties can be located within 
	 * the same shared subfolder hierarchy.
	 *
	 * @param filePath The absolute file path of the loaded properties file.
	 */
	private static void bindAccountDirFromPropertiesFile(String filePath) {
		if (filePath == null || filePath.isEmpty()) {
			return;
		}
		File accountFolder = new File(filePath).getParentFile();
		if (accountFolder != null) {
			AltaStataApp.account.setAccountDir(accountFolder.getAbsolutePath());
		}
	}

	/**
	 * Formats and retrieves the stack trace of an exception as a clean multi-line string.
	 * Truncates the stack trace to a maximum of 20 elements to keep popups readable.
	 *
	 * @param throwable The exception/error to format.
	 * @return A string containing formatted stack trace frames.
	 */
	private static String getStackTraceAsString(Throwable throwable) {
		StackTraceElement[] stackTrace = throwable.getStackTrace();
		StringBuilder sb = new StringBuilder();

		int limit = Math.min(stackTrace.length, 20); // Limit to first 20 rows
		for (int i = 0; i < limit; i++) {
			sb.append(stackTrace[i].toString()).append("\n");
		}

		return sb.toString();
	}

	/**
	 * Performs real-time scanning on user-provided properties text to enable or disable the 
	 * Cognito Setup button based on the presence of federated pool parameters.
	 *
	 * @param accountPropertiesTextArea        The properties text editor area.
	 * @param cognitoAccountPropertiesButton   The Cognito signup action button.
	 */
	private static void checkAccountPropertyTextForCognito(TextArea accountPropertiesTextArea, Button cognitoAccountPropertiesButton) {
		String text = accountPropertiesTextArea.getText();
		// if the text contain cognito pool id, but the user is not configured yet
		if (text.contains("cognito-fed-identity-pool-id") && !text.contains("cognito-identity-id")) {
			cognitoAccountPropertiesButton.setDisable(false);
		}
		else {
			cognitoAccountPropertiesButton.setDisable(true);
		}
	}

}
