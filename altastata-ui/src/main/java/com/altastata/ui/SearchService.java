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

import com.altastata.filesystem.common.CloudFile;
import com.altastata.filesystem.common.FileSystemHandler;
import com.altastata.utils.Account;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;

/**
 * Service class responsible for handling search functionality including
 * file filtering and search result management. This class was extracted 
 * from AltaStataApp to improve separation of concerns and centralize 
 * search-related operations.
 */
public class SearchService {
    
    private static final Logger LOGGER = LoggerFactory.getLogger(SearchService.class);
    
    private final Account account;
    private final NavigationPane container;
    
    /**
     * Creates a new SearchService.
     *
     * @param account The account instance for accessing file system
     * @param container The navigation pane container for displaying results
     */
    public SearchService(Account account, NavigationPane container) {
        this.account = account;
        this.container = container;
    }
    
    /**
     * Handles search operations based on the provided filter value.
     * If the filter is empty, restores the normal directory view.
     * If the filter contains text, performs a search and displays results.
     *
     * @param filterVal The search filter string
     */
    public void handleSearchByKey(String filterVal) {
        if (filterVal.trim().equals("")) {
            // Clear search and return to normal directory view
            restoreNormalDirectoryView();
        } else {
            // Perform search and display results
            performSearch(filterVal);
        }
        
        // Reset to root directory index
        container.setCurrentDirectoryIndex(0);
    }
    
    /**
     * Restores the normal directory view by recreating the directory list
     * from the root directory.
     */
    private void restoreNormalDirectoryView() {
        container.createAndPopulateDirectoryList(
            AltaStataApp.INITIAL_COLUMN_NUMBER_BEFORE_INCREMENTATION,
            new CloudFile(FileSystemHandler.INIT_DIR, true)
        );
        
        // Handle mobile navigation if needed
        if (NavigationPane.isMobileNavigation) {
            container.mobileClickAndMoveForward(container.getCurrentDirectoryIndex());
        }
    }
    
    /**
     * Performs a search operation using the provided filter value
     * and displays the search results.
     *
     * @param filterVal The search filter string
     */
    private void performSearch(String filterVal) {
        LOGGER.debug("Performing search with filter: " + filterVal);
        
        // Execute search using the file system handler
        Set<CloudFile> found = account.getFileSystemHandler().searchFiles(filterVal);
        
        LOGGER.debug("Search found " + found.size() + " results");
        
        // Display search results in the container
        container.createAndPopulateSearchList(found);
    }
    
    /**
     * Clears the current search and returns to the normal directory view.
     * This is a convenience method for clearing search results.
     */
    public void clearSearch() {
        handleSearchByKey("");
    }
    
    /**
     * Checks if the current view is showing search results.
     * This can be used by UI components to determine the current state.
     *
     * @return true if currently showing search results, false if showing normal directory view
     */
    public boolean isSearchActive() {
        // This could be enhanced to track search state if needed
        // For now, we can check if the container is in search mode
        return false; // TODO: Implement search state tracking if needed
    }
}
