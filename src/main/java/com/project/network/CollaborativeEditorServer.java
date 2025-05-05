package com.project.network;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.project.crdt.CRDTCharacter;
import com.project.crdt.Position;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class CollaborativeEditorServer extends WebSocketServer {
    
    private static final int DEFAULT_PORT = 8887;
    private final Gson gson = new Gson();
    
    // Map of connection to user ID
    private final Map<WebSocket, String> connectionToUserId = new ConcurrentHashMap<>();
    
    // Map of active sessions by code
    private final Map<String, EditorSession> sessionsByCode = new ConcurrentHashMap<>();
    
    // Map of user ID to session
    private final Map<String, EditorSession> userSessions = new ConcurrentHashMap<>();
    
    // Map of user ID to connection
    private final Map<String, WebSocket> userConnections = new ConcurrentHashMap<>();
    
    // Map of user ID to cursor position
    private final Map<String, Integer> userCursorPositions = new ConcurrentHashMap<>();
    
    // Map of user ID to username
    private final Map<String, String> userMap = new ConcurrentHashMap<>();
    
    // Map of user ID to username
    private final Map<String, String> usernames = new ConcurrentHashMap<>();
    
    public CollaborativeEditorServer() {
        super(new InetSocketAddress(DEFAULT_PORT));
    }
    
    public CollaborativeEditorServer(int port) {
        super(new InetSocketAddress(port));
    }
    
    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        System.out.println("New connection from " + conn.getRemoteSocketAddress());
    }
    
    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        String userId = connectionToUserId.get(conn);
        System.out.println("Connection closed for user " + userId);
        
        if (userId != null) {
            // Remove user from their session
            EditorSession session = userSessions.get(userId);
            if (session != null) {
                session.removeUser(userId);
                
                // Log the users that remain in the session
                System.out.println("Users remaining in session: " + session.getAllUsers());
                
                if (session.isEmpty()) {
                    // Remove the session if it's empty
                    sessionsByCode.remove(session.getEditorCode());
                    sessionsByCode.remove(session.getViewerCode());
                    System.out.println("Session removed as it's now empty");
                } else {
                    // Notify other users that this user has left
                    broadcastPresenceUpdate(session);
                    
                    // Also remove this user's cursor from others' view
                    userCursorPositions.remove(userId);
                    
                    // Broadcast cursor removal
                    JsonObject cursorRemoveMsg = new JsonObject();
                    cursorRemoveMsg.addProperty("type", "cursor_remove");
                    cursorRemoveMsg.addProperty("userId", userId);
                    
                    for (String otherUserId : session.getAllUsers()) {
                        WebSocket otherConn = userConnections.get(otherUserId);
                        if (otherConn != null && otherConn.isOpen()) {
                            otherConn.send(gson.toJson(cursorRemoveMsg));
                        }
                    }
                }
                userSessions.remove(userId);
            }
            
            // Remove user from mappings
            userConnections.remove(userId);
            connectionToUserId.remove(conn);
            usernames.remove(userId);
            userCursorPositions.remove(userId);
            
            // Run a full cleanup to catch any orphaned sessions or users
            cleanupInactiveConnections();
        }
    }
    
    @Override
    public void onMessage(WebSocket conn, String message) {
        try {
            JsonObject jsonMessage = gson.fromJson(message, JsonObject.class);
            String type = jsonMessage.get("type").getAsString();
            
            switch (type) {
                case "register":
                    handleRegister(conn, jsonMessage);
                    break;
                case "create_session":
                    handleCreateSession(conn, jsonMessage);
                    break;
                case "join_session":
                    handleJoinSession(conn, jsonMessage);
                    break;
                case "leave_session":
                    handleLeaveSession(conn, jsonMessage);
                    break;
                case "insert":
                    handleInsert(conn, jsonMessage);
                    break;
                case "delete":
                    handleDelete(conn, jsonMessage);
                    break;
                case "cursor_move":
                    handleCursorMove(conn, jsonMessage);
                    break;
                case "document_update":
                    handleDocumentUpdate(conn, jsonMessage);
                    break;
                case "instant_document_update":
                    handleInstantDocumentUpdate(conn, jsonMessage);
                    break;
                case "undo":
                    handleUndo(conn, jsonMessage);
                    break;
                case "redo":
                    handleRedo(conn, jsonMessage);
                    break;
                case "sync_confirmation":
                    handleSyncConfirmation(conn, jsonMessage);
                    break;
                case "request_resync":
                    handleResyncRequest(conn, jsonMessage);
                    break;
                case "update_username":
                case "username_update":
                    handleUpdateUsername(conn, jsonMessage);
                    break;
                case "presence":
                    handlePresenceUpdate(conn, jsonMessage);
                    break;
                case "request_presence":
                    handleRequestPresence(conn, jsonMessage);
                    break;
                default:
                    sendError(conn, "Unknown message type: " + type);
            }
        } catch (Exception e) {
            System.err.println("Error processing message: " + e.getMessage());
            e.printStackTrace();
            sendError(conn, "Error processing message: " + e.getMessage());
        }
    }
    
    @Override
    public void onError(WebSocket conn, Exception ex) {
        if (conn != null) {
            System.err.println("Error occurred on connection " + conn.getRemoteSocketAddress() + ":" + ex.getMessage());
        } else {
            System.err.println("Server error occurred: " + ex.getMessage());
        }
        ex.printStackTrace();
    }
    
    @Override
    public void onStart() {
        System.out.println("WebSocket server started on port " + getPort());
    }
    
    private void handleRegister(WebSocket conn, JsonObject message) {
        String userId = message.get("userId").getAsString();
        
        // First check for existing connections with this user ID and clean them up
        WebSocket existingConn = userConnections.get(userId);
        if (existingConn != null && existingConn != conn && existingConn.isOpen()) {
            System.out.println("Found existing connection for user " + userId + ", closing it");
            try {
                // Send disconnect message to the existing connection
                JsonObject disconnectMsg = new JsonObject();
                disconnectMsg.addProperty("type", "force_disconnect");
                disconnectMsg.addProperty("reason", "New connection established");
                existingConn.send(gson.toJson(disconnectMsg));
                
                // Close the existing connection
                existingConn.close();
            } catch (Exception e) {
                System.err.println("Error closing existing connection: " + e.getMessage());
            }
        }
        
        // Store username if provided
        String username = null;
        if (message.has("username")) {
            username = message.get("username").getAsString();
            usernames.put(userId, username);
        }
        
        connectionToUserId.put(conn, userId);
        userConnections.put(userId, conn);
        
        // Send acknowledgment
        JsonObject response = new JsonObject();
        response.addProperty("type", "register_ack");
        response.addProperty("userId", userId);
        conn.send(gson.toJson(response));
    }
    
    private void handleCreateSession(WebSocket conn, JsonObject message) {
        String userId = connectionToUserId.get(conn);
        
        if (userId == null) {
            sendError(conn, "Not registered");
            return;
        }
        
        String sessionId = UUID.randomUUID().toString().substring(0, 6);
        String documentTitle = message.has("title") ? message.get("title").getAsString() : "Untitled Document";
        
        // Create a new session with the same code for both editor and viewer for simplicity
        EditorSession session = new EditorSession(sessionId, sessionId);
        
        // Add user as the initial editor
        session.addEditor(userId);
        
        // Store the session
        sessionsByCode.put(sessionId, session);
        
        // Associate the user with the session
        userSessions.put(userId, session);
        
        // Set document title if provided
        if (message.has("initialContent")) {
            String initialContent = message.get("initialContent").getAsString();
            session.setDocumentContent(initialContent);
            System.out.println("Created session with initial content: " + initialContent.length() + " chars");
        }
        
        // Send acknowledgment
        JsonObject response = new JsonObject();
        response.addProperty("type", "create_session_ack");
        response.addProperty("sessionId", sessionId);
        response.addProperty("documentTitle", documentTitle);
        
        conn.send(gson.toJson(response));
        
        System.out.println("User " + userId + " created session with ID " + sessionId);
        
        // Broadcast presence update
        broadcastPresenceUpdate(session);
    }
    
    /**
     * Handles a request from a client to join a session.
     */
    private void handleJoinSession(WebSocket conn, JsonObject message) {
        String userId = connectionToUserId.get(conn);
        if (userId == null) {
            sendError(conn, "Not registered");
            return;
        }
        
        // Check if we're joining with the old format (code) or new format (sessionId)
        String sessionId;
        if (message.has("code")) {
            sessionId = message.get("code").getAsString();
        } else if (message.has("sessionId")) {
            sessionId = message.get("sessionId").getAsString();
        } else {
            sendError(conn, "Missing session identifier");
            return;
        }
        
        System.out.println("User " + userId + " is joining session " + sessionId);
        
        // Check if user is already in a session and remove them from it
        EditorSession currentSession = userSessions.get(userId);
        if (currentSession != null && !currentSession.getEditorCode().equals(sessionId) && 
            !currentSession.getViewerCode().equals(sessionId)) {
            // User is joining a different session, remove from current one
            System.out.println("User " + userId + " is leaving previous session " + 
                currentSession.getEditorCode() + " to join " + sessionId);
            
            currentSession.removeUser(userId);
            
            // If the session is now empty, remove it
            if (currentSession.isEmpty()) {
                sessionsByCode.remove(currentSession.getEditorCode());
                sessionsByCode.remove(currentSession.getViewerCode());
                System.out.println("Previous session removed as it's now empty");
            } else {
                // Notify remaining users about the departure
                broadcastPresenceUpdate(currentSession);
            }
        }
        
        // Get or create the session
        final EditorSession session;
        EditorSession existingSession = sessionsByCode.get(sessionId);
        if (existingSession == null) {
            // Create a new session if it doesn't exist - makes joining more reliable
            System.out.println("Session not found, creating new session: " + sessionId);
            session = new EditorSession(sessionId, sessionId);
            sessionsByCode.put(sessionId, session);
        } else {
            session = existingSession;
        }
        
        // Check if joining as editor or viewer
        boolean asEditor = message.has("asEditor") && message.get("asEditor").getAsBoolean();
        
        // Check authorization - verify the user is allowed to join as an editor if they requested that role
        if (asEditor && !sessionId.equals(session.getEditorCode())) {
            sendError(conn, "Not authorized to join as editor with this code");
            System.out.println("User " + userId + " tried to join as editor but used viewer code");
            return;
        }
        
        // Add user to session based on role
        if (asEditor) {
            session.addEditor(userId);
        } else {
            session.addViewer(userId);
        }
        
        // Add session to user's sessions
        userSessions.put(userId, session);
        
        // Get username if provided
        if (message.has("username") && !message.get("username").isJsonNull()) {
            String providedUsername = message.get("username").getAsString();
            if (providedUsername != null && !providedUsername.isEmpty()) {
                usernames.put(userId, providedUsername);
                System.out.println("User joining with username: " + providedUsername);
            }
        }
        
        // Create a proper usernames object with full usernames
        JsonObject usernamesObject = new JsonObject();
        for (String user : session.getAllUsers()) {
            // Get the username for this user, with a fallback
            String username = usernames.get(user);
            if (username == null || username.isEmpty()) {
                // Generate a nicer anonymous name if no username is available
                username = "User-" + user.substring(0, Math.min(6, user.length()));
            }
            usernamesObject.addProperty(user, username);
        }
        
        // Create the join response
        JsonObject response = new JsonObject();
        response.addProperty("type", "join_session_ack");
        response.addProperty("sessionId", sessionId);
        
        // Send document content if available
        String documentContent = session.getDocument();
        if (documentContent != null && !documentContent.isEmpty()) {
            System.out.println("Sending document content to new user (" + documentContent.length() + " characters)");
            response.addProperty("documentContent", documentContent);
        } else {
            System.out.println("No document content available for session " + sessionId);
        }
        
        // Add usernames to response
        response.add("usernames", usernamesObject);
        
        // Send the join response to the client that joined
        conn.send(gson.toJson(response));
        
        // Force immediate username broadcast to ensure all clients have current info
        broadcastUsernames(session);
        
        // Then broadcast presence update to all participants
        broadcastPresenceUpdate(session);
        
        // After a delay, do another set of presence broadcasts to ensure everyone is in sync
        final String finalUserId = userId;
        final EditorSession finalSession = session;
        new Thread(() -> {
            try {
                // Wait a short time to allow initial setup to complete
                Thread.sleep(500);
                
                // Second broadcast of usernames
                broadcastUsernames(finalSession);
                
                // Second broadcast of presence
                broadcastPresenceUpdate(finalSession);
                
                // Send a direct presence update to the newly joined user
                sendPresenceToUser(finalUserId, finalSession);
                
                // Special high-priority presence update to all existing users
                sendHighPriorityPresenceUpdate(finalSession, finalUserId);
                
                // After another delay, do one final presence update
                Thread.sleep(500);
                broadcastPresenceUpdate(finalSession);
            } catch (Exception e) {
                System.err.println("Error during delayed presence update: " + e.getMessage());
            }
        }).start();
    }
    
    /**
     * Sends a direct presence update to a specific user
     */
    private void sendPresenceToUser(String targetUserId, EditorSession session) {
        if (session == null || targetUserId == null) {
            return;
        }
        
        try {
            WebSocket conn = userConnections.get(targetUserId);
            if (conn == null || !conn.isOpen()) {
                return;
            }
            
            // Create a filtered map of valid users in the session
            Map<String, String> userMap = new HashMap<>();
            
            for (String userId : session.getAllUsers()) {
                // Skip disconnected users
                WebSocket userConn = userConnections.get(userId);
                if (userConn == null || !userConn.isOpen()) {
                    continue;
                }
                
                // Skip UUID-format users
                if (userId.contains("-") || userId.length() > 24) {
                    continue;
                }
                
                // Get username
                String username = usernames.get(userId);
                if (username == null || username.isEmpty()) {
                    continue;
                }
                
                userMap.put(userId, username);
            }
            
            // Create presence message
            JsonObject message = new JsonObject();
            message.addProperty("type", "presence");
            message.addProperty("highPriority", true);
            message.add("users", gson.toJsonTree(userMap));
            
            // Send to the target user
            conn.send(gson.toJson(message));
            System.out.println("Sent direct presence update to user " + targetUserId);
        } catch (Exception e) {
            System.err.println("Error sending direct presence update: " + e.getMessage());
        }
    }
    
    /**
     * Sends a high-priority presence update to all users in a session, 
     * notifying them about a newly joined user.
     */
    private void sendHighPriorityPresenceUpdate(EditorSession session, String newUserId) {
        if (session == null || newUserId == null) {
            return;
        }
        
        try {
            // Get the username of the new user
            String newUsername = usernames.get(newUserId);
            if (newUsername == null || newUsername.isEmpty()) {
                // Use a default name if not set
                newUsername = "User-" + newUserId.substring(0, Math.min(6, newUserId.length()));
            }
            
            System.out.println("Sending high-priority notification about user: " + newUsername + " (" + newUserId + ")");
            
            // First send a dedicated user_joined message
            JsonObject joinMessage = new JsonObject();
            joinMessage.addProperty("type", "user_joined");
            joinMessage.addProperty("userId", newUserId);
            joinMessage.addProperty("username", newUsername);
            
            // Create a map of userIds to usernames
            JsonObject newUserObj = new JsonObject();
            newUserObj.addProperty(newUserId, newUsername);
            joinMessage.add("users", newUserObj);
            
            // Then also send a full presence update with all users
            JsonObject presenceMessage = new JsonObject();
            presenceMessage.addProperty("type", "presence");
            presenceMessage.addProperty("highPriority", true);
            
            // Create a full user map for all users in the session
            JsonObject fullUserObj = new JsonObject();
            for (String userId : session.getAllUsers()) {
                String username = usernames.get(userId);
                if (username == null || username.isEmpty()) {
                    // Use a default name if not set
                    username = "User-" + userId.substring(0, Math.min(6, userId.length()));
                }
                fullUserObj.addProperty(userId, username);
            }
            
            presenceMessage.add("users", fullUserObj);
            
            // Send both messages to all users in the session except the new user
            for (String userId : session.getAllUsers()) {
                WebSocket conn = userConnections.get(userId);
                if (conn != null && conn.isOpen()) {
                    // First send the dedicated join notification
                    conn.send(gson.toJson(joinMessage));
                    
                    // Then send the full presence update
                    conn.send(gson.toJson(presenceMessage));
                    
                    System.out.println("Sent notification to user: " + userId);
                }
            }
        } catch (Exception e) {
            System.err.println("Error sending high-priority presence update: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Broadcasts the list of usernames to all clients in a session.
     * @param session The session to broadcast to.
     */
    private void broadcastUsernames(EditorSession session) {
        if (session == null || session.isEmpty()) {
            return;
        }
        
        try {
            // Create a map of all userIDs to usernames in this session
            JsonObject message = new JsonObject();
            message.addProperty("type", "usernames");
            
            JsonObject usernamesObj = new JsonObject();
            
            // Add all users in the session with active connections
            for (String userId : session.getAllUsers()) {
                // Skip users without active connections
                WebSocket conn = userConnections.get(userId);
                if (conn == null || !conn.isOpen()) {
                    continue;
                }
                
                String username = usernames.get(userId);
                if (username == null || username.isEmpty()) {
                    // Use a default name if no username is set
                    username = "User-" + userId.substring(0, Math.min(6, userId.length()));
                }
                
                // Format username nicely if needed
                if (username.startsWith("User-") && username.length() > 5) {
                    username = "User " + username.substring(5);
                }
                
                usernamesObj.addProperty(userId, username);
                System.out.println("Adding user to broadcast: " + userId + " -> " + username);
            }
            
            message.add("usernames", usernamesObj);
            
            // Only broadcast if we have valid users
            if (usernamesObj.size() > 0) {
                // Broadcast to all valid users in the session
                String messageJson = gson.toJson(message);
                for (String userId : session.getAllUsers()) {
                    WebSocket conn = userConnections.get(userId);
                    if (conn != null && conn.isOpen()) {
                        conn.send(messageJson);
                    }
                }
                
                System.out.println("Broadcasted " + usernamesObj.size() + " usernames to session");
            } else {
                System.out.println("No valid users to broadcast usernames for");
            }
        } catch (Exception e) {
            System.err.println("Error broadcasting usernames: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Updates the list of active users in a session.
     * This is more aggressively filtered to remove phantom users.
     */
    private void broadcastPresenceUpdate(EditorSession session) {
        if (session == null || session.isEmpty()) {
            return;
        }
        
        JsonObject message = new JsonObject();
        message.addProperty("type", "presence");
        
        // First run a cleanup to ensure we don't have phantom users
        cleanupInactiveConnections();
        
        // Create a map of user IDs to usernames
        Map<String, String> userMap = new HashMap<>();
        
        // Filter out inactive or suspicious users
        Set<String> validUsers = new HashSet<>();
        for (String userId : session.getAllUsers()) {
            // Skip users without active connections
            WebSocket conn = userConnections.get(userId);
            if (conn == null || !conn.isOpen()) {
                System.out.println("Skipping inactive user: " + userId);
                continue;
            }
            
            // Skip UUID-format users (containing hyphens)
            if (userId.contains("-")) {
                System.out.println("Skipping UUID-format user: " + userId);
                continue;
            }
            
            // Skip excessively long user IDs (MongoDB IDs are 24 chars)
            if (userId.length() > 24) {
                System.out.println("Skipping overly long user ID: " + userId);
                continue;
            }
            
            // Get username
            String username = usernames.get(userId);
            
            // Filter out users with invalid usernames
            if (username == null || username.isEmpty()) {
                System.out.println("Skipping user with empty username: " + userId);
                continue;
            }
            
            // Skip users with UUID-like usernames
            if (username.contains("-") && username.length() > 20) {
                System.out.println("Skipping user with UUID-like username: " + username);
                continue;
            }
            
            // This user passed all filters
            validUsers.add(userId);
            
            // Use a friendly format for the username
            if (username == null || username.isEmpty()) {
                username = "User " + userId.substring(0, Math.min(6, userId.length()));
            }
            
            userMap.put(userId, username);
        }
        
        System.out.println("Broadcasting presence update for session " + session.getEditorCode() + 
                           " with " + validUsers.size() + " valid users");
        
        // Add the user map to the message
        message.add("users", gson.toJsonTree(userMap));
        
        // Also include the specific role information
        JsonArray editorsArray = new JsonArray();
        for (String editor : session.getEditors()) {
            // Only include editors who passed our filtering
            if (validUsers.contains(editor)) {
                editorsArray.add(editor);
            }
        }
        message.add("editors", editorsArray);
        
        JsonArray viewersArray = new JsonArray();
        for (String viewer : session.getViewers()) {
            // Only include viewers who passed our filtering
            if (validUsers.contains(viewer)) {
                viewersArray.add(viewer);
            }
        }
        message.add("viewers", viewersArray);
        
        // Send to all filtered users in the session
        for (String userId : validUsers) {
            WebSocket conn = userConnections.get(userId);
            if (conn != null && conn.isOpen()) {
                try {
                    conn.send(gson.toJson(message));
                } catch (Exception e) {
                    System.err.println("Error sending presence update to " + userId + ": " + e.getMessage());
                }
            }
        }
        
        // Update user lists in the session object to match what we just sent
        session.syncUserLists(validUsers);
        
        // Log the current users for debugging
        System.out.println("Session " + session.getEditorCode() + " has users: " + userMap);
    }
    
    private void handleInsert(WebSocket conn, JsonObject message) {
        String userId = connectionToUserId.get(conn);
        if (userId == null) {
            sendError(conn, "Not registered");
            return;
        }
        
        EditorSession session = userSessions.get(userId);
        if (session == null) {
            sendError(conn, "Not in a session");
            return;
        }
        
        // Check if the user is an editor
        if (!session.isEditor(userId)) {
            sendError(conn, "Not authorized to edit");
            return;
        }
        
        // Forward the insert operation to all users in the session
        broadcastToSession(session, message, userId);
    }
    
    private void handleDelete(WebSocket conn, JsonObject message) {
        String userId = connectionToUserId.get(conn);
        if (userId == null) {
            sendError(conn, "Not registered");
            return;
        }
        
        EditorSession session = userSessions.get(userId);
        if (session == null) {
            sendError(conn, "Not in a session");
            return;
        }
        
        // Check if the user is an editor
        if (!session.isEditor(userId)) {
            sendError(conn, "Not authorized to edit");
            return;
        }
        
        // Forward the delete operation to all users in the session
        broadcastToSession(session, message, userId);
    }
    
    /**
     * Handles cursor movement from a client.
     * Optimized for high-frequency cursor updates.
     */
    private void handleCursorMove(WebSocket conn, JsonObject message) {
        String userId = connectionToUserId.get(conn);
        int position = message.get("position").getAsInt();
        
        if (userId == null) {
            sendError(conn, "Not registered");
            return;
        }
        
        EditorSession session = userSessions.get(userId);
        if (session == null) {
            sendError(conn, "Not in a session");
            return;
        }
        
        // Store the cursor position
        Integer oldPosition = userCursorPositions.put(userId, position);
        
        // If position hasn't changed significantly (within 5 characters), don't broadcast
        // This reduces network traffic for minor cursor movements
        if (oldPosition != null && Math.abs(oldPosition - position) < 5) {
            return;
        }
        
        // Forward the cursor move to all users in the session except the sender
        // We'll optimize to reduce network traffic
        Set<String> targetUsers = session.getAllUsers();
        if (targetUsers.size() > 1) { // Only broadcast if there are other users
            for (String otherUserId : targetUsers) {
            if (!otherUserId.equals(userId)) {
                WebSocket otherConn = userConnections.get(otherUserId);
                if (otherConn != null && otherConn.isOpen()) {
                        try {
                    otherConn.send(gson.toJson(message));
                        } catch (Exception e) {
                            System.err.println("Error sending cursor update to " + otherUserId + ": " + e.getMessage());
                        }
                    }
                }
            }
        }
    }
    
    private void handleDocumentUpdate(WebSocket conn, JsonObject message) {
        String userId = connectionToUserId.get(conn);
        if (userId == null) {
            sendError(conn, "Not registered");
            return;
        }
        
        EditorSession session = userSessions.get(userId);
        if (session == null) {
            sendError(conn, "Not in a session");
            return;
        }
        
        // Only editors can update document content
        if (!session.isEditor(userId)) {
            sendError(conn, "Not authorized to edit");
            return;
        }
        
        // Update document content in session
        String content = message.get("content").getAsString();
        
        // Check if content has changed
        String currentContent = session.getDocument();
        if (content.equals(currentContent)) {
            System.out.println("Document update ignored - content unchanged");
            return;
        }
        
        session.updateDocument(content);
        System.out.println("Document updated by user " + userId + " (" + content.length() + " characters)");
        
        // Broadcast to all users in the session except sender
        JsonObject broadcastMsg = new JsonObject();
        broadcastMsg.addProperty("type", "document_sync");
        broadcastMsg.addProperty("content", content);
        broadcastMsg.addProperty("senderId", userId);
        
        for (String user : session.getUsers()) {
            if (!user.equals(userId)) {  // Skip the sender
                WebSocket userConn = userConnections.get(user);
                if (userConn != null && userConn.isOpen()) {
                    try {
                        userConn.send(gson.toJson(broadcastMsg));
                    } catch (Exception e) {
                        System.err.println("Error sending document update to user " + user + ": " + e.getMessage());
                    }
                }
            }
        }
    }
    
    private void broadcastToSession(EditorSession session, JsonObject message, String excludeUserId) {
        for (String userId : session.getAllUsers()) {
            if (!userId.equals(excludeUserId)) {
                WebSocket conn = userConnections.get(userId);
                if (conn != null && conn.isOpen()) {
                    conn.send(gson.toJson(message));
                }
            }
        }
    }
    
    private void sendError(WebSocket conn, String errorMessage) {
        JsonObject message = new JsonObject();
        message.addProperty("type", "error");
        message.addProperty("message", errorMessage);
        conn.send(gson.toJson(message));
    }
    
    private String generateUniqueCode(String prefix) {
        // Generate a simple 6-character code
        String code = prefix + "-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        
        // Make sure it's unique
        while (sessionsByCode.containsKey(code)) {
            code = prefix + "-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        }
        
        return code;
    }
    
    /**
     * Handles a sync confirmation message from a client.
     */
    private void handleSyncConfirmation(WebSocket conn, JsonObject message) {
        String userId = connectionToUserId.get(conn);
        if (userId == null) {
            sendError(conn, "Not registered");
            return;
        }
        
        int receivedLength = message.get("receivedLength").getAsInt();
        System.out.println("User " + userId + " confirmed document sync with " + receivedLength + " characters");
        
        // Check if the user is in a session
        EditorSession session = userSessions.get(userId);
        if (session == null) {
            System.out.println("Warning: Sync confirmation from user not in a session: " + userId);
            return;
        }
        
        // Check if the document length matches what we have
        String docContent = session.getDocument();
        if (docContent != null && docContent.length() != receivedLength) {
            System.out.println("Document length mismatch: server=" + docContent.length() + ", client=" + receivedLength);
            
            // Send a new document sync to correct the mismatch
            JsonObject syncMessage = new JsonObject();
            syncMessage.addProperty("type", "document_sync");
            syncMessage.addProperty("content", docContent);
            syncMessage.addProperty("highPriority", true);  // Mark as high priority
            
            try {
                conn.send(gson.toJson(syncMessage));
                System.out.println("Sent corrective document sync to user " + userId);
            } catch (Exception e) {
                System.err.println("Error sending corrective sync: " + e.getMessage());
            }
        }
    }
    
    /**
     * Handles a specialized instant document update.
     * This provides faster synchronization than regular document updates.
     */
    private void handleInstantDocumentUpdate(WebSocket conn, JsonObject message) {
        String userId = connectionToUserId.get(conn);
        
        if (userId == null) {
            sendError(conn, "Not registered");
            return;
        }
        
        EditorSession session = userSessions.get(userId);
        if (session == null) {
            sendError(conn, "Not in a session");
            return;
        }
        
        // Check if the user is an editor
        if (!session.isEditor(userId)) {
            sendError(conn, "Not authorized to edit");
            return;
        }
        
        // Immediately update the session's document content
        String content = message.get("content").getAsString();
        
        // Add a uniqueness check to prevent duplicate updates
        String currentContent = session.getDocument();
        if (content.equals(currentContent)) {
            System.out.println("Ignoring duplicate document update with same content");
            return;
        }
        
        session.setDocumentContent(content);
        System.out.println("Instant document update from user " + userId + " (" + content.length() + " chars)");
        
        // Forward to all other users in session with high priority
        JsonObject forwardMsg = new JsonObject();
        forwardMsg.addProperty("type", "document_sync");
        forwardMsg.addProperty("content", content);
        forwardMsg.addProperty("highPriority", true);
        forwardMsg.addProperty("timestamp", System.currentTimeMillis()); // Add timestamp for deduplication
        
        // Get the operation type (undo/redo)
        String operation = message.has("operation") ? message.get("operation").getAsString() : "";
        if (!operation.isEmpty()) {
            forwardMsg.addProperty("operation", operation);
        }
        
        // Send to all other users in session
        for (String otherUserId : session.getAllUsers()) {
            if (!otherUserId.equals(userId)) {
                WebSocket otherConn = userConnections.get(otherUserId);
                if (otherConn != null && otherConn.isOpen()) {
                    otherConn.send(gson.toJson(forwardMsg));
                }
            }
        }
    }
    
    /**
     * Handles an undo operation.
     */
    private void handleUndo(WebSocket conn, JsonObject message) {
        String userId = connectionToUserId.get(conn);
        
        if (userId == null) {
            sendError(conn, "Not registered");
            return;
        }
        
        EditorSession session = userSessions.get(userId);
        if (session == null) {
            sendError(conn, "Not in a session");
            return;
        }
        
        // Forward to all other users in session
        message.addProperty("forwardedByServer", true);
        
        for (String otherUserId : session.getAllUsers()) {
            if (!otherUserId.equals(userId)) {
                WebSocket otherConn = userConnections.get(otherUserId);
                if (otherConn != null && otherConn.isOpen()) {
                    otherConn.send(gson.toJson(message));
                }
            }
        }
    }
    
    /**
     * Handles a redo operation.
     */
    private void handleRedo(WebSocket conn, JsonObject message) {
        String userId = connectionToUserId.get(conn);
        
        if (userId == null) {
            sendError(conn, "Not registered");
            return;
        }
        
        EditorSession session = userSessions.get(userId);
        if (session == null) {
            sendError(conn, "Not in a session");
            return;
        }
        
        // Forward to all other users in session
        message.addProperty("forwardedByServer", true);
        
        for (String otherUserId : session.getAllUsers()) {
            if (!otherUserId.equals(userId)) {
                WebSocket otherConn = userConnections.get(otherUserId);
                if (otherConn != null && otherConn.isOpen()) {
                    otherConn.send(gson.toJson(message));
                }
            }
        }
    }
    
    /**
     * Handles a document resync request.
     */
    private void handleResyncRequest(WebSocket conn, JsonObject message) {
        String userId = connectionToUserId.get(conn);
        
        if (userId == null) {
            sendError(conn, "Not registered");
            return;
        }
        
        EditorSession session = userSessions.get(userId);
        if (session == null) {
            sendError(conn, "Not in a session");
            return;
        }
        
        System.out.println("Document resync requested by user " + userId);
        
        // Send the current document content
        String docContent = session.getDocument();
        if (docContent != null) {
            JsonObject syncMessage = new JsonObject();
            syncMessage.addProperty("type", "document_sync");
            syncMessage.addProperty("content", docContent);
            syncMessage.addProperty("highPriority", true);
            
            try {
                conn.send(gson.toJson(syncMessage));
                System.out.println("Sent document resync to user " + userId + " (" + docContent.length() + " chars)");
            } catch (Exception e) {
                System.err.println("Error sending document resync: " + e.getMessage());
            }
        } else {
            System.out.println("No document content available for resync");
        }
    }
    
    /**
     * Handles a username update message.
     */
    private void handleUpdateUsername(WebSocket conn, JsonObject message) {
        String userId = connectionToUserId.get(conn);
        
        if (userId == null) {
            sendError(conn, "Not registered");
            return;
        }
        
        // Check if username is provided before trying to access it
        if (!message.has("username")) {
            sendError(conn, "Username not provided");
            return;
        }
        
        // Handle possible null value
        String username = null;
        try {
            if (!message.get("username").isJsonNull()) {
                username = message.get("username").getAsString();
            }
        } catch (Exception e) {
            System.err.println("Error parsing username: " + e.getMessage());
            username = null;
        }
        
        // Provide a default if username is still null
        if (username == null || username.trim().isEmpty()) {
            username = "User-" + userId.substring(0, Math.min(6, userId.length()));
        }
        
        // Update the username
        usernames.put(userId, username);
        System.out.println("Updated username for user " + userId + " to: " + username);
        
        // Get the user's session and broadcast the update to all session members
        EditorSession session = userSessions.get(userId);
        if (session != null) {
            broadcastPresenceUpdate(session);
            broadcastUsernames(session);
        }
        
        // Send confirmation back to the client
        JsonObject response = new JsonObject();
        response.addProperty("type", "username_update_ack");
        response.addProperty("status", "success");
        response.addProperty("username", username);
        conn.send(gson.toJson(response));
    }
    
    /**
     * Handles a presence update from a client
     */
    private void handlePresenceUpdate(WebSocket conn, JsonObject message) {
        String userId = connectionToUserId.get(conn);
        
        if (userId == null) {
            return; // Silently ignore presence updates from unregistered users
        }
        
        // Simply receiving this message confirms the user is still active
        // Update the user's connection timestamp if needed
        // No need to broadcast to other users as this is just for keeping connection alive
        
        // Update username if provided
        if (message.has("username") && !message.get("username").isJsonNull()) {
            String username = message.get("username").getAsString();
            if (username != null && !username.isEmpty()) {
                usernames.put(userId, username);
            }
        }
    }
    
    /**
     * Handles a request from a client to leave their current session
     */
    private void handleLeaveSession(WebSocket conn, JsonObject message) {
        String userId = connectionToUserId.get(conn);
        
        if (userId == null) {
            sendError(conn, "Not registered");
            return;
        }
        
        EditorSession session = userSessions.get(userId);
        if (session == null) {
            // User is not in a session, nothing to do
            return;
        }
        
        System.out.println("User " + userId + " is leaving session: " + session.getEditorCode());
        
        // Remove user from the session
        session.removeUser(userId);
        
        // Remove session from user's sessions
        userSessions.remove(userId);
        
        // Send acknowledgment
        JsonObject response = new JsonObject();
        response.addProperty("type", "leave_session_ack");
        response.addProperty("status", "success");
        conn.send(gson.toJson(response));
        
        // If session is now empty, remove it
        if (session.isEmpty()) {
            sessionsByCode.remove(session.getEditorCode());
            sessionsByCode.remove(session.getViewerCode());
            System.out.println("Session removed as it's now empty");
        } else {
            // Notify remaining users about the departure
            broadcastPresenceUpdate(session);
            broadcastUsernames(session);
        }
        
        // Clean up any cursor position
        userCursorPositions.remove(userId);
        
        // Broadcast cursor removal to other users in the session
        JsonObject cursorRemoveMsg = new JsonObject();
        cursorRemoveMsg.addProperty("type", "cursor_remove");
        cursorRemoveMsg.addProperty("userId", userId);
            
            for (String otherUserId : session.getAllUsers()) {
                    WebSocket otherConn = userConnections.get(otherUserId);
                    if (otherConn != null && otherConn.isOpen()) {
                otherConn.send(gson.toJson(cursorRemoveMsg));
            }
        }
    }
    
    /**
     * Performs cleanup of inactive connections and sessions
     */
    private void cleanupInactiveConnections() {
        try {
            // Check all connections to see if they're still open
            Set<String> inactiveUsers = new HashSet<>();
            
            // First identify disconnected users
            for (Map.Entry<String, WebSocket> entry : userConnections.entrySet()) {
                String userId = entry.getKey();
                WebSocket conn = entry.getValue();
                
                if (conn == null || !conn.isOpen()) {
                    inactiveUsers.add(userId);
                    System.out.println("Detected inactive connection for user: " + userId);
                }
            }
            
            // Also check for orphaned users in sessions with no active connections
            for (String userId : userSessions.keySet()) {
                if (!userConnections.containsKey(userId) || !userConnections.get(userId).isOpen()) {
                    inactiveUsers.add(userId);
                    System.out.println("Detected orphaned user in session: " + userId);
                }
            }
            
            // Remove inactive users
            for (String userId : inactiveUsers) {
                System.out.println("Cleaning up inactive user: " + userId);
                
                // Remove from their session
                EditorSession session = userSessions.get(userId);
                if (session != null) {
                    session.removeUser(userId);
                    
                    if (session.isEmpty()) {
                        sessionsByCode.remove(session.getEditorCode());
                        sessionsByCode.remove(session.getViewerCode());
                        System.out.println("Removed empty session during cleanup");
                    } else {
                        // Broadcast updated presence and usernames to remaining users
                        broadcastPresenceUpdate(session);
                        broadcastUsernames(session);
                    }
                }
                
                // Remove from all mappings
                userSessions.remove(userId);
                userConnections.remove(userId);
                userCursorPositions.remove(userId);
                usernames.remove(userId);
                
                // Remove from connectionToUserId (find the connection for this userId)
                for (Map.Entry<WebSocket, String> entry : new HashMap<>(connectionToUserId).entrySet()) {
                    if (userId.equals(entry.getValue())) {
                        connectionToUserId.remove(entry.getKey());
                    }
                }
            }
            
            if (!inactiveUsers.isEmpty()) {
                System.out.println("Cleaned up " + inactiveUsers.size() + " inactive users");
                
                // Debug - list remaining users
                System.out.println("Remaining active users: " + userConnections.keySet());
            }
            
            // Check for orphaned sessions with no users
            List<String> orphanedSessions = new ArrayList<>();
            for (Map.Entry<String, EditorSession> entry : sessionsByCode.entrySet()) {
                if (entry.getValue().isEmpty()) {
                    orphanedSessions.add(entry.getKey());
                }
            }
            
            // Remove orphaned sessions
            for (String sessionCode : orphanedSessions) {
                sessionsByCode.remove(sessionCode);
                System.out.println("Removed orphaned session: " + sessionCode);
            }
            
        } catch (Exception e) {
            System.err.println("Error during connection cleanup: " + e.getMessage());
        }
    }
    
    /**
     * Tests if a user is still connected by sending a ping message.
     * @param userId The user ID to test
     * @return true if the user is connected, false otherwise
     */
    private boolean isUserConnected(String userId) {
        WebSocket conn = userConnections.get(userId);
        if (conn == null || !conn.isOpen()) {
            return false;
        }
        
        try {
            // Try to send a ping
            conn.sendPing();
            return true;
        } catch (Exception e) {
            System.err.println("Error pinging user " + userId + ": " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Class representing an editing session
     */
    private static class EditorSession {
        private Set<String> editors = new HashSet<>();
        private Set<String> viewers = new HashSet<>();
        private String editorCode;
        private String viewerCode;
        private String documentContent = "";
        private long lastActivityTime = System.currentTimeMillis();
        
        public EditorSession(String editorCode, String viewerCode) {
            this.editorCode = editorCode;
            this.viewerCode = viewerCode;
        }
        
        public String getEditorCode() {
            return editorCode;
        }
        
        public String getViewerCode() {
            return viewerCode;
        }
        
        public void addEditor(String userId) {
            editors.add(userId);
            updateActivity();
        }
        
        public void addViewer(String userId) {
            viewers.add(userId);
            updateActivity();
        }
        
        public void addUser(String userId) {
            // By default, add as editor for simplicity in our updated model
            editors.add(userId);
            updateActivity();
            System.out.println("Added user " + userId + " as editor");
        }
        
        public boolean isEditor(String userId) {
            return editors.contains(userId);
        }
        
        public Set<String> getEditors() {
            return editors;
        }
        
        public Set<String> getViewers() {
            return viewers;
        }
        
        public Set<String> getAllUsers() {
            Set<String> allUsers = new HashSet<>(editors);
            allUsers.addAll(viewers);
            return allUsers;
        }
        
        public void setDocumentContent(String content) {
            this.documentContent = content;
            updateActivity();
        }
        
        public String getDocumentContent() {
            return documentContent;
        }
        
        public String getDocument() {
            return documentContent;
        }
        
        public void updateDocument(String content) {
            this.documentContent = content;
            updateActivity();
        }
        
        public Set<String> getUsers() {
            Set<String> users = new HashSet<>(editors);
            users.addAll(viewers);
            return users;
        }
        
        public void removeUser(String userId) {
            editors.remove(userId);
            viewers.remove(userId);
            updateActivity();
        }
        
        public boolean isEmpty() {
            return editors.isEmpty() && viewers.isEmpty();
        }
        
        public void syncUserLists(Set<String> filteredUsers) {
            // Update editor list - only keep users who are both in the filtered list and were editors
            Set<String> newEditors = new HashSet<>();
            for (String userId : filteredUsers) {
                if (editors.contains(userId)) {
                    newEditors.add(userId);
                }
            }
            editors = newEditors;
            
            // Update viewer list - only keep users who are both in the filtered list and were viewers
            Set<String> newViewers = new HashSet<>();
            for (String userId : filteredUsers) {
                if (viewers.contains(userId)) {
                    newViewers.add(userId);
                }
            }
            viewers = newViewers;
            
            updateActivity();
        }
        
        private void updateActivity() {
            lastActivityTime = System.currentTimeMillis();
        }
        
        public long getLastActivityTime() {
            return lastActivityTime;
        }
        
        public boolean isInactive() {
            // Session is inactive if no activity for more than 2 hours
            return System.currentTimeMillis() - lastActivityTime > 7200000;
        }
    }
    
    /**
     * Handles a request for presence updates
     */
    private void handleRequestPresence(WebSocket conn, JsonObject message) {
        String userId = connectionToUserId.get(conn);
        
        if (userId == null) {
            return; // Silently ignore if not registered
        }
        
        System.out.println("Received presence request from user: " + userId);
        
        // Find the session the user is in
        EditorSession session = userSessions.get(userId);
        if (session == null) {
            return; // Not in a session
        }
        
        // Send a direct presence update to this user
        sendPresenceToUser(userId, session);
        
        // Also broadcast usernames
        broadcastUsernames(session);
    }
    
    public static void main(String[] args) {
        int port = DEFAULT_PORT;
        
        // Check if a custom port was specified
        if (args.length > 0) {
            try {
                port = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                System.err.println("Invalid port number. Using default port " + DEFAULT_PORT);
            }
        }
        
        CollaborativeEditorServer server = new CollaborativeEditorServer(port);
        server.start();
        System.out.println("Collaborative Editor Server started on port " + port);
    }
} 