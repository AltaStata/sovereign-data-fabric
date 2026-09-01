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

import javafx.geometry.Insets;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

/**
 * Centralized theme configuration for the AltaStata desktop application.
 * 
 * <p>This class defines the visual design system including colors, typography,
 * spacing, and dimensions used throughout the application. It ensures visual
 * consistency and makes theme changes easy to apply globally.</p>
 * 
 * <h3>Design Principles:</h3>
 * <ul>
 *   <li>Professional and modern appearance</li>
 *   <li>High contrast for accessibility</li>
 *   <li>Consistent spacing and alignment</li>
 *   <li>Clear visual hierarchy</li>
 * </ul>
 * 
 * @author AltaStata Team
 * @since 1.0
 * @see UIStyleFactory
 */
public final class UITheme {
    
    // Prevent instantiation
    /**
     * Private constructor to prevent instantiation of utility class.
     */
    private UITheme() {}
    
    // ==================== COLOR PALETTE ====================
    
    /**
     * Primary brand color - used for main UI elements and highlights.
     * A sophisticated dark blue-gray that conveys professionalism.
     */
    public static final Color PRIMARY = Color.web("#2C3E50");
    
    /**
     * Secondary accent color - used for interactive elements and focus states.
     * A vibrant blue that complements the primary color.
     */
    public static final Color SECONDARY = Color.web("#3498DB");
    
    /**
     * Success state color - used for successful operations and positive feedback.
     * A natural green that clearly indicates success.
     */
    public static final Color SUCCESS = Color.web("#27AE60");
    
    /**
     * Warning state color - used for warnings and caution indicators.
     * An orange that draws attention without being alarming.
     */
    public static final Color WARNING = Color.web("#F39C12");
    
    /**
     * Error state color - used for errors and critical alerts.
     * A red that clearly indicates problems or dangerous actions.
     */
    public static final Color ERROR = Color.web("#E74C3C");

    /**
     * Muted "pale red" used for error/rejection lines in the messages list.
     * Softer than {@link #ERROR} so it stays readable on the light dialog background
     * while still standing out from normal (informational) messages.
     */
    public static final Color MESSAGE_ERROR = Color.web("#C0504D");
    
    /**
     * Information color - used for informational messages and neutral highlights.
     * A calm blue that provides information without urgency.
     */
    public static final Color INFO = Color.web("#5DADE2");
    
    // Neutral colors for backgrounds and text
    /**
     * Pure white - used for primary backgrounds and clean surfaces.
     */
    public static final Color WHITE = Color.WHITE;
    
    /**
     * Light gray - used for secondary backgrounds and subtle divisions.
     */
    public static final Color LIGHT_GRAY = Color.web("#F8F9FA");
    
    /**
     * Medium gray - used for borders and inactive elements.
     */
    public static final Color MEDIUM_GRAY = Color.web("#BDC3C7");
    
    /**
     * Dark gray - used for secondary text and subtle elements.
     */
    public static final Color DARK_GRAY = Color.web("#7F8C8D");
    
    /**
     * Almost black - used for primary text and strong contrasts.
     */
    public static final Color ALMOST_BLACK = Color.web("#2C3E50");
    
    // ==================== TYPOGRAPHY ====================
    
    /**
     * Primary font family used throughout the application.
     * Uses system fonts for better OS integration.
     */
    public static final String FONT_FAMILY = "Segoe UI, -apple-system, BlinkMacSystemFont, Arial, sans-serif";
    
    /**
     * Large header font - used for main titles and section headers.
     */
    public static final Font HEADER_LARGE = Font.font(FONT_FAMILY, FontWeight.BOLD, 18);
    
    /**
     * Medium header font - used for subsection titles and dialog headers.
     */
    public static final Font HEADER_MEDIUM = Font.font(FONT_FAMILY, FontWeight.BOLD, 16);
    
    /**
     * Small header font - used for group labels and minor headings.
     */
    public static final Font HEADER_SMALL = Font.font(FONT_FAMILY, FontWeight.BOLD, 14);
    
