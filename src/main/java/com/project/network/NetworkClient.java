package com.project.network;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.project.crdt.CRDTCharacter;
import com.project.crdt.Position;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import javafx.application.Platform;

/**
 * A WebSocket client for handling communication with the server.
 */
public class NetworkClient {
    private static final String DEFAULT_SERVER_URI = "ws://localhost:8887";
    
    private String userId;
    private String username;
    private WebSocketClient webSocketClient;
    private final Gson gson = new Gson();
    
    private final List<Consumer<Operation>> operationListeners = new ArrayList<>();
    private final List<Consumer<Map<String, String>>> presenceListeners = new ArrayList<>();
    private final List<Consumer<String>> errorListeners = new ArrayList<>();
    private final List<Consumer<CodePair>> codeListeners = new ArrayList<>();
    
    // Track current cursor position to avoid sending duplicates
    private volatile int lastSentCursorPosition = -1;
    
    // Track if a cursor move operation is pending
    private volatile boolean cursorMoveScheduled = false;
    
    // Cursor position update throttling
    private static final long CURSOR_MOVE_THROTTLE_MS = 40; // Reduced from 50ms to 40ms for smoother updates
    private volatile long lastCursorMoveTime = 0;
    
    // Keep track of the last operation times
    private Map<String, Long> lastOperationTimes = new ConcurrentHashMap<>();
    
    // Track last known cursor positions of all users
    private Map<String, Integer> lastKnownCursorPositions = new ConcurrentHashMap<>();
    
    private boolean connected = false;
    
    private List<Consumer<Boolean>> connectionListeners = new ArrayList<>();
    
    // Track seen document sync message IDs to prevent duplicates
    private final Set<String> recentlySyncedDocuments = new HashSet<>();
    
    /**
     * Returns the underlying WebSocketClient instance.
     * @return The WebSocketClient instance
     */
    public WebSocketClient getWebSocketClient() {
        return webSocketClient;
    }
    
    public NetworkClient(String userId) {
        this.userId = userId;
        this.username = userId; // Don't prefix with "User"
        
        // Start the sync history cleaner
        startSyncHistoryCleaner();
    }
    
    public NetworkClient(String userId, String username) {
        this.userId = userId;
        this.username = username;
        
        // Start the sync history cleaner
        startSyncHistoryCleaner();
        
        // Send username update immediately after connection
        addConnectionListener(connected -> {
            if (connected) {
                JsonObject usernameMessage = new JsonObject();
                usernameMessage.addProperty("type", "update_username");
                usernameMessage.addProperty("userId", userId);
                usernameMessage.addProperty("username", username);
                webSocketClient.send(gson.toJson(usernameMessage));
            }
        });
    }
    
