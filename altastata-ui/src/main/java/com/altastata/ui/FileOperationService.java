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

import com.altastata.api.AltaStataFileSystem.OperationState;
import com.altastata.api.CloudFileOperationStatus;
import com.altastata.filesystem.common.CloudFile;
import com.altastata.filesystem.common.FileSystemHandler;
import com.altastata.ui.theme.UITheme;
import com.altastata.utils.Account;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Popup;
import javafx.stage.Stage;
import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scala.Tuple2;

import java.io.File;
import java.io.IOException;
import java.text.DecimalFormat;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Service class responsible for handling file operations such as upload, download,
 * share, delete, and directory creation. This class was extracted from AltaStataApp
 * to improve separation of concerns and centralize file operation management.
 */
public class FileOperationService {
    
    private static final Logger LOGGER = LoggerFactory.getLogger(FileOperationService.class);
    
    private final Account account;
    private final NavigationPane container;
    private final Stage stage;
    private final Popup popup;
    private final Set<Long> selectedTimestamps;
    private final DecimalFormat formatter = new DecimalFormat("#,###");
    
    /**
     * Creates a new FileOperationService.
     *
     * @param account The account instance for file operations
     * @param container The navigation pane container
     * @param stage The primary stage for dialogs
     * @param popup The popup for dialogs
     * @param selectedTimestamps Set for tracking selected timestamps
     */
    public FileOperationService(Account account, NavigationPane container, Stage stage, 
                               Popup popup, Set<Long> selectedTimestamps) {
        this.account = account;
        this.container = container;
        this.stage = stage;
        this.popup = popup;
        this.selectedTimestamps = selectedTimestamps;
    }
    
    /**
     * Handles the download button action for downloading selected files/directories.
     */
    public void handleDownloadButton() {
        final CloudFile[] objectsToDownload = container.getLastSelectedObjects();
        
        if (objectsToDownload == null) {
            UIUtils.showWarningAlert("Download Error", "Please, select files or directories you wish to download!");
        } else {
            File selectedDirectory = selectDirectoryForDownload();
            
            if (selectedDirectory == null) {
                return; // User cancelled directory selection
            }
            
            for (CloudFile cloudFile : objectsToDownload) {
                if (cloudFile.isDirectory()) {
                    LOGGER.info("Selected for download: " + cloudFile.getPath());
                }
            }
            
            // If multiple files or directory is selected, download their latest versions
            // otherwise let user to select the version
            boolean toDownloadOnlyLatestFilesVersions = 
                    objectsToDownload.length > 1 || objectsToDownload[0].isDirectory();
            
            List<Long> timestamps = account.getFileSystemHandler().detectTimestamps(objectsToDownload,
                    toDownloadOnlyLatestFilesVersions);
            
            Task<Void> task = createDownloadTask(objectsToDownload, selectedDirectory, timestamps);
            
            // Choose timestamps for file if only one is selected
            if (!toDownloadOnlyLatestFilesVersions && timestamps.size() > 1) {
                showTimestampSelectionDialog(timestamps, task, false);
            } else {
                timestamps.add(System.currentTimeMillis());
                new Thread(task).start();
            }
        }
    }
    
