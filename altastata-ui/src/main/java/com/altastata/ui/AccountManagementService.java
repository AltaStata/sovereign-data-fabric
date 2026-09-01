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

import com.altastata.utils.Account;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.stage.Popup;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.altastata.ui.theme.UITheme;

import java.io.File;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Service class responsible for managing user accounts including selection, loading,
 * password validation, and initialization. This class was extracted from AltaStataApp
 * to improve separation of concerns and centralize account management functionality.
 */
public class AccountManagementService {
    
    private static final Logger LOGGER = LoggerFactory.getLogger(AccountManagementService.class);
    
    private final Account account;
    private final String accountsDirectory;
    private final Popup popup;

    /** Stops the previous account's periodic directory refresh on logout / account switch. */
    private final AtomicBoolean directoryRefreshRunning = new AtomicBoolean(false);
    private volatile Thread directoryRefreshThread;
    
    /**
     * Creates a new AccountManagementService.
     *
     * @param account The account instance to manage
     * @param accountsDirectory The directory where accounts are stored
     * @param popup The popup for displaying account selection dialogs
     */
    public AccountManagementService(Account account, String accountsDirectory, Popup popup) {
        this.account = account;
        this.accountsDirectory = accountsDirectory;
        this.popup = popup;
    }
    
    /**
     * Displays account selection dialog and handles account creation or loading.
     * Creates the accounts directory if it doesn't exist and shows available accounts.
     */
    public void selectAccount() {
        File dir = new File(accountsDirectory);
        
        if (!dir.exists()) {
            dir.mkdirs();
            
            Platform.runLater(() -> {
                Properties p = System.getProperties();
                Enumeration keys = p.keys();
                String allProperties = "";
                while (keys.hasMoreElements()) {
                    String key = (String) keys.nextElement();
                    String value = (String) p.get(key);
                    if (value.contains(File.separator) || value.contains("android") || value.contains("user")
                            || key.contains("os")) {
                        allProperties += "\n" + key + ": " + value;
                    }
                }
                
                LOGGER.error("No accounts directory exist at: " + dir.getAbsolutePath() + "\n" + allProperties);
                
                UIUtils.showInfoAlert("Main directory created", 
                    "No accounts directory exist at: " + dir.getAbsolutePath() + "\n" + allProperties);
            });
            
            return;
        }
        
        File[] filesList = dir.listFiles();
        List<File> directoriesOnly = new ArrayList<>();
        for (File file : filesList) {
            if (file.isDirectory()) {
                LOGGER.info("selectAccount Account: " + file);
                directoriesOnly.add(file);
            }
        }
        
        Collections.sort(directoriesOnly);
        
        // Show account selection popup
        VBox vbh = createAccountSelectionDialog(directoriesOnly);
        
        final StackPane stackPane = new StackPane();
        
        stackPane.setOnKeyReleased(ke -> {
            if (ke.getCode().equals(KeyCode.ESCAPE)) {
                AltaStataApp.hidePopup(popup);
            }
        });
        
        HBox hbh = createAccountActionButtons(directoriesOnly);
        
        stackPane.getChildren().add(vbh);
        vbh.getChildren().add(hbh);
        
        AltaStataApp.showPopup(popup, stackPane, true);
    }
    
