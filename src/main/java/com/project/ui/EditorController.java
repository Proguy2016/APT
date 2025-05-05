package com.project.ui;

import com.project.crdt.CRDTCharacter;
import com.project.crdt.CRDTDocument;
import com.project.crdt.Position;
import com.project.network.NetworkClient;
import com.project.network.Operation;
import com.project.network.DatabaseService;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.geometry.Bounds;
import javafx.scene.control.*;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.AnchorPane;
import javafx.scene.paint.Color;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.util.Pair;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.Optional;
import java.util.function.Supplier;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

public class EditorController {
    @FXML
    private TextArea editorArea;
    
    @FXML
    private ListView<String> usersListView;
    
    @FXML
    private TextField editorCodeField;
    
    @FXML
    private TextField viewerCodeField;
    
    @FXML
    private Label statusLabel;
    
    @FXML
    private AnchorPane editorContainer;
    
    // Add a label for word count
    @FXML
    private Label wordCountLabel;
    
    // Observable list for the users
    private ObservableList<String> users = FXCollections.observableArrayList();
    
    // CRDT document
    private CRDTDocument document;
    
    // Network client
    private NetworkClient networkClient;
    
    // User role
    private boolean isEditor = true; // By default, the user is an editor
    
    // Cursor markers for other users
    private Map<String, CursorMarker> cursorMarkers = new HashMap<>();
    
    // Custom user ID (can be set from Main)
    private String userId = null;
    
    // User's username
    private String username = null;
    
    // Document information
    private String documentId = null;
    private String documentTitle = null;
    
    // Auto-save timer
    private java.util.Timer autoSaveTimer;
    
    // Performance optimization flags
    private AtomicBoolean isUpdatingText = new AtomicBoolean(false);
    private AtomicBoolean isSendingCursor = new AtomicBoolean(false);
    private long lastCursorSendTime = 0;
    private static final long CURSOR_THROTTLE_MS = 100; // Throttle cursor updates
    
    // Batch operation for improved performance
    private StringBuilder pendingInserts = new StringBuilder();
    private int pendingInsertBasePosition = -1;
    private java.util.Timer batchTimer;
    
    // Available colors for cursors
    private final Color[] cursorColors = {
        Color.web("#4285f4"), // Blue
        Color.web("#ea4335"), // Red
        Color.web("#fbbc05"), // Yellow
        Color.web("#34a853")  // Green
    };
    
    // Map of user IDs to usernames
    private Map<String, String> userMap = new HashMap<>();
    
    // Flag to track whether initialize() has been called
    private boolean initialized = false;
    
    /**
     * Sets the userId for the editor.
     * @param userId The user ID
     */
    public void setUserId(String userId) {
        this.userId = userId;
        System.out.println("Initialized with userId: " + userId);
        
        // Initialize the network client with user ID
        if (networkClient == null) {
            networkClient = new NetworkClient(userId);
            setupNetworkListeners();
        } else {
            networkClient.setUserId(userId);
        }
    }
    
    /**
     * Sets the username for the editor.
     * @param username The username
     */
    public void setUsername(String username) {
        this.username = username;
        System.out.println("Initialized with username: " + username);
        
        // Make sure our own username is in the user map
        if (userMap == null) {
            userMap = new HashMap<>();
        }
            userMap.put(userId, username);
        
        // Update the network client with the username
        if (networkClient != null) {
            networkClient.setUsername(username);
            
            // If already connected, send username update
            if (networkClient.isConnected()) {
                sendUsernameUpdate();
            }
        }
        
        // Update the UI
        Platform.runLater(() -> {
            users.clear();
            users.add(username + " (you)");
        });
    }
    
    /**
     * Sends a username update to the server.
     */
    private void sendUsernameUpdate() {
        if (networkClient != null && networkClient.isConnected() && username != null) {
            JsonObject message = new JsonObject();
            message.addProperty("type", "username_update");
            message.addProperty("userId", userId);
            message.addProperty("username", username);
            networkClient.getWebSocketClient().send(new Gson().toJson(message));
            System.out.println("Sent username update to server: " + username);
        }
    }
    