    /**
     * Body font - used for most text content and labels.
     */
    public static final Font BODY = Font.font(FONT_FAMILY, FontWeight.NORMAL, 12);
    
    /**
     * List item font - used for file names in lists (slightly larger for readability).
     */
    public static final Font LIST_ITEM = Font.font(FONT_FAMILY, FontWeight.NORMAL, 14);
    
    /**
     * Small text font - used for secondary information and metadata.
     */
    public static final Font SMALL_TEXT = Font.font(FONT_FAMILY, FontWeight.NORMAL, 10);
    
    /**
     * Monospace font - used for code, file paths, and technical information.
     */
    public static final Font MONOSPACE = Font.font("Consolas, Monaco, 'Courier New', monospace", FontWeight.NORMAL, 11);
    
    // ==================== SPACING & DIMENSIONS ====================
    
    /**
     * Extra small spacing - used for tight layouts and minor adjustments.
     */
    public static final double SPACING_XS = 4.0;
    
    /**
     * Small spacing - used for compact elements and close relationships.
     */
    public static final double SPACING_SM = 8.0;
    
    /**
     * Medium spacing - standard spacing for most UI elements.
     */
    public static final double SPACING_MD = 12.0;
    
    /**
     * Large spacing - used for section separation and major groupings.
     */
    public static final double SPACING_LG = 16.0;
    
    /**
     * Extra large spacing - used for major layout divisions.
     */
    public static final double SPACING_XL = 24.0;
    
    // Padding presets for common use cases
    /**
     * Tight padding - for compact layouts and dense information.
     */
    public static final Insets PADDING_TIGHT = new Insets(SPACING_XS);
    
    /**
     * Standard padding - for most UI components and containers.
     */
    public static final Insets PADDING_STANDARD = new Insets(SPACING_MD);
    
    /**
     * Loose padding - for spacious layouts and better readability.
     */
    public static final Insets PADDING_LOOSE = new Insets(SPACING_LG);
    
    /**
     * Dialog padding - optimized for dialog boxes and modal windows.
     */
    public static final Insets PADDING_DIALOG = new Insets(SPACING_LG, SPACING_XL, SPACING_LG, SPACING_XL);
    
    /**
     * Component padding - for most UI components with minimal spacing.
     */
    public static final Insets PADDING_COMPONENT = new Insets(1, 0, 1, 0);
    
    /**
     * Text padding - for text elements with small left margin.
     */
    public static final Insets PADDING_TEXT = new Insets(0, 0, 0, 2);
    
    /**
     * Extra tight padding - for compact button layouts.
     */
    public static final Insets PADDING_MINIMAL = new Insets(2, 2, 2, 2);
    
    /**
     * Small padding - for form elements and settings panes.
     */
    public static final Insets PADDING_SMALL = new Insets(5);
    
    /**
     * Form padding - for grid panes and form layouts.
     */
    public static final Insets PADDING_FORM = new Insets(10, 10, 10, 10);
    
    /**
     * Tile padding - for tile layouts and button groups.
     */
    public static final Insets PADDING_TILE = new Insets(15, 15, 15, 15);
    
    /**
     * Large dialog padding - for major dialog boxes.
     */
    public static final Insets PADDING_LARGE_DIALOG = new Insets(20, 20, 20, 20);
    
    // Component dimensions
    /**
     * Standard button height for consistent button appearance.
     */
    public static final double BUTTON_HEIGHT = 32.0;
    
    /**
     * Standard icon size for toolbar and menu icons.
     */
    public static final double ICON_SIZE = 16.0;
    
    /**
     * Large icon size for prominent actions and file type icons.
     */
    public static final double ICON_SIZE_LARGE = 24.0;
    
    /**
     * Toolbar icon size for consistency.
     */
    public static final double ICON_SIZE_TOOLBAR = 37.0;
    
    /**
     * Standard border radius for rounded corners.
     */
    public static final double BORDER_RADIUS = 4.0;
    
    /**
     * Standard border width for outlines and divisions.
     */
    public static final double BORDER_WIDTH = 1.0;
    