    /**
     * Connects to the server.
     * @return true if connected successfully, false otherwise.
     */
    public boolean connect() {
        try {
            // Check if already connected
            if (webSocketClient != null && webSocketClient.isOpen()) {
                System.out.println("Already connected to WebSocket server");
                return true;
            }
            
            System.out.println("Connecting to WebSocket server");
            
            // First, clear any disconnected users from UI
            purgeDisconnectedUsers();
            
            // Connect to the server
            webSocketClient = new WebSocketClient(new URI(DEFAULT_SERVER_URI)) {
                @Override
                public void onOpen(ServerHandshake handshakedata) {
                    System.out.println("Connected to WebSocket server");
                    connected = true;
                    notifyConnectionListeners(true);
                    
                    // First register our userId
                    sendRegistration();
                    
                    // Then send our username immediately after registration
                    if (username != null && !username.isEmpty()) {
                        sendPresenceUpdate();
                    }
                }
                
                @Override
                public void onMessage(String message) {
                    handleServerMessage(message);
                }
                
                @Override
                public void onClose(int code, String reason, boolean remote) {
                    connected = false;
                    System.out.println("Connection closed: " + reason);
                    
                    // Clear presence data since we're no longer connected
                    lastKnownCursorPositions.clear();
                    
                    // Notify connection listeners
                    notifyConnectionListeners(false);
                    
                    // Try to automatically reconnect after a delay if this was a remote closure
                    if (remote) {
                        new Thread(() -> {
                            try {
                                System.out.println("Attempting to reconnect in 3 seconds...");
                                Thread.sleep(3000);
                                connect();
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                        }).start();
                    }
                }
                
                @Override
                public void onError(Exception ex) {
                    notifyErrorListeners("WebSocket error: " + ex.getMessage());
                    System.err.println("WebSocket error: " + ex.getMessage());
                    ex.printStackTrace();
                    
                    // Attempt to reconnect after error
                    if (connected) {
                        connected = false;
                        notifyConnectionListeners(false);
                    }
                }
            };
            
            // Set connection timeout
            webSocketClient.setConnectionLostTimeout(30);
            
            // Connect with timeout
            boolean success = false;
            try {
                success = webSocketClient.connectBlocking(5, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                System.err.println("Connection interrupted: " + e.getMessage());
                Thread.currentThread().interrupt();
            }
            
            if (!success) {
                System.err.println("Failed to connect to WebSocket server");
                notifyErrorListeners("Failed to connect to collaboration server");
                return false;
            }
            
            return true;
        } catch (URISyntaxException e) {
            notifyErrorListeners("Invalid server URI: " + e.getMessage());
            System.err.println("Invalid server URI: " + e.getMessage());
            return false;
        } catch (Exception e) {
            notifyErrorListeners("Connection error: " + e.getMessage());
            System.err.println("Connection error: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }
    
    /**
     * Sends user registration to the server.
     */
    private void sendRegistration() {
        if (webSocketClient != null && webSocketClient.isOpen()) {
            try {
                JsonObject message = new JsonObject();
                message.addProperty("type", "register");
                message.addProperty("userId", userId);
                if (username != null && !username.isEmpty()) {
                    message.addProperty("username", username);
                }
                
                webSocketClient.send(gson.toJson(message));
                System.out.println("Registering with server as user: " + (username != null ? username : userId));
            } catch (Exception e) {
                System.err.println("Error sending registration: " + e.getMessage());
            }
        }
    }
    
    /**
     * Disconnects from the server.
     */
    public void disconnect() {
        if (webSocketClient != null && webSocketClient.isOpen()) {
            try {
                webSocketClient.closeBlocking();
            } catch (InterruptedException e) {
                System.err.println("Error disconnecting: " + e.getMessage());
            }
        }
        connected = false;
    }
    
    /**
     * Sends an insert operation to the server.
     * @param character The character to insert.
     */
    public void sendInsert(CRDTCharacter character) {
        if (!connected) {
            notifyErrorListeners("Not connected to server");
            return;
        }
        
        // Record operation time
        lastOperationTimes.put("insert", System.currentTimeMillis());
        
        JsonObject message = new JsonObject();
        message.addProperty("type", "insert");
        message.addProperty("userId", userId);
        
        // Convert CRDTCharacter to JSON
        JsonObject charObj = new JsonObject();
        charObj.addProperty("value", character.getValue());
        charObj.add("position", gson.toJsonTree(character.getPosition()));
        charObj.addProperty("authorId", character.getAuthorId());
        charObj.addProperty("timestamp", character.getTimestamp());
        message.add("character", charObj);
        
        webSocketClient.send(gson.toJson(message));
    }
    
    /**
     * Sends a delete operation to the server.
     * @param position The position to delete.
     */
    public void sendDelete(Position position) {
        if (!connected) {
            notifyErrorListeners("Not connected to server");
            return;
        }
        
        // Record operation time
        lastOperationTimes.put("delete", System.currentTimeMillis());
        
        JsonObject message = new JsonObject();
        message.addProperty("type", "delete");
        message.addProperty("userId", userId);
        message.add("position", gson.toJsonTree(position));
        
        // Log for debugging
        System.out.println("Sending DELETE operation for position: " + position);
        
        webSocketClient.send(gson.toJson(message));
    }
    
    /**
     * Sends a cursor move operation to the server.
     * This method uses throttling to avoid sending too many updates.
     * @param position The new cursor position.
     */
    public void sendCursorMove(int position) {
        if (!connected) {
            return; // Silently fail instead of showing error - cursor movements are non-critical
        }
        
        // Don't send if the position hasn't changed
        if (position == lastSentCursorPosition) {
            return;
        }
        
        // Check if we recently sent an edit operation, if so, delay the cursor update
        long now = System.currentTimeMillis();
        long lastEditTime = Math.max(
            lastOperationTimes.getOrDefault("insert", 0L),
            lastOperationTimes.getOrDefault("delete", 0L)
        );
        
        // If we recently did an edit, send cursor updates less frequently
        if (now - lastEditTime < 100) {
            if (now - lastCursorMoveTime < CURSOR_MOVE_THROTTLE_MS * 2) { // Double the throttle time during edits
                return;
            }
        }
        
        // Throttle updates
        if (now - lastCursorMoveTime < CURSOR_MOVE_THROTTLE_MS) {
            // If we're already scheduled to send an update, just update the position
            if (cursorMoveScheduled) {
                lastSentCursorPosition = position;
                return;
            }
            
            // Schedule a delayed update
            cursorMoveScheduled = true;
            lastSentCursorPosition = position;
            
            final int capturedPosition = position;
            new Thread(() -> {
                try {
                    Thread.sleep(CURSOR_MOVE_THROTTLE_MS);
                    sendCursorMoveNow(lastSentCursorPosition); // Use the latest position
                    cursorMoveScheduled = false;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }).start();
            
            return;
        }
        
        // Send immediately
        sendCursorMoveNow(position);
    }
    
    /**
     * Immediately sends a cursor move without throttling.
     * @param position The cursor position.
     */
    private void sendCursorMoveNow(int position) {
        if (!connected) {
            return;
        }
        
        lastCursorMoveTime = System.currentTimeMillis();
        lastSentCursorPosition = position;
        
        JsonObject message = new JsonObject();
        message.addProperty("type", "cursor_move");
        message.addProperty("userId", userId);
        message.addProperty("position", position);
        
        try {
            webSocketClient.send(gson.toJson(message));
        } catch (Exception e) {
            System.err.println("Error sending cursor move: " + e.getMessage());
            // Don't show error to user - cursor movements are non-critical
        }
    }
    
    /**
     * Requests shareable codes from the server.
     */
    public void requestCodes() {
        if (!connected) {
            notifyErrorListeners("Not connected to server");
            return;
        }
        
        JsonObject message = new JsonObject();
        message.addProperty("type", "create_session");
        message.addProperty("userId", userId);
        
        webSocketClient.send(gson.toJson(message));
    }
    
    /**
     * Joins a session using a code.
     * @param code The session code.
     * @param isEditor Whether the user is joining as an editor or viewer.
     */
    public void joinSession(String code, boolean isEditor) {
        if (!connected) {
            System.err.println("Failed to join session: Not connected to server");
            notifyErrorListeners("Not connected to server");
            return;
        }
        
        try {
            // First, ensure our own ID is registered by re-registering
            JsonObject registerMsg = new JsonObject();
            registerMsg.addProperty("type", "register");
            registerMsg.addProperty("userId", userId);
            if (username != null && !username.isEmpty()) {
                registerMsg.addProperty("username", username);
            }
            webSocketClient.send(gson.toJson(registerMsg));
            
            System.out.println("=================================================");
            System.out.println("JOIN SESSION REQUEST:");
            System.out.println("Code: " + code);
            System.out.println("Role: " + (isEditor ? "EDITOR" : "VIEWER"));
            System.out.println("User ID: " + userId);
            System.out.println("=================================================");
            
            // Create and send the join message
            JsonObject joinMsg = new JsonObject();
            joinMsg.addProperty("type", "join_session");
            joinMsg.addProperty("code", code);
            joinMsg.addProperty("asEditor", isEditor);  
            joinMsg.addProperty("userId", userId);
            if (username != null && !username.isEmpty()) {
                joinMsg.addProperty("username", username);
            }
            
            // Send the join request
            webSocketClient.send(gson.toJson(joinMsg));
            System.out.println("Join request sent to server");
        } catch (Exception e) {
            System.err.println("Exception in joinSession: " + e.getMessage());
            e.printStackTrace();
            notifyErrorListeners("Error joining session: " + e.getMessage());
        }
    }
    
    /**
     * Handles messages from the server.
     * @param message The message from the server.
     */
    private void handleServerMessage(String message) {
        try {
            JsonObject jsonMessage = gson.fromJson(message, JsonObject.class);
            String type = jsonMessage.get("type").getAsString();
            
            System.out.println("Received message from server: type=" + type);
            
            switch (type) {
                case "register_ack":
                    System.out.println("Registration acknowledged by server");
                    // Registration successful, we can proceed with other operations
                    // Start a thread to clean the sync history periodically
                    startSyncHistoryCleaner();
                    break;
                    
                case "create_session_ack":
                    System.out.println("Create session acknowledged by server");
                    
                    // Get editor and viewer codes from response
                    String editorCode, viewerCode;
                    
                    if (jsonMessage.has("editorCode") && jsonMessage.has("viewerCode")) {
                        // New format with separate codes
                        editorCode = jsonMessage.get("editorCode").getAsString();
                        viewerCode = jsonMessage.get("viewerCode").getAsString();
                        System.out.println("Received distinct editor and viewer codes - Editor: " + editorCode + ", Viewer: " + viewerCode);
                    } else if (jsonMessage.has("sessionId")) {
                        // Legacy format with same code for both
                        editorCode = jsonMessage.get("sessionId").getAsString();
                        viewerCode = editorCode;
                        System.out.println("Received legacy session code: " + editorCode);
                    } else {
                        System.err.println("Invalid create_session_ack response - missing codes");
                        break;
                    }
                    
                    // Notify listeners of the received codes
                    CodePair codePair = new CodePair(editorCode, viewerCode);
                    notifyCodeListeners(codePair);
                    break;
                    
                case "join_session_ack":
                    System.out.println("Join session acknowledged by server");
                    boolean asEditor = jsonMessage.has("asEditor") && jsonMessage.get("asEditor").getAsBoolean();
                    System.out.println("Joined as: " + (asEditor ? "EDITOR" : "VIEWER"));
                    
                    // Extract editor and viewer codes if provided
                    if (jsonMessage.has("editorCode")) {
                        String joinEditorCode = jsonMessage.get("editorCode").getAsString();
                        String joinViewerCode = jsonMessage.has("viewerCode") ? 
                            jsonMessage.get("viewerCode").getAsString() : joinEditorCode;
                        
                        System.out.println("Received session codes in join response:");
                        System.out.println("  Editor code: " + joinEditorCode);
                        System.out.println("  Viewer code: " + joinViewerCode);
                        
                        // Notify code listeners with the received codes
                        notifyCodeListeners(new CodePair(joinEditorCode, joinViewerCode));
                    }
                    
                    if (jsonMessage.has("documentContent")) {
                        String documentContent = jsonMessage.get("documentContent").getAsString();
                        System.out.println("Document content received: " + documentContent.length() + " characters");
                        
                        // Create a special operation for document sync
                        Operation syncOperation = new Operation(
                            Operation.Type.DOCUMENT_SYNC, 
                            null, 
                            null, 
                            userId, 
                            -1,
                            documentContent
                        );
                        
                        // Notify immediately
                        notifyOperationListeners(syncOperation);
                    } else {
                        System.out.println("No document content in join response - will need to request sync");
                    }
                    
                    // Get any usernames provided
                    if (jsonMessage.has("usernames")) {
                        try {
                            JsonObject usernamesObj = jsonMessage.getAsJsonObject("usernames");
                            Map<String, String> userMapFromServer = new HashMap<>();
                            
                            for (Map.Entry<String, com.google.gson.JsonElement> entry : usernamesObj.entrySet()) {
                                userMapFromServer.put(entry.getKey(), entry.getValue().getAsString());
                            }
                            
                            if (!userMapFromServer.isEmpty()) {
                                notifyPresenceListeners(userMapFromServer);
                                System.out.println("Received usernames for " + userMapFromServer.size() + " users");
                            }
                        } catch (Exception e) {
                            System.err.println("Error processing usernames: " + e.getMessage());
                        }
                    }
                    break;
                    
                case "session_joined":
                    boolean asEditorJoined = jsonMessage.get("asEditor").getAsBoolean();
                    String editorCodeJoined = jsonMessage.get("editorCode").getAsString();
                    String viewerCodeJoined = jsonMessage.get("viewerCode").getAsString();
                    
                    System.out.println("==================================================");
                    System.out.println("SESSION JOINED SUCCESSFULLY as " + (asEditorJoined ? "EDITOR" : "VIEWER"));
                    System.out.println("Editor code: " + editorCodeJoined);
                    System.out.println("Viewer code: " + viewerCodeJoined);
                    System.out.println("User ID: " + userId);
                    System.out.println("==================================================");
                    
                    // After joining, update our code information
                    notifyCodeListeners(new CodePair(editorCodeJoined, viewerCodeJoined));
                    
                    // After joining, immediately send our username to help other clients know who we are
                    if (username != null && !username.isEmpty()) {
                        JsonObject usernameMessage = new JsonObject();
                        usernameMessage.addProperty("type", "update_username");
                        usernameMessage.addProperty("userId", userId);
                        usernameMessage.addProperty("username", username);
                        webSocketClient.send(gson.toJson(usernameMessage));
                        System.out.println("Sent username update: " + username);
                    }
                    break;
                    
                case "presence":
                    // Handle legacy server that sends only a list of user IDs
                    if (jsonMessage.has("users")) {
                        // Check if this is a high priority update
                        boolean highPriority = jsonMessage.has("highPriority") && 
                                               jsonMessage.get("highPriority").getAsBoolean();
                        
                        if (highPriority) {
                            System.out.println("Received HIGH PRIORITY presence update");
                        }
                        
                        if (jsonMessage.get("users").isJsonArray()) {
                            // Old format: just a list of user IDs
                            List<String> userIds = gson.fromJson(jsonMessage.get("users"), List.class);
                            
                            // Convert to a map for our new interface
                            Map<String, String> userMap = new HashMap<>();
                            for (String id : userIds) {
                                // Use the actual username for current user, don't add "User" prefix for others
                                if (id.equals(userId) && username != null) {
                                    userMap.put(id, username);
                                } else {
                                    // For other users, use their username if server provides it
                                    // or use the ID without "User" prefix
                                    userMap.put(id, id);
                                }
                            }
                            
                            if (highPriority) {
                                // For high priority updates, we force an update regardless of content
                                notifyPresenceListeners(userMap);
                            } else {
                                // For normal updates, we check if there's something new
                                notifyPresenceListeners(userMap);
                            }
                        } else {
                            // New format: a map of user IDs to usernames
                            Map<String, String> userMap = gson.fromJson(jsonMessage.get("users"), Map.class);
                            
                            // Log the received user map
                            StringBuilder userMapStr = new StringBuilder();
                            for (Map.Entry<String, String> entry : userMap.entrySet()) {
                                userMapStr.append(entry.getKey()).append("->").append(entry.getValue()).append(", ");
                            }
                            System.out.println("Received user map: " + userMapStr.toString());
                            
                            // Always notify for high priority updates
                            if (highPriority) {
                                System.out.println("Forcing presence update due to high priority");
                                notifyPresenceListeners(userMap);
                            } else {
                                notifyPresenceListeners(userMap);
                            }
                        }
                    }
                    break;
                    
                case "update_username":
                    // Someone updated their username, broadcast to all clients
                    if (jsonMessage.has("userId") && jsonMessage.has("username")) {
                        String updatedUserId = jsonMessage.get("userId").getAsString();
                        String updatedUsername = jsonMessage.get("username").getAsString();
                        
                        // Create a one-element map for this update
                        Map<String, String> updateMap = new HashMap<>();
                        updateMap.put(updatedUserId, updatedUsername);
                        
                        // Notify listeners about this username
                        notifyPresenceListeners(updateMap);
                    }
                    break;
                    
                case "username_updates":
                    // New message type - handles bulk username updates
                    if (jsonMessage.has("usernames")) {
                        try {
                            JsonObject usernamesObj = jsonMessage.getAsJsonObject("usernames");
                            Map<String, String> userMapFromServer = new HashMap<>();
                            
                            for (Map.Entry<String, com.google.gson.JsonElement> entry : usernamesObj.entrySet()) {
                                userMapFromServer.put(entry.getKey(), entry.getValue().getAsString());
                            }
                            
                            if (!userMapFromServer.isEmpty()) {
                                notifyPresenceListeners(userMapFromServer);
                                System.out.println("Received bulk username updates for " + userMapFromServer.size() + " users");
                            }
                        } catch (Exception e) {
                            System.err.println("Error processing username updates: " + e.getMessage());
                        }
                    }
                    break;
                    
                case "insert":
                    handleInsertOperation(jsonMessage);
                    break;
                    
                case "delete":
                    handleDeleteOperation(jsonMessage);
                    break;
                    
                case "cursor_move":
                    handleCursorMoveOperation(jsonMessage);
                    break;
                    
                case "cursor_remove":
                    handleCursorRemoveOperation(jsonMessage);
                    break;
                    
                case "document_sync":
                    handleDocumentSyncOperation(jsonMessage);
                    break;
                    
                case "sync_confirmation_request":
                    handleSyncConfirmationRequest(jsonMessage);
                    break;
                    
                case "sync_confirmation":
                    // Log that document sync is confirmed
                    int docLength = jsonMessage.get("documentLength").getAsInt();
                    System.out.println("Document sync confirmed - document length: " + docLength);
                    break;
                    
                case "error":
                    notifyErrorListeners(jsonMessage.get("message").getAsString());
                    break;
                    
                case "usernames":
                    // Handle list of all usernames in the session
                    Map<String, String> userMap = new HashMap<>();
                    JsonObject usernamesObj = jsonMessage.getAsJsonObject("usernames");
                    
                    if (usernamesObj != null) {
                        for (Map.Entry<String, com.google.gson.JsonElement> entry : usernamesObj.entrySet()) {
                            String id = entry.getKey();
                            String name = entry.getValue().getAsString();
                            userMap.put(id, name);
                        }
                        
                        System.out.println("Received usernames for " + userMap.size() + " users");
                        
                        // Notify listeners of presence update
                        notifyPresenceListeners(userMap);
                    }
                    break;
                    
                case "user_joined":
                    // Handle notification that a specific user has joined
                    if (jsonMessage.has("userId") && jsonMessage.has("username")) {
                        String joinedUserId = jsonMessage.get("userId").getAsString();
                        String joinedUsername = jsonMessage.get("username").getAsString();
                        
                        System.out.println("⭐ IMPORTANT: Received notification that user joined: " + 
                                         joinedUsername + " (" + joinedUserId + ")");
                        
                        // Create a user map for this update
                        Map<String, String> joinUpdateMap = new HashMap<>();
                        
                        // Always add ourselves to this map
                        if (username != null && !username.isEmpty()) {
                            joinUpdateMap.put(userId, username);
                        }
                        
                        // Add the new user that joined
                        joinUpdateMap.put(joinedUserId, joinedUsername);
                        
                        // Force an immediate presence update with special flag to avoid filtering
                        notifyPresenceListeners(joinUpdateMap);
                        
                        // Request a full user list to ensure we're in sync
                        JsonObject presenceRequest = new JsonObject();
                        presenceRequest.addProperty("type", "request_presence");
                        presenceRequest.addProperty("userId", userId);
                        webSocketClient.send(gson.toJson(presenceRequest));
                        
                        // Also add this user to our last known cursor positions
                        lastKnownCursorPositions.put(joinedUserId, -1);
                    }
                    break;
                    
                default:
                    System.out.println("Unknown message type: " + type);
            }
        } catch (Exception e) {
            System.err.println("Error processing message: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    private void handleInsertOperation(JsonObject message) {
        try {
            String sourceUserId = message.get("userId").getAsString();
            JsonObject characterObj = message.getAsJsonObject("character");
            
            char value = characterObj.get("value").getAsCharacter();
            Position position = gson.fromJson(characterObj.getAsJsonObject("position"), Position.class);
            String authorId = characterObj.get("authorId").getAsString();
            long timestamp = characterObj.get("timestamp").getAsLong();
            
            // Create the CRDTCharacter
            CRDTCharacter character = new CRDTCharacter(value, position, authorId, timestamp);
            
            // Create and notify with the operation
            Operation operation = new Operation(Operation.Type.INSERT, character, null, sourceUserId, -1);
            notifyOperationListeners(operation);
        } catch (Exception e) {
            System.err.println("Error processing insert operation: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    private void handleDeleteOperation(JsonObject message) {
        try {
            String sourceUserId = message.get("userId").getAsString();
            Position position = gson.fromJson(message.getAsJsonObject("position"), Position.class);
            
            // Create and notify with the operation
            Operation operation = new Operation(Operation.Type.DELETE, null, position, sourceUserId, -1);
            notifyOperationListeners(operation);
        } catch (Exception e) {
            System.err.println("Error processing delete operation: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    private void handleCursorMoveOperation(JsonObject message) {
        try {
            String sourceUserId = message.get("userId").getAsString();
            int position = message.get("position").getAsInt();
            
            // Store the cursor position for later reference
            lastKnownCursorPositions.put(sourceUserId, position);
            
            // Create and notify with the operation
            Operation operation = new Operation(Operation.Type.CURSOR_MOVE, null, null, sourceUserId, position);
            notifyOperationListeners(operation);
        } catch (Exception e) {
            System.err.println("Error processing cursor move operation: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    private void handleCursorRemoveOperation(JsonObject message) {
        try {
            String sourceUserId = message.get("userId").getAsString();
            
            // Create and notify with the operation (position -1 means remove)
            Operation operation = new Operation(Operation.Type.CURSOR_MOVE, null, null, sourceUserId, -1);
            notifyOperationListeners(operation);
        } catch (Exception e) {
            System.err.println("Error processing cursor remove operation: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    private void handleDocumentSyncOperation(JsonObject message) {
        try {
            String content = message.get("content").getAsString();
            
            // Create a unique identifier for this sync message
            String syncId = "";
            if (message.has("timestamp")) {
                syncId = message.get("timestamp").getAsString();
            } else {
                syncId = content.hashCode() + "-" + System.currentTimeMillis();
            }
            
            // Check if we've already processed this exact sync recently
            synchronized (recentlySyncedDocuments) {
                if (recentlySyncedDocuments.contains(syncId)) {
                    System.out.println("Ignoring duplicate document sync: " + syncId);
                    return;
                }
                
                // Remember this sync to avoid duplicates
                recentlySyncedDocuments.add(syncId);
                
                // Limit the size of history to prevent memory leaks
                if (recentlySyncedDocuments.size() > 100) {
                    // Remove oldest entries when we exceed 100
                    Iterator<String> iterator = recentlySyncedDocuments.iterator();
                    for (int i = 0; i < 50 && iterator.hasNext(); i++) {
                        iterator.next();
                        iterator.remove();
                    }
                }
            }
            
            System.out.println("Received document sync with " + content.length() + " characters");
            
            // Check if there's a sender ID and it's our own message echoed back
            if (message.has("senderId") && message.get("senderId").getAsString().equals(userId)) {
                System.out.println("Ignoring document sync from our own user ID");
                return;
            }
            
            // Always log document sync for debugging
            boolean highPriority = message.has("highPriority") && message.get("highPriority").getAsBoolean();
            if (highPriority) {
                System.out.println("HIGH PRIORITY document sync received");
            }
            
            // Create a special operation for document sync
            Operation operation = new Operation(
                Operation.Type.DOCUMENT_SYNC, 
                null, 
                null, 
                message.has("senderId") ? message.get("senderId").getAsString() : userId, 
                -1, 
                content
            );
            
            // Queue the operation for immediate processing
            notifyOperationListeners(operation);
            
            // Send confirmation back to server (but not for our own updates)
            if (!message.has("senderId") || !message.get("senderId").getAsString().equals(userId)) {
                JsonObject confirmMsg = new JsonObject();
                confirmMsg.addProperty("type", "sync_confirmation");
                confirmMsg.addProperty("receivedLength", content.length());
                confirmMsg.addProperty("userId", userId);
                confirmMsg.addProperty("timestamp", System.currentTimeMillis());
                
                webSocketClient.send(gson.toJson(confirmMsg));
                System.out.println("Sent sync confirmation for " + content.length() + " characters");
            }
        } catch (Exception e) {
            System.err.println("Error processing document sync operation: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Handles a sync confirmation request from the server.
     * @param message The message from the server.
     */
    private void handleSyncConfirmationRequest(JsonObject message) {
        // Get the current document length through EditorController
        int currentDocLength = -1;
        
        try {
            // Create a special operation to request the document length
            Operation getDocumentOperation = new Operation(Operation.Type.GET_DOCUMENT_LENGTH, null, null, userId, -1);
            
            // Wait for listener to update the length
            for (Consumer<Operation> listener : operationListeners) {
                try {
                    listener.accept(getDocumentOperation);
                    // The listener should update the document length in the operation object
                    currentDocLength = getDocumentOperation.getDocumentLength();
                    break; // Only need one successful response
                } catch (Exception e) {
                    System.err.println("Error getting document length: " + e.getMessage());
                }
            }
            
            // Send confirmation back to server
            JsonObject confirmMsg = new JsonObject();
            confirmMsg.addProperty("type", "sync_confirmation");
            confirmMsg.addProperty("receivedLength", currentDocLength);
            confirmMsg.addProperty("userId", userId);
            
            webSocketClient.send(gson.toJson(confirmMsg));
            
            System.out.println("Sent document sync confirmation with length: " + currentDocLength);
            
            // Check if server expects us to have content but we don't
            int expectedLength = -1;
            if (message.has("expectedLength")) {
                expectedLength = message.get("expectedLength").getAsInt();
            }
            
            // If server expects content but we have none, or lengths don't match,
            // request a document resync
            if ((expectedLength > 0 && currentDocLength <= 0) || 
                (expectedLength > 0 && expectedLength != currentDocLength)) {
                System.out.println("Length mismatch: local=" + currentDocLength + 
                                  ", expected=" + expectedLength + ". Requesting resync.");
                
                // Force a document resync
                JsonObject resyncRequest = new JsonObject();
                resyncRequest.addProperty("type", "request_resync");
                resyncRequest.addProperty("userId", userId);
                webSocketClient.send(gson.toJson(resyncRequest));
                
                // Also notify listeners to trigger a local resync request
                Operation requestResyncOperation = new Operation(Operation.Type.REQUEST_DOCUMENT_RESYNC, 
                                                              null, null, userId, -1);
                notifyOperationListeners(requestResyncOperation);
            }
        } catch (Exception e) {
            System.err.println("Error handling sync confirmation request: " + e.getMessage());
            e.printStackTrace();
            
            // Send error notification
            JsonObject errorMsg = new JsonObject();
            errorMsg.addProperty("type", "sync_confirmation");
            errorMsg.addProperty("receivedLength", -1);
            errorMsg.addProperty("error", e.getMessage());
            errorMsg.addProperty("userId", userId);
            
            webSocketClient.send(gson.toJson(errorMsg));
        }
    }
    
    /**
     * Adds a listener for operations.
     * @param listener The listener to add.
     */
    public void addOperationListener(Consumer<Operation> listener) {
        operationListeners.add(listener);
    }
    
    /**
     * Adds a listener for presence updates.
     * @param listener the listener to add
     */
    public void addPresenceListener(Consumer<Map<String, String>> listener) {
        presenceListeners.add(listener);
    }
    
    /**
     * Adds a listener for errors.
     * @param listener The listener to add.
     */
    public void addErrorListener(Consumer<String> listener) {
        errorListeners.add(listener);
    }
    
    /**
     * Adds a listener for code updates.
     * @param listener The listener to add.
     */
    public void addCodeListener(Consumer<CodePair> listener) {
        codeListeners.add(listener);
    }
    
    private void notifyOperationListeners(Operation operation) {
        // Make a copy to avoid concurrent modification issues
        final List<Consumer<Operation>> listenersCopy = new ArrayList<>(operationListeners);
        
        if (Platform.isFxApplicationThread()) {
            // If already on JavaFX thread, execute directly
            for (Consumer<Operation> listener : listenersCopy) {
                try {
                    listener.accept(operation);
                } catch (Exception e) {
                    System.err.println("Error in operation listener: " + e.getMessage());
                }
            }
        } else {
            // Otherwise, use Platform.runLater
            Platform.runLater(() -> {
                for (Consumer<Operation> listener : listenersCopy) {
                    try {
                        listener.accept(operation);
                    } catch (Exception e) {
                        System.err.println("Error in operation listener: " + e.getMessage());
                    }
                }
            });
        }
    }
    
    /**
     * Notifies all presence listeners about user presence changes.
     * This version has been improved to handle both regular and high-priority updates.
     *
     * @param userMap User map from server update
     */
    private void notifyPresenceListeners(Map<String, String> userMap) {
        if (userMap == null) {
            return;
        }
        
        if (userMap.isEmpty() && !userMap.containsKey(userId)) {
            // Make sure we're always in our own map at minimum
            if (username != null && !username.isEmpty()) {
                userMap = new HashMap<>(userMap);
                userMap.put(userId, username);
            }
        }
        
        // Before notifying listeners, log what we're doing
        boolean hasOtherUsers = false;
        for (Map.Entry<String, String> entry : userMap.entrySet()) {
            if (!entry.getKey().equals(userId)) {
                hasOtherUsers = true;
                break;
            }
        }
        System.out.println("Notifying presence listeners with " + userMap.size() + 
                          " users" + (hasOtherUsers ? " including remote users" : " (only self)"));
        
        // Add all these users to our tracked cursor positions map
        for (String remoteUserId : userMap.keySet()) {
            if (!remoteUserId.equals(userId) && !lastKnownCursorPositions.containsKey(remoteUserId)) {
                // If we don't have a cursor position for this user yet, initialize to -1
                // (indicates no known position yet)
                lastKnownCursorPositions.put(remoteUserId, -1);
            }
        }
        
        // Make a copy to avoid concurrent modification issues
        final List<Consumer<Map<String, String>>> listenersCopy = new ArrayList<>(presenceListeners);
        final Map<String, String> userMapCopy = new HashMap<>(userMap);
        
        if (Platform.isFxApplicationThread()) {
            // If already on JavaFX thread, execute directly
            for (Consumer<Map<String, String>> listener : listenersCopy) {
                try {
                    listener.accept(userMapCopy);
                } catch (Exception e) {
                    System.err.println("Error in presence listener: " + e.getMessage());
                    e.printStackTrace();
                }
            }
        } else {
            // Otherwise, use Platform.runLater
            Platform.runLater(() -> {
                for (Consumer<Map<String, String>> listener : listenersCopy) {
                    try {
                        listener.accept(userMapCopy);
                    } catch (Exception e) {
                        System.err.println("Error in presence listener: " + e.getMessage());
                        e.printStackTrace();
                    }
                }
            });
        }
    }
    
    private void notifyErrorListeners(String error) {
        // Make a copy to avoid concurrent modification issues
        final List<Consumer<String>> listenersCopy = new ArrayList<>(errorListeners);
        final String errorMessage = error;
        
        if (Platform.isFxApplicationThread()) {
            // If already on JavaFX thread, execute directly
            for (Consumer<String> listener : listenersCopy) {
                try {
                    listener.accept(errorMessage);
                } catch (Exception e) {
                    System.err.println("Error in error listener: " + e.getMessage());
                }
            }
        } else {
            // Otherwise, use Platform.runLater
            Platform.runLater(() -> {
                for (Consumer<String> listener : listenersCopy) {
                    try {
                        listener.accept(errorMessage);
                    } catch (Exception e) {
                        System.err.println("Error in error listener: " + e.getMessage());
                    }
                }
            });
        }
    }
    
    private void notifyCodeListeners(CodePair codePair) {
        // Make a copy to avoid concurrent modification issues
        final List<Consumer<CodePair>> listenersCopy = new ArrayList<>(codeListeners);
        final CodePair codePairCopy = codePair;
        
        if (Platform.isFxApplicationThread()) {
            // If already on JavaFX thread, execute directly
            for (Consumer<CodePair> listener : listenersCopy) {
                try {
                    listener.accept(codePairCopy);
                } catch (Exception e) {
                    System.err.println("Error in code listener: " + e.getMessage());
                }
            }
        } else {
            // Otherwise, use Platform.runLater
            Platform.runLater(() -> {
                for (Consumer<CodePair> listener : listenersCopy) {
                    try {
                        listener.accept(codePairCopy);
                    } catch (Exception e) {
                        System.err.println("Error in code listener: " + e.getMessage());
                    }
                }
            });
        }
    }
    
    /**
     * Class representing a pair of editor and viewer codes.
     */
    public static class CodePair {
        private final String editorCode;
        private final String viewerCode;
        
        public CodePair(String editorCode, String viewerCode) {
            this.editorCode = editorCode;
            this.viewerCode = viewerCode;
        }
        
        public String getEditorCode() {
            return editorCode;
        }
        
        public String getViewerCode() {
            return viewerCode;
        }
    }
    
    /**
     * Sends the full document content to the server.
     * @param content The full document content.
     */
    public void sendDocumentUpdate(String content) {
        if (!connected) {
            notifyErrorListeners("Not connected to server");
            return;
        }
        
        // Don't send empty content
        final String finalContent = (content == null) ? "" : content;
        
        // Track document update frequency more strictly to prevent sync storms
        long now = System.currentTimeMillis();
        long lastDocUpdateTime = lastOperationTimes.getOrDefault("document_update", 0L);
        
        // Significantly increase throttling to 1000ms (1 second)
        if (now - lastDocUpdateTime < 1000) {
            System.out.println("Throttling document update - last update was " + (now - lastDocUpdateTime) + "ms ago");
            return;
        }
        
        // Record this update time
        lastOperationTimes.put("document_update", now);
        
        // Create the message
        JsonObject message = new JsonObject();
        message.addProperty("type", "document_update");
        message.addProperty("userId", userId);
        message.addProperty("content", finalContent);
        message.addProperty("timestamp", now);
        
        // Add a unique sequence number to help server detect duplicates
        message.addProperty("seq", System.currentTimeMillis());
        
        // Add retry logic for important document updates
        try {
            webSocketClient.send(gson.toJson(message));
            System.out.println("Sent document update with " + finalContent.length() + " chars");
        } catch (Exception e) {
            System.err.println("Error sending document update: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Sends an undo operation to the server.
     * @param operation The operation that was undone
     */
    public void sendUndo(Operation operation) {
        if (!connected) {
            notifyErrorListeners("Not connected to server");
            return;
        }
        
        JsonObject message = new JsonObject();
        message.addProperty("type", "undo");
        message.addProperty("userId", userId);
        message.addProperty("username", username);
        
        if (operation.getType() == Operation.Type.INSERT) {
            // For insert operations, we need to send the character position
            message.add("position", gson.toJsonTree(operation.getCharacter().getPosition()));
        } else {
            // For delete operations, we need to send the character
            JsonObject charObj = new JsonObject();
            charObj.addProperty("value", operation.getCharacter().getValue());
            charObj.add("position", gson.toJsonTree(operation.getCharacter().getPosition()));
            charObj.addProperty("authorId", operation.getCharacter().getAuthorId());
            charObj.addProperty("timestamp", operation.getCharacter().getTimestamp());
            message.add("character", charObj);
        }
        
        message.addProperty("operationType", operation.getType().toString());
        
        webSocketClient.send(gson.toJson(message));
    }
    
    /**
     * Sends a redo operation to the server.
     * @param operation The operation that was redone
     */
    public void sendRedo(Operation operation) {
        if (!connected) {
            notifyErrorListeners("Not connected to server");
            return;
        }
        
        JsonObject message = new JsonObject();
        message.addProperty("type", "redo");
        message.addProperty("userId", userId);
        message.addProperty("username", username);
        
        if (operation.getType() == Operation.Type.INSERT) {
            // For insert operations, we need to send the character
            JsonObject charObj = new JsonObject();
            charObj.addProperty("value", operation.getCharacter().getValue());
            charObj.add("position", gson.toJsonTree(operation.getCharacter().getPosition()));
            charObj.addProperty("authorId", operation.getCharacter().getAuthorId());
            charObj.addProperty("timestamp", operation.getCharacter().getTimestamp());
            message.add("character", charObj);
        } else {
            // For delete operations, we need to send the position
            message.add("position", gson.toJsonTree(operation.getCharacter().getPosition()));
        }
        
        message.addProperty("operationType", operation.getType().toString());
        
        webSocketClient.send(gson.toJson(message));
    }
    
    public void sendPresenceUpdate() {
        if (!connected) {
            return;
        }
        
        try {
            JsonObject message = new JsonObject();
            message.addProperty("type", "presence");
            message.addProperty("userId", userId);
            message.addProperty("username", username);
            message.addProperty("timestamp", System.currentTimeMillis());
            webSocketClient.send(gson.toJson(message));
        } catch (Exception e) {
            System.err.println("Error sending presence update: " + e.getMessage());
        }
    }
    
    public void addConnectionListener(Consumer<Boolean> listener) {
        connectionListeners.add(listener);
    }
    
    private void notifyConnectionListeners(boolean connected) {
        for (Consumer<Boolean> listener : connectionListeners) {
            listener.accept(connected);
        }
    }
    
    /**
     * Gets the last known cursor position for a specific user.
     * @param userId The user ID
     * @return The cursor position or null if unknown
     */
    public Integer getLastKnownCursorPosition(String userId) {
        return lastKnownCursorPositions.get(userId);
    }
    
    /**
     * Sets the user ID
     * @param userId The user ID to set
     */
    public void setUserId(String userId) {
        this.userId = userId;
    }
    
    /**
     * Sets the username
     * @param username The username to set
     */
    public void setUsername(String username) {
        this.username = username;
    }
    
    /**
     * Checks if the client is connected to the server
     * @return true if connected, false otherwise
     */
    public boolean isConnected() {
        return connected && webSocketClient != null && webSocketClient.isOpen();
    }
    
    /**
     * Clears the sync history periodically to prevent memory leaks.
     * This will run every 30 seconds to clean up the message history.
     */
    private void startSyncHistoryCleaner() {
        // Clean up every 30 seconds
        new Timer(true).schedule(new TimerTask() {
            @Override
            public void run() {
                synchronized (recentlySyncedDocuments) {
                    recentlySyncedDocuments.clear();
                    System.out.println("Cleared sync history");
                }
            }
        }, 30000, 30000);
    }
    
    /**
     * Sends a "leave session" message to properly clean up on the server.
     * Should be called when the user intentionally leaves a session.
     */
    public void leaveSession() {
        if (!connected) {
            return;
        }
        
        try {
            JsonObject message = new JsonObject();
            message.addProperty("type", "leave_session");
            message.addProperty("userId", userId);
            webSocketClient.send(gson.toJson(message));
            System.out.println("Sent leave session message to server");
        } catch (Exception e) {
            System.err.println("Error sending leave session message: " + e.getMessage());
        }
    }
    
    /**
     * Purges all users that might be disconnected from the client's perspective.
     * This method is called when reconnecting to ensure a clean slate.
     */
    private void purgeDisconnectedUsers() {
        // Clear all last known cursor positions
        lastKnownCursorPositions.clear();
        
        // Create an empty user map and notify listeners to clear their user lists
        Map<String, String> emptyUserMap = new HashMap<>();
        
        // Only add ourselves to this map
        if (username != null && !username.isEmpty()) {
            emptyUserMap.put(userId, username);
        }
        
        // Notify listeners to rebuild their user lists with just ourselves
        notifyPresenceListeners(emptyUserMap);
        
        System.out.println("Purged all disconnected users");
    }
} 