    /**
     * Creates a download task for executing file downloads in background.
     */
    private Task<Void> createDownloadTask(CloudFile[] objectsToDownload, File selectedDirectory, 
                                         List<Long> timestamps) {
        return new Task<Void>() {
            @Override
            public Void call() {
                LOGGER.info("Chosen directory: " + selectedDirectory);
                
                if (AltaStataApp.accountManagementService.checkOrInputPassword()) {
                    MediaPlayerManager.reduceOpacity(AltaStataApp.downloadButton);
                    
                    CloudFileOperationStatus[] retrieveResults = account.fileSystemModel()
                            .retrieveCloudFilesToLocalDirectory(objectsToDownload,
                                    selectedDirectory.getAbsolutePath(), timestamps, false, true, false);
                    
                    final AtomicInteger errors = new AtomicInteger(0);
                    
                    for (int i = 0; i < retrieveResults.length; i++) {
                        if (retrieveResults[i].getOperationState().equals(OperationState.DONE)) {
                            LOGGER.info("Download successful: " + retrieveResults[i].getCloudFileVersionPath());
                        } else {
                            LOGGER.info("Download failed: " + retrieveResults[i].getCloudFileVersionPath()
                                    + " error: " + retrieveResults[i].getError());
                            
                            account.getUserMsgs().add("Download error: \n" + retrieveResults[i].getError());
                            errors.incrementAndGet();
                        }
                    }
                    
                    if (errors.intValue() > 0) {
                        AltaStataApp.popupHandlerErrorAlert("Download",
                                errors.intValue() + " errors detected.\nPlease, see the message box for the details.");
                    }
                    
                    MediaPlayerManager.increaseOpacity(AltaStataApp.downloadButton);
                }
                
                return null;
            }
        };
    }
    
    /**
     * Handles the upload button action for uploading files to cloud storage.
     */
    public void handleUploadButton() {
        final CloudFile directoryToUpload = container.bestMatchingDirForSelection();
        
        LOGGER.info("Directory Selected for upload: " + directoryToUpload.getPath());
        
        Platform.runLater(() -> {
            FileChooser fileChooser = new FileChooser();
            fileChooser.setTitle("Select files you wish to upload");
            
            List<File> selectedFiles = fileChooser.showOpenMultipleDialog(stage);
            
            if (selectedFiles != null) {
                List<Tuple2<File, CloudFile>> listForSubTree = account.getFileSystemHandler()
                        .mapFilesTreeToCloudFileList(selectedFiles, selectedFiles.get(0).getParent(),
                                directoryToUpload.getPath(), System.currentTimeMillis());
                
                new Thread(createStoreTask(listForSubTree)).start();
                
                Platform.runLater(() -> {
                    // Find all files including ones in sub directories
                    for (Tuple2<File, CloudFile> tuple : listForSubTree) {
                        container.insertNewFile(directoryToUpload, tuple._2);
                    }
                });
            }
        });
    }
    
    /**
     * Creates a task for storing files to cloud storage.
     */
    public Task<Void> createStoreTask(List<Tuple2<File, CloudFile>> listForSubTree) {
        return new Task<Void>() {
            @Override
            public Void call() {
                if (AltaStataApp.accountManagementService.checkOrInputPassword()) {
                    MediaPlayerManager.reduceOpacity(AltaStataApp.uploadButton);
                    
                    CloudFileOperationStatus[] storeResults = account.fileSystemModel()
                            .uploadLocalFilesToCloud(listForSubTree, true);
                    
                    final AtomicInteger errors = new AtomicInteger(0);
                    
                    for (int i = 0; i < storeResults.length; i++) {
                        if (storeResults[i].getOperationState().equals(OperationState.DONE)) {
                            LOGGER.info("Upload successful: " + storeResults[i].getCloudFileVersionPath());
                        } else {
                            LOGGER.info("Upload failed: " + storeResults[i].getCloudFileVersionPath() 
                                    + " error: " + storeResults[i].getError());
                            
                            account.getUserMsgs().add("Store error: \n" + storeResults[i].getError());
                            errors.incrementAndGet();
                        }
                    }
                    
                    if (errors.intValue() > 0) {
                        AltaStataApp.popupHandlerErrorAlert("Store",
                                errors.intValue() + " errors detected.\nPlease, see the message box for the details.");
                    }
                    
                    MediaPlayerManager.increaseOpacity(AltaStataApp.uploadButton);
                }
                
                return null;
            }
        };
    }
    
