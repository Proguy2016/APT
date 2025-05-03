package com.project.network;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import org.bson.Document;
import org.bson.conversions.Bson;
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
    private static final String CONNECTION_STRING = "mongodb://localhost:27017/";
    private static final String DATABASE_NAME = "collaborative_editor";
    private static final String USERS_COLLECTION = "users";
    private static final String DOCUMENTS_COLLECTION = "documents";
    
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
            mongoClient = MongoClients.create(CONNECTION_STRING);
            database = mongoClient.getDatabase(DATABASE_NAME);
            usersCollection = database.getCollection(USERS_COLLECTION);
            documentsCollection = database.getCollection(DOCUMENTS_COLLECTION);
            System.out.println("Connected to MongoDB successfully");
        } catch (Exception e) {
            System.err.println("Error connecting to MongoDB: " + e.getMessage());
            System.out.println("Using in-memory storage instead");
            useInMemoryStorage = true;
        }
    }
    
    /**
     * Registers a new user.
     * @param username The username.
     * @param password The password.
     * @return True if registration was successful, false otherwise.
     */
    public boolean registerUser(String username, String password) {
        if (useInMemoryStorage) {
            return registerUserInMemory(username, password);
        }
        
        try {
            // Check if username already exists
            Document existingUser = usersCollection.find(Filters.eq("username", username)).first();
            if (existingUser != null) {
                return false;
            }
            
            // Hash the password
            String hashedPassword = BCrypt.hashpw(password, BCrypt.gensalt());
            
            // Create a new user document
            Document newUser = new Document()
                    .append("username", username)
                    .append("password", hashedPassword)
                    .append("createdAt", new Date());
            
            usersCollection.insertOne(newUser);
            return true;
        } catch (Exception e) {
            System.err.println("Error registering user: " + e.getMessage());
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
            return authenticateUserInMemory(username, password);
        }
        
        try {
            Document user = usersCollection.find(Filters.eq("username", username)).first();
            if (user == null) {
                return null;
            }
            
            String hashedPassword = user.getString("password");
            if (BCrypt.checkpw(password, hashedPassword)) {
                return user.getObjectId("_id").toString();
            }
            
            return null;
        } catch (Exception e) {
            System.err.println("Error authenticating user: " + e.getMessage());
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
            return newDocument.getObjectId("_id").toString();
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
            Bson filter = Filters.eq("_id", documentId);
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
     * Gets a document by ID.
     * @param documentId The document ID.
     * @return The document, or null if not found.
     */
    public Document getDocument(String documentId) {
        if (useInMemoryStorage) {
            return getDocumentInMemory(documentId);
        }
        
        try {
            return documentsCollection.find(Filters.eq("_id", documentId)).first();
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
        public final Date createdAt;
        public Date updatedAt;
        
        public InMemoryDocument(String id, String title, String ownerId, String content, Date createdAt, Date updatedAt) {
            this.id = id;
            this.title = title;
            this.ownerId = ownerId;
            this.content = content;
            this.createdAt = createdAt;
            this.updatedAt = updatedAt;
        }
    }
} 