    /**
     * Sets the document information. If initialize() has already been called,
     * this will immediately load the document.
     * @param documentId the document ID
     * @param documentTitle the document title
     */
    public void setDocumentInfo(String documentId, String documentTitle) {
        this.documentId = documentId;
        this.documentTitle = documentTitle;
        
        // If already initialized, load the document now
        if (initialized && documentId != null) {
            try {
                // Give a little time for network connection to establish
                Thread.sleep(200);
                loadDocumentContent();
            } catch (Exception e) {
                updateStatus("Error loading document: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }
    
    /**
     * Sets session join information directly.
     * This is used when joining a session from the document selection dialog.
     * @param sessionCode The session code to join
     * @param isEditorRole Whether to join as editor or viewer
     */
    public void setJoinSessionInfo(String sessionCode, boolean isEditorRole) {
        if (sessionCode == null || sessionCode.isEmpty()) {
            updateStatus("Invalid session code");
            return;
        }
        
        System.out.println("Setting join session info: Code=" + sessionCode + ", Role=" + (isEditorRole ? "EDITOR" : "VIEWER"));
        
        // Make sure we're initialized before joining
        if (!initialized) {
            // If not initialized yet, save the info for when initialization completes
            new Thread(() -> {
                // Wait for initialization to complete
                while (!initialized) {
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                
                // Now join the session
                Platform.runLater(() -> joinExistingSession(sessionCode, isEditorRole));
            }).start();
        } else {
            // Already initialized, join immediately
            joinExistingSession(sessionCode, isEditorRole);
        }
    }
    
    @FXML
    public void initialize() {
        // Prevent double initialization
        if (initialized) {
            return;
        }
        
        try {
            // Initialize the CRDT document with a site ID (user ID or random UUID)
            String siteId = (userId != null) ? userId : UUID.randomUUID().toString();
            document = new CRDTDocument(siteId);
            
            // Initialize the network client with both userId and username
            networkClient = new NetworkClient(siteId, username);
            
            System.out.println("Initialized with username: " + username + ", userId: " + siteId);
            this.userId = siteId;
            
            // Add the network client's operation listener
            networkClient.addOperationListener(this::handleRemoteOperation);
            
            // Add presence listener to update user list
            networkClient.addPresenceListener(this::updateUserList);
            
            // Set up listeners
            setupNetworkListeners();
            setupEditorListeners();
            
            // Initialize user list
            usersListView.setItems(users);
            
            // Create word count label if it doesn't exist
            if (wordCountLabel == null) {
                wordCountLabel = new Label("Words: 0 | Characters: 0 | Lines: 0");
                wordCountLabel.setStyle("-fx-text-fill: #555; -fx-font-size: 12px; -fx-padding: 5px; -fx-background-color: #f5f5f5; -fx-border-color: #ddd; -fx-border-width: 1px; -fx-border-radius: 3px;");
                
                // If there's a container for this, add it properly
                // Otherwise, we'll need to add it to the scene in the FXML
                if (editorContainer != null && !editorContainer.getChildren().contains(wordCountLabel)) {
                    editorContainer.getChildren().add(wordCountLabel);
                    AnchorPane.setBottomAnchor(wordCountLabel, 10.0);
                    AnchorPane.setRightAnchor(wordCountLabel, 15.0);
                }
            }
            
            // Connect to the network - all documents are always online
            boolean connected = networkClient.connect();
            if (connected) {
                updateStatus("Connected to network as " + (username != null ? username : siteId));
                
                // Setup auto-save timer (save every 30 seconds)
                setupAutoSaveTimer();
                
                // Setup batch timer for performance
                setupBatchTimer();
                
                // Set flag to prevent double initialization
                initialized = true;
                
                // If document info is already set, load it now
                if (documentId != null) {
                    System.out.println("Document ID already set, loading content: " + documentId);
                    // Use a brief delay to allow connection to establish
                    new Thread(() -> {
                        try {
                            Thread.sleep(300);
                            Platform.runLater(this::loadDocumentContent);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }).start();
                }
            } else {
                updateStatus("Failed to connect to network");
            }
            
            // Make sure editorContainer contains editorArea
            if (!editorContainer.getChildren().contains(editorArea)) {
                editorContainer.getChildren().add(editorArea);
                AnchorPane.setTopAnchor(editorArea, 0.0);
                AnchorPane.setRightAnchor(editorArea, 0.0);
                AnchorPane.setBottomAnchor(editorArea, 0.0);
                AnchorPane.setLeftAnchor(editorArea, 0.0);
            }
            
            // Initialize word count
            updateWordCount();
        } catch (Exception e) {
            updateStatus("Error initializing editor: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    private void setupNetworkListeners() {
        // Add operation listener
        networkClient.addOperationListener(this::handleRemoteOperation);
        
        // Add presence listener
        networkClient.addPresenceListener(this::updateUserList);
        
        // Add error listener
        networkClient.addErrorListener(this::updateStatus);
        
        // Add code listener
        networkClient.addCodeListener(codes -> {
            Platform.runLater(() -> {
                // Log the received codes
                System.out.println("Received session codes - Editor: " + codes.getEditorCode() + ", Viewer: " + codes.getViewerCode());
                
                // Update the UI fields based on user role
                if (isEditor) {
                    editorCodeField.setText(codes.getEditorCode());
                    viewerCodeField.setText(codes.getViewerCode());
                } else {
                    // For viewers, we still store the codes but don't display the editor code
                    // in the UI to prevent confusion
                    viewerCodeField.setText(codes.getViewerCode());
                }
                
                // Save the codes with the document in the database
                if (documentId != null) {
                    boolean saved = DatabaseService.getInstance().updateDocumentWithSession(
                        documentId, document.getText(), codes.getEditorCode(), codes.getViewerCode());
                    
                    if (saved) {
                        System.out.println("Session codes saved with document ID: " + documentId);
                    } else {
                        System.err.println("Failed to save session codes with document ID: " + documentId);
                    }
                }
            });
        });
    }
    
    private void setupEditorListeners() {
        // Add a listener for key typing events
        editorArea.addEventFilter(KeyEvent.KEY_TYPED, event -> {
            if (!isEditor) {
                // Prevent editing for viewers
                event.consume();
                return;
            }
            
            // Get the character typed
            String ch = event.getCharacter();
            if (ch == null || ch.isEmpty()) {
                return;
            }
            
            char c = ch.charAt(0);
            
            // Ignore control characters (e.g., backspace, delete) and other non-printable characters
            if (c < 32 && c != '\t') {
                return;
            }
            
            // Add additional safety check for other potentially problematic characters
            if (c >= 127 && c <= 159) { // Extended ASCII control characters
                event.consume();
                return;
            }
            
            // Get the current caret position
            int caretPosition = editorArea.getCaretPosition();
            
            // Add to batch for better performance
            addToBatchInsert(caretPosition, c);
            
            // Consume the event to prevent the default handling
            event.consume();
        });
        
        // Add a listener for key press events (for delete/backspace)
        editorArea.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (!isEditor) {
                // Prevent editing for viewers
                event.consume();
                return;
            }
            
            // Process batch before handling these special keys
            processBatchInserts();
            
            switch (event.getCode()) {
                case BACK_SPACE:
                    handleBackspace();
                    event.consume();
                    break;
                case DELETE:
                    handleDelete();
                    event.consume();
                    break;
                case ENTER:
                    handleEnter();
                    event.consume();
                    break;
            }
        });
        
        // Add a listener for cursor position changes
        editorArea.caretPositionProperty().addListener((obs, oldPos, newPos) -> {
            // Only send cursor updates when user moves the cursor manually
            // Don't send cursor updates when the cursor position changes due to editing
            if (!isEditor || isUpdatingText.get() || isSendingCursor.get()) {
                return;
            }
            
            // Throttle cursor position updates to reduce network traffic
            long currentTime = System.currentTimeMillis();
            if (currentTime - lastCursorSendTime > CURSOR_THROTTLE_MS) {
                lastCursorSendTime = currentTime;
                
                // Set flag to prevent recursive cursor updates
                isSendingCursor.set(true);
                try {
                    // Send cursor position to network
                    networkClient.sendCursorMove(newPos.intValue());
                } finally {
                    isSendingCursor.set(false);
                }
            }
        });
    }
    
    private void setupBatchTimer() {
        batchTimer = new java.util.Timer(true);
        batchTimer.scheduleAtFixedRate(new java.util.TimerTask() {
            @Override
            public void run() {
                Platform.runLater(() -> processBatchInserts());
            }
        }, 100, 100); // Process batch every 100ms
    }
    
    private void addToBatchInsert(int position, char c) {
        // If this is first char in batch or not consecutive position
        if (pendingInsertBasePosition == -1 || position != pendingInsertBasePosition + pendingInserts.length()) {
            // Process any existing batch first
            processBatchInserts();
            
            // Start new batch
            pendingInsertBasePosition = position;
            pendingInserts.append(c);
        } else {
            // Add to existing batch
            pendingInserts.append(c);
        }
        
        // Update UI immediately for responsiveness
        String text = editorArea.getText();
        int insertPos = Math.min(position, text.length());
        text = text.substring(0, insertPos) + c + text.substring(insertPos);
        
        // Avoid triggering cursor update events
        isUpdatingText.set(true);
        try {
            editorArea.setText(text);
            editorArea.positionCaret(position + 1);
        } finally {
            isUpdatingText.set(false);
        }
    }
    
    private void processBatchInserts() {
        if (pendingInserts.length() == 0) {
            return;
        }
        
        int startPosition = pendingInsertBasePosition;
        String textToInsert = pendingInserts.toString();
        
        // Reset batch
        pendingInserts.setLength(0);
        pendingInsertBasePosition = -1;
        
        // Process each character in the batch
        for (int i = 0; i < textToInsert.length(); i++) {
            char c = textToInsert.charAt(i);
            
            // Insert the character in the CRDT document
            CRDTCharacter character = document.localInsert(startPosition + i, c);
            
            // Send the insertion to the network
            networkClient.sendInsert(character);
        }
        
        // Send cursor position to network once at end of batch
        networkClient.sendCursorMove(startPosition + textToInsert.length());
        
        // Update the word count after batch processing
        updateWordCount();
    }
    
    /**
     * Handles remote operations from the server.
     * @param operation The operation received from the server.
     */
    private void handleRemoteOperation(Operation operation) {
        // For operations that modify the document, synchronize on the document
        if (operation.getType() == Operation.Type.INSERT || 
            operation.getType() == Operation.Type.DELETE || 
            operation.getType() == Operation.Type.DOCUMENT_SYNC) {
            
            // Log for debugging
            System.out.println("Processing remote operation: " + operation.getType());
            
            try {
            synchronized (document) {
                switch (operation.getType()) {
                    case INSERT:
                        CRDTCharacter character = operation.getCharacter();
                            
                            // Skip if this is our own insert that was echoed back
                            if (character.getAuthorId().equals(userId)) {
                                // This is our own character insert that got echoed back to us
                                // No need to apply it again, but do update UI
                                Platform.runLater(() -> updateEditorText(document.getText()));
                                return;
                            }
                            
                        document.remoteInsert(character);
                            System.out.println("Inserted character at position: " + character.getPosition().toString());
                        break;
                            
                    case DELETE:
                        Position position = operation.getPosition();
                            
                            // Skip if this is our own delete that was echoed back
                            if (operation.getUserId().equals(userId)) {
                                // This is our own delete operation that got echoed back
                                // No need to apply it again, but do update UI
                                Platform.runLater(() -> updateEditorText(document.getText()));
                                return;
                            }
                            
                        document.remoteDelete(position);
                            System.out.println("Deleted character at position: " + position.toString());
                        break;
                            
                    case DOCUMENT_SYNC:
                            System.out.println("Document sync received with " + 
                                (operation.getDocumentContent() != null ? operation.getDocumentContent().length() : 0) + 
                                " characters");
                        handleDocumentSync(operation.getDocumentContent());
                        return; // Skip the text update since handleDocumentSync does it
                            
                    default:
                        // Should not happen
                        break;
                }
                
                // Update the text area with the document's current text
                final String documentText = document.getText();
                    
                    // Use Platform.runLater to update UI on JavaFX thread
                    Platform.runLater(() -> {
                        try {
                            // Remember cursor position before update
                            int caretPosition = editorArea.getCaretPosition();
                            
                            // Update text
                            updateEditorText(documentText);
                            
                            // Restore cursor position if possible
                            if (caretPosition >= 0 && caretPosition <= documentText.length()) {
                                editorArea.positionCaret(caretPosition);
                            }
                            
                            // Send cursor position after remote edits
                            if (operation.getType() == Operation.Type.INSERT || operation.getType() == Operation.Type.DELETE) {
                                networkClient.sendCursorMove(editorArea.getCaretPosition());
                            }
                        } catch (Exception e) {
                            System.err.println("Error updating UI after remote operation: " + e.getMessage());
                            e.printStackTrace();
                            
                            // This might indicate client corruption
                            handlePossibleCorruption();
                        }
                    });
                
                // Also update the server with the complete document text occasionally
                    if (Math.random() < 0.05) { // Reduced from 10% to 5% chance to send document update
                    networkClient.sendDocumentUpdate(documentText);
                }
            }
            } catch (Exception e) {
                System.err.println("Error processing remote operation: " + e.getMessage());
                e.printStackTrace();
                
                // This might indicate client corruption
                handlePossibleCorruption();
            }
        } else if (operation.getType() == Operation.Type.CURSOR_MOVE) {
            // Handle cursor movement - should be fast
            updateRemoteCursor(operation.getUserId(), operation.getCursorPosition());
        } else if (operation.getType() == Operation.Type.GET_DOCUMENT_LENGTH) {
            // Special operation to get document length for sync confirmation
                    if (document != null) {
                operation.setDocumentLength(document.getText().length());
            } else {
                operation.setDocumentLength(0);
            }
        } else if (operation.getType() == Operation.Type.REQUEST_DOCUMENT_RESYNC) {
            // Handle request for document resync
            Platform.runLater(() -> {
                if (networkClient != null) {
                    String currentContent = document.getText();
                    networkClient.sendDocumentUpdate(currentContent);
                    System.out.println("Sent document resync with " + currentContent.length() + " characters");
                }
            });
        }
    }
    
    /**
     * Handles a document sync operation.
     * This is called when the server sends the full document content.
     * @param content The document content.
     */
    private void handleDocumentSync(String content) {
        try {
            if (content == null) {
                System.err.println("Received null content in document sync");
            return;
        }
        
            System.out.println("Received document sync with " + content.length() + " characters");
            
            // Save the current cursor position before updating
            final int currentCaretPosition = editorArea.getCaretPosition();
            final String currentText = document.getText();
            
            // Check for major discrepancies that might indicate client corruption
            if (currentText != null && currentText.length() > 0 && content.length() > 0) {
                double lengthRatio = (double) Math.max(currentText.length(), content.length()) / 
                                    Math.min(Math.max(1, currentText.length()), content.length());
                
                if (lengthRatio > 5.0) {
                    // Extreme difference in document lengths might indicate corruption
                    System.err.println("Extreme document length mismatch: current=" + currentText.length() + 
                                       ", sync=" + content.length() + ", ratio=" + lengthRatio);
                    handlePossibleCorruption();
                }
            }
            
            // Only update if the content differs from what we have
            if (content.equals(currentText)) {
                System.out.println("Document sync content matches current content, no update needed");
                
                // Still send a confirmation for this sync
                if (networkClient != null) {
                    JsonObject confirmMsg = new JsonObject();
                    confirmMsg.addProperty("type", "sync_confirmation");
                    confirmMsg.addProperty("receivedLength", content.length());
                    confirmMsg.addProperty("userId", userId);
                    
                    networkClient.getWebSocketClient().send(new Gson().toJson(confirmMsg));
                    System.out.println("Sent sync confirmation for " + content.length() + " characters");
                }
                return;
            }
            
            // If we're updating with new content
            synchronized (document) {
                // Create a new CRDT document with the synced content
                CRDTDocument newDocument = new CRDTDocument(userId);
                
                // Insert each character with a position
                    for (int i = 0; i < content.length(); i++) {
                    newDocument.localInsert(i, content.charAt(i));
                }
                
                // Replace our document with the synced one
                document = newDocument;
                
                // Update UI - but try to maintain cursor position logically
                Platform.runLater(() -> {
                    // Calculate the best cursor position - the smaller of:
                    // 1. The original position
                    // 2. The end of the new document
                    int newPosition = Math.min(currentCaretPosition, content.length());
                
                    // Update the UI
                    updateEditorText(content);
                    
                    // Try to maintain a reasonable cursor position
                    try {
                        if (newPosition >= 0) {
                            editorArea.positionCaret(newPosition);
                            
                            // If we're restoring cursor position, send an update to other clients
                            // Only send if the cursor has actually moved to avoid loops
                            if (newPosition != currentCaretPosition) {
                                networkClient.sendCursorMove(newPosition);
                            }
                        }
                    } catch (Exception e) {
                        System.err.println("Error restoring cursor position: " + e.getMessage());
                    }
                    
                    // Update word count
                    updateWordCount();
                    
                    // Refresh cursor markers after document sync
                    refreshCursorMarkers();
                });
                
                // Send confirmation for this sync
                if (networkClient != null) {
                    JsonObject confirmMsg = new JsonObject();
                    confirmMsg.addProperty("type", "sync_confirmation");
                    confirmMsg.addProperty("receivedLength", content.length());
                    confirmMsg.addProperty("userId", userId);
                    
                    networkClient.getWebSocketClient().send(new Gson().toJson(confirmMsg));
                    System.out.println("Sent sync confirmation for " + content.length() + " characters");
                }
            }
        } catch (Exception e) {
            System.err.println("Error handling document sync: " + e.getMessage());
            e.printStackTrace();
            
            // This might indicate client corruption
            handlePossibleCorruption();
        }
    }
    
    /**
     * Updates the word count label with the current count from the document.
     * Also displays character and line counts.
     */
    private void updateWordCount() {
        if (document == null || wordCountLabel == null) {
            return;
        }
        
        String text = document.getText();
        int wordCount = 0;
        int charCount = 0;
        int lineCount = 0;
        
        if (text != null) {
            charCount = text.length();
            
            if (!text.isEmpty()) {
                // Count lines (newlines + 1)
                lineCount = 1;
                for (char c : text.toCharArray()) {
                    if (c == '\n') {
                        lineCount++;
                    }
                }
                
                // Split by whitespace and count non-empty words
                String[] words = text.split("\\s+");
                for (String word : words) {
                    if (!word.trim().isEmpty()) {
                        wordCount++;
                    }
                }
            } else {
                // Empty document
                lineCount = 1;
            }
        }
        
        final int finalWordCount = wordCount;
        final int finalCharCount = charCount;
        final int finalLineCount = lineCount;
        
        Platform.runLater(() -> {
            wordCountLabel.setText(String.format("Words: %d | Characters: %d | Lines: %d", 
                finalWordCount, finalCharCount, finalLineCount));
        });
    }
    
    private void updateEditorText(String newText) {
        if (isUpdatingText.get()) {
            return;
        }
        
        isUpdatingText.set(true);
        try {
            // Save current selection/caret
            int caretPosition = editorArea.getCaretPosition();
            
            // Update text
            editorArea.setText(newText);
            
            // Restore selection/caret if possible
            if (caretPosition > newText.length()) {
                caretPosition = newText.length();
            }
            editorArea.positionCaret(caretPosition);
            
            // Update the word count
            updateWordCount();
        } finally {
            isUpdatingText.set(false);
        }
    }
    
    /**
     * Updates the list of active users in the UI.
     * @param userMap Map of user IDs to usernames
     */
    private void updateUserList(Map<String, String> userMap) {
        if (userMap == null || userMap.isEmpty()) {
            return;
        }
        
        System.out.println("Received user update with " + userMap.size() + " users: " + userMap.keySet());
        
        // Create a new simplified clean map with just valid users
        Map<String, String> cleanUserMap = new HashMap<>();
        
        // Always add ourselves first to ensure we're in the list
        if (userId != null && username != null) {
            cleanUserMap.put(userId, username);
        }
        
        // Track if we found new valid users
        boolean foundNewValidUsers = false;
        
        // Check if this is a single-user update (likely a new user joining)
        boolean isSingleUserUpdate = userMap.size() == 1 && !userMap.containsKey(userId);
        
        for (Map.Entry<String, String> entry : userMap.entrySet()) {
            String id = entry.getKey();
            String name = entry.getValue();
            
            // Skip ourselves (already added)
            if (id.equals(userId)) {
                continue;
            }
            
            // Skip if no username
            if (name == null || name.trim().isEmpty()) {
                System.out.println("Skipping user with empty name: " + id);
                continue;
            }
            
            // This is a real user - add them to our map
            cleanUserMap.put(id, name);
            
            // If this user wasn't in our existing map, they're new
            if (!this.userMap.containsKey(id)) {
                foundNewValidUsers = true;
                System.out.println("Found new user: " + name + " (" + id + ")");
            }
        }
        
        // Special case: if this is a single-user update and we found a valid user,
        // merge with our existing users instead of replacing them
        if (isSingleUserUpdate && foundNewValidUsers && this.userMap != null && !this.userMap.isEmpty()) {
            // Add our existing users to the map (except for any overwritten by this update)
            for (Map.Entry<String, String> entry : this.userMap.entrySet()) {
                if (!cleanUserMap.containsKey(entry.getKey())) {
                    cleanUserMap.put(entry.getKey(), entry.getValue());
                }
            }
            System.out.println("Merged single-user update with existing users, now have " + 
                              cleanUserMap.size() + " users");
        }
        
        // Save the updated clean user map
        this.userMap = new HashMap<>(cleanUserMap);
        
        // Update the UI on the JavaFX thread
        Platform.runLater(() -> {
            try {
                // Clear and rebuild the users list
            users.clear();
                
                // Add ourselves first with "you" indicator
                if (userId != null && username != null) {
                    users.add(username + " (you)");
                }
                
                // Add all other users
                for (Map.Entry<String, String> entry : cleanUserMap.entrySet()) {
                    if (!entry.getKey().equals(userId)) {
                        users.add(entry.getValue());
                    }
                }
                
                // Update collaboration status in the console
                boolean hasOtherUsers = users.size() > 1;
                if (hasOtherUsers) {
                    System.out.println("Collaborating with " + (users.size() - 1) + 
                                      " other user" + (users.size() > 2 ? "s" : ""));
                } else {
                    System.out.println("No other users connected");
                }
                
                System.out.println("Updated active users list with " + (cleanUserMap.size() - 1) + 
                                  " real users: " + users);
                
                // Update cursor markers only for valid users
                updateCursorMarkers(new ArrayList<>(cleanUserMap.keySet()));
            } catch (Exception e) {
                System.err.println("Error updating user list: " + e.getMessage());
                e.printStackTrace();
            }
        });
    }
    
    /**
     * Get the username for a given user ID
     * @param userId The user ID
     * @return The username or a formatted user ID if username not found
     */
    private String getUsernameForId(String userId) {
        // Get the username from our map
        String username = userMap.get(userId);
        
        // If not found or empty, create a default name with shorter formatting
        if (username == null || username.trim().isEmpty()) {
            // Instead of using UUID format, create a shorter nickname
            return "User-" + userId.substring(0, Math.min(6, userId.length()));
        }
        
        return username;
    }
    
    private void updateCursorMarkers(List<String> userIds) {
        Platform.runLater(() -> {
            // Remove old cursors for users no longer present
            Set<String> currentUsers = new HashSet<>(userIds);
            Set<String> markersToRemove = new HashSet<>();
            
            for (String markerId : cursorMarkers.keySet()) {
                if (!currentUsers.contains(markerId)) {
                    markersToRemove.add(markerId);
                }
            }
            
            for (String markerId : markersToRemove) {
                CursorMarker marker = cursorMarkers.remove(markerId);
                cleanupCursorMarker(marker);
            }
            
            // Assign colors to new users
            int colorIndex = 0;
            for (String userId : userIds) {
                if (!userId.equals(this.userId) && !cursorMarkers.containsKey(userId)) {
                    CursorMarker marker = new CursorMarker(getUsernameForId(userId), cursorColors[colorIndex % cursorColors.length]);
                    cursorMarkers.put(userId, marker);
                    editorContainer.getChildren().add(marker);
                    
                    colorIndex++;
                }
            }
        });
    }
    
    /**
     * Gets the bounds of the caret in the text area.
     * This is a utility method as TextArea doesn't directly provide this.
     * @return The bounds of the caret, or null if not available
     */
    private Bounds getCaretBounds() {
        // Get current caret position
        int caretPosition = editorArea.getCaretPosition();
        
        // Calculate line and column
        int lineNo = 0;
        int colNo = 0;
        int pos = 0;
        
        // Find the line number and column position
        for (CharSequence paragraph : editorArea.getParagraphs()) {
            int paragraphLength = paragraph.length() + 1; // +1 for newline
            if (pos + paragraphLength > caretPosition) {
                colNo = caretPosition - pos;
                break;
            }
            pos += paragraphLength;
            lineNo++;
        }
        
        // Use fixed width for consistent positioning across clients
        double charWidth = 8.0; // Width of a single character in pixels
        double lineHeight = 18.0; // Height of a line in pixels
        
        // Calculate position
        double x = colNo * charWidth;
        double y = lineNo * lineHeight;
        
        // Return a bounds object
        return new javafx.geometry.BoundingBox(x, y, 1, lineHeight);
    }
    
    /**
     * Updates a remote cursor position.
     * @param userId The user ID.
     * @param position The cursor position.
     */
    private void updateRemoteCursor(String userId, int position) {
        if (position < 0) {
            // Negative position means cursor should be removed
            Platform.runLater(() -> {
                CursorMarker marker = cursorMarkers.get(userId);
                if (marker != null) {
                    cleanupCursorMarker(marker);
                }
            });
            return;
        }

        // Using runLater for thread safety
        final int cursorPosition = position; // Make position effectively final for lambda
        Platform.runLater(() -> {
            try {
                // Ensure we have a marker for this user
                CursorMarker marker = cursorMarkers.get(userId);
                if (marker == null) {
                    // Create a new marker with the user's username
                    String username = getUsernameForId(userId);
                    
                    // Choose a color based on the user ID to ensure consistency
                    // This makes sure a user always gets the same color
                    int colorIndex = Math.abs(userId.hashCode() % cursorColors.length);
                    Color color = cursorColors[colorIndex];
                    
                    marker = new CursorMarker(username, color);
                    cursorMarkers.put(userId, marker);
                    editorContainer.getChildren().add(marker);
                    marker.toFront(); // Ensure marker is on top
                }
        
                // Calculate cursor position directly from the position index
                if (cursorPosition <= editorArea.getLength()) {
                    // Get the text content
                    String content = editorArea.getText();
                    
                    // Safety check
                    int adjustedPosition = cursorPosition;
                    if (adjustedPosition > content.length()) {
                        adjustedPosition = content.length();
                    }
                    
                    // Calculate line and column more accurately
                    int lineNo = 0;
                    int colNo = 0;
                    int currentPos = 0;
                    
                    // Process each line to find the correct position
                    for (String line : content.split("\\R", -1)) {
                        int lineLength = line.length() + 1; // +1 for newline
                        
                        if (currentPos + lineLength > adjustedPosition) {
                            // Found the line - calculate column
                            colNo = adjustedPosition - currentPos;
                            break;
                        }
                        
                        currentPos += lineLength;
                        lineNo++;
                    }
                    
                    // Get accurate measurements based on the current font
                    // For consistent results across different machines
                    double charWidth = 8.5; // Adjusted for better accuracy
                    double lineHeight = 18.0; // Typical line height
                    
                    // Calculate position with slight adjustments for better visibility
                    double x = colNo * charWidth;
                    double y = lineNo * lineHeight;
                    
                    // Create bounds and update marker
                    Bounds bounds = new javafx.geometry.BoundingBox(x, y, 1, lineHeight);
                    marker.updatePosition(bounds);
                    
                    // Update username in case it changed
                    String username = getUsernameForId(userId);
                    marker.setUsername(username);
                }
            } catch (Exception e) {
                System.err.println("Error updating cursor: " + e.getMessage());
                e.printStackTrace();
            }
        });
    }
    
    private void handleBackspace() {
        if (!isEditor) {
            return; // Don't allow editing if not an editor
        }
        
        try {
            // Process any pending batch inserts first
            processBatchInserts();
            
            int caretPosition = editorArea.getCaretPosition();
            if (caretPosition > 0) {
                // First update UI for responsiveness
                String text = editorArea.getText();
                
                // Make sure we're not at the beginning of the document
                if (text.isEmpty() || caretPosition <= 0) {
                    return;
                }
                
                // Safety check for text length
                if (caretPosition > text.length()) {
                    caretPosition = text.length();
                }
                
                // Log for debugging
                System.out.println("Attempting backspace at position: " + caretPosition);
                
                // Try to delete the character from the CRDT document
                CRDTCharacter deletedChar = document.localDelete(caretPosition - 1);
                
                if (deletedChar != null) {
                    // If successful, send the delete operation to the network
                    System.out.println("Successfully deleted char: " + deletedChar.getValue() + " at position: " + deletedChar.getPosition());
                    networkClient.sendDelete(deletedChar.getPosition());
                    
                    // Also directly update the UI for better responsiveness
                    String newText = text.substring(0, caretPosition - 1) + text.substring(caretPosition);
                    
                    // Avoid triggering cursor update events
                    isUpdatingText.set(true);
                    try {
                        editorArea.setText(newText);
                        editorArea.positionCaret(caretPosition - 1);
                    } finally {
                        isUpdatingText.set(false);
                    }
                    
                    // Send a full document update occasionally to ensure consistency
                    if (Math.random() < 0.1) {
                        networkClient.sendDocumentUpdate(document.getText());
                    }
                    
                    // Update word count
                    updateWordCount();
                } else {
                    System.err.println("Failed to delete character at position " + (caretPosition - 1));
                    // If deletion failed in CRDT, do a forced UI update to sync with internal state
                    String currentDocText = document.getText();
                    updateEditorText(currentDocText);
                    System.err.println("Backspace failed - forced text resync");
                }
            }
        } catch (Exception e) {
            System.err.println("Error handling backspace: " + e.getMessage());
            e.printStackTrace();
            
            // Force UI update to match internal state
            try {
                updateEditorText(document.getText());
            } catch (Exception ex) {
                System.err.println("Failed to update UI after error: " + ex.getMessage());
            }
        }
    }
    
    private void handleDelete() {
        if (!isEditor) {
            return; // Don't allow editing if not an editor
        }
        
        try {
            // Process any pending batch inserts first
            processBatchInserts();
            
            int caretPosition = editorArea.getCaretPosition();
            String text = editorArea.getText();
            
            // Safety checks
            if (text.isEmpty() || caretPosition >= text.length()) {
                return;
            }
            
            // Log for debugging
            System.out.println("Attempting to delete character at position: " + caretPosition);
            
            // Try to delete the character from the CRDT document
            CRDTCharacter deletedChar = document.localDelete(caretPosition);
            
            if (deletedChar != null) {
                // If successful, send the delete operation to the network
                System.out.println("Successfully deleted char: " + deletedChar.getValue() + " at position: " + deletedChar.getPosition());
                networkClient.sendDelete(deletedChar.getPosition());
                
                // Also directly update the UI for better responsiveness
                String newText = text.substring(0, caretPosition) + text.substring(caretPosition + 1);
                
                // Avoid triggering cursor update events
                isUpdatingText.set(true);
                try {
                    editorArea.setText(newText);
                    editorArea.positionCaret(caretPosition); // Keep caret at same position
                } finally {
                    isUpdatingText.set(false);
                }
                
                // Send a full document update occasionally to ensure consistency
                if (Math.random() < 0.1) {
                    networkClient.sendDocumentUpdate(document.getText());
                }
                
                // Update word count
                updateWordCount();
            } else {
                System.err.println("Failed to delete character at position " + caretPosition);
                // If deletion failed in CRDT, do a forced UI update to sync with internal state
                String currentDocText = document.getText();
                updateEditorText(currentDocText);
                System.err.println("Delete failed - forced text resync");
            }
        } catch (Exception e) {
            System.err.println("Error handling delete: " + e.getMessage());
            e.printStackTrace();
            
            // Force UI update to match internal state
            try {
                updateEditorText(document.getText());
            } catch (Exception ex) {
                System.err.println("Failed to update UI after error: " + ex.getMessage());
            }
        }
    }
    
    private void handleEnter() {
        // Process any pending batch inserts first
        processBatchInserts();
        
        // Get the current caret position
        int caretPosition = editorArea.getCaretPosition();
        
        // First update the UI immediately for responsiveness
        String text = editorArea.getText();
        int insertPos = Math.min(caretPosition, text.length());
        text = text.substring(0, insertPos) + "\n" + text.substring(insertPos);
        
        // Avoid triggering cursor update events during UI update
        isUpdatingText.set(true);
        try {
            editorArea.setText(text);
            editorArea.positionCaret(caretPosition + 1);
        } finally {
            isUpdatingText.set(false);
        }
        
        // Insert a newline character in the CRDT document
        CRDTCharacter character = document.localInsert(caretPosition, '\n');
        
        // Send the insertion to the network
        networkClient.sendInsert(character);
        
        // Send cursor position to network
        networkClient.sendCursorMove(caretPosition + 1);
    }
    
    @FXML
    private void handleImportFile(ActionEvent event) {
        if (!isEditor) {
            updateStatus("Viewers cannot import files");
            return;
        }
        
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Import Text File");
        fileChooser.getExtensionFilters().add(
            new FileChooser.ExtensionFilter("Text Files", "*.txt")
        );
        
        File selectedFile = fileChooser.showOpenDialog(editorArea.getScene().getWindow());
        if (selectedFile != null) {
            try {
                String content = new String(Files.readAllBytes(Paths.get(selectedFile.getPath())));
                
                // Clear the current document
                editorArea.clear();
                
                // Insert each character of the file into the CRDT document
                for (int i = 0; i < content.length(); i++) {
                    CRDTCharacter character = document.localInsert(i, content.charAt(i));
                    networkClient.sendInsert(character);
                }
                
                // Update the text area with the document's current text
                updateEditorText(document.getText());
                
                updateStatus("File imported: " + selectedFile.getName());
            } catch (IOException e) {
                updateStatus("Error importing file: " + e.getMessage());
            }
        }
    }
    
    @FXML
    private void handleExportFile(ActionEvent event) {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Export Document");
        fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Text Files", "*.txt"));
        
        // Set initial file name to document title if available
        if (documentTitle != null) {
            fileChooser.setInitialFileName(documentTitle + ".txt");
        }
        
        // Show save dialog
        File file = fileChooser.showSaveDialog(editorArea.getScene().getWindow());
        
        if (file != null) {
            try {
                Files.write(Paths.get(file.getPath()), document.getText().getBytes());
                updateStatus("Document exported to " + file.getName());
            } catch (IOException e) {
                updateStatus("Error exporting document: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }
    
    /**
     * Handles undo action.
     * @param event The action event.
     */
    @FXML
    private void handleUndo(ActionEvent event) {
        if (!isEditor) {
            updateStatus("Viewers cannot undo changes");
            return;
        }
        
        // Process any pending batch inserts first
        processBatchInserts();
        
        if (document != null) {
            try {
                // Get the operation that will be undone
                Operation undoOperation = document.peekUndo();
                if (undoOperation == null) {
                    updateStatus("Nothing to undo");
                    return;
                }

                // Store the original content
                String oldContent = document.getText();
                
                // Perform the undo
                boolean undone = document.undo();
                if (undone) {
                    // Get latest text
                    String newContent = document.getText();
                    
                    // Update the UI
                    updateEditorText(newContent);
                    
                    // Now explicitly send the exact operation that was undone
                    // This way it will be processed exactly like a regular insert or delete
                    if (undoOperation.getType() == Operation.Type.INSERT) {
                        // Undoing an insert means we need to delete that character
                        if (networkClient != null && undoOperation.getPosition() != null) {
                            networkClient.sendDelete(undoOperation.getPosition());
                        }
                    } else if (undoOperation.getType() == Operation.Type.DELETE) {
                        // Undoing a delete means we need to insert that character
                        if (networkClient != null && undoOperation.getCharacter() != null) {
                            networkClient.sendInsert(undoOperation.getCharacter());
                        }
                    }
                    
                    // Also send a backup full document update with high priority
                    sendUndoRedoFullDocumentUpdate(newContent, "undo");
                    
                    updateStatus("Undo operation");
                } else {
                    updateStatus("Nothing to undo - or undo failed");
                    
                    // Force a document sync to ensure consistency
                    String currentContent = document.getText();
                    networkClient.sendDocumentUpdate(currentContent);
                }
            } catch (Exception e) {
                System.err.println("Error performing undo: " + e.getMessage());
                e.printStackTrace();
                updateStatus("Error during undo: " + e.getMessage());
                
                // Try to recover from the error by forcing a document update
                try {
                    String currentContent = document.getText();
                    networkClient.sendDocumentUpdate(currentContent);
                } catch (Exception ex) {
                    System.err.println("Could not recover from undo error: " + ex.getMessage());
                }
            }
        }
    }
    
    /**
     * Handles redo action.
     * @param event The action event.
     */
    @FXML
    private void handleRedo(ActionEvent event) {
        if (!isEditor) {
            updateStatus("Viewers cannot redo changes");
            return;
        }
        
        // Process any pending batch inserts first
        processBatchInserts();
        
        if (document != null) {
            try {
                // Get the operation that will be redone
                Operation redoOperation = document.peekRedo();
                if (redoOperation == null) {
                    updateStatus("Nothing to redo");
                    return;
                }

                // Store the original content
                String oldContent = document.getText();
                
                // Perform the redo
                boolean redone = document.redo();
                if (redone) {
                    // Get latest text
                    String newContent = document.getText();
                    
                    // Update the UI
                    updateEditorText(newContent);
                    
                    // Now explicitly send the exact operation that was redone
                    // This way it will be processed exactly like a regular insert or delete
                    if (redoOperation.getType() == Operation.Type.INSERT) {
                        // Redoing an insert means we need to insert that character
                        if (networkClient != null && redoOperation.getCharacter() != null) {
                            networkClient.sendInsert(redoOperation.getCharacter());
                        }
                    } else if (redoOperation.getType() == Operation.Type.DELETE) {
                        // Redoing a delete means we need to delete that character
                        if (networkClient != null && redoOperation.getPosition() != null) {
                            networkClient.sendDelete(redoOperation.getPosition());
                        }
                    }
                    
                    // Also send a backup full document update with high priority
                    sendUndoRedoFullDocumentUpdate(newContent, "redo");
                    
                    updateStatus("Redo operation");
                } else {
                    updateStatus("Nothing to redo - or redo failed");
                    
                    // Force a document sync to ensure consistency
                    String currentContent = document.getText();
                    networkClient.sendDocumentUpdate(currentContent);
                }
            } catch (Exception e) {
                System.err.println("Error performing redo: " + e.getMessage());
                e.printStackTrace();
                updateStatus("Error during redo: " + e.getMessage());
                
                // Try to recover from the error by forcing a document update
                try {
                    String currentContent = document.getText();
                    networkClient.sendDocumentUpdate(currentContent);
                } catch (Exception ex) {
                    System.err.println("Could not recover from redo error: " + ex.getMessage());
                }
            }
        }
    }
    
    /**
     * Helper method to send a full document update after undo/redo operations
     * 
     * @param content The current document content
     * @param operationType The type of operation (undo or redo)
     */
    private void sendUndoRedoFullDocumentUpdate(String content, String operationType) {
        if (networkClient != null && networkClient.getWebSocketClient() != null) {
            try {
                // Create a specialized message with HIGH_PRIORITY
                JsonObject message = new JsonObject();
                message.addProperty("type", "instant_document_update");
                message.addProperty("userId", userId);
                message.addProperty("username", username);
                message.addProperty("content", content);
                message.addProperty("operation", operationType);
                message.addProperty("highPriority", true);
                message.addProperty("timestamp", System.currentTimeMillis());
                
                networkClient.getWebSocketClient().send(new Gson().toJson(message));
                System.out.println("Sent " + operationType + " full document update with " + content.length() + " chars");
            } catch (Exception e) {
                System.err.println("Error sending " + operationType + " sync: " + e.getMessage());
            }
        }
    }
    
    @FXML
    private void handleGenerateCodes(ActionEvent event) {
        if (!isEditor) {
            updateStatus("Viewers cannot generate codes");
            return;
        }
        
        // First check if we already have codes in the UI fields
        String existingEditorCode = editorCodeField.getText();
        String existingViewerCode = viewerCodeField.getText();
        
        if (existingEditorCode != null && !existingEditorCode.isEmpty() &&
            existingViewerCode != null && !existingViewerCode.isEmpty()) {
            // We already have codes, just save them with the document
            if (documentId != null) {
                boolean saved = DatabaseService.getInstance().updateDocumentWithSession(
                    documentId, document.getText(), existingEditorCode, existingViewerCode);
                
                if (saved) {
                    updateStatus("Session codes saved with document");
                } else {
                    updateStatus("Failed to save session codes");
                }
            }
            return;
        }
        
        // Request new codes from the server
        networkClient.requestCodes();
    }
    
    @FXML
    private void handleJoinSession(ActionEvent event) {
        // Show the join session dialog
        JoinSessionDialog dialog = new JoinSessionDialog();
        dialog.showAndWait().ifPresent(result -> {
            String code = result.getKey();
            boolean isEditorRole = result.getValue();
            
            try {
                // Update our editor status
                isEditor = isEditorRole;
                
                // Update UI based on role
                editorArea.setEditable(isEditor);
                
                // Clear the editor before joining to prepare for document sync
                processBatchInserts(); // Process any pending operations
                updateEditorText(""); // Clear editor
                document = new CRDTDocument(userId); // Reset document
                
                // Clear existing cursor markers
                for (CursorMarker marker : new ArrayList<>(cursorMarkers.values())) {
                    cleanupCursorMarker(marker);
                }
                cursorMarkers.clear();
                
                // Important: Join the session with the server FIRST
                System.out.println("Joining session with code: " + code + " as " + (isEditorRole ? "editor" : "viewer"));
                networkClient.joinSession(code, isEditor);
                
                // Update UI to show we're waiting
                if (isEditor) {
                    editorCodeField.setText(code); // Set the editor code field for editors
                    updateStatus("Joined session as editor - waiting for content sync...");
                } else {
                    updateStatus("Joined session as viewer - waiting for content sync...");
                    // Hide the sharing codes for viewers
                    editorCodeField.setText("");
                    viewerCodeField.setText("");
                }
                
                // Wait a moment to let the session join process complete
                new Thread(() -> {
                    try {
                        // Give the server time to process the join
                        Thread.sleep(500);
                        
                        // Now create/update the local document for this session
                        Platform.runLater(() -> {
                            try {
                                // Create a new document for this session if one doesn't exist already
                                createDocumentForSession(code, isEditorRole);
                                
                                // Force a sync request
                                updateStatus("Requesting document sync...");
                                Operation requestResyncOperation = 
                                    new Operation(Operation.Type.REQUEST_DOCUMENT_RESYNC, null, null, userId, -1);
                                handleRemoteOperation(requestResyncOperation);
                            } catch (Exception e) {
                                updateStatus("Error creating document: " + e.getMessage());
                                e.printStackTrace();
                            }
                        });
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }).start();
                
            } catch (Exception e) {
                updateStatus("Error joining session: " + e.getMessage());
                e.printStackTrace();
            }
        });
    }
    
    /**
     * Creates or retrieves a document for a session.
     * @param sessionCode The session code.
     * @param isEditorRole Whether the user is joining as an editor.
     */
    private void createDocumentForSession(String sessionCode, boolean isEditorRole) {
        try {
            System.out.println("Creating document for session: " + sessionCode);
            
            // First, check if there's already a document in the database for this session code
            org.bson.Document existingDoc = DatabaseService.getInstance().getDocumentBySessionCode(sessionCode);
            if (existingDoc != null) {
                // We found an existing document for this session
                String foundDocId = existingDoc.get("_id").toString();
                String foundTitle = existingDoc.getString("title");
                
                System.out.println("Found existing document for session: " + foundDocId + " (" + foundTitle + ")");
                
                // Update our current document info
                documentId = foundDocId;
                documentTitle = foundTitle;
                
                // Set window title
                final boolean isViewerMode = !isEditorRole;
                    Platform.runLater(() -> {
                    try {
                        String windowTitle = "Collaborative Text Editor - " + documentTitle;
                        if (isViewerMode) {
                            windowTitle += " (Viewer Mode)";
                        }
                        Stage stage = (Stage) editorArea.getScene().getWindow();
                        stage.setTitle(windowTitle);
                    } catch (Exception e) {
                        System.err.println("Error updating window title: " + e.getMessage());
                    }
                });
                
                return;
            }
            
            // No existing document found for this session, create a new one
            System.out.println("No existing document found for session. Creating new document.");
            
            // Create a default title based on the session code
            String newTitle = "Shared Document - " + sessionCode;
            
            // Create the document in the database
            String newDocId = DatabaseService.getInstance().createDocument(newTitle, userId);
            
            // Store session code with the document
            boolean updated = DatabaseService.getInstance().updateDocumentWithSession(
                newDocId, "", sessionCode, sessionCode);
            
            if (updated) {
                System.out.println("Created new document with ID: " + newDocId + " for session: " + sessionCode);
            } else {
                System.err.println("Failed to update document with session codes");
            }
            
            // Update our current document info
            documentId = newDocId;
            documentTitle = newTitle;
            
            // Set window title
            final boolean isViewerMode = !isEditorRole;
                Platform.runLater(() -> {
                try {
                    String windowTitle = "Collaborative Text Editor - " + documentTitle;
                    if (isViewerMode) {
                        windowTitle += " (Viewer Mode)";
                    }
                    Stage stage = (Stage) editorArea.getScene().getWindow();
                    stage.setTitle(windowTitle);
                } catch (Exception e) {
                    System.err.println("Error updating window title: " + e.getMessage());
                }
            });
            
            // After creating document, immediately ask for any existing content
            if (networkClient != null) {
                // Wait a bit, then request document sync
                final NetworkClient finalNetworkClient = networkClient;
                new Thread(() -> {
                    try {
                        Thread.sleep(500);
                    Platform.runLater(() -> {
                            // Send a special request for document sync
                            JsonObject resyncRequest = new JsonObject();
                            resyncRequest.addProperty("type", "request_resync");
                            resyncRequest.addProperty("userId", userId);
                            finalNetworkClient.getWebSocketClient().send(new Gson().toJson(resyncRequest));
                            System.out.println("Requested document content from server for new session");
                        });
                    } catch (Exception e) {
                        System.err.println("Error requesting document sync: " + e.getMessage());
                    }
                }).start();
            }
        } catch (Exception e) {
            System.err.println("Error creating document for session: " + e.getMessage());
            e.printStackTrace();
            updateStatus("Error creating document: " + e.getMessage());
        }
    }
    
    @FXML
    private void handleCopyEditorCode(ActionEvent event) {
        if (!isEditor) {
            updateStatus("Viewers cannot access codes");
            return;
        }
        
        String code = editorCodeField.getText();
        if (code != null && !code.isEmpty()) {
            copyToSystemClipboard(code);
            updateStatus("Editor code copied to clipboard");
        }
    }
    
    @FXML
    private void handleCopyViewerCode(ActionEvent event) {
        if (!isEditor) {
            updateStatus("Viewers cannot access codes");
            return;
        }
        
        String code = viewerCodeField.getText();
        if (code != null && !code.isEmpty()) {
            copyToSystemClipboard(code);
            updateStatus("Viewer code copied to clipboard");
        }
    }
    
    @FXML
    private void handleExit(ActionEvent event) {
        // Save document before exiting
        saveDocument();
        
        // Clean up cursor markers
        for (CursorMarker marker : new ArrayList<>(cursorMarkers.values())) {
            cleanupCursorMarker(marker);
        }
        cursorMarkers.clear();
        
        // Send a leave message to the server to ensure proper cleanup
        if (networkClient != null) {
            networkClient.leaveSession();
            
            // Give time for leave message to be sent
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            
            // Then fully disconnect
            networkClient.disconnect();
        }
        
        // Close the window
        ((Stage) editorArea.getScene().getWindow()).close();
    }
    
    /**
     * Copies text to the system clipboard.
     * @param text The text to copy.
     */
    private void copyToSystemClipboard(String text) {
        try {
            // Use JavaFX Clipboard
            final javafx.scene.input.Clipboard clipboard = javafx.scene.input.Clipboard.getSystemClipboard();
            final javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
            content.putString(text);
            clipboard.setContent(content);
        } catch (Exception e) {
            updateStatus("Could not copy to clipboard: " + e.getMessage());
        }
    }
    
    private void updateStatus(String message) {
        if (Platform.isFxApplicationThread()) {
            statusLabel.setText(message);
        } else {
            Platform.runLater(() -> statusLabel.setText(message));
        }
    }
    
    @FXML
    private void handleOpenDocuments(ActionEvent event) {
        try {
            // Process any pending inserts before changing documents
            processBatchInserts();
            
            // Save current document if needed
            saveDocument();
            
            // Leave current session, but don't disconnect completely
            leaveCurrentSession(false);
            
            // Show document selection dialog
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
                        
                        // Handle joining directly
                        joinExistingSession(code, isEditorRole);
                        return;
                    }
                }
                
                // Normal document open
                documentId = key;
                documentTitle = documentInfo.getValue();
                
                // Set window title
                Stage stage = (Stage) editorArea.getScene().getWindow();
                stage.setTitle("Collaborative Text Editor - " + documentTitle + " (" + username + ")");
                
                // Load the newly selected document
                loadDocumentContent();
                
                updateStatus("Opened document: " + documentTitle);
            }
        } catch (Exception e) {
            updateStatus("Error opening document: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Joins an existing session using the provided code.
     * 
     * @param code The session code
     * @param isEditorRole Whether to join as editor or viewer
     */
    private void joinExistingSession(String code, boolean isEditorRole) {
        try {
            System.out.println("======== JOIN EXISTING SESSION ========");
            System.out.println("Code: " + code);
            System.out.println("Role: " + (isEditorRole ? "EDITOR" : "VIEWER"));
            System.out.println("User ID: " + userId);
            
            // Save this code to recent session codes
            DocumentSelectionDialog.saveRecentSessionCode(code);
            
            // Update our editor status
            isEditor = isEditorRole;
            
            // Update UI based on role
            editorArea.setEditable(isEditor);
            
            // Clear the editor before joining to prepare for document sync
            processBatchInserts(); // Process any pending operations
            updateEditorText(""); // Clear editor
            document = new CRDTDocument(userId); // Reset document
            
            // Clear existing cursor markers
            for (CursorMarker marker : new ArrayList<>(cursorMarkers.values())) {
                cleanupCursorMarker(marker);
            }
            cursorMarkers.clear();
            
            // Ensure connection is established before joining
            if (networkClient == null || !networkClient.getWebSocketClient().isOpen()) {
                updateStatus("Establishing connection...");
                
                // If network client doesn't exist or is disconnected, create a new one
                if (networkClient == null) {
                    networkClient = new NetworkClient(userId, username);
                    setupNetworkListeners();
                }
                
                // Connect and wait for connection to establish
                boolean connected = networkClient.connect();
                if (!connected) {
                    updateStatus("Failed to connect to server. Retrying...");
                    // Try one more time after a short delay
                    new Thread(() -> {
                        try {
                            Thread.sleep(1000);
                            Platform.runLater(() -> {
                                if (networkClient.connect()) {
                                    completeJoinSession(code, isEditorRole);
                                } else {
                                    updateStatus("Failed to connect to collaboration server");
                                }
                            });
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }).start();
                    return;
                }
            }
            
            completeJoinSession(code, isEditorRole);
            
        } catch (Exception e) {
            updateStatus("Error joining session: " + e.getMessage());
            System.err.println("Error in joinExistingSession: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Completes the session join process after ensuring connection is established
     */
    private void completeJoinSession(String code, boolean isEditorRole) {
        try {
            // Important: Join the session with the server
            System.out.println("Joining session with code: " + code + " as " + (isEditorRole ? "editor" : "viewer"));
            networkClient.joinSession(code, isEditor);
            
            // Update UI to show we're waiting
            if (isEditor) {
                editorCodeField.setText(code); // Set the editor code field for editors
                updateStatus("Joined session as editor - waiting for content sync...");
            } else {
                updateStatus("Joined session as viewer - waiting for content sync...");
                // Hide the sharing codes for viewers
                editorCodeField.setText("");
                viewerCodeField.setText("");
            }
            
            // Wait a moment to let the session join process complete
            new Thread(() -> {
                try {
                    // Give the server time to process the join
                    Thread.sleep(1000);
                    
                    // Now create/update the local document for this session
                    Platform.runLater(() -> {
                        try {
                            // Create a new document for this session if one doesn't exist already
                            createDocumentForSession(code, isEditorRole);
                            
                            // Force a sync request
                            updateStatus("Requesting document sync...");
                            Operation requestResyncOperation = 
                                new Operation(Operation.Type.REQUEST_DOCUMENT_RESYNC, null, null, userId, -1);
                            handleRemoteOperation(requestResyncOperation);
                        
                            // Also force sending our current document state to the server
                            if (document != null && isEditor) {
                                String currentText = document.getText();
                                networkClient.sendDocumentUpdate(currentText);
                            }
                        } catch (Exception e) {
                            System.err.println("Error in document sync after join: " + e.getMessage());
                            updateStatus("Error creating document: " + e.getMessage());
                            e.printStackTrace();
                        }
                    });
                } catch (Exception e) {
                    System.err.println("Error in join session thread: " + e.getMessage());
                    e.printStackTrace();
                }
            }).start();
        } catch (Exception e) {
            System.err.println("Error in completeJoinSession: " + e.getMessage());
            updateStatus("Error joining session: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Leaves the current editing session if any.
     * Modified to preserve session codes and only clean up UI elements.
     * @param fullDisconnect If true, completely disconnects from the network (for app shutdown)
     */
    private void leaveCurrentSession(boolean fullDisconnect) {
        try {
            // Save current session codes before clearing UI
            String currentEditorCode = editorCodeField.getText();
            String currentViewerCode = viewerCodeField.getText();
            
            // Clear cursor markers
            for (CursorMarker marker : new ArrayList<>(cursorMarkers.values())) {
                cleanupCursorMarker(marker);
            }
            cursorMarkers.clear();
            
            // Clear users list and reset user map
            users.clear();
            userMap.clear();
            
            // Reset to editor mode
            isEditor = true;
            editorArea.setEditable(true);
            
            // Always send a leave session message to the server
            if (networkClient != null && networkClient.isConnected()) {
                // Send leave message
                networkClient.leaveSession();
                
                // Give a small delay for the message to be sent
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                
                // Add ourselves back to the user list with (you) indicator
                if (username != null) {
                    users.add(username + " (you)");
                }
            }
            
            if (fullDisconnect) {
                // Clear codes when fully disconnecting
                editorCodeField.setText("");
                viewerCodeField.setText("");
                
                // Disconnect from network completely (only for app shutdown)
                if (networkClient != null) {
                    networkClient.disconnect();
                    
                    // Wait a bit before reconnecting
                    try {
                        Thread.sleep(200);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    
                    networkClient = new NetworkClient(userId, username);
                    setupNetworkListeners();
                    networkClient.connect();
                }
            } else {
                // For regular document switching, we just clean up UI but preserve network connection
                System.out.println("Temporary session transition - preserving codes: " + 
                                  "Editor=" + currentEditorCode + ", Viewer=" + currentViewerCode);
                
                // Store codes for possible restoration when loading new document
                if (documentId != null && currentEditorCode != null && !currentEditorCode.isEmpty()) {
                    // Save the session codes with the document
                    DatabaseService.getInstance().updateDocumentWithSession(
                        documentId, document.getText(), currentEditorCode, currentViewerCode);
                }
            }
        } catch (Exception e) {
            System.err.println("Error during session transition: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Legacy version of leaveCurrentSession that fully disconnects.
     * Kept for backward compatibility.
     */
    private void leaveCurrentSession() {
        leaveCurrentSession(true);
    }
    
    private void setupAutoSaveTimer() {
        if (autoSaveTimer != null) {
            autoSaveTimer.cancel();
        }
        
        autoSaveTimer = new java.util.Timer(true);
        autoSaveTimer.scheduleAtFixedRate(new java.util.TimerTask() {
            @Override
            public void run() {
                saveDocument();
            }
        }, 30000, 30000); // 30 seconds
    }
    
    private void saveDocument() {
        // Process any pending inserts before saving
        processBatchInserts();
        
        if (documentId != null) {
            String content = document.getText();
            
            // Get the editor and viewer codes
            String editorCode = editorCodeField.getText();
            String viewerCode = viewerCodeField.getText();
            
            boolean saved = false;
            if (editorCode != null && !editorCode.isEmpty() && 
                viewerCode != null && !viewerCode.isEmpty()) {
                // Save with both codes
                saved = DatabaseService.getInstance().updateDocumentWithSession(
                    documentId, content, editorCode, viewerCode);
            } else {
                // Legacy save method if codes are missing
                saved = DatabaseService.getInstance().updateDocument(documentId, content);
            }
            
            if (saved) {
                // Also update the server with the complete document
                networkClient.sendDocumentUpdate(content);
                
                Platform.runLater(() -> updateStatus("Document saved"));
            } else {
                Platform.runLater(() -> updateStatus("Failed to save document"));
            }
        }
    }
    
    /**
     * Handles the save document action event from the UI.
     * @param event the action event
     */
    @FXML
    private void handleSaveDocument(ActionEvent event) {
        try {
            // Process any pending batch operations first
            processBatchInserts();
            
            // Save the document
            saveDocument();
            
            // Force a document update to ensure it's synced with the server
            if (document != null) {
                String content = document.getText();
                networkClient.sendDocumentUpdate(content);
            }
            
            updateStatus("Document saved successfully");
        } catch (Exception e) {
            updateStatus("Error saving document: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    // Fix for CursorMarker in updateCursorMarkers
    private void cleanupCursorMarker(CursorMarker marker) {
        if (marker != null) {
            marker.setVisible(false);
            editorContainer.getChildren().remove(marker);
        }
    }
    
    /**
     * Cleans the document by removing any problematic characters and resetting the internal state.
     * This is useful for recovering from error conditions.
     */
    @FXML
    private void handleFixDocument(ActionEvent event) {
        try {
            // First process any pending operations
            processBatchInserts();
            
            // Get current text
            String currentText = editorArea.getText();
            
            // Clean the document text - remove any control or problematic characters
            StringBuilder cleanContent = new StringBuilder(currentText.length());
            for (int i = 0; i < currentText.length(); i++) {
                char c = currentText.charAt(i);
                // Only keep visible characters and standard whitespace
                if (c >= 32 || c == '\t' || c == '\n' || c == '\r') {
                    cleanContent.append(c);
                } else {
                    System.out.println("Removed problematic character at position " + i + 
                                      ": code=" + (int)c);
                }
            }
            
            String cleanedText = cleanContent.toString();
            
            // Reset the document
            synchronized (document) {
                document = new CRDTDocument(userId);
                
                // Insert all characters into the fresh document
                for (int i = 0; i < cleanedText.length(); i++) {
                    document.localInsert(i, cleanedText.charAt(i));
                }
                
                // Update the UI
                updateEditorText(cleanedText);
                
                // Update word count
                updateWordCount();
                
                // Reset cursor markers
                Platform.runLater(() -> {
                    for (CursorMarker marker : new ArrayList<>(cursorMarkers.values())) {
                        cleanupCursorMarker(marker);
                    }
                    cursorMarkers.clear();
                    
                    // Update cursor markers for current users
                    if (userMap != null && !userMap.isEmpty()) {
                        updateCursorMarkers(new ArrayList<>(userMap.keySet()));
                    }
                });
                
                // Send the updated document to the server
                networkClient.sendDocumentUpdate(cleanedText);
                
                updateStatus("Document fixed and cleaned");
            }
        } catch (Exception e) {
            System.err.println("Error fixing document: " + e.getMessage());
            e.printStackTrace();
            updateStatus("Error fixing document: " + e.getMessage());
        }
    }
    
    /**
     * Tests the MongoDB connection and updates the status label with the result.
     * This helps diagnose if data is going to MongoDB or just being stored in-memory.
     */
    @FXML
    private void handleTestDatabaseConnection(ActionEvent event) {
        try {
            updateStatus("Testing MongoDB connection...");
            
            // Test the connection using the DatabaseService
            boolean connected = DatabaseService.getInstance().testMongoDBConnection();
            
            if (connected) {
                updateStatus("MongoDB connection successful! Your data is being saved to the cloud.");
            } else {
                updateStatus("WARNING: Using in-memory storage. Your data is NOT being saved to MongoDB!");
            }
        } catch (Exception e) {
            updateStatus("Error testing MongoDB connection: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Instead of a separate Share button, this method is used to copy the current document's codes.
     * @param event The action event.
     */
    @FXML
    private void handleShareCodes(ActionEvent event) {
        // This replaces the old Share button functionality
        // Just copy codes to clipboard and show in UI
        String editorCode = editorCodeField.getText();
        String viewerCode = viewerCodeField.getText();
        
        if (editorCode != null && !editorCode.isEmpty() && 
            viewerCode != null && !viewerCode.isEmpty()) {
            // Create a nicely formatted message for sharing
            String shareMessage = "Join my document!\n" +
                                 "Editor code: " + editorCode + "\n" +
                                 "Viewer code: " + viewerCode;
            
            copyToSystemClipboard(shareMessage);
            updateStatus("Sharing codes copied to clipboard");
        } else {
            updateStatus("No sharing codes available for this document");
        }
    }
    
    /**
     * Loads the content of the current document.
     * Automatically creates or joins a session for collaborative editing.
     */
    private void loadDocumentContent() {
        try {
            // First clear any existing content and process any pending operations
            processBatchInserts();
            document = new CRDTDocument(userId);
            updateEditorText("");
            
            updateStatus("Loading document: " + documentTitle + "...");
            
            // Make sure document ID is valid
            if (documentId == null || documentId.isEmpty()) {
                updateStatus("Invalid document ID");
                return;
            }
            
            System.out.println("==================================================");
            System.out.println("LOADING DOCUMENT");
            System.out.println("Document ID: " + documentId);
            System.out.println("Document Title: " + documentTitle);
            System.out.println("User ID: " + userId);
            System.out.println("==================================================");
            
            org.bson.Document doc = null;
            try {
                doc = DatabaseService.getInstance().getDocument(documentId);
                if (doc == null) {
                    System.err.println("Document not found in database: " + documentId);
                    updateStatus("Document not found. Creating a new one.");
                    // Create a new document and generate a session for it
                    createNewDocumentWithSession();
                    return;
                }
            } catch (Exception e) {
                System.err.println("Database error: " + e.getMessage());
                e.printStackTrace();
                updateStatus("Error connecting to database. Using empty document.");
                // Create a new document and generate a session for it
                createNewDocumentWithSession();
                return;
            }
            
            // Output details about the document for debugging
            System.out.println("Document details from database:");
            System.out.println("  ID: " + doc.get("_id"));
            System.out.println("  Title: " + doc.getString("title"));
            System.out.println("  Owner ID: " + doc.getString("ownerId"));
            String docContent = doc.getString("content");
            System.out.println("  Content length: " + (docContent != null ? docContent.length() : 0) + " characters");
            
                // Get owner ID to check if this is our own document or a shared one
                String ownerId = doc.getString("ownerId");
                boolean isOwnedByCurrentUser = userId.equals(ownerId);
            System.out.println("Document is owned by current user: " + isOwnedByCurrentUser);
                
            // Get content from the document
                String content = null;
                try {
                    content = doc.getString("content");
                if (content == null) {
                    content = "";
                    System.out.println("Content was null, using empty string");
                }
                } catch (Exception e) {
                    System.err.println("Error reading document content: " + e.getMessage());
                    updateStatus("Error reading document content. Using empty document.");
                content = "";
                }
                
                // Check for existing session codes
                String existingEditorCode = null;
                String existingViewerCode = null;
                try {
                    existingEditorCode = doc.getString("editorCode");
                    existingViewerCode = doc.getString("viewerCode");
                System.out.println("Found session codes - Editor: " + existingEditorCode + ", Viewer: " + existingViewerCode);
                } catch (Exception e) {
                    System.err.println("Error reading session codes: " + e.getMessage());
                }
                
            // ALWAYS ENSURE DOCUMENT IS IN A SESSION
            final String finalEditorCode = existingEditorCode;
            final String finalViewerCode = existingViewerCode;
            
            if (finalEditorCode != null && !finalEditorCode.isEmpty()) {
                // Document already has a session, join it
                System.out.println("Document has existing session, joining it...");
                
                // Set the editor and viewer codes in the UI
                Platform.runLater(() -> {
                    editorCodeField.setText(finalEditorCode);
                    if (finalViewerCode != null && !finalViewerCode.isEmpty()) {
                        viewerCodeField.setText(finalViewerCode);
                    } else {
                        viewerCodeField.setText(finalEditorCode);
                    }
                });
                
                // Load the content first before joining session
                if (content != null && !content.isEmpty()) {
                    try {
                        final String finalContent = content;
                        Platform.runLater(() -> {
                            try {
                                // Insert the content character by character
                                for (int i = 0; i < finalContent.length(); i++) {
                                    document.localInsert(i, finalContent.charAt(i));
                                }
                                
                                // Update the text area
                                updateEditorText(finalContent);
                            } catch (Exception e) {
                                System.err.println("Error inserting document content: " + e.getMessage());
                                e.printStackTrace();
                            }
                        });
                    } catch (Exception e) {
                        System.err.println("Error loading document content: " + e.getMessage());
                        e.printStackTrace();
                    }
                }
                
                // Join the session (always as editor if it's our document)
                boolean joinAsEditor = isOwnedByCurrentUser;
                DocumentSelectionDialog.saveRecentSessionCode(finalEditorCode);
                joinExistingSession(finalEditorCode, joinAsEditor);
                    } else {
                // No session exists yet, create one automatically
                System.out.println("Document has no session, creating one...");
                
                // Load content first
                if (content != null && !content.isEmpty()) {
                    try {
                        final String finalContent = content;
                        Platform.runLater(() -> {
                            try {
                                // Insert the content character by character
                                for (int i = 0; i < finalContent.length(); i++) {
                                    document.localInsert(i, finalContent.charAt(i));
                                }
                                
                                // Update the text area
                                updateEditorText(finalContent);
                            } catch (Exception e) {
                                System.err.println("Error inserting document content: " + e.getMessage());
                                e.printStackTrace();
                            }
                        });
                    } catch (Exception e) {
                        System.err.println("Error loading document content: " + e.getMessage());
                        e.printStackTrace();
                    }
                }
                
                // Request new session codes
                if (isOwnedByCurrentUser) {
                    // Only create new session if we own the document
                    networkClient.requestCodes();
                    updateStatus("Creating new session for document...");
                    
                    // Wait for codes to be assigned, then synchronize
                    new Thread(() -> {
                        try {
                            Thread.sleep(500);
                            Platform.runLater(() -> {
                                // Send full document content to server
                                if (document != null) {
                                    String docText = document.getText();
                                    networkClient.sendDocumentUpdate(docText);
                                }
                            });
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }).start();
                } else {
                    // If we don't own the document but there's no session, create view-only local session
                    updateStatus("Viewing document in read-only mode");
                    isEditor = false;
                    editorArea.setEditable(false);
                }
            }
        } catch (Exception e) {
            updateStatus("Error loading document: " + e.getMessage());
            e.printStackTrace();
            
            // Reset to empty document in case of error
            try {
                document = new CRDTDocument(userId);
                updateEditorText("");
                
                // Always ensure a session even after error
                createNewDocumentWithSession();
            } catch (Exception ex) {
                System.err.println("Failed to reset document: " + ex.getMessage());
            }
        }
    }
    
    /**
     * Creates a new empty document and automatically generates a session for it.
     */
    private void createNewDocumentWithSession() {
        try {
            // Create a new document if we don't have one
            if (documentId == null) {
                documentId = DatabaseService.getInstance().createDocument(
                    documentTitle != null ? documentTitle : "Untitled Document", 
                    userId
                );
                
                System.out.println("Created new document with ID: " + documentId);
            }
            
            // Request session codes for the new document
                    networkClient.requestCodes();
            updateStatus("Creating new session for document...");
                    
            // Wait for codes to be assigned, then synchronize
                    new Thread(() -> {
                        try {
                            Thread.sleep(500);
                            Platform.runLater(() -> {
                        // Make sure we're in editor mode
                        isEditor = true;
                        editorArea.setEditable(true);
                        
                        // Send empty document content to server to ensure synchronization
                        networkClient.sendDocumentUpdate("");
                            });
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }).start();
        } catch (Exception e) {
            System.err.println("Error creating new document with session: " + e.getMessage());
            e.printStackTrace();
            updateStatus("Error creating document. Please try again.");
        }
    }

    /**
     * Refreshes all cursor markers after document content changes.
     * This ensures cursors are properly positioned after syncs.
     */
    private void refreshCursorMarkers() {
        if (cursorMarkers.isEmpty()) {
            return;
        }
        
        Platform.runLater(() -> {
            try {
                // For each active cursor, request its latest position from the server
                for (String userId : new HashSet<>(cursorMarkers.keySet())) {
                    // Skip our own cursor
                    if (userId.equals(this.userId)) {
                        continue;
                    }
                    
                    // Get the stored position if available
                    Integer position = networkClient.getLastKnownCursorPosition(userId);
                    if (position != null && position >= 0) {
                        // Update the cursor with the current position
                        updateRemoteCursor(userId, position);
                    }
                }
            } catch (Exception e) {
                System.err.println("Error refreshing cursor markers: " + e.getMessage());
            }
        });
    }
    
    /**
     * Resets the client state in case of corruption.
     * This is a last-resort measure when the client appears to have issues.
     */
    @FXML
    private void handleResetClient(ActionEvent event) {
        resetClient();
    }
    
    /**
     * Completely resets the client state in case of corruption.
     * This clears all internal state and reestablishes connections.
     */
    private void resetClient() {
        try {
            Platform.runLater(() -> {
                updateStatus("Resetting client state...");
                
                // Process any pending operations
                processBatchInserts();
                
                // Save document before reset if possible
                if (documentId != null) {
                    try {
                        saveDocument();
                    } catch (Exception e) {
                        System.err.println("Error saving document during reset: " + e.getMessage());
                    }
                }
                
                // Clear cursor markers
                for (CursorMarker marker : new ArrayList<>(cursorMarkers.values())) {
                    cleanupCursorMarker(marker);
                }
                cursorMarkers.clear();
                
                // Clear users list
                users.clear();
                
                // Reset local data structures
                userMap.clear();
                
                // Disconnect from network
                if (networkClient != null) {
                    try {
                        networkClient.disconnect();
        } catch (Exception e) {
                        System.err.println("Error disconnecting: " + e.getMessage());
                    }
                }
            
                // Wait a moment before reconnecting
                new Thread(() -> {
            try {
                        Thread.sleep(500);
                        
                        Platform.runLater(() -> {
                            try {
                                // Create fresh CRDT document
                document = new CRDTDocument(userId);
                                
                                // Create fresh network client
                                networkClient = new NetworkClient(userId, username);
                                
                                // Set up network listeners
                                setupNetworkListeners();
                                
                                // Reconnect to network
                                networkClient.connect();
                                
                                // If we have document info, reload the document
                                if (documentId != null) {
                                    loadDocumentContent();
                                }
                                
                                updateStatus("Client reset completed successfully");
                            } catch (Exception e) {
                                System.err.println("Error during client reset: " + e.getMessage());
                                updateStatus("Error during client reset: " + e.getMessage());
                            }
                        });
                    } catch (Exception e) {
                        System.err.println("Error during client reset: " + e.getMessage());
                    }
                }).start();
            });
        } catch (Exception e) {
            System.err.println("Error resetting client: " + e.getMessage());
            updateStatus("Error resetting client: " + e.getMessage());
        }
    }
    
    /**
     * Handles document corruption by checking and potentially resetting the client.
     * Called when we suspect the client is in an inconsistent state.
     */
    private void handlePossibleCorruption() {
        try {
            // Check if text sync is severely out of sync
            if (document != null && editorArea != null) {
                String docText = document.getText();
                String uiText = editorArea.getText();
                
                if (docText != null && uiText != null) {
                    // If lengths differ by more than 20%, consider the client corrupt
                    int docLength = docText.length();
                    int uiLength = uiText.length();
                    
                    double lengthRatio = (docLength > uiLength) ? 
                        (double)docLength / Math.max(1, uiLength) : 
                        (double)uiLength / Math.max(1, docLength);
                    
                    if (lengthRatio > 1.2) {
                        System.err.println("Document content significantly out of sync (ratio: " + lengthRatio + 
                                          "). Doc length: " + docLength + ", UI length: " + uiLength);
                        
                        // Ask user if they want to reset
                        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
                        alert.setTitle("Document Sync Issue");
                        alert.setHeaderText("Document content is out of sync");
                        alert.setContentText("The editor has detected that the document content is significantly out of sync. " +
                                            "Would you like to reset the client to fix this issue?");
                        
                        Optional<ButtonType> result = alert.showAndWait();
                        if (result.isPresent() && result.get() == ButtonType.OK) {
                            resetClient();
                        } else {
                            // If user chooses not to reset, at least try to fix the document
                            updateEditorText(docText);
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Error checking for corruption: " + e.getMessage());
        }
    }
} 