    /**
     * Handles the share button action for sharing files with other users.
     */
    public void handleShareButton() {
        if (container.getLastSelectedObjects() == null) {
            UIUtils.showWarningAlert("Share Warning", "Please, select files or directories you wish to share!");
        } else {
            for (CloudFile file : container.getLastSelectedObjects()) {
                LOGGER.trace("Selected for share: " + file.getPath());
            }
            
            Platform.runLater(() -> {
                List<String> users = new ArrayList<>();
                Iterator<String> it = account.fileSystemModel().listUsers();
                while (it.hasNext()) {
                    String user = it.next();
                    if (!account.MY_USER().equals(user) && !account.CUSTODIAN_USER().equals(user)) {
                        users.add(user);
                    }
                }
                
                // Add groups
                File groupDir = new File(AltaStataApp.accountDirPath + File.separator + "groups");
                if (groupDir.exists()) {
                    for (String groupName : groupDir.list()) {
                        users.add(groupName);
                    }
                }
                
                showShareDialog(users);
            });
        }
    }
    
    /**
     * Shows the share dialog for selecting users to share with.
     */
    private void showShareDialog(List<String> users) {
        final StackPane stackPane = new StackPane();
        
        stackPane.setOnKeyReleased(ke -> {
            if (ke.getCode().equals(KeyCode.ESCAPE)) {
                AltaStataApp.hidePopup(popup);
            }
        });
        
        VBox vbh = new VBox();
        vbh.setPadding(UITheme.PADDING_LARGE_DIALOG);
        vbh.setStyle(UITheme.DIALOG_BOX_STYLE);
        vbh.setOpacity(0.9);
        
        ComboBox<String> myComboBox = new ComboBox<>(FXCollections.observableArrayList(users));
        AutocompleteCombobox.autoCompleteComboBoxPlus(myComboBox, (typedText, itemToCompare) -> 
                itemToCompare.toLowerCase().contains(typedText.toLowerCase()));
        
        stackPane.getChildren().add(vbh);
        vbh.getChildren().add(myComboBox);
        
        // If multiple files or directory is selected, share all versions of all files
        boolean toShareAllFilesVersions = 
                container.getLastSelectedObjects().length > 1 || container.getLastSelectedObjects()[0].isDirectory();

        List<Long> timestamps = toShareAllFilesVersions
                ? null
                : account.getFileSystemHandler().detectTimestamps(container.getLastSelectedObjects(), false);
        
        // VBox with timestamps
        if (!toShareAllFilesVersions && timestamps.size() > 1) {
            vbh.getChildren().add(filterTimestamps(timestamps, true));
        }
        
        Button okButton = new Button("OK");
        vbh.getChildren().add(okButton);
        
        AltaStataApp.showPopup(popup, stackPane, true);
        
        okButton.setOnAction(event1 -> {
            AltaStataApp.hidePopup(popup);
            
            Task<Void> task = createShareTask(myComboBox, toShareAllFilesVersions, timestamps);
            new Thread(task).start();
        });
    }
    
