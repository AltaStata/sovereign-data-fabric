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

package com.altastata.ui.theme;

import javafx.animation.ScaleTransition;
import javafx.geometry.Insets;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.effect.DropShadow;
import javafx.scene.layout.*;
import javafx.scene.text.TextAlignment;
import javafx.stage.Stage;

/**
 * Factory class for applying consistent styling to JavaFX UI components.
 * 
 * <p>This class provides methods to style various UI components according to
 * the AltaStata design system defined in {@link UITheme}. It encapsulates
 * styling logic and ensures visual consistency across the application.</p>
 * 
 * <h3>Key Features:</h3>
 * <ul>
 *   <li>Consistent component styling</li>
 *   <li>Interactive state management (hover, focus, disabled)</li>
 *   <li>Smooth animations and transitions</li>
 *   <li>Accessibility considerations</li>
 * </ul>
 * 
 * <h3>Usage Example:</h3>
 * <pre>{@code
 * Button saveButton = new Button("Save");
 * UIStyleFactory.styleButton(saveButton, ButtonStyle.PRIMARY);
 * 
 * ListView<String> fileList = new ListView<>();
 * UIStyleFactory.styleListView(fileList);
 * }</pre>
 * 
 * @author AltaStata Team
 * @since 1.0
 * @see UITheme
 */
public final class UIStyleFactory {
    
    // Prevent instantiation
    /**
     * Private constructor to prevent instantiation of utility class.
     */
    private UIStyleFactory() {}
    
    /**
     * Enumeration of button style variants.
     */
    public enum ButtonStyle {
        /** Primary action button - most important actions */
        PRIMARY,
        /** Secondary action button - alternative actions */
        SECONDARY,
        /** Success button - positive confirmations */
        SUCCESS,
        /** Warning button - caution actions */
        WARNING,
        /** Danger button - destructive actions */
        DANGER,
        /** Ghost button - minimal styling */
        GHOST
    }
    
    /**
     * Enumeration of input field style variants.
     */
    public enum InputStyle {
        /** Standard input field */
        STANDARD,
        /** Search input with search icon */
        SEARCH,
        /** Error state input */
        ERROR,
        /** Success state input */
        SUCCESS
    }
    
    /**
     * Enumeration of card/container style variants.
     */
    public enum CardStyle {
        /** Standard card with subtle shadow */
        STANDARD,
        /** Elevated card with prominent shadow */
        ELEVATED,
        /** Flat card without shadow */
        FLAT,
        /** Outlined card with border */
        OUTLINED
    }
    
    // ==================== BUTTON STYLING ====================
    
    /**
     * Applies styling to a button according to the specified style variant.
     * 
     * <p>This method sets up the button's appearance, including colors, fonts,
     * spacing, and interactive states (hover, focus, pressed, disabled).</p>
     * 
     * @param button The button to style
     * @param style The button style variant to apply
     */
    public static void styleButton(Button button, ButtonStyle style) {
        if (button == null) return;
        
        // Base button styling
        button.setFont(UITheme.BODY);
        button.setPrefHeight(UITheme.BUTTON_HEIGHT);
        button.setPadding(new Insets(UITheme.SPACING_SM, UITheme.SPACING_LG, UITheme.SPACING_SM, UITheme.SPACING_LG));
        button.setCursor(Cursor.HAND);
        
        // Apply style-specific colors and effects
        switch (style) {
            case PRIMARY:
                applyPrimaryButtonStyle(button);
                break;
            case SECONDARY:
                applySecondaryButtonStyle(button);
                break;
            case SUCCESS:
                applySuccessButtonStyle(button);
                break;
            case WARNING:
                applyWarningButtonStyle(button);
                break;
            case DANGER:
                applyDangerButtonStyle(button);
                break;
            case GHOST:
                applyGhostButtonStyle(button);
                break;
        }
        
        // Add CSS class for additional styling
        button.getStyleClass().add("styled-button");
        button.getStyleClass().add(getButtonStyleClass(style));
        
        // Add hover and click animations
        addButtonAnimations(button);
    }
    
    /**
     * Creates a styled button with the specified text and style.
     * 
     * @param text The button text
     * @param style The button style variant
     * @return A new styled button
     */
    public static Button createStyledButton(String text, ButtonStyle style) {
        Button button = new Button(text);
        styleButton(button, style);
        return button;
    }
    
