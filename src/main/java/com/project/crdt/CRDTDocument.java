package com.project.crdt;

import java.util.*;

/**
 * The main CRDT document class that manages the collaborative text editing.
 * It uses a tree-based CRDT algorithm to handle concurrent edits.
 */
public class CRDTDocument {
    // The site identifier for this instance
    private final String siteId;
    
    // The characters in the document, sorted by position
    private final TreeSet<CRDTCharacter> characters;
    
    // Counter for generating new position identifiers
    private int counter;
    
    // History for undo/redo operations
    private final Deque<Operation> history;
    private final Deque<Operation> redoStack;
    private static final int MAX_HISTORY_SIZE = 50;
    
    /**
     * Creates a new CRDT document.
     * @param siteId A unique identifier for this site (user).
     */
    public CRDTDocument(String siteId) {
        this.siteId = siteId;
        this.characters = new TreeSet<>();
        this.counter = 0;
        this.history = new LinkedList<>();
        this.redoStack = new LinkedList<>();
    }
    
    /**
     * Inserts a character at the specified index.
     * @param index The index to insert at.
     * @param c The character to insert.
     * @return The CRDT character that was inserted.
     */
    public CRDTCharacter localInsert(int index, char c) {
        Position position = generatePositionBetween(index);
        
        CRDTCharacter character = new CRDTCharacter(
                c, position, siteId, System.currentTimeMillis());
        
        characters.add(character);
        
        // Add to history
        Operation insertOperation = new Operation(OperationType.INSERT, character);
        addToHistory(insertOperation);
        
        return character;
    }
    