    /**
     * Creates a task for sharing files with selected users.
     */
    private Task<Void> createShareTask(ComboBox<String> myComboBox, boolean toShareAllFilesVersions, 
                                      List<Long> timestamps) {
        return new Task<Void>() {
            @Override
            public Void call() {
                if (AltaStataApp.accountManagementService.checkOrInputPassword()) {
                    List<Long> providedTimestamps = (!toShareAllFilesVersions && timestamps.size() > 1)
                            ? new ArrayList<>(selectedTimestamps)
                            : timestamps;
                    
                    String userToShare = AutocompleteCombobox.getComboBoxValue(myComboBox);
                    String[] usersToShare = null;
                    
                    if (userToShare.endsWith(".group")) {
                        List<String> list;
                        try {
                            list = FileUtils.readLines(new File(AltaStataApp.accountDirPath + File.separator
                                    + "groups" + File.separator + userToShare));
                            usersToShare = list.toArray(new String[list.size()]);
                        } catch (IOException e) {
                            Platform.runLater(() -> {
                                UIUtils.showErrorAlert("Share Error", "Cannot read group: " + userToShare);
                            });
                            return null;
                        }
                    } else {
                        usersToShare = new String[] { userToShare };
                    }
                    
                    MediaPlayerManager.reduceOpacity(AltaStataApp.shareButton);
                    
                    CloudFileOperationStatus[] shareResults = account.fileSystemModel()
                            .shareCloudFiles(container.getLastSelectedObjects(), usersToShare, providedTimestamps);
                    
                    final AtomicInteger errors = new AtomicInteger(0);
                    
                    for (int i = 0; i < shareResults.length; i++) {
                        if (shareResults[i].getOperationState().equals(OperationState.DONE)) {
                            LOGGER.info("Share successful: " + shareResults[i].getCloudFileVersionPath());
                        } else {
                            LOGGER.info("Share failed: " + shareResults[i].getCloudFileVersionPath() 
                                    + " error: " + shareResults[i].getError());
                            
                            account.getUserMsgs().add("Share error: \n" + shareResults[i].getError());
                            errors.incrementAndGet();
                        }
                    }
                    
                    if (errors.intValue() > 0) {
                        AltaStataApp.popupHandlerErrorAlert("Share",
                                errors.intValue() + " errors detected.\nPlease, see the message box for the details.");
                    }
                    
                    MediaPlayerManager.increaseOpacity(AltaStataApp.shareButton);
                }
                
                return null;
            }
        };
    }
    
    /**
     * Handles the revoke button action for revoking reader access from selected files/directories.
     */
    public void handleRevokeButton() {
        if (container.getLastSelectedObjects() == null) {
            UIUtils.showWarningAlert("Revoke Warning", "Please, select files or directories you wish to revoke access for!");
        } else {
            for (CloudFile file : container.getLastSelectedObjects()) {
                LOGGER.trace("Selected for revoke: " + file.getPath());
            }
            
            Platform.runLater(() -> {
                List<String> users = new ArrayList<>();
                Iterator<String> it = account.fileSystemModel().listUsers();
                while (it.hasNext()) {
                    String user = it.next();
                    if (!account.MY_USER().equals(user) && !account.CUSTODIAN_USER().equals(user)) {
                        users.add(user);
                    }
                }
                
                // Add groups
                File groupDir = new File(AltaStataApp.accountDirPath + File.separator + "groups");
                if (groupDir.exists()) {
                    for (String groupName : groupDir.list()) {
                        users.add(groupName);
                    }
                }
                
                showRevokeDialog(users);
            });
        }
    }
    
    /**
     * Shows the revoke dialog for selecting readers to revoke access from.
     */
    private void showRevokeDialog(List<String> users) {
        final StackPane stackPane = new StackPane();
        
        stackPane.setOnKeyReleased(ke -> {
            if (ke.getCode().equals(KeyCode.ESCAPE)) {
                AltaStataApp.hidePopup(popup);
            }
        });
        
        VBox vbh = new VBox();
        vbh.setPadding(UITheme.PADDING_LARGE_DIALOG);
        vbh.setStyle(UITheme.DIALOG_BOX_STYLE);
        vbh.setOpacity(0.9);
        
        ComboBox<String> myComboBox = new ComboBox<>(FXCollections.observableArrayList(users));
        AutocompleteCombobox.autoCompleteComboBoxPlus(myComboBox, (typedText, itemToCompare) -> 
                itemToCompare.toLowerCase().contains(typedText.toLowerCase()));
        
        stackPane.getChildren().add(vbh);
        vbh.getChildren().add(myComboBox);
        
        // If multiple files or directory is selected, revoke for all versions of all files
        boolean toRevokeAllFilesVersions = 
                container.getLastSelectedObjects().length > 1 || container.getLastSelectedObjects()[0].isDirectory();

        List<Long> timestamps = toRevokeAllFilesVersions
                ? null
                : account.getFileSystemHandler().detectTimestamps(container.getLastSelectedObjects(), false);
        
        // VBox with timestamps
        if (!toRevokeAllFilesVersions && timestamps.size() > 1) {
            vbh.getChildren().add(filterTimestamps(timestamps, true));
        }
        
        Button okButton = new Button("OK");
        vbh.getChildren().add(okButton);
        
        AltaStataApp.showPopup(popup, stackPane, true);
        
        okButton.setOnAction(event1 -> {
            AltaStataApp.hidePopup(popup);
            
            Task<Void> task = createRevokeTask(myComboBox, toRevokeAllFilesVersions, timestamps);
            new Thread(task).start();
        });
    }
    
