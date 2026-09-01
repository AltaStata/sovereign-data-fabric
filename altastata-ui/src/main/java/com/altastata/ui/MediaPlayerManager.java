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

import javafx.application.Platform;
import javafx.scene.control.Button;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Service class responsible for managing UI feedback through button opacity changes.
 * Provides visual feedback to users during long-running operations by dimming and restoring
 * button opacity to indicate when operations are in progress.
 * 
 * This class was extracted from AltaStataApp to improve separation of concerns and
 * centralize UI feedback management.
 */
public class MediaPlayerManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(MediaPlayerManager.class);
    
    // Opacity constants
    private static final double FULL_OPACITY = 1.0;
    private static final double REDUCED_OPACITY = 0.50;
    private static final double OPACITY_INCREMENT = 0.01;

    /**
     * Reduces the opacity of a button to provide visual feedback that an operation is starting.
     * This method is thread-safe and will execute on the JavaFX Application Thread.
     * 
     * @param button The button whose opacity should be reduced
     */
    public static void reduceOpacity(Button button) {
        if (button == null) {
            LOGGER.warn("Attempted to reduce opacity of null button");
            return;
        }
        
        Platform.runLater(() -> {
            if (button.getOpacity() == FULL_OPACITY) {
                button.setOpacity(button.getOpacity() - REDUCED_OPACITY);
            } else {
                button.setOpacity(button.getOpacity() - OPACITY_INCREMENT);
            }
            LOGGER.trace("Reduced opacity for button: {}", button.getId());
        });
    }

    /**
     * Increases the opacity of a button to provide visual feedback that an operation has completed.
     * This method is thread-safe and will execute on the JavaFX Application Thread.
     * 
     * @param button The button whose opacity should be increased
     */
    public static void increaseOpacity(Button button) {
        if (button == null) {
            LOGGER.warn("Attempted to increase opacity of null button");
            return;
        }
        
        Platform.runLater(() -> {
            if (button.getOpacity() == REDUCED_OPACITY) {
                button.setOpacity(button.getOpacity() + REDUCED_OPACITY);
            } else {
                button.setOpacity(button.getOpacity() + OPACITY_INCREMENT);
            }
            LOGGER.trace("Increased opacity for button: {}", button.getId());
        });
    }

    /**
     * Resets a button's opacity to full opacity immediately.
     * This method is thread-safe and will execute on the JavaFX Application Thread.
     * 
     * @param button The button whose opacity should be reset
     */
    public static void resetOpacity(Button button) {
        if (button == null) {
            LOGGER.warn("Attempted to reset opacity of null button");
            return;
        }
        
        Platform.runLater(() -> {
            button.setOpacity(FULL_OPACITY);
            LOGGER.trace("Reset opacity for button: {}", button.getId());
        });
    }

    /**
     * Sets all provided buttons to reduced opacity.
     * Useful for disabling multiple UI elements at once during initialization.
     * 
     * @param buttons The buttons whose opacity should be reduced
     */
    public static void reduceOpacityAll(Button... buttons) {
        for (Button button : buttons) {
            reduceOpacity(button);
        }
    }

    /**
     * Sets all provided buttons to full opacity.
     * Useful for enabling multiple UI elements at once after initialization.
     * 
     * @param buttons The buttons whose opacity should be increased to full
     */
    public static void resetOpacityAll(Button... buttons) {
        for (Button button : buttons) {
            resetOpacity(button);
        }
    }

    /**
     * Checks if a button is currently in a reduced opacity state.
     * 
     * @param button The button to check
     * @return true if the button's opacity is less than full opacity
     */
    public static boolean isReducedOpacity(Button button) {
        if (button == null) {
            return false;
        }
        return button.getOpacity() < FULL_OPACITY;
    }
}