    // ==================== COMMON UI COLORS ====================
    
    /**
     * Header background color - professional dark blue.
     */
    public static final Color HEADER_BACKGROUND = Color.web("#112138");
    
    /**
     * Dialog background color - light gray.
     */
    public static final Color DIALOG_BACKGROUND = Color.web("#f3f3f3");
    
    /**
     * Alternating row background color - light medium gray for striped lists.
     */
    public static final Color ALTERNATING_ROW_BACKGROUND = Color.web("#F0F0F0");
    
    // ==================== COMMON CSS STYLES ====================
    
    /**
     * Standard dialog box CSS style string.
     */
    public static final String DIALOG_BOX_STYLE = 
        "-fx-border-color: lightgrey;" +
        "-fx-border-width: 2px;" +
        "-fx-padding: 13;" +
        "-fx-spacing: 15;" +
        "-fx-background-color: " + toHexString(DIALOG_BACKGROUND) + ";" +
        "-fx-background-insets: 0, 1, 2;" +
        "-fx-background-radius: 5 5 5 5;";
    
    /**
     * Clean list view CSS style string for minimal appearance.
     */
    public static final String CLEAN_LISTVIEW_STYLE = 
        "-fx-background-color: null;" +
        "-fx-background-radius: 0;" +
        "-fx-background-insets: 0;" +
        "-fx-border-width: 0;" +
        "-fx-padding: 0;";
    
    /**
     * Compact dialog box CSS style string with reduced spacing.
     */
    public static final String COMPACT_DIALOG_BOX_STYLE = 
        "-fx-border-color: grey;" +
        "-fx-border-width: 2px;" +
        "-fx-padding: 4;" +
        "-fx-spacing: 6;" +
        "-fx-background-color: " + toHexString(DIALOG_BACKGROUND) + ";" +
        "-fx-background-insets: 0, 1, 2;" +
        "-fx-background-radius: 5 5 5 5;";
    
    /**
     * Transparent tile background style.
     */
    public static final String TRANSPARENT_TILE_STYLE = 
        "-fx-background-color: transparent;" +
        "-fx-background-radius: 10;";
    
    /**
     * Large header text style.
     */
    public static final String LARGE_HEADER_STYLE = "-fx-font-size: 1.6em;";
    
    /**
     * Medium header text style.
     */
    public static final String MEDIUM_HEADER_STYLE = "-fx-font-size: 1.2em;";
    
    // ==================== ANIMATION & TRANSITIONS ====================
    
    /**
     * Fast animation duration - for immediate feedback (hover, focus).
     */
    public static final javafx.util.Duration ANIMATION_FAST = javafx.util.Duration.millis(150);
    
    /**
     * Standard animation duration - for most UI transitions.
     */
    public static final javafx.util.Duration ANIMATION_STANDARD = javafx.util.Duration.millis(300);
    
    /**
     * Slow animation duration - for complex transitions and loading states.
     */
    public static final javafx.util.Duration ANIMATION_SLOW = javafx.util.Duration.millis(500);
    
    // ==================== OPACITY VALUES ====================
    
    /**
     * Disabled state opacity - for inactive elements.
     */
    public static final double OPACITY_DISABLED = 0.5;
    
    /**
     * Hover state opacity - for interactive feedback.
     */
    public static final double OPACITY_HOVER = 0.8;
    
    /**
     * Pressed state opacity - for click feedback.
     */
    public static final double OPACITY_PRESSED = 0.6;
    
    /**
     * Overlay opacity - for modal backgrounds and overlays.
     */
    public static final double OPACITY_OVERLAY = 0.7;
    
    // ==================== CSS STYLE CLASSES ====================
    
    /**
     * CSS class for primary buttons (main actions).
     */
    public static final String CSS_BUTTON_PRIMARY = "button-primary";
    
    /**
     * CSS class for secondary buttons (alternative actions).
     */
    public static final String CSS_BUTTON_SECONDARY = "button-secondary";
    
