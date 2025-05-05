package com.project;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.stage.Stage;
import com.project.ui.EditorController;
import com.project.ui.LoginDialog;
import com.project.ui.DocumentSelectionDialog;
import javafx.util.Pair;
import com.project.network.CollaborativeEditorServer;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;
import java.util.prefs.Preferences;
import java.util.Date;
import java.io.PrintWriter;
import java.io.StringWriter;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.GridPane;

public class Main extends Application {
    private static String userId = null;
    private static String username = null;
    private static String documentId = null;
    private static String documentTitle = null;
    
    // Preferences keys for storing last login
    private static final String PREF_LAST_USERNAME = "lastUsername";
    private static final String PREF_LAST_USER_ID = "lastUserId";
    
    @Override
    public void start(Stage primaryStage) {
        try {
            // Load the last username from preferences
            Preferences prefs = Preferences.userNodeForPackage(Main.class);
            String lastUsername = prefs.get(PREF_LAST_USERNAME, null);
            
            // First, show login or registration dialog
            Pair<String, String> userInfo = LoginDialog.showLoginDialog(lastUsername);
            if (userInfo != null) {
                try {
                    username = userInfo.getKey();
                    userId = userInfo.getValue();
                    
                    // Save the username and user ID in preferences
                    prefs.put(PREF_LAST_USERNAME, username);
                    prefs.put(PREF_LAST_USER_ID, userId);
                    
                    // Show success message
                    System.out.println("Successfully logged in as: " + username + " (ID: " + userId + ")");
                    
                    // Get last login time if available
                    Date lastLogin = com.project.network.DatabaseService.getInstance().getLastLoginTime(userId);
                    if (lastLogin != null) {
                        System.out.println("Last login: " + lastLogin);
                    }
                    
                    // Load the main editor UI
                    FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/editor.fxml"));
                    Parent root = loader.load();
                    
                    // Get the controller and set the user info
                    EditorController controller = loader.getController();
                    controller.setUserId(userId);
                    controller.setUsername(username);
                    
                    // Set window title
                    primaryStage.setTitle("Collaborative Text Editor - " + username);
                    
                    // Set min size and default size
                    primaryStage.setMinWidth(800);
                    primaryStage.setMinHeight(600);
                    primaryStage.setScene(new Scene(root, 1024, 768));
                    
                    // Show the window
                    primaryStage.show();
                    
                    // Either show document selection dialog or open the document
                    if (documentId == null) {
                        handleShowDocumentSelection(controller, primaryStage);
                    } else {
                        controller.setDocumentInfo(documentId, documentTitle);
                    }
                    
                    // Register window close handler
                    primaryStage.setOnCloseRequest(event -> {
                        System.out.println("Shutting down application...");
                        // Clean up resources
                        try {
                            com.project.network.DatabaseService.getInstance().close();
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    });
                } catch (Exception e) {
                    showError("Error loading editor", e.getMessage(), e);
                }
            } else {
                // User canceled login
                Platform.exit();
            }
        } catch (Exception e) {
            showError("Startup Error", "An error occurred during application startup", e);
            Platform.exit();
        }
    }
    
    /**
     * Shows the document selection dialog and sets the selected document in the controller.
     * @param controller The editor controller.
     * @param stage The primary stage.
     */
    private void handleShowDocumentSelection(EditorController controller, Stage stage) {
        Pair<String, String> documentInfo = DocumentSelectionDialog.showDocumentSelectionDialog(userId);
        if (documentInfo != null) {
            String key = documentInfo.getKey();
            
            // Check if this is a join request
            if (key != null && key.startsWith("JOIN:")) {
                // Parse the join information - format is JOIN:CODE:ROLE
                String[] parts = key.split(":");
                if (parts.length >= 3) {
                    String code = parts[1];
                    boolean isEditorRole = "EDITOR".equals(parts[2]);
                    
                    // For join requests, we don't have a document ID yet
                    // Let the controller handle joining (it will create a document if needed)
                    System.out.println("Handling join request - Code: " + code + ", Role: " + (isEditorRole ? "EDITOR" : "VIEWER"));
                    controller.setJoinSessionInfo(code, isEditorRole);
                }
            } else {
                // Normal document open
                documentId = key;
                documentTitle = documentInfo.getValue();
                
                // Set window title
                stage.setTitle("Collaborative Text Editor - " + documentTitle + " (" + username + ")");
                
                // Set document info in controller
                controller.setDocumentInfo(documentId, documentTitle);
            }
        } else {
            // User canceled, create a default document
            String defaultTitle = "Untitled Document";
            String defaultId = com.project.network.DatabaseService.getInstance().createDocument(defaultTitle, userId);
            
            // Set window title
            stage.setTitle("Collaborative Text Editor - " + defaultTitle + " (" + username + ")");
            
            // Set document info in controller
            controller.setDocumentInfo(defaultId, defaultTitle);
        }
    }

    public static void main(String[] args) {
        CollaborativeEditorServer server = new CollaborativeEditorServer();
        server.start();
        System.out.println("Server started successfully on port " + server.getPort());
        
        // Check if a user ID was provided as a command-line argument (for testing)
        if (args.length > 0) {
            userId = args[0];
            username = "Test User";
        }
        
        launch(args);
    }

    /**
     * Shows an error dialog.
     * @param title The error title.
     * @param message The error message.
     * @param exception The exception that caused the error.
     */
    private void showError(String title, String message, Exception exception) {
        exception.printStackTrace();
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(title);
        alert.setHeaderText(title);
        alert.setContentText(message);
        
        // Create expandable Exception section
        if (exception != null) {
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            exception.printStackTrace(pw);
            String exceptionText = sw.toString();

            Label label = new Label("Exception stacktrace:");
            TextArea textArea = new TextArea(exceptionText);
            textArea.setEditable(false);
            textArea.setWrapText(true);
            textArea.setMaxWidth(Double.MAX_VALUE);
            textArea.setMaxHeight(Double.MAX_VALUE);

            GridPane expContent = new GridPane();
            expContent.setMaxWidth(Double.MAX_VALUE);
            expContent.add(label, 0, 0);
            expContent.add(textArea, 0, 1);

            alert.getDialogPane().setExpandableContent(expContent);
        }
        
        alert.showAndWait();
    }
} 