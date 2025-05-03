package com.project.ui;

import com.project.network.DatabaseService;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.util.Pair;

import java.util.Optional;

/**
 * Dialog for user login and registration.
 */
public class LoginDialog {
    
    /**
     * Shows a login dialog and returns the authenticated user details.
     * @return A pair containing the username and user ID if login was successful, null otherwise.
     */
    public static Pair<String, String> showLoginDialog() {
        // Create the custom dialog
        Dialog<Pair<String, String>> dialog = new Dialog<>();
        dialog.setTitle("Login");
        dialog.setHeaderText("Welcome to Collaborative Text Editor");
        
        // Set the button types
        ButtonType loginButtonType = new ButtonType("Login", ButtonBar.ButtonData.OK_DONE);
        ButtonType registerButtonType = new ButtonType("Register", ButtonBar.ButtonData.OTHER);
        dialog.getDialogPane().getButtonTypes().addAll(loginButtonType, registerButtonType, ButtonType.CANCEL);
        
        // Create the grid and add the components
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(20, 150, 10, 10));
        
        TextField usernameField = new TextField();
        usernameField.setPromptText("Username");
        PasswordField passwordField = new PasswordField();
        passwordField.setPromptText("Password");
        
        grid.add(new Label("Username:"), 0, 0);
        grid.add(usernameField, 1, 0);
        grid.add(new Label("Password:"), 0, 1);
        grid.add(passwordField, 1, 1);
        
        // Enable/Disable login button depending on whether a username was entered
        Node loginButton = dialog.getDialogPane().lookupButton(loginButtonType);
        loginButton.setDisable(true);
        
        // Do validation
        usernameField.textProperty().addListener((observable, oldValue, newValue) -> {
            loginButton.setDisable(newValue.trim().isEmpty());
        });
        
        dialog.getDialogPane().setContent(grid);
        
        // Request focus on the username field by default
        Platform.runLater(usernameField::requestFocus);
        
        // Convert the result to a username-password pair when the login button is clicked
        dialog.setResultConverter(dialogButton -> {
            if (dialogButton == loginButtonType) {
                // Try to authenticate
                String username = usernameField.getText().trim();
                String password = passwordField.getText();
                
                String userId = DatabaseService.getInstance().authenticateUser(username, password);
                if (userId != null) {
                    return new Pair<>(username, userId);
                } else {
                    // Show an error alert
                    Alert alert = new Alert(Alert.AlertType.ERROR);
                    alert.setTitle("Authentication Failed");
                    alert.setHeaderText("Invalid username or password");
                    alert.setContentText("Please try again with correct credentials.");
                    alert.showAndWait();
                    return null;
                }
            } else if (dialogButton == registerButtonType) {
                // Show the registration dialog
                return showRegistrationDialog();
            }
            return null;
        });
        
        Optional<Pair<String, String>> result = dialog.showAndWait();
        return result.orElse(null);
    }
    
    /**
     * Shows a registration dialog.
     * @return A pair containing the username and user ID if registration was successful, null otherwise.
     */
    private static Pair<String, String> showRegistrationDialog() {
        // Create the custom dialog
        Dialog<Pair<String, String>> dialog = new Dialog<>();
        dialog.setTitle("Register");
        dialog.setHeaderText("Create a new account");
        
        // Set the button types
        ButtonType registerButtonType = new ButtonType("Register", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(registerButtonType, ButtonType.CANCEL);
        
        // Create the grid and add the components
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(20, 150, 10, 10));
        
        TextField usernameField = new TextField();
        usernameField.setPromptText("Username");
        PasswordField passwordField = new PasswordField();
        passwordField.setPromptText("Password");
        PasswordField confirmPasswordField = new PasswordField();
        confirmPasswordField.setPromptText("Confirm Password");
        
        grid.add(new Label("Username:"), 0, 0);
        grid.add(usernameField, 1, 0);
        grid.add(new Label("Password:"), 0, 1);
        grid.add(passwordField, 1, 1);
        grid.add(new Label("Confirm Password:"), 0, 2);
        grid.add(confirmPasswordField, 1, 2);
        
        // Enable/Disable register button depending on whether input is valid
        Node registerButton = dialog.getDialogPane().lookupButton(registerButtonType);
        registerButton.setDisable(true);
        
        // Add a label to show validation errors
        Label errorLabel = new Label();
        errorLabel.setStyle("-fx-text-fill: red;");
        grid.add(errorLabel, 1, 3);
        
        // Do validation
        Runnable validateInput = () -> {
            String username = usernameField.getText().trim();
            String password = passwordField.getText();
            String confirmPassword = confirmPasswordField.getText();
            
            if (username.isEmpty()) {
                errorLabel.setText("Username cannot be empty");
                registerButton.setDisable(true);
            } else if (password.isEmpty()) {
                errorLabel.setText("Password cannot be empty");
                registerButton.setDisable(true);
            } else if (!password.equals(confirmPassword)) {
                errorLabel.setText("Passwords do not match");
                registerButton.setDisable(true);
            } else {
                errorLabel.setText("");
                registerButton.setDisable(false);
            }
        };
        
        usernameField.textProperty().addListener((observable, oldValue, newValue) -> validateInput.run());
        passwordField.textProperty().addListener((observable, oldValue, newValue) -> validateInput.run());
        confirmPasswordField.textProperty().addListener((observable, oldValue, newValue) -> validateInput.run());
        
        dialog.getDialogPane().setContent(grid);
        
        // Request focus on the username field by default
        Platform.runLater(usernameField::requestFocus);
        
        // Convert the result when the register button is clicked
        dialog.setResultConverter(dialogButton -> {
            if (dialogButton == registerButtonType) {
                String username = usernameField.getText().trim();
                String password = passwordField.getText();
                
                // Try to register
                boolean registered = DatabaseService.getInstance().registerUser(username, password);
                if (registered) {
                    // If registration succeeded, try to authenticate to get the user ID
                    String userId = DatabaseService.getInstance().authenticateUser(username, password);
                    if (userId != null) {
                        Alert alert = new Alert(Alert.AlertType.INFORMATION);
                        alert.setTitle("Registration Successful");
                        alert.setHeaderText("Your account has been created");
                        alert.setContentText("You can now log in with your credentials.");
                        alert.showAndWait();
                        return new Pair<>(username, userId);
                    }
                } else {
                    // Show an error alert
                    Alert alert = new Alert(Alert.AlertType.ERROR);
                    alert.setTitle("Registration Failed");
                    alert.setHeaderText("Could not create account");
                    alert.setContentText("Username may already be taken.");
                    alert.showAndWait();
                }
            }
            return null;
        });
        
        Optional<Pair<String, String>> result = dialog.showAndWait();
        return result.orElse(null);
    }
} 