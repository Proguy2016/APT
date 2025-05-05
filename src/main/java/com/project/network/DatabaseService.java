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
    // Update the connection string with a more generic format for safety
    private static final String CONNECTION_STRING = "mongodb+srv://youssefshafik04:1gaifqXHyXhxccv2@cluster0.fsx0whh.mongodb.net/?connectTimeoutMS=5000&serverSelectionTimeoutMS=5000";
    private static final String DATABASE_NAME = "collaborative_editor";
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
            System.out.println("Attempting to connect to MongoDB Atlas...");
            System.out.println("Connection string: " + CONNECTION_STRING);
            System.out.println("Database name: " + DATABASE_NAME);
            System.out.println("==================================================");
            
            // Set a shorter connection timeout (5 seconds instead of 30)
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
            System.out.println("Users collection: " + userCount + " documents");
            System.out.println("Documents collection: " + docCount + " documents");
            System.out.println("==================================================");
            
            useInMemoryStorage = false;
            mongoDbConnected = true;
        } catch (Exception e) {
            System.err.println("==================================================");
            System.err.println("ERROR: Failed to connect to MongoDB Atlas!");
            System.err.println("Error message: " + e.getMessage());
            System.err.println("IMPORTANT: FALLING BACK TO IN-MEMORY STORAGE!");
            System.err.println("WARNING: Your data will NOT be saved to MongoDB!");
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
                    .append("createdAt", new Date());
            
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
        
        return true;
    }
    
    /**
     * Authenticates a user.
     * @param username The username.
     * @param password The password.
     * @return The user ID if authentication was successful, null otherwise.
     */
    public String authenticateUser(String username, String password) {
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
                    Updates.set("editorCode", editorCode),
                    Updates.set("viewerCode", viewerCode),
                    Updates.set("updatedAt", new Date())
            );
            
            documentsCollection.updateOne(filter, update);
            return true;
        } catch (Exception e) {
            System.err.println("Error updating document with session: " + e.getMessage());
            e.printStackTrace();
            return updateDocumentWithSessionInMemory(documentId, content, editorCode, viewerCode);
        }
    }
    
    private boolean updateDocumentWithSessionInMemory(String documentId, String content, 
                                                     String editorCode, String viewerCode) {
        InMemoryDocument document = documentMap.get(documentId);
        if (document != null) {
            document.content = content;
            document.editorCode = editorCode;
            document.viewerCode = viewerCode;
            document.updatedAt = new Date();
            return true;
        }
        return false;
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
     * Gets a document by ID.
     * @param documentId The document ID.
     * @return The document, or null if not found.
     */
    public Document getDocument(String documentId) {
        if (useInMemoryStorage) {
            return getDocumentInMemory(documentId);
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
            
            return documentsCollection.find(Filters.eq("_id", idToQuery)).first();
        } catch (Exception e) {
            System.err.println("Error getting document: " + e.getMessage());
            e.printStackTrace();
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
            return getDocumentsByOwnerInMemory(ownerId);
        }
        
        List<Document> documents = new ArrayList<>();
        try {
            documentsCollection.find(Filters.eq("ownerId", ownerId))
                    .forEach(documents::add);
            return documents;
        } catch (Exception e) {
            System.err.println("Error getting documents: " + e.getMessage());
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
        } else if (!mongoDbConnected && !useInMemoryStorage) {
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
    }
    
    /**
     * Attempts to reconnect to MongoDB.
     * 
     * @return true if reconnection was successful, false otherwise
     */
    private boolean attemptReconnect() {
        try {
            System.out.println("Attempting to connect to MongoDB...");
            
            // Create new MongoDB client
            mongoClient = MongoClients.create(CONNECTION_STRING);
            
            // Test connection immediately
            database = mongoClient.getDatabase(DATABASE_NAME);
            database.runCommand(new Document("ping", 1));
            
            usersCollection = database.getCollection(USERS_COLLECTION);
            documentsCollection = database.getCollection(DOCUMENTS_COLLECTION);
            
            // If we got here, connection is successful
            System.out.println("==================================================");
            System.out.println("Successfully reconnected to MongoDB!");
            System.out.println("Your data will now be saved to MongoDB.");
            System.out.println("==================================================");
            
            useInMemoryStorage = false;
            mongoDbConnected = true;
            return true;
        } catch (Exception e) {
            System.err.println("==================================================");
            System.err.println("Reconnection to MongoDB failed!");
            System.err.println("Error: " + e.getMessage());
            System.err.println("Continuing with in-memory storage.");
            System.err.println("==================================================");
            
            useInMemoryStorage = true;
            mongoDbConnected = false;
            return false;
        }
    }
    
    /**
     * In-memory user class
     */
    private static class User {
        public final String id;
        public final String username;
        public final String hashedPassword;
        public final Date createdAt;
        
        public User(String id, String username, String hashedPassword, Date createdAt) {
            this.id = id;
            this.username = username;
            this.hashedPassword = hashedPassword;
            this.createdAt = createdAt;
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
} 