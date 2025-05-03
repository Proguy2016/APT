package com.project;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;
import com.project.ui.EditorController;
import com.project.ui.LoginDialog;
import com.project.ui.DocumentSelectionDialog;
import javafx.util.Pair;

import java.io.IOException;
import java.util.UUID;

public class Main extends Application {
    private static String userId = null;
    private static String username = null;
    private static String documentId = null;
    private static String documentTitle = null;
    
    @Override
    public void start(Stage primaryStage) {
        try {
            // Show login dialog if user is not authenticated
            if (userId == null) {
                Pair<String, String> userInfo = LoginDialog.showLoginDialog();
                if (userInfo == null) {
                    // User cancelled login
                    System.exit(0);
                    return;
                }
                
                username = userInfo.getKey();
                userId = userInfo.getValue();
            }
            
            // Show document selection dialog
            Pair<String, String> documentInfo = DocumentSelectionDialog.showDocumentSelectionDialog(userId);
            if (documentInfo == null) {
                // User cancelled document selection
                System.exit(0);
                return;
            }
            
            documentId = documentInfo.getKey();
            documentTitle = documentInfo.getValue();
            
            // Load the FXML file
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/editor.fxml"));
            Parent root = loader.load();
            
            // Get the controller and set the user ID and document info
            EditorController controller = loader.getController();
            controller.setUserId(userId);
            controller.setUsername(username);
            controller.setDocumentInfo(documentId, documentTitle);
            
            // Set up the scene
            Scene scene = new Scene(root);
            
            // Set up the stage
            primaryStage.setTitle("Collaborative Text Editor - " + documentTitle + " (" + username + ")");
            primaryStage.setScene(scene);
            primaryStage.show();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static void main(String[] args) {
        // Check if a user ID was provided as a command-line argument (for testing)
        if (args.length > 0) {
            userId = args[0];
            username = "Test User";
        }
        
        launch(args);
    }
} 