    /**
     * Deletes a character at the specified index.
     * @param index The index to delete at.
     * @return The CRDT character that was deleted, or null if deletion was not possible.
     */
    public CRDTCharacter localDelete(int index) {
        // Safety checks
        if (characters.isEmpty()) {
            System.err.println("Cannot delete from empty document");
            return null;
        }
        
        if (index < 0 || index >= characters.size()) {
            System.err.println("Delete attempted with invalid index: " + index + 
                            " (document size: " + characters.size() + ")");
            return null;
        }
        
        try {
            CRDTCharacter character = getCharacterAtIndex(index);
            
            if (character != null) {
                characters.remove(character);
                
                // Add to history
                Operation deleteOperation = new Operation(OperationType.DELETE, character);
                addToHistory(deleteOperation);
                
                return character;
            } else {
                System.err.println("Character at index " + index + " is null, cannot delete");
                
                // Try a different approach if the normal lookup fails
                // This is a fallback recovery mechanism
                if (index >= 0 && index < characters.size()) {
                    // Try to get the character by iterating through the set
                    int i = 0;
                    for (CRDTCharacter c : characters) {
                        if (i == index) {
                            // Found the character, now remove it
                            characters.remove(c);
                            
                            // Add to history
                            Operation deleteOperation = new Operation(OperationType.DELETE, c);
                            addToHistory(deleteOperation);
                            
                            System.out.println("Successfully deleted character using fallback method");
                            return c;
                        }
                        i++;
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Error during deletion: " + e.getMessage());
            e.printStackTrace();
            
            // Additional recovery attempt if there was an exception
            try {
                if (index >= 0 && index < characters.size()) {
                    // Get an array of characters for safer access
                    CRDTCharacter[] charArray = characters.toArray(new CRDTCharacter[0]);
                    if (index < charArray.length) {
                        CRDTCharacter charToDelete = charArray[index];
                        if (charToDelete != null) {
                            characters.remove(charToDelete);
                            
                            // Add to history
                            Operation deleteOperation = new Operation(OperationType.DELETE, charToDelete);
                            addToHistory(deleteOperation);
                            
                            System.out.println("Successfully deleted character using array recovery method");
                            return charToDelete;
                        }
                    }
                }
            } catch (Exception ex) {
                System.err.println("Recovery attempt also failed: " + ex.getMessage());
            }
        }
        
        return null;
    }
    
    /**
     * Handles a remote insert operation.
     * @param character The character to insert.
     */
    public void remoteInsert(CRDTCharacter character) {
        characters.add(character);
    }
    
    /**
     * Handles a remote delete operation.
     * @param position The position of the character to delete.
     */
    public void remoteDelete(Position position) {
        CRDTCharacter toRemove = null;
        
        for (CRDTCharacter character : characters) {
            if (character.getPosition().equals(position)) {
                toRemove = character;
                break;
            }
        }
        
        if (toRemove != null) {
            characters.remove(toRemove);
        }
    }
    
    /**
     * Gets the character at the specified index.
     * @param index The index.
     * @return The character at the index.
     */
    private CRDTCharacter getCharacterAtIndex(int index) {
        if (characters.isEmpty() || index < 0 || index >= characters.size()) {
            return null;
        }
        
        int i = 0;
        for (CRDTCharacter character : characters) {
            if (i == index) {
                return character;
            }
            i++;
        }
        
        return null;
    }
    
    /**
     * Generates a position between two existing positions.
     * @param index The index to insert at.
     * @return A new position.
     */
    private Position generatePositionBetween(int index) {
        if (characters.isEmpty()) {
            // If the document is empty, create a position in the middle
            List<Identifier> identifiers = new ArrayList<>();
            identifiers.add(new Identifier(32768, siteId)); // Start with a position in the middle (2^15)
            return new Position(identifiers);
        }
        
        if (index == 0) {
            // If inserting at the beginning, create a position before the first character
            CRDTCharacter firstChar = characters.first();
            List<Identifier> firstIdentifiers = firstChar.getPosition().getIdentifiers();
            
            List<Identifier> newIdentifiers = new ArrayList<>();
            // Create a position before the first position
            int firstPos = firstIdentifiers.get(0).getPosition();
            if (firstPos > 0) {
                // If there's room before the first position
                newIdentifiers.add(new Identifier(firstPos / 2, siteId));
            } else {
                // If the first position is already at 0, add a new level
                newIdentifiers.add(new Identifier(0, siteId));
                newIdentifiers.add(new Identifier(32768, siteId));
            }
            
            return new Position(newIdentifiers);
        }
        
        if (index >= characters.size()) {
            // If inserting at the end, create a position after the last character
            CRDTCharacter lastChar = characters.last();
            List<Identifier> lastIdentifiers = lastChar.getPosition().getIdentifiers();
            
            List<Identifier> newIdentifiers = new ArrayList<>();
            // Create a position after the last position
            int lastPos = lastIdentifiers.get(0).getPosition();
            newIdentifiers.add(new Identifier(lastPos + 1, siteId));
            
            return new Position(newIdentifiers);
        }
        
        // Find the two characters between which to insert
        CRDTCharacter prevChar = getCharacterAtIndex(index - 1);
        CRDTCharacter nextChar = getCharacterAtIndex(index);
        
        List<Identifier> prevIdentifiers = prevChar.getPosition().getIdentifiers();
        List<Identifier> nextIdentifiers = nextChar.getPosition().getIdentifiers();
        
        // Check if we can insert a new position at the first level
        int prevPos = prevIdentifiers.get(0).getPosition();
        int nextPos = nextIdentifiers.get(0).getPosition();
        
        if (nextPos - prevPos > 1) {
            // If there's room between the positions
            List<Identifier> newIdentifiers = new ArrayList<>();
            newIdentifiers.add(new Identifier(prevPos + (nextPos - prevPos) / 2, siteId));
            return new Position(newIdentifiers);
        }
        
        // If there's no room at the first level, we need a more sophisticated strategy
        // For simplicity, we'll just add a new level
        List<Identifier> newIdentifiers = new ArrayList<>(prevIdentifiers);
        newIdentifiers.add(new Identifier(counter++, siteId));
        
        return new Position(newIdentifiers);
    }
    
    /**
     * Gets the current text of the document.
     * @return The text.
     */
    public String getText() {
        StringBuilder sb = new StringBuilder();
        for (CRDTCharacter character : characters) {
            sb.append(character.getValue());
        }
        return sb.toString();
    }
    
    /**
     * Adds an operation to the history.
     * @param operation The operation to add.
     */
    private void addToHistory(Operation operation) {
        history.push(operation);
        if (history.size() > MAX_HISTORY_SIZE) {
            history.removeLast();
        }
        redoStack.clear(); // Clear redo stack when a new operation is performed
    }
    
    /**
     * Undoes the last operation.
     * @return true if the operation was undone, false otherwise.
     */
    public boolean undo() {
        if (history.isEmpty()) {
            return false;
        }
        
        Operation lastOperation = history.pop();
        redoStack.push(lastOperation);
        
        if (lastOperation.getType() == OperationType.INSERT) {
            // Undo an insert by removing the character
            characters.remove(lastOperation.getCharacter());
        } else {
            // Undo a delete by adding the character back
            characters.add(lastOperation.getCharacter());
        }
        
        return true;
    }
    
    /**
     * Redoes the last undone operation.
     * @return true if the operation was redone, false otherwise.
     */
    public boolean redo() {
        if (redoStack.isEmpty()) {
            return false;
        }
        
        Operation lastUndoneOperation = redoStack.pop();
        history.push(lastUndoneOperation);
        
        if (lastUndoneOperation.getType() == OperationType.INSERT) {
            // Redo an insert by adding the character back
            characters.add(lastUndoneOperation.getCharacter());
        } else {
            // Redo a delete by removing the character
            characters.remove(lastUndoneOperation.getCharacter());
        }
        
        return true;
    }
    
    /**
     * Gets the site ID.
     * @return The site ID.
     */
    public String getSiteId() {
        return siteId;
    }
    
    /**
     * Operation type enum.
     */
    private enum OperationType {
        INSERT,
        DELETE
    }
    
    /**
     * Class representing an operation for undo/redo.
     */
    private static class Operation {
        private final OperationType type;
        private final CRDTCharacter character;
        
        public Operation(OperationType type, CRDTCharacter character) {
            this.type = type;
            this.character = character;
        }
        
        public OperationType getType() {
            return type;
        }
        
        public CRDTCharacter getCharacter() {
            return character;
        }
    }
    
    /**
     * Gets the last operation (for undo tracking).
     * @return The last operation, or null if the history is empty.
     */
    public Operation getLastOperation() {
        return history.isEmpty() ? null : history.peek();
    }
    
    /**
     * Gets the last undone operation (for redo tracking).
     * @return The last undone operation, or null if there are no undone operations.
     */
    public Operation getLastUndoneOperation() {
        return redoStack.isEmpty() ? null : redoStack.peek();
    }
    
    /**
     * Peeks at the next operation that would be undone.
     * @return The next operation to undo, or null if there are no operations to undo.
     */
    public com.project.network.Operation peekUndo() {
        if (history.isEmpty()) {
            return null;
        }
        
        Operation internalOp = history.peek();
        if (internalOp == null) {
            return null;
        }
        
        // Convert internal Operation to network Operation
        return new com.project.network.Operation(
            internalOp.getType() == OperationType.INSERT ? 
                com.project.network.Operation.Type.INSERT : 
                com.project.network.Operation.Type.DELETE,
            internalOp.getCharacter(),
            internalOp.getCharacter().getPosition(),
            this.siteId,
            -1
        );
    }
    
    /**
     * Peeks at the next operation that would be redone.
     * @return The next operation to redo, or null if there are no operations to redo.
     */
    public com.project.network.Operation peekRedo() {
        if (redoStack.isEmpty()) {
            return null;
        }
        
        Operation internalOp = redoStack.peek();
        if (internalOp == null) {
            return null;
        }
        
        // Convert internal Operation to network Operation
        return new com.project.network.Operation(
            internalOp.getType() == OperationType.INSERT ? 
                com.project.network.Operation.Type.INSERT : 
                com.project.network.Operation.Type.DELETE,
            internalOp.getCharacter(),
            internalOp.getCharacter().getPosition(),
            this.siteId,
            -1
        );
    }
} 