    /**
     * Creates the account selection dialog with radio buttons for available accounts.
     *
     * @param directoriesOnly List of account directories
     * @return VBox containing the account selection UI
     */
    private VBox createAccountSelectionDialog(List<File> directoriesOnly) {
        VBox vbh = new VBox();
        vbh.setPadding(UITheme.PADDING_LARGE_DIALOG);
        vbh.setStyle(UITheme.DIALOG_BOX_STYLE);
        vbh.setOpacity(0.9);
        
        vbh.getChildren().add(0, new Text("Select Account"));
        
        ToggleGroup group = new ToggleGroup();
        RadioButton[] radioBox = new RadioButton[directoriesOnly.size()];
        
        for (int i = 0; i < directoriesOnly.size(); i++) {
            final int index = i;
            
            radioBox[index] = new RadioButton(directoriesOnly.get(index).getName());
            radioBox[index].selectedProperty().addListener((obs, wasOn, isNowOn) -> {
                if (isNowOn) {
                    selectedAccount = directoriesOnly.get(index);
                    LOGGER.debug("Selected account: " + selectedAccount.getName());
                } else {
                    if (selectedAccount != null && selectedAccount.equals(directoriesOnly.get(index))) {
                        selectedAccount = null;
                    }
                }
            });
            
            radioBox[index].setToggleGroup(group);
            vbh.getChildren().add(index + 1, radioBox[index]);
            
            if (directoriesOnly.size() == 1) {
                radioBox[index].setSelected(true);
                selectedAccount = directoriesOnly.get(index);
            }
        }
        
        return vbh;
    }
    
    /**
     * Creates action buttons for account management (New, Config, Go).
     *
     * @param directoriesOnly List of account directories
     * @return HBox containing the action buttons
     */
    private HBox createAccountActionButtons(List<File> directoriesOnly) {
        HBox hbh = new HBox(UITheme.SPACING_MD);
        hbh.setAlignment(Pos.CENTER);
        hbh.setPadding(UITheme.PADDING_SMALL);
        hbh.setMaxWidth(Double.MAX_VALUE);
        hbh.setStyle(UITheme.DIALOG_BOX_STYLE);
        
        Button newButton = new Button("New");
        newButton.setOnAction(event -> {
            AltaStataApp.hidePopup(popup);
            
            Platform.runLater(() -> {
                StackPane stackPane = SetupUI.accountConfig(accountsDirectory, popup, null);
                AltaStataApp.showPopup(popup, stackPane, false);
            });
        });
        
        Button configButton = new Button("Config");
        configButton.setOnAction(event -> {
            AltaStataApp.hidePopup(popup);
            
            Platform.runLater(() -> {
                if (selectedAccount != null) {
                    StackPane stackPane = SetupUI.accountConfig(accountsDirectory, popup, selectedAccount.getName());
                    AltaStataApp.showPopup(popup, stackPane, false);
                } else {
                    LOGGER.warn("No account selected for configuration");
                }
            });
        });
        
        Button goButton = new Button("Go");
        goButton.setOnAction(event -> {
            AltaStataApp.hidePopup(popup);
            
            if (selectedAccount != null) {
                loadAccount(selectedAccount);
            } else {
                LOGGER.warn("No account selected to load");
            }
        });
        
        hbh.getChildren().addAll(newButton, configButton, goButton);
        return hbh;
    }
    
    /**
     * Helper method to get currently selected accounts from the UI.
     * This is a simplified version that needs to be properly implemented
     * to track the actual radio button selections.
     *
     * @param directoriesOnly List of available account directories
     * @return List of selected account directories
     */
    private List<File> getSelectedAccounts(List<File> directoriesOnly) {
        // For now, return the first directory if only one exists
        // In a full implementation, you would track the actual selection
        if (directoriesOnly.size() == 1) {
            return Arrays.asList(directoriesOnly.get(0));
        }
        return new ArrayList<>();
    }
    
    /**
     * Tracks the currently selected account from radio button UI.
     * This field should be updated by the radio button listeners.
     */
    private File selectedAccount = null;
    
    /**
     * Sets the currently selected account (called by radio button listeners).
     */
    public void setSelectedAccount(File account) {
        this.selectedAccount = account;
    }
    