    /**
     * Creates a task for revoking reader access from selected files.
     */
    private Task<Void> createRevokeTask(ComboBox<String> myComboBox, boolean toRevokeAllFilesVersions, 
                                       List<Long> timestamps) {
        return new Task<Void>() {
            @Override
            public Void call() {
                if (AltaStataApp.accountManagementService.checkOrInputPassword()) {
                    List<Long> providedTimestamps = (!toRevokeAllFilesVersions && timestamps.size() > 1)
                            ? new ArrayList<>(selectedTimestamps)
                            : timestamps;
                    
                    String readerToRevoke = AutocompleteCombobox.getComboBoxValue(myComboBox);
                    String[] readersToRevoke = null;
                    
                    if (readerToRevoke != null && !readerToRevoke.isEmpty()) {
                        if (readerToRevoke.endsWith(".group")) {
                            List<String> list;
                            try {
                                list = FileUtils.readLines(new File(AltaStataApp.accountDirPath + File.separator
                                        + "groups" + File.separator + readerToRevoke));
                                readersToRevoke = list.toArray(new String[list.size()]);
                            } catch (IOException e) {
                                Platform.runLater(() -> {
                                    UIUtils.showErrorAlert("Revoke Error", "Cannot read group: " + readerToRevoke);
                                });
                                return null;
                            }
                        } else {
                            readersToRevoke = new String[] { readerToRevoke };
                        }
                    }
                    
                    if (readersToRevoke == null || readersToRevoke.length == 0) {
                        Platform.runLater(() -> {
                            UIUtils.showWarningAlert("Revoke Warning", "Please, select a reader to revoke.");
                        });
                        return null;
                    }
                    
                    MediaPlayerManager.reduceOpacity(AltaStataApp.revokeButton);
                    
                    CloudFileOperationStatus[] revokeResults = account.fileSystemModel()
                            .revokeReaderAccess(container.getLastSelectedObjects(), readersToRevoke, providedTimestamps);
                    
                    final AtomicInteger errors = new AtomicInteger(0);
                    
                    for (int i = 0; i < revokeResults.length; i++) {
                        if (revokeResults[i].getOperationState().equals(OperationState.DONE)) {
                            LOGGER.info("Revoke successful: " + revokeResults[i].getCloudFileVersionPath());
                        } else {
                            LOGGER.info("Revoke failed: " + revokeResults[i].getCloudFileVersionPath() 
                                    + " error: " + revokeResults[i].getError());
                            
                            account.getUserMsgs().add("Revoke error: \n" + revokeResults[i].getError());
                            errors.incrementAndGet();
                        }
                    }
                    
                    if (errors.intValue() > 0) {
                        AltaStataApp.popupHandlerErrorAlert("Revoke",
                                errors.intValue() + " errors detected.\nPlease, see the message box for the details.");
                    }
                    
                    MediaPlayerManager.increaseOpacity(AltaStataApp.revokeButton);
                }
                
                return null;
            }
        };
    }
    
