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

import com.altastata.ui.theme.UIStyleFactory;
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.control.Button;
import javafx.stage.Stage;

/**
 * Utility class for common UI operations shared across multiple UI classes.
 * Consolidates redundant dialog styling, error handling, and common utilities.
 */
public class UIUtils {

	/**
	 * Shows a standardized error alert dialog with modern styling.
	 */
	public static void showErrorAlert(final String title, final String content) {
		final Alert alert = new Alert(AlertType.ERROR);
		alert.setTitle(title);
		alert.setHeaderText(null); // Remove header
		alert.setContentText(content);
		
		// Apply modern styling with UIStyleFactory
		UIStyleFactory.styleDialog(alert);

		((Stage) alert.getDialogPane().getScene().getWindow()).setResizable(true);

		// Style the buttons using UIStyleFactory
		alert.getDialogPane().lookupAll(".button").forEach(node -> {
			if (node instanceof Button) {
				Button button = (Button) node;
				UIStyleFactory.styleButton(button, UIStyleFactory.ButtonStyle.DANGER);
			}
		});

		alert.showAndWait();
	}

	/**
	 * Shows a warning alert with modern styling.
	 */
	public static void showWarningAlert(final String title, final String message) {
		final Alert alert = new Alert(AlertType.WARNING);
		alert.setTitle(title);
		alert.setHeaderText(null); // Remove header
		alert.setContentText(message);
		
		// Apply modern styling with UIStyleFactory
		UIStyleFactory.styleDialog(alert);

		// Style the buttons using UIStyleFactory
		alert.getDialogPane().lookupAll(".button").forEach(node -> {
			if (node instanceof Button) {
				Button button = (Button) node;
				UIStyleFactory.styleButton(button, UIStyleFactory.ButtonStyle.WARNING);
			}
		});
		
		alert.showAndWait();
	}

	/**
	 * Shows an information alert with modern styling.
	 */
	public static void showInfoAlert(final String title, final String message) {
		final Alert alert = new Alert(AlertType.INFORMATION);
		alert.setTitle(title);
		alert.setHeaderText(null); // Remove header
		alert.setContentText(message);
		
		// Apply modern styling with UIStyleFactory
		UIStyleFactory.styleDialog(alert);

		// Style the buttons using UIStyleFactory
		alert.getDialogPane().lookupAll(".button").forEach(node -> {
			if (node instanceof Button) {
				Button button = (Button) node;
				UIStyleFactory.styleButton(button, UIStyleFactory.ButtonStyle.SECONDARY);
			}
		});
		
		alert.showAndWait();
	}
}