    /**
     * Loads the specified account and initializes the file system.
     *
     * @param accountDir The account directory to load
     */
    public void loadAccount(File accountDir) {
        String accountDirPath = accountDir.getAbsolutePath();

        // Set the accountDirPath in AltaStataApp for other components to use
        AltaStataApp.accountDirPath = accountDirPath;

        // Same pattern as createAccountLoadingTask: heavy work off the FX thread.
        Task<Void> task = new Task<Void>() {
            @Override
            public Void call() {
                // Stop refresh from the previous session before password/handlers are cleared.
                stopDirectoryRefresh();

                try {
                    // Load account settings (throws SecurityException on license gate failure).
                    // RSA/PQC: event loop starts from setPassword. HSM/HPCS: auto-starts on load.
                    String[] errors = account.loadAccountProperties(accountDirPath);

                    if (errors.length > 0) {
                        String joinedString = StringUtils.join(errors, "\n");

                        Platform.runLater(() -> {
                            UIUtils.showErrorAlert("Load Account Error", joinedString);
                        });
                    } else {
                        Platform.runLater(() -> {
                            if (checkCognitoPassword() && checkOrInputPassword()) {
                                // Window title: account only (OS title can't split left/right).
                                // Version is shown gray on the far right of the toolbar.
                                if (AltaStataApp.primaryStage != null) {
                                    AltaStataApp.primaryStage.setTitle(accountDir.getName());
                                }

                                new Thread(createAccountLoadingTask()).start();
                            }
                        });
                    }
                } catch (SecurityException ex) {
                    LOGGER.error("loadAccount license/security gate failed for {}", accountDirPath, ex);
                    // Core already called abandonFailedLogin(); return to the 4-button apps menu.
                    Platform.runLater(() -> {
                        UIUtils.showErrorAlert("License Error", ex.getMessage());
                        SetupUI.showAppsMenu();
                    });
                } catch (Exception ex) {
                    LOGGER.error("loadAccount failed for {}", accountDirPath, ex);
                    account.abandonFailedLogin();
                    Platform.runLater(() -> {
                        UIUtils.showErrorAlert("Load Account Error",
                                ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName());
                        SetupUI.showAppsMenu();
                    });
                }

                return null;
            }
        };
        new Thread(task).start();
    }
    
    /**
     * Creates a background task for loading account cloud files.
     *
     * @return Task for account loading
     */
    private Task<Void> createAccountLoadingTask() {
        return new Task<Void>() {
            @Override
            public Void call() {
                try {
                    // Reset opacity using MediaPlayerManager
                    MediaPlayerManager.resetOpacityAll(
                        AltaStataApp.uploadButton, 
                        AltaStataApp.downloadButton, 
                        AltaStataApp.shareButton, 
                        AltaStataApp.revokeButton, 
                        AltaStataApp.deleteButton, 
                        AltaStataApp.appsButton
                    );
                    
                    MediaPlayerManager.reduceOpacity(AltaStataApp.appsButton);
                    
                    loadAccountCloudFiles();
                    
                    MediaPlayerManager.increaseOpacity(AltaStataApp.appsButton);
                    
                    if (!account.CUSTODIAN_USER().equals(account.MY_USER())) {
                        refreshDirectoriesPeriodically();
                    }
                } catch (Throwable ex) {
                    LOGGER.error("loadAccount", ex);
                    
                    Platform.runLater(() -> {
                        UIUtils.showErrorAlert("Load Account Error", ex.getMessage());
                    });
                }
                
                return null;
            }
        };
    }
    
    /**
     * Loads cloud files for the current account and initializes the UI.
     */
    private void loadAccountCloudFiles() {
        account.getFileSystemHandler().init();
        
        if (AltaStataApp.container != null) {
            AltaStataApp.container.setCurrentDirectoryIndex(0);
            
            Platform.runLater(() -> {
                // Fill up the root
                AltaStataApp.container.createAndPopulateDirectoryList(
                    AltaStataApp.INITIAL_COLUMN_NUMBER_BEFORE_INCREMENTATION,
                    new com.altastata.filesystem.common.CloudFile(
                        com.altastata.filesystem.common.FileSystemHandler.INIT_DIR, true)
                );
                
                if (NavigationPane.isMobileNavigation) {
                    AltaStataApp.container.mobileClickAndMoveForward(AltaStataApp.container.getCurrentDirectoryIndex());
                }
                
                AltaStataApp.container.refreshCurrentDirectoryListAndSelectFirstCloudFile();
                AltaStataApp.container.getItems().get(0).requestFocus();
            });
        }
    }
    