    // ==================== INPUT FIELD STYLING ====================
    
    /**
     * Applies styling to a text field according to the specified style variant.
     * 
     * @param textField The text field to style
     * @param style The input style variant to apply
     */
    public static void styleTextField(TextField textField, InputStyle style) {
        if (textField == null) return;
        
        // Base input styling
        textField.setFont(UITheme.BODY);
        textField.setPrefHeight(UITheme.BUTTON_HEIGHT);
        textField.setPadding(new Insets(UITheme.SPACING_SM));
        
        // Apply style-specific appearance
        switch (style) {
            case STANDARD:
                applyStandardInputStyle(textField);
                break;
            case SEARCH:
                applySearchInputStyle(textField);
                break;
            case ERROR:
                applyErrorInputStyle(textField);
                break;
            case SUCCESS:
                applySuccessInputStyle(textField);
                break;
        }
        
        textField.getStyleClass().add("styled-input");
        addInputFocusEffects(textField);
    }
    
    /**
     * Applies styling to a password field.
     * 
     * @param passwordField The password field to style
     */
    public static void stylePasswordField(PasswordField passwordField) {
        if (passwordField == null) return;
        
        passwordField.setFont(UITheme.BODY);
        passwordField.setPrefHeight(UITheme.BUTTON_HEIGHT);
        passwordField.setPadding(new Insets(UITheme.SPACING_SM));
        
        applyStandardInputStyle(passwordField);
        passwordField.getStyleClass().add("styled-input");
        addInputFocusEffects(passwordField);
    }
    
    // ==================== LIST VIEW STYLING ====================
    
    /**
     * Applies modern styling to a ListView component.
     * 
     * <p>This includes clean borders, hover effects, selection highlighting,
     * and improved spacing for better readability.</p>
     * 
     * @param listView The ListView to style
     */
    public static void styleListView(ListView<?> listView) {
        if (listView == null) return;
        
        // Remove default styling
        listView.getStyleClass().add("styled-listview");
        
        // Apply custom styling through CSS
        String style = String.format(
            "-fx-background-color: %s; " +
            "-fx-border-color: %s; " +
            "-fx-border-width: %fpx; " +
            "-fx-border-radius: %fpx; " +
            "-fx-background-radius: %fpx;",
            UITheme.toHexString(UITheme.WHITE),
            UITheme.toHexString(UITheme.MEDIUM_GRAY),
            UITheme.BORDER_WIDTH,
            UITheme.BORDER_RADIUS,
            UITheme.BORDER_RADIUS
        );
        
        listView.setStyle(style);
    }
    
    // ==================== DIALOG STYLING ====================
    
    /**
     * Applies consistent styling to a dialog.
     * 
     * @param dialog The dialog to style
     */
    public static void styleDialog(Dialog<?> dialog) {
        if (dialog == null) return;
        
        DialogPane dialogPane = dialog.getDialogPane();
        if (dialogPane == null) return;
        
        // Set consistent fonts and spacing
        dialogPane.setPadding(UITheme.PADDING_DIALOG);

        ((Stage) dialogPane.getScene().getWindow()).setResizable(true);

        // Style header
        Node header = dialogPane.getHeader();
        if (header instanceof Label) {
            ((Label) header).setFont(UITheme.HEADER_MEDIUM);
            ((Label) header).setTextFill(UITheme.PRIMARY);
        }
        
        // Style content
        Node content = dialogPane.getContent();
        if (content instanceof Label) {
            ((Label) content).setFont(UITheme.BODY);
            ((Label) content).setWrapText(true);
        }
        
        dialogPane.getStyleClass().add("styled-dialog");
    }
    
    // ==================== CONTAINER STYLING ====================
    
    /**
     * Creates a styled card container with the specified style.
     * 
     * @param style The card style variant
     * @return A new styled Pane container
     */
    public static Pane createStyledCard(CardStyle style) {
        Pane card = new Pane();
        styleCard(card, style);
        return card;
    }
    
