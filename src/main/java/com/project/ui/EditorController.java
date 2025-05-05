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
     * Sets a custom user ID. Must be called before initialize().
     * @param userId the user ID to set
     */
    public void setUserId(String userId) {
        this.userId = userId;
    }
    
    /**
     * Sets the username. Must be called before initialize().
     * @param username the username to set
     */
    public void setUsername(String username) {
        this.username = username;
        // Store in user map right away
        if (userId != null && username != null) {
            userMap.put(userId, username);
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
            
            // Initialize the network client
            networkClient = new NetworkClient(siteId);
            
            // Set up listeners
            setupNetworkListeners();
            setupEditorListeners();
            
            // Initialize user list
            usersListView.setItems(users);
            
            // Connect to the network
            boolean connected = networkClient.connect();
            if (connected) {
                updateStatus("Connected to network as " + (username != null ? username : siteId));
                
                // Setup auto-save timer (save every 30 seconds)
                setupAutoSaveTimer();
                
                // Setup batch timer for performance
                setupBatchTimer();
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
            
            // Set flag to prevent double initialization
            initialized = true;
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
                // Update the UI fields
                editorCodeField.setText(codes.getEditorCode());
                viewerCodeField.setText(codes.getViewerCode());
                
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
    }
    
    private void handleRemoteOperation(Operation operation) {
        // For operations that modify the document, synchronize on the document
        if (operation.getType() == Operation.Type.INSERT || 
            operation.getType() == Operation.Type.DELETE || 
            operation.getType() == Operation.Type.DOCUMENT_SYNC) {
            
            synchronized (document) {
                switch (operation.getType()) {
                    case INSERT:
                        CRDTCharacter character = operation.getCharacter();
                        document.remoteInsert(character);
                        break;
                    case DELETE:
                        Position position = operation.getPosition();
                        document.remoteDelete(position);
                        break;
                    case DOCUMENT_SYNC:
                        handleDocumentSync(operation.getDocumentContent());
                        return; // Skip the text update since handleDocumentSync does it
                    default:
                        // Should not happen
                        break;
                }
                
                // Update the text area with the document's current text
                final String documentText = document.getText();
                Platform.runLater(() -> updateEditorText(documentText));
                
                // Also update the server with the complete document text occasionally
                if (Math.random() < 0.1) { // 10% chance to send document update
                    networkClient.sendDocumentUpdate(documentText);
                }
            }
        } else {
            // For non-document operations, no need to synchronize
            switch (operation.getType()) {
                case CURSOR_MOVE:
                    updateRemoteCursor(operation.getUserId(), operation.getCursorPosition());
                    break;
                case GET_DOCUMENT_LENGTH:
                    // This is a request to get the current document length (for sync confirmation)
                    synchronized (document) {
                        operation.setDocumentLength(document != null ? document.getText().length() : 0);
                    }
                    break;
                case REQUEST_DOCUMENT_RESYNC:
                    // Force resend the current document content to the server
                    if (document != null) {
                        synchronized (document) {
                            final String text = document.getText();
                            if (text.length() > 0) {
                                networkClient.sendDocumentUpdate(text);
                            }
                        }
                    }
                    break;
                case PRESENCE:
                    // This is handled by the presence listener
                    break;
                default:
                    System.err.println("Unknown operation type: " + operation.getType());
                    break;
            }
        }
    }
    
    private void handleDocumentSync(String content) {
        if (content == null || isUpdatingText.get()) {
            return;
        }
        
        try {
            // Acquire lock for document sync
            synchronized (document) {
                // Reset the document
                document = new CRDTDocument(userId);
                
                // Reset the UI first to show the sync is happening
                updateEditorText("");
                
                // Clean the content - remove any control characters or problematic characters
                // that might be causing issues
                if (content != null && !content.isEmpty()) {
                    StringBuilder cleanContent = new StringBuilder(content.length());
                    for (int i = 0; i < content.length(); i++) {
                        char c = content.charAt(i);
                        // Only keep visible characters and standard whitespace (space, tab, newline)
                        if (c >= 32 || c == '\t' || c == '\n' || c == '\r') {
                            cleanContent.append(c);
                        } else {
                            System.out.println("Removed control character at position " + i + 
                                               ": code=" + (int)c);
                        }
                    }
                    content = cleanContent.toString();
                }
                
                // Insert each character
                if (content != null && !content.isEmpty()) {
                    // Insert all characters at once rather than one by one
                    for (int i = 0; i < content.length(); i++) {
                        document.localInsert(i, content.charAt(i));
                    }
                
                    // Update the UI
                    updateEditorText(content);
                    
                    // Log successful sync
                    System.out.println("Document synchronized with " + content.length() + " characters");
                    updateStatus("Document synchronized successfully");
                    
                    // Remove all cursor markers and recreate them
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
                } else {
                    updateStatus("Synchronized empty document");
                }
            }
        } catch (Exception e) {
            System.err.println("Error during document sync: " + e.getMessage());
            e.printStackTrace();
            updateStatus("Error during document sync: " + e.getMessage());
        }
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
        } finally {
            isUpdatingText.set(false);
        }
    }
    
    private void updateUserList(Map<String, String> userMap) {
        Platform.runLater(() -> {
            users.clear();
            
            // Store all users for cursor markers
            for (Map.Entry<String, String> entry : userMap.entrySet()) {
                if (!entry.getKey().equals(userId)) {
                    users.add(entry.getValue());
                }
                // Update our internal user map
                this.userMap.put(entry.getKey(), entry.getValue());
            }
            
            // Update cursor markers for all users
            updateCursorMarkers(new ArrayList<>(userMap.keySet()));
        });
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
        Platform.runLater(() -> {
            try {
                // Ensure we have a marker for this user
                CursorMarker marker = cursorMarkers.get(userId);
                if (marker == null) {
                    // This shouldn't happen normally as markers are created in updateCursorMarkers
                    // But if it does, we'll create a marker with a default color
                    String username = getUsernameForId(userId);
                    Color color = cursorColors[0]; // Default to first color
                    marker = new CursorMarker(username, color);
                    cursorMarkers.put(userId, marker);
                    editorContainer.getChildren().add(marker);
                }
        
                // Calculate cursor position directly from the position index
                if (position <= editorArea.getLength()) {
                    // Calculate line and column for this position
                    int lineNo = 0;
                    int colNo = 0;
                    int pos = 0;
                    
                    // Find the line number and column position
                    for (CharSequence paragraph : editorArea.getParagraphs()) {
                        int paragraphLength = paragraph.length() + 1; // +1 for newline
                        if (pos + paragraphLength > position) {
                            colNo = position - pos;
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
                    
                    // Create bounds and update marker
                    Bounds bounds = new javafx.geometry.BoundingBox(x, y, 1, lineHeight);
                    marker.updatePosition(bounds);
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
                
                // Try to delete the character from the CRDT document
                CRDTCharacter deletedChar = document.localDelete(caretPosition - 1);
                
                if (deletedChar != null) {
                    // If successful, send the delete operation to the network
                    networkClient.sendDelete(deletedChar.getPosition());
                    
                    // Update the text area with the document's current text
                    updateEditorText(document.getText());
                } else {
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
            int caretPosition = editorArea.getCaretPosition();
            String text = editorArea.getText();
            
            // Safety checks
            if (text.isEmpty() || caretPosition >= text.length()) {
                return;
            }
            
            // Try to delete the character from the CRDT document
            CRDTCharacter deletedChar = document.localDelete(caretPosition);
            
            if (deletedChar != null) {
                // If successful, send the delete operation to the network
                networkClient.sendDelete(deletedChar.getPosition());
                
                // Update the text area with the document's current text
                updateEditorText(document.getText());
            } else {
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
                
                // Join the session
                networkClient.joinSession(code, isEditor);
                
                // Create a new document for this session if one doesn't exist already
                createDocumentForSession(code, isEditorRole);
                
                if (isEditor) {
                    updateStatus("Joined session as editor - waiting for content sync...");
                } else {
                    updateStatus("Joined session as viewer - waiting for content sync...");
                    // Hide the sharing codes for viewers
                    editorCodeField.setText("");
                    viewerCodeField.setText("");
                }
                
                // Force a sync request after a short delay
                new Thread(() -> {
                    try {
                        Thread.sleep(500);
                        
                        // Tell the server we need content by sending a special sync request
                        if (networkClient != null) {
                            Platform.runLater(() -> {
                                updateStatus("Requesting document sync...");
                                Operation requestResyncOperation = 
                                    new Operation(Operation.Type.REQUEST_DOCUMENT_RESYNC, null, null, userId, -1);
                                handleRemoteOperation(requestResyncOperation);
                            });
                        }
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
     * Creates a new document for the current user when joining a session.
     * 
     * @param sessionCode The session code being joined
     * @param isEditorRole Whether the user is joining as an editor or viewer
     */
    private void createDocumentForSession(String sessionCode, boolean isEditorRole) {
        try {
            if (userId == null) {
                System.err.println("Cannot create document: User ID is null");
                return;
            }
            
            // Create a title for the document based on the session code
            String title = "Session " + sessionCode.substring(0, Math.min(8, sessionCode.length()));
            
            // Check if we already have a document with this session code
            boolean documentExists = false;
            List<org.bson.Document> userDocs = DatabaseService.getInstance().getDocumentsByOwner(userId);
            
            for (org.bson.Document doc : userDocs) {
                String editorCode = doc.getString("editorCode");
                String viewerCode = doc.getString("viewerCode");
                
                // If this document already has the session code we're joining, use it
                if ((editorCode != null && editorCode.equals(sessionCode)) || 
                    (viewerCode != null && viewerCode.equals(sessionCode))) {
                    documentId = doc.get("_id").toString();
                    documentTitle = doc.getString("title");
                    documentExists = true;
                    
                    // Update the window title
                    Platform.runLater(() -> {
                        Stage stage = (Stage) editorArea.getScene().getWindow();
                        stage.setTitle("Collaborative Text Editor - " + documentTitle + " (" + username + ")");
                    });
                    
                    System.out.println("Using existing document for session: " + documentId);
                    break;
                }
            }
            
            // If no document exists for this session, create a new one
            if (!documentExists) {
                // Create a new document
                documentId = DatabaseService.getInstance().createDocument(title, userId);
                documentTitle = title;
                
                // Update the window title
                Platform.runLater(() -> {
                    Stage stage = (Stage) editorArea.getScene().getWindow();
                    stage.setTitle("Collaborative Text Editor - " + documentTitle + " (" + username + ")");
                });
                
                // Save the session code with the document
                if (isEditorRole) {
                    // When joining as editor, save the code as editorCode
                    DatabaseService.getInstance().updateDocumentWithSession(
                        documentId, "", sessionCode, sessionCode);
                    // Update the UI fields
                    Platform.runLater(() -> {
                        editorCodeField.setText(sessionCode);
                        viewerCodeField.setText(sessionCode);
                    });
                } else {
                    // When joining as viewer, save the code as viewerCode
                    DatabaseService.getInstance().updateDocumentWithSession(
                        documentId, "", sessionCode, sessionCode);
                }
                
                System.out.println("Created new document for session: " + documentId);
            }
        } catch (Exception e) {
            System.err.println("Error creating document for session: " + e.getMessage());
            e.printStackTrace();
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
        
        // Disconnect from network
        if (networkClient != null) {
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
            
            // Leave current session if in one
            leaveCurrentSession();
            
            // Show document selection dialog
            Pair<String, String> documentInfo = DocumentSelectionDialog.showDocumentSelectionDialog(userId);
            if (documentInfo != null) {
                documentId = documentInfo.getKey();
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
            
            org.bson.Document doc = null;
            try {
                doc = DatabaseService.getInstance().getDocument(documentId);
            } catch (Exception e) {
                System.err.println("Database error: " + e.getMessage());
                e.printStackTrace();
                updateStatus("Error connecting to database. Using empty document.");
                return;
            }
            
            if (doc != null) {
                String content = null;
                try {
                    content = doc.getString("content");
                } catch (Exception e) {
                    System.err.println("Error reading document content: " + e.getMessage());
                    updateStatus("Error reading document content. Using empty document.");
                    return;
                }
                
                // Check for existing session codes
                String existingEditorCode = null;
                String existingViewerCode = null;
                try {
                    existingEditorCode = doc.getString("editorCode");
                    existingViewerCode = doc.getString("viewerCode");
                } catch (Exception e) {
                    System.err.println("Error reading session codes: " + e.getMessage());
                }
                
                // If at least editor code exists, use it
                if (existingEditorCode != null && !existingEditorCode.isEmpty()) {
                    try {
                        updateStatus("Using existing session: " + existingEditorCode);
                        
                        // Set the codes in the UI
                        editorCodeField.setText(existingEditorCode);
                        if (existingViewerCode != null && !existingViewerCode.isEmpty()) {
                            viewerCodeField.setText(existingViewerCode);
                        }
                        
                        // Join the existing session as an editor
                        networkClient.joinSession(existingEditorCode, true);
                        isEditor = true;
                        
                        // Request document sync after joining
                        Operation requestResyncOperation = 
                            new Operation(Operation.Type.REQUEST_DOCUMENT_RESYNC, null, null, userId, -1);
                        handleRemoteOperation(requestResyncOperation);
                        
                        updateStatus("Joined existing document session: " + existingEditorCode);
                        return; // Content will be synced through the session
                    } catch (Exception e) {
                        System.err.println("Error joining existing session: " + e.getMessage());
                        // If session join fails, fall back to loading content directly
                    }
                }
                
                // Otherwise proceed with normal content loading
                if (content != null && !content.isEmpty()) {
                    updateStatus("Loading document content...");
                    
                    try {
                        // Insert the content character by character
                        for (int i = 0; i < content.length(); i++) {
                            document.localInsert(i, content.charAt(i));
                        }
                        
                        // Update the text area
                        updateEditorText(content);
                        
                        // Explicitly send document update to sync with server
                        networkClient.sendDocumentUpdate(content);
                        
                        // If no session codes exist, generate new ones for this document
                        if ((existingEditorCode == null || existingEditorCode.isEmpty()) &&
                            (existingViewerCode == null || existingViewerCode.isEmpty())) {
                            // Request new codes from server (will be saved when received)
                            networkClient.requestCodes();
                        }
                        
                        updateStatus("Loaded document: " + documentTitle);
                    } catch (Exception e) {
                        System.err.println("Error inserting document content: " + e.getMessage());
                        e.printStackTrace();
                        updateStatus("Error loading document content. Using empty document.");
                    }
                } else {
                    // Empty document
                    updateEditorText("");
                    
                    // If no session codes exist, generate new ones for this document
                    if ((existingEditorCode == null || existingEditorCode.isEmpty()) &&
                        (existingViewerCode == null || existingViewerCode.isEmpty())) {
                        // Request new codes from server (will be saved when received)
                        networkClient.requestCodes();
                    }
                    
                    updateStatus("Loaded empty document: " + documentTitle);
                }
            } else {
                // No document found
                updateStatus("Could not find document with ID: " + documentId);
            }
        } catch (Exception e) {
            updateStatus("Error loading document: " + e.getMessage());
            e.printStackTrace();
            
            // Reset to empty document in case of error
            try {
                document = new CRDTDocument(userId);
                updateEditorText("");
            } catch (Exception ex) {
                System.err.println("Failed to reset document: " + ex.getMessage());
            }
        }
    }
    
    /**
     * Leaves the current editing session if any.
     */
    private void leaveCurrentSession() {
        try {
            // Clear sharing codes
            editorCodeField.setText("");
            viewerCodeField.setText("");
            
            // Clear cursor markers
            for (CursorMarker marker : new ArrayList<>(cursorMarkers.values())) {
                cleanupCursorMarker(marker);
            }
            cursorMarkers.clear();
            
            // Clear users list
            users.clear();
            
            // Reset to editor mode
            isEditor = true;
            editorArea.setEditable(true);
            
            // Send leave session message (if supported by the server)
            // For now, just disconnect and reconnect
            if (networkClient != null) {
                networkClient.disconnect();
                
                // Wait a bit before reconnecting
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                
                networkClient = new NetworkClient(userId);
                setupNetworkListeners();
                networkClient.connect();
            }
        } catch (Exception e) {
            System.err.println("Error leaving session: " + e.getMessage());
            e.printStackTrace();
        }
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
    
    /**
     * Get the username for a given user ID
     * @param userId The user ID
     * @return The username or a shortened user ID if username not found
     */
    private String getUsernameForId(String userId) {
        String username = userMap.get(userId);
        return username != null ? username : "User " + userId.substring(0, 4);
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
} 