    /**
     * Handles the delete button action for deleting selected files/directories.
     */
    public void handleDeleteButton() {
        final CloudFile[] selectedFilesAndDirectories = container.getLastSelectedObjects();
        
        if (selectedFilesAndDirectories == null) {
            UIUtils.showWarningAlert("Warning", "Please, select files or directories you wish to delete!");
        } else {
            // Show confirmation dialog before deletion
            if (showDeleteConfirmationDialog(selectedFilesAndDirectories)) {
                Platform.runLater(() -> {
                    int lastDirectoryIndex = container.getDirectoryIndexForCloudFile(selectedFilesAndDirectories[0]);
                    container.setCurrentDirectoryIndex(lastDirectoryIndex);
                    
                    // If multiple files or directory is selected, delete all versions of all files
                    boolean toDeleteAllFilesVersions = 
                            selectedFilesAndDirectories.length > 1 || selectedFilesAndDirectories[0].isDirectory();

                    if (toDeleteAllFilesVersions) {
                        // Bulk path: no pre-detect. Core resolves real subtree and processes all versions.
                        Task<Void> task = createDeleteTask(selectedFilesAndDirectories, null);
                        new Thread(task).start();
                    } else {
                        List<Long> timestamps = account.getFileSystemHandler().detectTimestamps(selectedFilesAndDirectories, false);
                        Task<Void> task = createDeleteTask(selectedFilesAndDirectories, timestamps);

                        // If only one file is selected, let user to select the version to delete
                        if (timestamps.size() > 1) {
                            showTimestampSelectionDialog(timestamps, task, true);
                        } else {
                            new Thread(task).start();
                        }
                    }
                });
            }
        }
    }
    