    /**
     * Applies card styling to a container.
     * 
     * @param container The container to style
     * @param style The card style variant
     */
    public static void styleCard(Pane container, CardStyle style) {
        if (container == null) return;
        
        container.setPadding(UITheme.PADDING_STANDARD);
        container.getStyleClass().add("styled-card");
        
        switch (style) {
            case STANDARD:
                applyStandardCardStyle(container);
                break;
            case ELEVATED:
                applyElevatedCardStyle(container);
                break;
            case FLAT:
                applyFlatCardStyle(container);
                break;
            case OUTLINED:
                applyOutlinedCardStyle(container);
                break;
        }
    }
    
    // ==================== PROGRESS INDICATOR STYLING ====================
    
    /**
     * Applies modern styling to a progress bar.
     * 
     * @param progressBar The progress bar to style
     */
    public static void styleProgressBar(ProgressBar progressBar) {
        if (progressBar == null) return;
        
        progressBar.getStyleClass().add("styled-progress");
        progressBar.setPrefHeight(6);
        
        String style = String.format(
            "-fx-accent: %s; " +
            "-fx-background-color: %s; " +
            "-fx-background-radius: 3px;",
            UITheme.toHexString(UITheme.SECONDARY),
            UITheme.toHexString(UITheme.LIGHT_GRAY)
        );
        
        progressBar.setStyle(style);
    }
    
    /**
     * Applies styling to a progress indicator.
     * 
     * @param progressIndicator The progress indicator to style
     */
    public static void styleProgressIndicator(ProgressIndicator progressIndicator) {
        if (progressIndicator == null) return;
        
        progressIndicator.getStyleClass().add("styled-progress-indicator");
        
        String style = String.format(
            "-fx-accent: %s;",
            UITheme.toHexString(UITheme.SECONDARY)
        );
        
        progressIndicator.setStyle(style);
    }
    
    // ==================== LABEL STYLING ====================
    
    /**
     * Styles a label as a header with the specified level.
     * 
     * @param label The label to style
     * @param level The header level (1=largest, 3=smallest)
     */
    public static void styleHeaderLabel(Label label, int level) {
        if (label == null) return;
        
        label.setTextFill(UITheme.PRIMARY);
        label.setTextAlignment(TextAlignment.LEFT);
        
        switch (level) {
            case 1:
                label.setFont(UITheme.HEADER_LARGE);
                break;
            case 2:
                label.setFont(UITheme.HEADER_MEDIUM);
                break;
            case 3:
            default:
                label.setFont(UITheme.HEADER_SMALL);
                break;
        }
        
        label.getStyleClass().add("header-label");
        label.getStyleClass().add("header-level-" + level);
    }
    
    /**
     * Styles a label for secondary/supporting text.
     * 
     * @param label The label to style
     */
    public static void styleSecondaryLabel(Label label) {
        if (label == null) return;
        
        label.setFont(UITheme.SMALL_TEXT);
        label.setTextFill(UITheme.DARK_GRAY);
        label.getStyleClass().add("secondary-label");
    }
    
    // ==================== TOOLBAR STYLING ====================
    
    /**
     * Applies consistent styling to a toolbar.
     * 
     * @param toolBar The toolbar to style
     */
    public static void styleToolBar(ToolBar toolBar) {
        if (toolBar == null) return;
        
        toolBar.setPadding(UITheme.PADDING_STANDARD);
        toolBar.getStyleClass().add("styled-toolbar");
        
        String style = String.format(
            "-fx-background-color: %s; " +
            "-fx-border-color: %s; " +
            "-fx-border-width: 0 0 %fpx 0;",
            UITheme.toHexString(UITheme.LIGHT_GRAY),
            UITheme.toHexString(UITheme.MEDIUM_GRAY),
            UITheme.BORDER_WIDTH
        );
        
        toolBar.setStyle(style);
    }
    
    // ==================== PRIVATE HELPER METHODS ====================
    
    /**
     * Applies the primary action style to a button.
     *
     * @param button the button to style
     */
    private static void applyPrimaryButtonStyle(Button button) {
        String style = String.format(
            "-fx-background-color: %s; " +
            "-fx-text-fill: white; " +
            "-fx-background-radius: %fpx; " +
            "-fx-border-radius: %fpx;",
            UITheme.toHexString(UITheme.SECONDARY),
            UITheme.BORDER_RADIUS,
            UITheme.BORDER_RADIUS
        );
        button.setStyle(style);
        
        // Hover effect
        button.setOnMouseEntered(e -> {
            String hoverStyle = style.replace(
                UITheme.toHexString(UITheme.SECONDARY),
                UITheme.toHexString(UITheme.darken(UITheme.SECONDARY, 0.1))
            );
            button.setStyle(hoverStyle);
        });
        
        button.setOnMouseExited(e -> button.setStyle(style));
    }
    
