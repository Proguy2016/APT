package com.project.ui;

import com.project.network.DatabaseService;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.util.Pair;
import org.bson.Document;
import org.bson.types.ObjectId;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.prefs.Preferences;

/**
 * Dialog for selecting a document to open.
 */
public class DocumentSelectionDialog {
    private static final String PREF_RECENT_CODES = "recentSessionCodes";
    private static final int MAX_RECENT_CODES = 20;
    
    /**
     * Shows a document selection dialog.
     * @param userId The current user ID.
     * @return A pair containing the document ID and title, or null if canceled.
     */
    public static Pair<String, String> showDocumentSelectionDialog(String userId) {
        // Create the custom dialog
        Dialog<Pair<String, String>> dialog = new Dialog<>();
        dialog.setTitle("Open Document");
        dialog.setHeaderText("Select a document to open or join a shared session");
        
        // Set the button types
        ButtonType openButtonType = new ButtonType("Open", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(openButtonType, ButtonType.CANCEL);
        
        // Create the grid and tabs
        TabPane tabPane = new TabPane();
        
        // Create the documents tab
        Tab documentsTab = new Tab("My Documents");
        documentsTab.setClosable(false);
        
        VBox documentsBox = new VBox(10);
        documentsBox.setPadding(new Insets(20, 150, 10, 10));
        
        ListView<DocumentItem> documentsListView = new ListView<>();
        documentsListView.setPrefHeight(300);
        
        // Get user's documents
        List<org.bson.Document> documents = DatabaseService.getInstance().getDocumentsByOwner(userId);
        for (org.bson.Document doc : documents) {
            String id = doc.get("_id").toString();
            String title = doc.getString("title");
            if (title == null || title.isEmpty()) {
                title = "Untitled Document";
            }
            
            // Add to the list view
            documentsListView.getItems().add(new DocumentItem(id, title));
        }
        
        // Add a button to create a new document
        Button newDocumentButton = new Button("New Document");
        newDocumentButton.setOnAction(e -> {
            TextInputDialog textDialog = new TextInputDialog("Untitled Document");
            textDialog.setTitle("New Document");
            textDialog.setHeaderText("Create a new document");
            textDialog.setContentText("Enter document title:");
            
            Optional<String> result = textDialog.showAndWait();
            if (result.isPresent()) {
                String title = result.get();
                if (title.isEmpty()) {
                    title = "Untitled Document";
                }
                
                // Add to list view
                String docId = DatabaseService.getInstance().createDocument(title, userId);
                documentsListView.getItems().add(new DocumentItem(docId, title));
                
                // Select the new document
                documentsListView.getSelectionModel().selectLast();
            }
        });
        
        documentsBox.getChildren().addAll(documentsListView, newDocumentButton);
        documentsTab.setContent(documentsBox);
        
        // Create the shared documents tab
        Tab sharedTab = new Tab("Join Session");
        sharedTab.setClosable(false);
        
        GridPane sharedGrid = new GridPane();
        sharedGrid.setHgap(10);
        sharedGrid.setVgap(10);
        sharedGrid.setPadding(new Insets(20, 150, 10, 10));
        
        TextField codeField = new TextField();
        codeField.setPromptText("Enter session code");
        
        // Radio buttons for editor/viewer role
        ToggleGroup roleGroup = new ToggleGroup();
        RadioButton editorRadio = new RadioButton("Join as Editor");
        RadioButton viewerRadio = new RadioButton("Join as Viewer");
        editorRadio.setToggleGroup(roleGroup);
        viewerRadio.setToggleGroup(roleGroup);
        viewerRadio.setSelected(true);
        
        VBox roleBox = new VBox(5, editorRadio, viewerRadio);
        
        // Recent sessions list
        ListView<String> recentSessionsListView = new ListView<>();
        List<String> recentCodes = loadRecentSessionCodes();
        recentSessionsListView.getItems().addAll(recentCodes);
        recentSessionsListView.setPrefHeight(200);
        
        // When a recent code is selected, populate the code field
        recentSessionsListView.getSelectionModel().selectedItemProperty().addListener(
            (observable, oldValue, newValue) -> {
                if (newValue != null) {
                    codeField.setText(newValue);
                }
            });
        
        Label recentLabel = new Label("Recent Sessions:");
        
        sharedGrid.add(new Label("Session Code:"), 0, 0);
        sharedGrid.add(codeField, 1, 0);
        sharedGrid.add(roleBox, 1, 1);
        sharedGrid.add(recentLabel, 0, 2);
        sharedGrid.add(recentSessionsListView, 0, 3, 2, 1);
        
        sharedTab.setContent(sharedGrid);
        
        // Add tabs to the tab pane
        tabPane.getTabs().addAll(documentsTab, sharedTab);
        
        // Add the tab pane to the dialog
        dialog.getDialogPane().setContent(tabPane);
        
        // Request focus on the appropriate field
        codeField.requestFocus();
        
        // Convert the result to a document ID-title pair when the open button is clicked
        dialog.setResultConverter(dialogButton -> {
            if (dialogButton == openButtonType) {
                // Check which tab is selected
                if (tabPane.getSelectionModel().getSelectedItem() == documentsTab) {
                    DocumentItem selectedItem = documentsListView.getSelectionModel().getSelectedItem();
                    if (selectedItem != null) {
                        return new Pair<>(selectedItem.getId(), selectedItem.getTitle());
                    }
                } else {
                    // Handle join session tab
                String code = codeField.getText().trim();
                    if (!code.isEmpty()) {
                        // Save this code for future use
                        saveRecentSessionCode(code);
                        
                        boolean asEditor = editorRadio.isSelected();
                        // Return special format for join requests: JOIN:CODE:ROLE
                        return new Pair<>(
                            "JOIN:" + code + ":" + (asEditor ? "EDITOR" : "VIEWER"), 
                            "Session " + code
                        );
                    }
                }
            }
            return null;
        });
        
        // Show the dialog and process the result
        Optional<Pair<String, String>> result = dialog.showAndWait();
        return result.orElse(null);
    }
    
    /**
     * Saves a session code to recent codes in preferences
     * @param code the session code to save
     */
    public static void saveRecentSessionCode(String code) {
        try {
            if (code == null || code.isEmpty()) {
                return;
            }
            
            Preferences prefs = Preferences.userNodeForPackage(DocumentSelectionDialog.class);
            
            // Get existing codes
            List<String> codes = loadRecentSessionCodes();
            
            // Remove if already exists (to move to top)
            codes.remove(code);
            
            // Add to beginning
            codes.add(0, code);
            
            // Limit size
            while (codes.size() > MAX_RECENT_CODES) {
                codes.remove(codes.size() - 1);
            }
            
            // Save back to preferences
            StringBuilder sb = new StringBuilder();
            for (String c : codes) {
                if (sb.length() > 0) {
                    sb.append(',');
                }
                sb.append(c);
            }
            
            prefs.put(PREF_RECENT_CODES, sb.toString());
        } catch (Exception e) {
            System.err.println("Error saving recent session code: " + e.getMessage());
        }
    }
    
    /**
     * Loads recent session codes from preferences
     * @return list of recent session codes
     */
    public static List<String> loadRecentSessionCodes() {
        List<String> codes = new ArrayList<>();
        try {
            Preferences prefs = Preferences.userNodeForPackage(DocumentSelectionDialog.class);
            String savedCodes = prefs.get(PREF_RECENT_CODES, "");
            
            if (!savedCodes.isEmpty()) {
                String[] parts = savedCodes.split(",");
                for (String part : parts) {
                    if (!part.trim().isEmpty()) {
                        codes.add(part.trim());
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Error loading recent session codes: " + e.getMessage());
        }
        return codes;
    }
    
    /**
     * Helper class to represent a document in the list view
     */
    private static class DocumentItem {
        private final String id;
        private final String title;
        
        public DocumentItem(String id, String title) {
            this.id = id;
            this.title = title;
        }
        
        public String getId() {
            return id;
        }
        
        public String getTitle() {
            return title;
        }
        
        @Override
        public String toString() {
            return title;
        }
    }
} 