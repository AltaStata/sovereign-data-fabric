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

import javafx.scene.control.SplitPane;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.VBox;

/**
 * A customized JavaFX {@link VBox} container that integrates with the main {@link NavigationPane}.
 * 
 * Manages standard sizing defaults for desktop and mobile navigation screens.
 * Automatically intercepts navigation/cancellation key events (such as the ESCAPE key)
 * to slide backwards in mobile navigation viewports.
 */
public class VerticalBox extends VBox {

	NavigationPane container = null;

	/**
	 * Creates a new VerticalBox instance linked to the specified NavigationPane container.
	 *
	 * @param container The active {@link NavigationPane} parent workspace.
	 */
	public VerticalBox(NavigationPane container) {
		this.container = container;

		if (NavigationPane.isMobileNavigation) {
			setMinWidth(0);
		} else {
			setMinWidth(320);
			setPrefWidth(400);
		}

		// setup listener for ESCAPE (back key on phone)
		setOnKeyReleased(ke -> {
			if (ke.getCode().equals(KeyCode.ESCAPE)) {

				if (NavigationPane.isMobileNavigation) {

					int currentDirectoryIndex = container.findIndex(this);

					// slide the previous directory
					if (currentDirectoryIndex > 0) {
						// remove the vertical boxes after this one
						container.mobileNavigationBackward(currentDirectoryIndex - 1, ke.getCode());
					}
				}
			}
		});
	}
}