    /**
     * Applies the secondary outline style to a button.
     *
     * @param button the button to style
     */
    private static void applySecondaryButtonStyle(Button button) {
        String style = String.format(
            "-fx-background-color: transparent; " +
            "-fx-text-fill: %s; " +
            "-fx-border-color: %s; " +
            "-fx-border-width: %fpx; " +
            "-fx-background-radius: %fpx; " +
            "-fx-border-radius: %fpx;",
            UITheme.toHexString(UITheme.SECONDARY),
            UITheme.toHexString(UITheme.SECONDARY),
            UITheme.BORDER_WIDTH,
            UITheme.BORDER_RADIUS,
            UITheme.BORDER_RADIUS
        );
        button.setStyle(style);
        
        // Hover effect
        button.setOnMouseEntered(e -> {
            String hoverStyle = String.format(
                "-fx-background-color: %s; " +
                "-fx-text-fill: white; " +
                "-fx-border-color: %s; " +
                "-fx-border-width: %fpx; " +
                "-fx-background-radius: %fpx; " +
                "-fx-border-radius: %fpx;",
                UITheme.toHexString(UITheme.SECONDARY),
                UITheme.toHexString(UITheme.SECONDARY),
                UITheme.BORDER_WIDTH,
                UITheme.BORDER_RADIUS,
                UITheme.BORDER_RADIUS
            );
            button.setStyle(hoverStyle);
        });
        
        button.setOnMouseExited(e -> button.setStyle(style));
    }
    
    /**
     * Applies the success background style to a button.
     *
     * @param button the button to style
     */
    private static void applySuccessButtonStyle(Button button) {
        String style = String.format(
            "-fx-background-color: %s; " +
            "-fx-text-fill: white; " +
            "-fx-background-radius: %fpx; " +
            "-fx-border-radius: %fpx;",
            UITheme.toHexString(UITheme.SUCCESS),
            UITheme.BORDER_RADIUS,
            UITheme.BORDER_RADIUS
        );
        button.setStyle(style);
        
        // Hover effect
        button.setOnMouseEntered(e -> {
            String hoverStyle = style.replace(
                UITheme.toHexString(UITheme.SUCCESS),
                UITheme.toHexString(UITheme.darken(UITheme.SUCCESS, 0.1))
            );
            button.setStyle(hoverStyle);
        });
        
        button.setOnMouseExited(e -> button.setStyle(style));
    }
    
    /**
     * Applies the warning background style to a button.
     *
     * @param button the button to style
     */
    private static void applyWarningButtonStyle(Button button) {
        String style = String.format(
            "-fx-background-color: %s; " +
            "-fx-text-fill: white; " +
            "-fx-background-radius: %fpx; " +
            "-fx-border-radius: %fpx;",
            UITheme.toHexString(UITheme.WARNING),
            UITheme.BORDER_RADIUS,
            UITheme.BORDER_RADIUS
        );
        button.setStyle(style);
        
        // Hover effect
        button.setOnMouseEntered(e -> {
            String hoverStyle = style.replace(
                UITheme.toHexString(UITheme.WARNING),
                UITheme.toHexString(UITheme.darken(UITheme.WARNING, 0.1))
            );
            button.setStyle(hoverStyle);
        });
        
        button.setOnMouseExited(e -> button.setStyle(style));
    }
    
    /**
     * Applies the danger background style to a button.
     *
     * @param button the button to style
     */
    private static void applyDangerButtonStyle(Button button) {
        String style = String.format(
            "-fx-background-color: %s; " +
            "-fx-text-fill: white; " +
            "-fx-background-radius: %fpx; " +
            "-fx-border-radius: %fpx;",
            UITheme.toHexString(UITheme.ERROR),
            UITheme.BORDER_RADIUS,
            UITheme.BORDER_RADIUS
        );
        button.setStyle(style);
        
        // Hover effect
        button.setOnMouseEntered(e -> {
            String hoverStyle = style.replace(
                UITheme.toHexString(UITheme.ERROR),
                UITheme.toHexString(UITheme.darken(UITheme.ERROR, 0.1))
            );
            button.setStyle(hoverStyle);
        });
        
        button.setOnMouseExited(e -> button.setStyle(style));
    }
    