    /**
     * Stops the periodic directory-refresh thread from a previous login, if any.
     * Must run before {@link Account#loadAccountProperties} clears the password.
     */
    private void stopDirectoryRefresh() {
        directoryRefreshRunning.set(false);
        Thread previous = directoryRefreshThread;
        directoryRefreshThread = null;
        if (previous != null) {
            previous.interrupt();
            try {
                previous.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Starts a background task to periodically refresh directories for the current login.
     * Replaces any prior refresh thread so account switches cannot race with a null password.
     */
    private void refreshDirectoriesPeriodically() {
        stopDirectoryRefresh();
        directoryRefreshRunning.set(true);

        Thread refreshThread = new Thread(() -> {
            while (directoryRefreshRunning.get()) {
                try {
                    Thread.sleep(10000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }

                if (!directoryRefreshRunning.get()) {
                    break;
                }
                // Skip while switching accounts / before setPassword completes.
                // Use isPasswordSet (not getPassword) to avoid Vault round-trips every 10s.
                if (!account.isPasswordSet()) {
                    continue;
                }
                if (AltaStataApp.container != null) {
                    AltaStataApp.container.refreshAllDirectories();
                }
            }
        }, "directory-refresh");
        refreshThread.setDaemon(true);
        directoryRefreshThread = refreshThread;
        refreshThread.start();
    }
    
    /**
     * Checks or prompts for user password if required.
     * Must be called from the JavaFX Application Thread.
     *
     * @return true if password is valid or not required, false otherwise
     */
    public boolean checkOrInputPassword() {
        // Driven by account type (RSA/PQC vs HSM/HPCS), not by whether a PEM file is on disk.
        if (account.requiresLocalPassword() && account.getPassword() == null) {
            Optional<String> result = new PasswordDialog("Password", "Please, enter your password.").showAndWait();
            
            if (result.isPresent()) {
                String password = result.get();
                
                LOGGER.trace("checkOrInputPassword Password: " + password);
                try {
                    account.setPassword(password.toCharArray());
                    return true;
                } catch (SecurityException ex) {
                    LOGGER.error("checkOrInputPassword license/identity gate", ex);
                    // Core already abandoned; show license error and return to apps menu.
                    UIUtils.showErrorAlert("License Error", ex.getMessage());
                    SetupUI.showAppsMenu();
                    return false;
                } catch (Exception ex) {
                    LOGGER.error("checkOrInputPassword", ex);
                    account.abandonFailedLogin();
                    UIUtils.showErrorAlert("Password Error", "Password does not match: " + ex.getMessage()
                            + ". Make sure you have defined correct metadata-encryption in you properties.");
                    SetupUI.showAppsMenu();
                    return false;
                }
            } else {
                account.abandonFailedLogin();
                SetupUI.showAppsMenu();
                return false;
            }
        } else {
            return true;
        }
    }
    
    /**
     * Checks and prompts for Cognito password if Cognito authentication is configured.
     *
     * @return true if Cognito auth succeeded or is not required, false to abort login
     */
    private boolean checkCognitoPassword() {
        if (account.getProperty("cognito-identity-id") != null) {
            Optional<String> cognitoResult =
                    new PasswordDialog("Cognito password", "Please enter your cognito user password.").showAndWait();

            if (cognitoResult.isPresent()) {
                if (account.cognitoClient().validateUser(account.MY_USER(), cognitoResult.get()) == null) {
                    UIUtils.showErrorAlert("Cognito Password Error", "Cognito Password does not match.");
                    account.abandonFailedLogin();
                    SetupUI.showAppsMenu();
                    return false;
                }
                account.setCognitoPassword(cognitoResult.get());
                return true;
            }
            account.abandonFailedLogin();
            SetupUI.showAppsMenu();
            return false;
        }
        return true;
    }
}
