package com.project.network;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;
import org.mindrot.jbcrypt.BCrypt;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Service for handling database operations related to users and documents.
 * This implementation can work without MongoDB by using in-memory storage.
 */
public class DatabaseService {
    // Get MongoDB connection string from environment variable or use default for local development
    private static final String CONNECTION_STRING = System.getenv("MONGODB_URI") != null ? 
            System.getenv("MONGODB_URI") : 
            "mongodb://mongo:InLginAnmktFiZxGJRuqWcmtAbRROPnC@centerbeam.proxy.rlwy.net:26289";
            
    private static final String DATABASE_NAME = System.getenv("MONGODB_DATABASE") != null ? 
            System.getenv("MONGODB_DATABASE") : 
            "collaborative_editor";
            
    private static final String USERS_COLLECTION = "users";
    private static final String DOCUMENTS_COLLECTION = "documents";
    
    // Add a flag to track if we successfully connected to MongoDB
    private boolean mongoDbConnected = false;
    private MongoClient mongoClient;
    private MongoDatabase database;
    private MongoCollection<Document> usersCollection;
    private MongoCollection<Document> documentsCollection;
    
    // In-memory storage for when MongoDB is not available
    private Map<String, User> userMap = new HashMap<>();
    private Map<String, InMemoryDocument> documentMap = new HashMap<>();
    
    private boolean useInMemoryStorage = false;
    
    private static DatabaseService instance;
    
    /**
     * Gets the singleton instance of the DatabaseService.
     * @return The DatabaseService instance.
     */
    public static synchronized DatabaseService getInstance() {
        if (instance == null) {
            instance = new DatabaseService();
        }
        return instance;
    }
    
    /**
     * Private constructor for the singleton pattern.
     */
    private DatabaseService() {
        try {
            System.out.println("==================================================");
            System.out.println("Attempting to connect to MongoDB...");
            
            // Print environment information for debugging
            System.out.println("Environment variables:");
            System.out.println("  MONGODB_URI: " + (System.getenv("MONGODB_URI") != null ? 
                    System.getenv("MONGODB_URI").replaceAll(":[^/]+@", ":******@") : "not set"));
            System.out.println("  MONGODB_DATABASE: " + (System.getenv("MONGODB_DATABASE") != null ? 
                    System.getenv("MONGODB_DATABASE") : "not set"));
            
            System.out.println("Using connection string: " + CONNECTION_STRING.replaceAll(":[^/]+@", ":******@"));
            
            // Set a shorter connection timeout 
            mongoClient = MongoClients.create(CONNECTION_STRING);
            
            // Test connection immediately to fail fast
            database = mongoClient.getDatabase(DATABASE_NAME);
            database.runCommand(new Document("ping", 1));
            
            usersCollection = database.getCollection(USERS_COLLECTION);
            documentsCollection = database.getCollection(DOCUMENTS_COLLECTION);
            
            // Verify collections by counting documents
            long userCount = usersCollection.countDocuments();
            long docCount = documentsCollection.countDocuments();
            
            System.out.println("==================================================");
            System.out.println("MongoDB connection successful!");
            System.out.println("Database: " + DATABASE_NAME);
            System.out.println("Users collection: " + userCount + " documents");
            System.out.println("Documents collection: " + docCount + " documents");
            System.out.println("Your data will be saved persistently to MongoDB");
            System.out.println("==================================================");
            
            // Ensure collections exist - if they don't, create them
            if (!collectionExists(USERS_COLLECTION)) {
                database.createCollection(USERS_COLLECTION);
                System.out.println("Created users collection");
            }
            
            if (!collectionExists(DOCUMENTS_COLLECTION)) {
                database.createCollection(DOCUMENTS_COLLECTION);
                System.out.println("Created documents collection");
            }
            
            useInMemoryStorage = false;
            mongoDbConnected = true;
        } catch (Exception e) {
            System.err.println("==================================================");
            System.err.println("ERROR: Failed to connect to MongoDB!");
            System.err.println("Error message: " + e.getMessage());
            System.err.println("IMPORTANT: FALLING BACK TO IN-MEMORY STORAGE!");
            System.err.println("WARNING: Your data will NOT be saved permanently!");
            System.err.println("==================================================");
            e.printStackTrace();
            
            // Make sure to close any connection that might have been created
            if (mongoClient != null) {
                try {
                    mongoClient.close();
                } catch (Exception ex) {
                    // Ignore, we're already in an error state
                }
                mongoClient = null;
            }
            
            useInMemoryStorage = true;
            mongoDbConnected = false;
            
            // Create a demo user for easier testing when MongoDB is not available
            createDemoUser();
        }
    }
    