    /**
     * Applies the ghost/transparent background style to a button.
     *
     * @param button the button to style
     */
    private static void applyGhostButtonStyle(Button button) {
        String style = String.format(
            "-fx-background-color: transparent; " +
            "-fx-text-fill: %s; " +
            "-fx-background-radius: %fpx; " +
            "-fx-border-radius: %fpx;",
            UITheme.toHexString(UITheme.DARK_GRAY),
            UITheme.BORDER_RADIUS,
            UITheme.BORDER_RADIUS
        );
        button.setStyle(style);
        
        // Hover effect
        button.setOnMouseEntered(e -> {
            String hoverStyle = String.format(
                "-fx-background-color: %s; " +
                "-fx-text-fill: %s; " +
                "-fx-background-radius: %fpx; " +
                "-fx-border-radius: %fpx;",
                UITheme.toHexString(UITheme.LIGHT_GRAY),
                UITheme.toHexString(UITheme.DARK_GRAY),
                UITheme.BORDER_RADIUS,
                UITheme.BORDER_RADIUS
            );
            button.setStyle(hoverStyle);
        });
        
        button.setOnMouseExited(e -> button.setStyle(style));
    }
    
    /**
     * Applies standard input styling (borders, backgrounds) to a text input.
     *
     * @param input the input control
     */
    private static void applyStandardInputStyle(TextInputControl input) {
        String style = String.format(
            "-fx-background-color: %s; " +
            "-fx-border-color: %s; " +
            "-fx-border-width: %fpx; " +
            "-fx-border-radius: %fpx; " +
            "-fx-background-radius: %fpx;",
            UITheme.toHexString(UITheme.WHITE),
            UITheme.toHexString(UITheme.MEDIUM_GRAY),
            UITheme.BORDER_WIDTH,
            UITheme.BORDER_RADIUS,
            UITheme.BORDER_RADIUS
        );
        input.setStyle(style);
    }
    
    /**
     * Applies specialized search styling to a text input.
     *
     * @param input the text input field
     */
    private static void applySearchInputStyle(TextField input) {
        applyStandardInputStyle(input);
        input.setPromptText("Search...");
        input.getStyleClass().add("search-input");
    }
    
    /**
     * Applies error/invalid styling (red border) to a text input.
     *
     * @param input the input control
     */
    private static void applyErrorInputStyle(TextInputControl input) {
        String style = String.format(
            "-fx-background-color: %s; " +
            "-fx-border-color: %s; " +
            "-fx-border-width: %fpx; " +
            "-fx-border-radius: %fpx; " +
            "-fx-background-radius: %fpx;",
            UITheme.toHexString(UITheme.WHITE),
            UITheme.toHexString(UITheme.ERROR),
            UITheme.BORDER_WIDTH,
            UITheme.BORDER_RADIUS,
            UITheme.BORDER_RADIUS
        );
        input.setStyle(style);
    }
    
    /**
     * Applies success/valid styling (green border) to a text input.
     *
     * @param input the input control
     */
    private static void applySuccessInputStyle(TextInputControl input) {
        String style = String.format(
            "-fx-background-color: %s; " +
            "-fx-border-color: %s; " +
            "-fx-border-width: %fpx; " +
            "-fx-border-radius: %fpx; " +
            "-fx-background-radius: %fpx;",
            UITheme.toHexString(UITheme.WHITE),
            UITheme.toHexString(UITheme.SUCCESS),
            UITheme.BORDER_WIDTH,
            UITheme.BORDER_RADIUS,
            UITheme.BORDER_RADIUS
        );
        input.setStyle(style);
    }
    
