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

import com.altastata.ui.theme.UITheme;
import javafx.application.Platform;
import javafx.scene.control.ButtonBar.ButtonData;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.PasswordField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;

/**
 * PasswordDialog is a JavaFX dialog prompting the user for password entry.
 */
public class PasswordDialog extends Dialog<String> {
	private PasswordField passwordField;

	/**
	 * Creates a new password prompt dialog with the specified title and prompt message.
	 *
	 * @param title dialog title
	 * @param promptMsg header text message
	 */
	public PasswordDialog(String title, String promptMsg) {
		setTitle(title);
		setHeaderText(promptMsg);
		
		ButtonType passwordButtonType = new ButtonType("OK", ButtonData.OK_DONE);
		getDialogPane().getButtonTypes().addAll(passwordButtonType, ButtonType.CANCEL);

		passwordField = new PasswordField();
		passwordField.setPromptText("Password");

		HBox hBox = new HBox();
		hBox.getChildren().add(passwordField);
		hBox.setPadding(UITheme.PADDING_LOOSE);

		HBox.setHgrow(passwordField, Priority.ALWAYS);

		getDialogPane().setContent(hBox);

		Platform.runLater(() -> passwordField.requestFocus());

		setResultConverter(dialogButton -> {
			if (dialogButton == passwordButtonType) {
				return passwordField.getText();
			}
			return null;
		});
	}

	/**
	 * Gets the internal password field.
	 *
	 * @return the password input field
	 */
	public PasswordField getPasswordField() {
		return passwordField;
	}
}