    /**
     * Shows a confirmation dialog before deleting files/directories.
     * @param selectedFiles the files/directories to be deleted
     * @return true if user confirms deletion, false otherwise
     */
    private boolean showDeleteConfirmationDialog(CloudFile[] selectedFiles) {
        Alert confirmDialog = new Alert(Alert.AlertType.CONFIRMATION);
        confirmDialog.setTitle("Confirm Delete");
        
        String headerText;
        String contentText;
        
        if (selectedFiles.length == 1) {
            CloudFile file = selectedFiles[0];
            if (file.isDirectory()) {
                headerText = "Delete Directory";
                contentText = "Are you sure you want to delete the directory '" + file.getName() + "'?\n\n" +
                             "This will permanently delete the directory and all its contents.";
            } else {
                headerText = "Delete File";
                contentText = "Are you sure you want to delete the file '" + file.getName() + "'?\n\n" +
                             "This action cannot be undone.";
            }
        } else {
            headerText = "Delete Multiple Items";
            contentText = "Are you sure you want to delete " + selectedFiles.length + " selected items?\n\n" +
                         "This action cannot be undone and will delete all selected files and directories.";
        }
        
        confirmDialog.setHeaderText(headerText);
        confirmDialog.setContentText(contentText);
        
        // Customize button text
        ButtonType deleteButton = new ButtonType("Delete", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancelButton = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        confirmDialog.getButtonTypes().setAll(deleteButton, cancelButton);
        
        // Style the dialog and make it resizable
        DialogPane dialogPane = confirmDialog.getDialogPane();
        dialogPane.setMinHeight(200);
        dialogPane.setPrefWidth(450);
        
        // Make the dialog resizable
        Stage stage = (Stage) dialogPane.getScene().getWindow();
        stage.setResizable(true);
        stage.setMinWidth(400);
        stage.setMinHeight(180);
        
        Optional<ButtonType> result = confirmDialog.showAndWait();
        return result.isPresent() && result.get() == deleteButton;
    }
    
    /**
     * Creates a task for deleting files from cloud storage.
     */
    private Task<Void> createDeleteTask(CloudFile[] selectedFilesAndDirectories, List<Long> timestamps) {
        return new Task<Void>() {
            @Override
            public Void call() {
                if (AltaStataApp.accountManagementService.checkOrInputPassword()) {
                    MediaPlayerManager.reduceOpacity(AltaStataApp.deleteButton);
                    
                    CloudFileOperationStatus[] deleteResults = account.fileSystemModel()
                            .deleteCloudFiles(selectedFilesAndDirectories, timestamps);
                    
                    final AtomicInteger errors = new AtomicInteger(0);
                    
                    for (int i = 0; i < deleteResults.length; i++) {
                        if (deleteResults[i].getOperationState().equals(OperationState.DONE)) {
                            LOGGER.info("Delete successful: " + deleteResults[i].getCloudFileVersionPath());
                        } else {
                            LOGGER.info("Delete failed: " + deleteResults[i].getCloudFileVersionPath() 
                                    + " error: " + deleteResults[i].getError());
                            
                            account.getUserMsgs().add("Delete error: \n" + deleteResults[i].getError());
                            errors.incrementAndGet();
                        }
                    }
                    if (errors.intValue() > 0) {
                        AltaStataApp.popupHandlerErrorAlert("Delete",
                                errors.intValue() + " errors detected.\nPlease, see the message box for the details.");
                    }
                    
                    MediaPlayerManager.increaseOpacity(AltaStataApp.deleteButton);
                }
                
                return null;
            }
        };
    }
    
    /**
     * Handles the create directory button action.
     */
    public void handleCreateButton() {
        CloudFile parentDir = container.bestMatchingDirForSelection();
        
        if (parentDir != null) {
            final String parentDirAbsolutePath = parentDir.getPath();
            
            TextInputDialog dialog = new TextInputDialog();
            dialog.setTitle("New directory name");
            dialog.setHeaderText("Enter a new directory name");
            
            Optional<String> result = dialog.showAndWait();
            
            if (result.isPresent()) {
                // parentDirAbsolutePath.length() == 0 in case of root
                String newDirName = (parentDirAbsolutePath.length() > 0)
                        ? parentDirAbsolutePath + "/" + result.get()
                        : result.get();
                
                CloudFile newDir = new CloudFile(newDirName, true);
                
                if (account.getFileSystemHandler().listDirectory(newDir.getPath()).isEmpty()) {
                    account.getFileSystemHandler().addCloudFileInUploadingProcess(newDir);
                    container.insertNewFile(parentDir, newDir);
                } else {
                    UIUtils.showWarningAlert("Warning", "The provided directory name already exists.");
                }
            }
        }
    }
    
    /**
     * Creates a rename task for renaming files/directories.
     */
    public Task<Void> createRenameTask(List<CloudFile> listForSubTree, String oldPrefix, String newPrefix) {
        return new Task<Void>() {
            @Override
            public Void call() {
                if (AltaStataApp.accountManagementService.checkOrInputPassword()) {
                    // Convert list to array
                    CloudFile[] arrayForSubTree = listForSubTree.toArray(new CloudFile[listForSubTree.size()]);
                    
                    List<Long> timestampsFilter = account.getFileSystemHandler().detectTimestamps(arrayForSubTree, false);
                    
                    CloudFileOperationStatus[] renameResults = account.fileSystemModel()
                            .renameCloudFiles(arrayForSubTree, oldPrefix, newPrefix, timestampsFilter);
                    
                    final AtomicInteger errors = new AtomicInteger(0);
                    
                    for (int i = 0; i < renameResults.length; i++) {
                        if (renameResults[i].getOperationState().equals(OperationState.DONE)) {
                            LOGGER.info("Rename successful: " + renameResults[i].getCloudFileVersionPath());
                        } else {
                            LOGGER.info("Rename failed: " + renameResults[i].getCloudFileVersionPath() 
                                    + " error: " + renameResults[i].getError());
                            
                            account.getUserMsgs().add("Rename error: \n" + renameResults[i].getError());
                            errors.incrementAndGet();
                        }
                    }
                    
                    container.refreshAllDirectories();
                    
                    if (errors.intValue() > 0) {
                        AltaStataApp.popupHandlerErrorAlert("Rename",
                                errors.intValue() + " errors detected.\nPlease, see the message box for the details.");
                    }
                }
                
                return null;
            }
        };
    }
    
    /**
     * Shows timestamp selection dialog for version selection.
     */
    private void showTimestampSelectionDialog(List<Long> timestamps, Task<Void> task, boolean useCheckBox) {
        VBox vBox = filterTimestamps(timestamps, useCheckBox);
        
        final StackPane stackPane = new StackPane();
        
        stackPane.setOnKeyReleased(ke -> {
            if (ke.getCode().equals(KeyCode.ESCAPE)) {
                AltaStataApp.hidePopup(popup);
            }
        });
        
        Button okButton = new Button("OK");
        okButton.setOnAction(event -> {
            timestamps.clear();
            timestamps.addAll(selectedTimestamps);
            
            AltaStataApp.hidePopup(popup);
            new Thread(task).start();
        });
        
        stackPane.getChildren().add(vBox);
        vBox.getChildren().add(okButton);
        
        AltaStataApp.showPopup(popup, stackPane, false);
    }
    
    /**
     * Creates a timestamp filter UI for version selection.
     */
    private VBox filterTimestamps(List<Long> detectedTimestamps, boolean isCheckBox) {
        selectedTimestamps.clear();
        
        VBox vbh = new VBox();
        vbh.setPadding(UITheme.PADDING_LARGE_DIALOG);
        vbh.setStyle(UITheme.DIALOG_BOX_STYLE);
        vbh.setOpacity(0.9);
        
        String[] names = new String[detectedTimestamps.size()];
        vbh.getChildren().add(0, new Text("Select version[s]"));
        
        if (isCheckBox) {
            CheckBox allCheckBox = new CheckBox("All");
            CheckBox[] checkBox = new CheckBox[detectedTimestamps.size() + 1];
            
            vbh.getChildren().add(1, allCheckBox);
            
            for (int i = 0; i < detectedTimestamps.size(); i++) {
                final int index = i;
                names[index] = CloudFile.DATEFORMAT.format(detectedTimestamps.get(index));
                
                checkBox[index] = new CheckBox(names[index]);
                
                checkBox[index].selectedProperty().addListener((obs, wasOn, isNowOn) -> {
                    LOGGER.debug("filterTimestamps -> " + checkBox[index] + " updated with " + isNowOn);
                    
                    if (isNowOn) {
                        selectedTimestamps.add(detectedTimestamps.get(index));
                    } else {
                        selectedTimestamps.remove(detectedTimestamps.get(index));
                    }
                });
                
                vbh.getChildren().add(index + 2, checkBox[index]);
            }
            
            allCheckBox.selectedProperty().addListener((obs, wasOn, isNowOn) -> {
                for (int i = 0; i < detectedTimestamps.size(); i++) {
                    checkBox[i].setSelected(isNowOn);
                }
            });
        } else {
            ToggleGroup group = new ToggleGroup();
            RadioButton[] radioBox = new RadioButton[detectedTimestamps.size()];
            
            for (int i = 0; i < detectedTimestamps.size(); i++) {
                final int index = i;
                names[index] = CloudFile.DATEFORMAT.format(detectedTimestamps.get(index));
                
                radioBox[index] = new RadioButton(names[index]);
                radioBox[index].selectedProperty().addListener((obs, wasOn, isNowOn) -> {
                    if (isNowOn) {
                        selectedTimestamps.add(detectedTimestamps.get(index));
                    } else {
                        selectedTimestamps.remove(detectedTimestamps.get(index));
                    }
                });
                
                radioBox[index].setToggleGroup(group);
                vbh.getChildren().add(index + 1, radioBox[index]);
            }
        }
        
        return vbh;
    }
    
    /**
     * Shows directory selection dialog for downloads.
     */
    private File selectDirectoryForDownload() {
        try {
            DirectoryChooser directoryChooser = new DirectoryChooser();
            directoryChooser.setTitle("Select a folder to download files");
            
            return directoryChooser.showDialog(stage);
        } catch (UnsupportedOperationException ex) {
            // For Android
            return new File("/storage/emulated/0/Download");
        }
    }
}
