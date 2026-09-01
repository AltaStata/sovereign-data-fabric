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

package com.altastata.ui.util;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public class VersionUtils {
    private static final String VERSION_PROPERTIES_FILE = "/version.properties";
    private static Properties versionProperties = null;
    
    /**
     * Loads version properties from the resource file
     */
    private static void loadVersionProperties() {
        if (versionProperties == null) {
            versionProperties = new Properties();
            try (InputStream inputStream = VersionUtils.class.getResourceAsStream(VERSION_PROPERTIES_FILE)) {
                if (inputStream != null) {
                    versionProperties.load(inputStream);
                } else {
                    // Fallback values if properties file is not found
                    versionProperties.setProperty("version", "Unknown");
                    versionProperties.setProperty("build.timestamp", "Unknown");
                    versionProperties.setProperty("application.name", "AltaStata Cloud File Explorer");
                    versionProperties.setProperty("vendor", "AltaStata Inc.");
                }
            } catch (IOException e) {
                // Fallback values if there's an error reading the file
                versionProperties.setProperty("version", "Unknown");
                versionProperties.setProperty("build.timestamp", "Unknown");
                versionProperties.setProperty("application.name", "AltaStata Cloud File Explorer");
                versionProperties.setProperty("vendor", "AltaStata Inc.");
            }
        }
    }
    
    /**
     * Gets the application version
     * @return the version string
     */
    public static String getVersion() {
        loadVersionProperties();
        return versionProperties.getProperty("version");
    }
    
    /**
     * Gets the build timestamp
     * @return the build timestamp string
     */
    public static String getBuildTimestamp() {
        loadVersionProperties();
        return versionProperties.getProperty("build.timestamp");
    }
    
    /**
     * Gets the application name
     * @return the application name
     */
    public static String getApplicationName() {
        loadVersionProperties();
        return versionProperties.getProperty("application.name");
    }
    
    /**
     * Gets the vendor name
     * @return the vendor name
     */
    public static String getVendor() {
        loadVersionProperties();
        return versionProperties.getProperty("vendor");
    }
    
    /**
     * Gets a formatted version info string
     * @return formatted version information
     */
    public static String getVersionInfo() {
        return String.format("%s v%s", getApplicationName(), getVersion());
    }
    
    /**
     * Gets a complete version information string including build timestamp
     * @return complete version information
     */
    public static String getCompleteVersionInfo() {
        return String.format("%s v%s\nBuilt: %s\n%s", 
            getApplicationName(), getVersion(), getBuildTimestamp(), getVendor());
    }
} 