    /**
     * Applies standard card border and drop shadow effects to a container pane.
     *
     * @param container the target pane
     */
    private static void applyStandardCardStyle(Pane container) {
        String style = String.format(
            "-fx-background-color: %s; " +
            "-fx-background-radius: %fpx;",
            UITheme.toHexString(UITheme.WHITE),
            UITheme.BORDER_RADIUS
        );
        container.setStyle(style);
        
        // Add subtle shadow
        DropShadow shadow = new DropShadow();
        shadow.setColor(UITheme.withOpacity(UITheme.ALMOST_BLACK, 0.1));
        shadow.setRadius(4);
        shadow.setOffsetY(2);
        container.setEffect(shadow);
    }
    
    /**
     * Applies elevated card border and deeper drop shadow effects to a container pane.
     *
     * @param container the target pane
     */
    private static void applyElevatedCardStyle(Pane container) {
        applyStandardCardStyle(container);
        
        // Add prominent shadow
        DropShadow shadow = new DropShadow();
        shadow.setColor(UITheme.withOpacity(UITheme.ALMOST_BLACK, 0.15));
        shadow.setRadius(8);
        shadow.setOffsetY(4);
        container.setEffect(shadow);
    }
    
    /**
     * Applies flat card background styling (no shadow) to a container pane.
     *
     * @param container the target pane
     */
    private static void applyFlatCardStyle(Pane container) {
        String style = String.format(
            "-fx-background-color: %s; " +
            "-fx-background-radius: %fpx;",
            UITheme.toHexString(UITheme.WHITE),
            UITheme.BORDER_RADIUS
        );
        container.setStyle(style);
    }
    
    /**
     * Applies outlined card styling (no shadow, medium-gray border) to a container pane.
     *
     * @param container the target pane
     */
    private static void applyOutlinedCardStyle(Pane container) {
        String style = String.format(
            "-fx-background-color: %s; " +
            "-fx-border-color: %s; " +
            "-fx-border-width: %fpx; " +
            "-fx-border-radius: %fpx; " +
            "-fx-background-radius: %fpx;",
            UITheme.toHexString(UITheme.WHITE),
            UITheme.toHexString(UITheme.MEDIUM_GRAY),
            UITheme.BORDER_WIDTH,
            UITheme.BORDER_RADIUS,
            UITheme.BORDER_RADIUS
        );
        container.setStyle(style);
    }
    
    /**
     * Adds click-release scale animations to a Button.
     *
     * @param button target button
     */
    private static void addButtonAnimations(Button button) {
        // Scale animation on click
        button.setOnMousePressed(e -> {
            ScaleTransition scale = new ScaleTransition(UITheme.ANIMATION_FAST, button);
            scale.setToX(0.95);
            scale.setToY(0.95);
            scale.play();
        });
        
        button.setOnMouseReleased(e -> {
            ScaleTransition scale = new ScaleTransition(UITheme.ANIMATION_FAST, button);
            scale.setToX(1.0);
            scale.setToY(1.0);
            scale.play();
        });
    }
    
    /**
     * Adds focus state listener to transition border colors on a TextInputControl.
     *
     * @param input target input control
     */
    private static void addInputFocusEffects(TextInputControl input) {
        // Focus border effect
        input.focusedProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal) {
                // Focused state
                String focusedStyle = input.getStyle().replace(
                    UITheme.toHexString(UITheme.MEDIUM_GRAY),
                    UITheme.toHexString(UITheme.SECONDARY)
                );
                input.setStyle(focusedStyle);
            } else {
                // Unfocused state
                String unfocusedStyle = input.getStyle().replace(
                    UITheme.toHexString(UITheme.SECONDARY),
                    UITheme.toHexString(UITheme.MEDIUM_GRAY)
                );
                input.setStyle(unfocusedStyle);
            }
        });
    }
    
    /**
     * Resolves the CSS style class string for the given ButtonStyle enum.
     *
     * @param style button style enum value
     * @return matching CSS class name
     */
    private static String getButtonStyleClass(ButtonStyle style) {
        switch (style) {
            case PRIMARY: return UITheme.CSS_BUTTON_PRIMARY;
            case SECONDARY: return UITheme.CSS_BUTTON_SECONDARY;
            case SUCCESS: return UITheme.CSS_BUTTON_SUCCESS;
            case WARNING: return UITheme.CSS_BUTTON_WARNING;
            case DANGER: return UITheme.CSS_BUTTON_DANGER;
            default: return UITheme.CSS_BUTTON_SECONDARY;
        }
    }
}