    /**
     * Checks if a collection exists in the database.
     */
    private boolean collectionExists(String collectionName) {
        for (String name : database.listCollectionNames()) {
            if (name.equals(collectionName)) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * Creates a demo user when in-memory storage is active.
     */
    private void createDemoUser() {
        if (!useInMemoryStorage) {
            return;
        }
        
        String userId = UUID.randomUUID().toString();
        String username = "demo";
        String hashedPassword = BCrypt.hashpw("password", BCrypt.gensalt());
        User user = new User(userId, username, hashedPassword, new Date());
        userMap.put(userId, user);
        
        System.out.println("Created demo user. Username: 'demo', Password: 'password'");
        
        // Create a sample document for the demo user
        String documentId = createDocumentInMemory("Welcome Document", userId);
        InMemoryDocument document = documentMap.get(documentId);
        document.content = "Welcome to the collaborative editor!\n\nThis is a sample document created for demonstration purposes.";
    }
    
    /**
     * Registers a new user.
     * @param username The username.
     * @param password The password.
     * @return True if registration was successful, false otherwise.
     */
    public boolean registerUser(String username, String password) {
        if (username == null || username.trim().isEmpty() || password == null || password.isEmpty()) {
            System.err.println("Cannot register user: Username or password is empty");
            return false;
        }
        
        // Trim username for consistency
        username = username.trim();
        
        if (useInMemoryStorage) {
            System.out.println("Using in-memory storage for user registration: " + username);
            return registerUserInMemory(username, password);
        }
        
        try {
            System.out.println("Attempting to register user in MongoDB: " + username);
            
            // Double-check the MongoDB connection
            if (!mongoDbConnected) {
                System.err.println("MongoDB not connected, falling back to in-memory storage");
                return registerUserInMemory(username, password);
            }
            
            // Check if username already exists
            Document existingUser = usersCollection.find(Filters.eq("username", username)).first();
            if (existingUser != null) {
                System.out.println("Username already exists: " + username);
                return false;
            }
            
            // Hash the password
            String hashedPassword = BCrypt.hashpw(password, BCrypt.gensalt());
            
            // Create a new user document
            Document newUser = new Document()
                    .append("username", username)
                    .append("password", hashedPassword)
                    .append("createdAt", new Date())
                    .append("lastLogin", new Date());
            
            // Insert the user into MongoDB
            usersCollection.insertOne(newUser);
            
            // Confirm the user was added by fetching the new document
            Document confirmUser = usersCollection.find(Filters.eq("username", username)).first();
            if (confirmUser != null) {
                System.out.println("Successfully registered user in MongoDB: " + username);
                return true;
            } else {
                System.err.println("User registration verification failed: " + username);
                return registerUserInMemory(username, password);
            }
        } catch (Exception e) {
            System.err.println("Error registering user in MongoDB: " + e.getMessage());
            e.printStackTrace();
            return registerUserInMemory(username, password);
        }
    }
    
    private boolean registerUserInMemory(String username, String password) {
        // Check if username already exists
        if (userMap.values().stream().anyMatch(u -> u.username.equals(username))) {
            return false;
        }
        
        // Hash the password
        String hashedPassword = BCrypt.hashpw(password, BCrypt.gensalt());
        
        // Create a new user
        String userId = UUID.randomUUID().toString();
        User user = new User(userId, username, hashedPassword, new Date());
        userMap.put(userId, user);
        
        System.out.println("Successfully registered user in memory: " + username + " with ID: " + userId);
        return true;
    }
    
    /**
     * Authenticates a user.
     * @param username The username.
     * @param password The password.
     * @return The user ID if authentication was successful, null otherwise.
     */
    public String authenticateUser(String username, String password) {
        if (username == null || username.trim().isEmpty() || password == null || password.isEmpty()) {
            System.err.println("Cannot authenticate: Username or password is empty");
            return null;
        }
        
        // Trim username for consistency
        username = username.trim();
        
        if (useInMemoryStorage) {
            System.out.println("Using in-memory storage for authentication: " + username);
            return authenticateUserInMemory(username, password);
        }
        
        try {
            System.out.println("Attempting to authenticate user in MongoDB: " + username);
            
            // Double-check the MongoDB connection
            if (!mongoDbConnected) {
                System.err.println("MongoDB not connected, falling back to in-memory authentication");
                return authenticateUserInMemory(username, password);
            }
            
            Document user = usersCollection.find(Filters.eq("username", username)).first();
            if (user == null) {
                System.out.println("User not found in MongoDB: " + username);
                return authenticateUserInMemory(username, password);
            }
            
            String hashedPassword = user.getString("password");
            if (BCrypt.checkpw(password, hashedPassword)) {
                Object idObj = user.get("_id");
                String userId;
                if (idObj instanceof ObjectId) {
                    userId = ((ObjectId) idObj).toString();
                } else {
                    userId = idObj.toString();
                }
                
                // Update last login time
                usersCollection.updateOne(
                    Filters.eq("_id", user.get("_id")), 
                    Updates.set("lastLogin", new Date())
                );
                
                System.out.println("Successfully authenticated user in MongoDB: " + username + " with ID: " + userId);
                return userId;
            } else {
                System.out.println("Invalid password for user: " + username);
            }
            
            return null;
        } catch (Exception e) {
            System.err.println("Error authenticating user in MongoDB: " + e.getMessage());
            e.printStackTrace();
            return authenticateUserInMemory(username, password);
        }
    }
    
    private String authenticateUserInMemory(String username, String password) {
        // Find user by username
        for (Map.Entry<String, User> entry : userMap.entrySet()) {
            if (entry.getValue().username.equals(username)) {
                if (BCrypt.checkpw(password, entry.getValue().hashedPassword)) {
                    // Update last login time
                    entry.getValue().lastLogin = new Date();
                    System.out.println("Successfully authenticated user in memory: " + username + " with ID: " + entry.getKey());
                    return entry.getKey();
                }
                return null;
            }
        }
        
        // If we're in demo mode, auto-create a user
        if (username.equals("demo") || userMap.isEmpty()) {
            String userId = UUID.randomUUID().toString();
            String hashedPassword = BCrypt.hashpw(password, BCrypt.gensalt());
            User user = new User(userId, username, hashedPassword, new Date());
            userMap.put(userId, user);
            System.out.println("Auto-created and authenticated user in memory: " + username + " with ID: " + userId);
            return userId;
        }
        
        return null;
    }
    
    /**
     * Creates a new document.
     * @param title The document title.
     * @param ownerId The owner's user ID.
     * @return The document ID if creation was successful, null otherwise.
     */
    public String createDocument(String title, String ownerId) {
        if (useInMemoryStorage) {
            return createDocumentInMemory(title, ownerId);
        }
        
        try {
            Document newDocument = new Document()
                    .append("title", title)
                    .append("ownerId", ownerId)
                    .append("content", "")
                    .append("createdAt", new Date())
                    .append("updatedAt", new Date());
            
            documentsCollection.insertOne(newDocument);
            Object idObj = newDocument.get("_id");
            if (idObj instanceof ObjectId) {
                return ((ObjectId) idObj).toString();
            } else {
                return idObj.toString();
            }
        } catch (Exception e) {
            System.err.println("Error creating document: " + e.getMessage());
            e.printStackTrace();
            return createDocumentInMemory(title, ownerId);
        }
    }
    
    private String createDocumentInMemory(String title, String ownerId) {
        String documentId = UUID.randomUUID().toString();
        InMemoryDocument document = new InMemoryDocument(documentId, title, ownerId, "", new Date(), new Date());
        documentMap.put(documentId, document);
        return documentId;
    }
    
    /**
     * Updates a document's content.
     * @param documentId The document ID.
     * @param content The new content.
     * @return True if the update was successful, false otherwise.
     */
    public boolean updateDocument(String documentId, String content) {
        if (useInMemoryStorage) {
            return updateDocumentInMemory(documentId, content);
        }
        
        try {
            // Handle different ID formats safely
            Object idToQuery;
            try {
                // First try to parse as ObjectId
                idToQuery = new ObjectId(documentId);
            } catch (Exception e) {
                // If it fails, use as string ID
                System.out.println("Using string ID instead of ObjectId: " + documentId);
                idToQuery = documentId;
            }
            
            Bson filter = Filters.eq("_id", idToQuery);
            Bson update = Updates.combine(
                    Updates.set("content", content),
                    Updates.set("updatedAt", new Date())
            );
            
            documentsCollection.updateOne(filter, update);
            return true;
        } catch (Exception e) {
            System.err.println("Error updating document: " + e.getMessage());
            e.printStackTrace();
            return updateDocumentInMemory(documentId, content);
        }
    }
    
    private boolean updateDocumentInMemory(String documentId, String content) {
        InMemoryDocument document = documentMap.get(documentId);
        if (document != null) {
            document.content = content;
            document.updatedAt = new Date();
            return true;
        }
        return false;
    }
    
    /**
     * Updates a document's content and session codes.
     * @param documentId The document ID.
     * @param content The new content.
     * @param editorCode The editor session code.
     * @param viewerCode The viewer session code.
     * @return True if the update was successful, false otherwise.
     */
    public boolean updateDocumentWithSession(String documentId, String content, 
                                          String editorCode, String viewerCode) {
        if (useInMemoryStorage) {
            return updateDocumentWithSessionInMemory(documentId, content, editorCode, viewerCode);
        }
        
        try {
            // Check MongoDB connection status first
            if (!mongoDbConnected) {
                System.out.println("MongoDB not connected, falling back to in-memory storage");
                return updateDocumentWithSessionInMemory(documentId, content, editorCode, viewerCode);
            }
            
            // Try to safely convert the document ID to an ObjectId
            Object documentObjectId;
            try {
                documentObjectId = new ObjectId(documentId);
            } catch (Exception e) {
                System.out.println("Invalid ObjectId format, using string ID: " + documentId);
                documentObjectId = documentId;
            }
            
            // Create the update document with the new fields
            Document update = new Document();
            if (content != null) {
                update.append("content", content);
            }
            update.append("editorCode", editorCode)
                  .append("viewerCode", viewerCode)
                  .append("updatedAt", new Date());
            
            try {
                // Try to update the document safely
                documentsCollection.updateOne(
                    Filters.eq("_id", documentObjectId),
                    new Document("$set", update));
                return true;
            } catch (IllegalStateException e) {
                // This is a connection state error - mark connection as closed and try to reconnect
                System.out.println("MongoDB connection state error: " + e.getMessage());
                mongoDbConnected = false;
                
                // Try once to reconnect
                if (attemptReconnect()) {
                    try {
                        documentsCollection.updateOne(
                            Filters.eq("_id", documentObjectId),
                            new Document("$set", update));
                        return true;
                    } catch (Exception e2) {
                        System.err.println("Update failed even after reconnect: " + e2.getMessage());
                        return updateDocumentWithSessionInMemory(documentId, content, editorCode, viewerCode);
                    }
                } else {
                    // If reconnection failed, fall back to in-memory
                    return updateDocumentWithSessionInMemory(documentId, content, editorCode, viewerCode);
                }
            } catch (Exception e) {
                System.err.println("Error updating document: " + e.getMessage());
                return updateDocumentWithSessionInMemory(documentId, content, editorCode, viewerCode);
            }
        } catch (Exception e) {
            System.err.println("Error updating document with session: " + e.getMessage());
            return updateDocumentWithSessionInMemory(documentId, content, editorCode, viewerCode);
        }
    }
    
    /**
     * Attempts to reconnect to MongoDB after a connection failure.
     * @return true if the reconnection was successful, false otherwise.
     */
    private synchronized boolean attemptReconnect() {
        // If we're already using in-memory storage, no need to attempt reconnection
        if (useInMemoryStorage) {
            return false;
        }
        
        System.out.println("Attempting to reconnect to MongoDB...");
        
        try {
            // Close existing client if any
            if (mongoClient != null) {
                try {
                    mongoClient.close();
                } catch (Exception e) {
                    // Ignore cleanup errors
                }
                mongoClient = null;
            }
            
            // Create a new client with a shorter timeout
            mongoClient = MongoClients.create(
                CONNECTION_STRING + "&connectTimeoutMS=2000&serverSelectionTimeoutMS=2000");
            database = mongoClient.getDatabase(DATABASE_NAME);
            
            // Test the connection by checking if the database exists
            database.runCommand(new Document("ping", 1));
            
            // If we get here, the connection is successful
            System.out.println("Successfully reconnected to MongoDB");
            
            // Re-initialize collections
            usersCollection = database.getCollection(USERS_COLLECTION);
            documentsCollection = database.getCollection(DOCUMENTS_COLLECTION);
            
            mongoDbConnected = true;
            return true;
        } catch (Exception e) {
            // If we still can't connect, switch to in-memory mode
            System.err.println("Failed to reconnect to MongoDB: " + e.getMessage());
            
            if (!useInMemoryStorage) {
                System.out.println("Permanently switching to in-memory storage after repeated connection failures");
                useInMemoryStorage = true;
                
                // Create a demo user if in-memory storage is empty
                if (userMap.isEmpty()) {
                    createDemoUser();
                }
            }
            return false;
        }
    }
    
    /**
     * Gets a document by ID.
     * @param documentId The document ID.
     * @return The document, or null if not found.
     */
    public Document getDocument(String documentId) {
        if (documentId == null || documentId.isEmpty()) {
            System.err.println("Cannot get document: Document ID is null or empty");
            return null;
        }
        
        System.out.println("Retrieving document with ID: " + documentId);
        
        if (useInMemoryStorage) {
            System.out.println("Using in-memory storage to retrieve document");
            Document doc = getDocumentInMemory(documentId);
            if (doc != null) {
                System.out.println("Found document in memory with ID: " + documentId);
                System.out.println("Title: " + doc.getString("title"));
                System.out.println("Content length: " + (doc.getString("content") != null ? doc.getString("content").length() : 0) + " characters");
            } else {
                System.out.println("Document not found in memory: " + documentId);
            }
            return doc;
        }
        
        try {
            // Double-check MongoDB connection
            if (!mongoDbConnected || database == null) {
                System.err.println("MongoDB not connected for document retrieval, falling back to in-memory");
                return getDocumentInMemory(documentId);
            }
            
            System.out.println("Looking up document in MongoDB with ID: " + documentId);
            
            // Handle different ID formats safely
            Object idToQuery;
            try {
                // First try to parse as ObjectId
                idToQuery = new ObjectId(documentId);
                System.out.println("Using ObjectId format: " + idToQuery);
            } catch (Exception e) {
                // If it fails, use as string ID
                System.out.println("Using string ID format: " + documentId);
                idToQuery = documentId;
            }
            
            Document doc = documentsCollection.find(Filters.eq("_id", idToQuery)).first();
            
            if (doc != null) {
                System.out.println("Document found in MongoDB: " + documentId);
                System.out.println("Title: " + doc.getString("title"));
                System.out.println("Content length: " + (doc.getString("content") != null ? doc.getString("content").length() : 0) + " characters");
                
                // If content is null, set it to empty string for safety
                if (doc.getString("content") == null) {
                    doc.append("content", "");
                }
            } else {
                System.err.println("Document not found in MongoDB: " + documentId);
            }
            
            return doc;
        } catch (Exception e) {
            System.err.println("Error getting document from MongoDB: " + e.getMessage());
            e.printStackTrace();
            
            // Try in-memory as fallback
            System.out.println("Falling back to in-memory storage due to error");
            return getDocumentInMemory(documentId);
        }
    }
    
    private Document getDocumentInMemory(String documentId) {
        InMemoryDocument inMemoryDoc = documentMap.get(documentId);
        if (inMemoryDoc != null) {
            Document doc = new Document();
            doc.append("_id", inMemoryDoc.id);
            doc.append("title", inMemoryDoc.title);
            doc.append("ownerId", inMemoryDoc.ownerId);
            doc.append("content", inMemoryDoc.content);
            doc.append("editorCode", inMemoryDoc.editorCode);
            doc.append("viewerCode", inMemoryDoc.viewerCode);
            doc.append("createdAt", inMemoryDoc.createdAt);
            doc.append("updatedAt", inMemoryDoc.updatedAt);
            return doc;
        }
        return null;
    }
    
    /**
     * Gets all documents owned by a user.
     * @param ownerId The owner's user ID.
     * @return A list of documents.
     */
    public List<Document> getDocumentsByOwner(String ownerId) {
        if (useInMemoryStorage) {
            System.out.println("Using in-memory storage to retrieve documents for user: " + ownerId);
            return getDocumentsByOwnerInMemory(ownerId);
        }
        
        List<Document> documents = new ArrayList<>();
        try {
            System.out.println("Retrieving documents from MongoDB for user: " + ownerId);
            
            // Double-check MongoDB connection
            if (!mongoDbConnected || database == null) {
                System.err.println("MongoDB not connected for document retrieval, using in-memory");
                return getDocumentsByOwnerInMemory(ownerId);
            }
            
            // Create index on ownerId if it doesn't exist for better performance
            try {
                documentsCollection.createIndex(Filters.eq("ownerId", 1));
            } catch (Exception e) {
                // Ignore index creation errors
                System.out.println("Note: Could not create index on ownerId: " + e.getMessage());
            }
            
            // Find documents with the given owner ID
            documentsCollection.find(Filters.eq("ownerId", ownerId))
                    .forEach(documents::add);
            
            System.out.println("Retrieved " + documents.size() + " documents from MongoDB for user: " + ownerId);
            
            // If no documents found, create a default document
            if (documents.isEmpty()) {
                System.out.println("No documents found for user, creating default document");
                String docId = createDocument("Untitled Document", ownerId);
                Document newDoc = getDocument(docId);
                if (newDoc != null) {
                    documents.add(newDoc);
                    System.out.println("Created default document with ID: " + docId);
                }
            }
            
            return documents;
        } catch (Exception e) {
            System.err.println("Error getting documents from MongoDB: " + e.getMessage());
            e.printStackTrace();
            return getDocumentsByOwnerInMemory(ownerId);
        }
    }
    
    private List<Document> getDocumentsByOwnerInMemory(String ownerId) {
        List<Document> documents = new ArrayList<>();
        for (InMemoryDocument inMemoryDoc : documentMap.values()) {
            if (inMemoryDoc.ownerId.equals(ownerId)) {
                Document doc = new Document();
                doc.append("_id", inMemoryDoc.id);
                doc.append("title", inMemoryDoc.title);
                doc.append("ownerId", inMemoryDoc.ownerId);
                doc.append("content", inMemoryDoc.content);
                doc.append("editorCode", inMemoryDoc.editorCode);
                doc.append("viewerCode", inMemoryDoc.viewerCode);
                doc.append("createdAt", inMemoryDoc.createdAt);
                doc.append("updatedAt", inMemoryDoc.updatedAt);
                documents.add(doc);
            }
        }
        
        // If no documents found, create a default document
        if (documents.isEmpty()) {
            String docId = createDocumentInMemory("Untitled Document", ownerId);
            InMemoryDocument inMemoryDoc = documentMap.get(docId);
            
            Document doc = new Document();
            doc.append("_id", inMemoryDoc.id);
            doc.append("title", inMemoryDoc.title);
            doc.append("ownerId", inMemoryDoc.ownerId);
            doc.append("content", inMemoryDoc.content);
            doc.append("editorCode", inMemoryDoc.editorCode);
            doc.append("viewerCode", inMemoryDoc.viewerCode);
            doc.append("createdAt", inMemoryDoc.createdAt);
            doc.append("updatedAt", inMemoryDoc.updatedAt);
            documents.add(doc);
        }
        
        return documents;
    }
    
    /**
     * Gets all documents that have a specific session code (either editor or viewer).
     * This allows users to find documents they have access to via a session code.
     * 
     * @param sessionCode The session code to search for
     * @return A list of documents matching the session code
     */
    public List<Document> getDocumentsBySessionCode(String sessionCode) {
        if (useInMemoryStorage) {
            return getDocumentsBySessionCodeInMemory(sessionCode);
        }
        
        List<Document> documents = new ArrayList<>();
        try {
            // Check both editorCode and viewerCode fields for the given code
            Bson filter = Filters.or(
                Filters.eq("editorCode", sessionCode),
                Filters.eq("viewerCode", sessionCode)
            );
            
            documentsCollection.find(filter).forEach(documents::add);
            return documents;
        } catch (Exception e) {
            System.err.println("Error getting documents by session code: " + e.getMessage());
            e.printStackTrace();
            return getDocumentsBySessionCodeInMemory(sessionCode);
        }
    }
    
    private List<Document> getDocumentsBySessionCodeInMemory(String sessionCode) {
        List<Document> documents = new ArrayList<>();
        for (InMemoryDocument inMemoryDoc : documentMap.values()) {
            // Check if either code matches the session code
            if ((inMemoryDoc.editorCode != null && inMemoryDoc.editorCode.equals(sessionCode)) ||
                (inMemoryDoc.viewerCode != null && inMemoryDoc.viewerCode.equals(sessionCode))) {
                
                Document doc = new Document();
                doc.append("_id", inMemoryDoc.id);
                doc.append("title", inMemoryDoc.title);
                doc.append("ownerId", inMemoryDoc.ownerId);
                doc.append("content", inMemoryDoc.content);
                doc.append("editorCode", inMemoryDoc.editorCode);
                doc.append("viewerCode", inMemoryDoc.viewerCode);
                doc.append("createdAt", inMemoryDoc.createdAt);
                doc.append("updatedAt", inMemoryDoc.updatedAt);
                documents.add(doc);
            }
        }
        return documents;
    }
    
    /**
     * Closes the MongoDB connection.
     */
    public void close() {
        if (mongoClient != null) {
            mongoClient.close();
        }
    }
    
    /**
     * Tests the MongoDB connection and displays detailed diagnostic information.
     * If the connection has failed previously, this will attempt to reconnect.
     * 
     * @return true if MongoDB is connected, false if using in-memory storage
     */
    public boolean testMongoDBConnection() {
        try {
            if (mongoDbConnected && mongoClient != null) {
                try {
                    // Test the connection by running a simple command
                    database.runCommand(new Document("ping", 1));
                    
                    // Count documents to verify collections
                    long userCount = usersCollection.countDocuments();
                    long docCount = documentsCollection.countDocuments();
                    
                    System.out.println("==================================================");
                    System.out.println("MongoDB connection test: SUCCESS");
                    System.out.println("Connected to: " + DATABASE_NAME);
                    System.out.println("Users collection: " + userCount + " documents");
                    System.out.println("Documents collection: " + docCount + " documents");
                    System.out.println("==================================================");
                    
                    return true;
                } catch (Exception e) {
                    System.err.println("==================================================");
                    System.err.println("MongoDB connection test: FAILED");
                    System.err.println("Error: " + e.getMessage());
                    System.err.println("Will attempt to reconnect...");
                    System.err.println("==================================================");
                    
                    // Close the existing client
                    try {
                        mongoClient.close();
                    } catch (Exception ex) {
                        // Ignore
                    }
                    
                    // Try to reconnect
                    return attemptReconnect();
                }
            } else if (mongoDbConnected && mongoClient == null) {
                // This is an inconsistent state - try to reconnect
                System.err.println("==================================================");
                System.err.println("MongoDB connection state inconsistent!");
                System.err.println("Attempting to reconnect...");
                System.err.println("==================================================");
                
                return attemptReconnect();
            } else {
                // Currently using in-memory storage
                System.err.println("==================================================");
                System.err.println("Currently using IN-MEMORY STORAGE.");
                System.err.println("Your data is NOT being saved to MongoDB!");
                System.err.println("Attempting to reconnect to MongoDB...");
                System.err.println("==================================================");
                
                return attemptReconnect();
            }
        } catch (Exception e) {
            System.err.println("Error during connection test: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }
    
    /**
     * Gets the last login time for a user.
     * @param userId The user ID.
     * @return The last login time, or null if not found.
     */
    public Date getLastLoginTime(String userId) {
        if (useInMemoryStorage) {
            User user = userMap.get(userId);
            return user != null ? user.lastLogin : null;
        }
        
        try {
            if (!mongoDbConnected) {
                return null;
            }
            
            // For MongoDB, first try to find the user by their ID
            Document user = null;
            try {
                // Try to parse as ObjectId first
                ObjectId objId = new ObjectId(userId);
                user = usersCollection.find(Filters.eq("_id", objId)).first();
            } catch (Exception e) {
                // If not an ObjectId, try as string
                user = usersCollection.find(Filters.eq("_id", userId)).first();
            }
            
            if (user != null) {
                return user.getDate("lastLogin");
            }
            
            return null;
        } catch (Exception e) {
            System.err.println("Error getting last login time: " + e.getMessage());
            return null;
        }
    }
    
    /**
     * In-memory user class
     */
    private static class User {
        String id;
        String username;
        String hashedPassword;
        Date createdAt;
        Date lastLogin;
        
        public User(String id, String username, String hashedPassword, Date createdAt) {
            this.id = id;
            this.username = username;
            this.hashedPassword = hashedPassword;
            this.createdAt = createdAt;
            this.lastLogin = createdAt; // Initialize lastLogin to createdAt
        }
    }
    
    /**
     * In-memory document class
     */
    private static class InMemoryDocument {
        public final String id;
        public final String title;
        public final String ownerId;
        public String content;
        public String editorCode;
        public String viewerCode;
        public final Date createdAt;
        public Date updatedAt;
        
        public InMemoryDocument(String id, String title, String ownerId, String content, Date createdAt, Date updatedAt) {
            this.id = id;
            this.title = title;
            this.ownerId = ownerId;
            this.content = content;
            this.editorCode = "";
            this.viewerCode = "";
            this.createdAt = createdAt;
            this.updatedAt = updatedAt;
        }
    }
    
    /**
     * Gets a document by its session code (either editor or viewer code).
     * 
     * @param sessionCode The session code to search for.
     * @return The document if found, null otherwise.
     */
    public Document getDocumentBySessionCode(String sessionCode) {
        if (sessionCode == null || sessionCode.isEmpty()) {
            return null;
        }
        
        if (useInMemoryStorage) {
            return getDocumentBySessionCodeInMemory(sessionCode);
        }
        
        try {
            // Check MongoDB connection
            if (!mongoDbConnected && !attemptReconnect()) {
                return getDocumentBySessionCodeInMemory(sessionCode);
            }
            
            // Search for documents with matching session codes
            Document doc = documentsCollection.find(
                new Document("$or", List.of(
                    new Document("editorCode", sessionCode),
                    new Document("viewerCode", sessionCode)
                ))
            ).first();
            
            if (doc != null) {
                System.out.println("Found document with session code: " + sessionCode + ", document ID: " + doc.get("_id"));
                return doc;
            } else {
                System.out.println("No document found with session code: " + sessionCode);
                return null;
            }
        } catch (Exception e) {
            System.err.println("Error searching for document by session code: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }
    
    /**
     * Gets a document by session code from in-memory storage.
     * 
     * @param sessionCode The session code to search for.
     * @return The document if found, null otherwise.
     */
    private Document getDocumentBySessionCodeInMemory(String sessionCode) {
        for (InMemoryDocument doc : documentMap.values()) {
            if ((doc.editorCode != null && doc.editorCode.equals(sessionCode)) ||
                (doc.viewerCode != null && doc.viewerCode.equals(sessionCode))) {
                
                System.out.println("Found in-memory document with session code: " + sessionCode);
                
                // Convert to MongoDB Document format
                Document document = new Document()
                    .append("_id", doc.id)
                    .append("title", doc.title)
                    .append("ownerId", doc.ownerId)
                    .append("content", doc.content)
                    .append("editorCode", doc.editorCode)
                    .append("viewerCode", doc.viewerCode)
                    .append("createdAt", doc.createdAt)
                    .append("updatedAt", doc.updatedAt);
                
                return document;
            }
        }
        
        System.out.println("No in-memory document found with session code: " + sessionCode);
        return null;
    }
    
    /**
     * Updates a document's content and session code (legacy method).
     * @param documentId The document ID.
     * @param content The new content.
     * @param sessionCode The session code.
     * @return True if the update was successful, false otherwise.
     */
    public boolean updateDocumentWithSession(String documentId, String content, String sessionCode) {
        // For backward compatibility - use the same code for both editor and viewer
        return updateDocumentWithSession(documentId, content, sessionCode, sessionCode);
    }
    
    /**
     * Updates a document in memory with session codes.
     * @param documentId The document ID.
     * @param content The document content.
     * @param editorCode The editor session code.
     * @param viewerCode The viewer session code.
     * @return True if successful, false otherwise.
     */
    private boolean updateDocumentWithSessionInMemory(String documentId, String content, 
                                                     String editorCode, String viewerCode) {
        InMemoryDocument document = documentMap.get(documentId);
        if (document != null) {
            if (content != null) {
                document.content = content;
            }
            document.editorCode = editorCode;
            document.viewerCode = viewerCode;
            document.updatedAt = new Date();
            return true;
        }
        return false;
    }
} 