    /**
     * CSS class for success buttons (positive actions).
     */
    public static final String CSS_BUTTON_SUCCESS = "button-success";
    
    /**
     * CSS class for warning buttons (caution actions).
     */
    public static final String CSS_BUTTON_WARNING = "button-warning";
    
    /**
     * CSS class for danger buttons (destructive actions).
     */
    public static final String CSS_BUTTON_DANGER = "button-danger";
    
    /**
     * CSS class for card-style containers.
     */
    public static final String CSS_CARD = "card";
    
    /**
     * CSS class for header sections.
     */
    public static final String CSS_HEADER = "header";
    
    /**
     * CSS class for form input groups.
     */
    public static final String CSS_FORM_GROUP = "form-group";
    
    /**
     * CSS class for status indicators.
     */
    public static final String CSS_STATUS_INDICATOR = "status-indicator";
    
    // ==================== HELPER METHODS ====================
    
    /**
     * Converts a Color to its CSS hex string representation.
     * 
     * @param color The color to convert
     * @return CSS hex string (e.g., "#2C3E50")
     */
    public static String toHexString(Color color) {
        return String.format("#%02X%02X%02X",
            (int) (color.getRed() * 255),
            (int) (color.getGreen() * 255),
            (int) (color.getBlue() * 255)
        );
    }
    
    /**
     * Creates a darker variant of the given color for hover effects.
     * 
     * @param color The base color
     * @param factor The darkening factor (0.0 to 1.0)
     * @return A darker version of the color
     */
    public static Color darken(Color color, double factor) {
        return color.deriveColor(0, 1, 1 - factor, 1);
    }
    
    /**
     * Creates a lighter variant of the given color for disabled states.
     * 
     * @param color The base color
     * @param factor The lightening factor (0.0 to 1.0)
     * @return A lighter version of the color
     */
    public static Color lighten(Color color, double factor) {
        return color.deriveColor(0, 1, 1 + factor, 1);
    }
    
    /**
     * Creates a semi-transparent version of the given color.
     * 
     * @param color The base color
     * @param opacity The opacity value (0.0 to 1.0)
     * @return A semi-transparent version of the color
     */
    public static Color withOpacity(Color color, double opacity) {
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), opacity);
    }
    
    // ==================== CSS GENERATION METHODS ====================
    
    /**
     * Generates CSS custom properties (variables) that can be used in CSS files.
     * This allows CSS files to reference theme colors dynamically.
     * 
     * @return CSS string with custom properties
     */
    public static String generateCSSVariables() {
        return ":root {\n" +
            "  --primary-color: " + toHexString(PRIMARY) + ";\n" +
            "  --secondary-color: " + toHexString(SECONDARY) + ";\n" +
            "  --success-color: " + toHexString(SUCCESS) + ";\n" +
            "  --warning-color: " + toHexString(WARNING) + ";\n" +
            "  --error-color: " + toHexString(ERROR) + ";\n" +
            "  --info-color: " + toHexString(INFO) + ";\n" +
            "  --light-gray: " + toHexString(LIGHT_GRAY) + ";\n" +
            "  --medium-gray: " + toHexString(MEDIUM_GRAY) + ";\n" +
            "  --dark-gray: " + toHexString(DARK_GRAY) + ";\n" +
            "  --header-background: " + toHexString(HEADER_BACKGROUND) + ";\n" +
            "  --dialog-background: " + toHexString(DIALOG_BACKGROUND) + ";\n" +
            "  --border-radius: " + BORDER_RADIUS + "px;\n" +
            "  --border-width: " + BORDER_WIDTH + "px;\n" +
            "  --font-family: '" + FONT_FAMILY + "';\n" +
            "}\n";
    }
    
    /**
     * Writes theme-aware CSS to a file or returns it as a string for injection.
     * This method can be used to dynamically update CSS files with current theme values.
     * 
     * @param cssContent The base CSS content to update
     * @return Updated CSS content with theme values
     */
    public static String injectThemeValues(String cssContent) {
        return generateCSSVariables() + "\n" + cssContent;
    }
}
