package com.project.ui;

import javafx.geometry.Bounds;
import javafx.scene.control.TextArea;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Line;
import javafx.scene.text.Text;
import javafx.animation.FadeTransition;
import javafx.util.Duration;

/**
 * A visual marker that represents another user's cursor in the editor.
 */
public class CursorMarker {
    private final Pane parent;
    private final String userId;
    private final Color color;
    private final TextArea textArea;
    
    // Visual elements
    private Line cursorLine;
    private Text usernameLabel;
    
    // Cursor position
    private int position;
    
    // Size constants
    private static final double CURSOR_HEIGHT = 16.0;
    private static final double CURSOR_WIDTH = 1.0; // Thinner line for subtlety
    private static final double LABEL_OFFSET_Y = -16.0;
    
    private final FadeTransition blinkAnimation;
    
    /**
     * Creates a new cursor marker.
     * @param parent The parent pane.
     * @param userId The user ID associated with this cursor.
     * @param color The color of the cursor.
     * @param textArea The text area this cursor is tracking.
     */
    public CursorMarker(Pane parent, String userId, Color color, TextArea textArea) {
        this.parent = parent;
        this.userId = userId;
        this.color = color;
        this.textArea = textArea;
        this.position = 0;
        
        createVisualElements();
        
        // Add a blinking animation to make the cursor more noticeable but still subtle
        blinkAnimation = new FadeTransition(Duration.millis(800), cursorLine);
        blinkAnimation.setFromValue(0.8);
        blinkAnimation.setToValue(0.4);
        blinkAnimation.setCycleCount(FadeTransition.INDEFINITE);
        blinkAnimation.setAutoReverse(true);
        blinkAnimation.play();
    }
    
    /**
     * Creates the visual elements for this cursor marker.
     */
    private void createVisualElements() {
        // Create a line for the cursor
        cursorLine = new Line();
        cursorLine.setStroke(color);
        cursorLine.setStrokeWidth(CURSOR_WIDTH);
        
        // Create a label for the user ID
        usernameLabel = new Text(userId);
        usernameLabel.setFill(color);
        usernameLabel.setOpacity(0.8); // Make username slightly transparent
        
        // Add the elements to the parent pane
        parent.getChildren().addAll(cursorLine, usernameLabel);
    }
    
    /**
     * Updates the cursor position.
     * @param newPosition The new position for the cursor.
     */
    public void updatePosition(int newPosition) {
        // Update the position
        this.position = newPosition;
        
        // Update the visual representation
        updateVisuals();
    }
    
    /**
     * Updates the visual representation of the cursor.
     */
    public void updateVisuals() {
        // Try to get the bounds of the cursor position
        try {
            // Make sure the position is valid
            int textLength = textArea.getText().length();
            int safePosition = Math.min(position, textLength);
            
            // Get caret bounds for positioning
            Bounds caretBounds = getCaretBoundsAt(safePosition);
            if (caretBounds != null) {
                // Position the cursor line
                double x = caretBounds.getMinX();
                double y = caretBounds.getMinY();
                
                cursorLine.setStartX(x);
                cursorLine.setStartY(y);
                cursorLine.setEndX(x);
                cursorLine.setEndY(y + CURSOR_HEIGHT);
                
                // Position the username label
                usernameLabel.setLayoutX(x);
                usernameLabel.setLayoutY(y + LABEL_OFFSET_Y);
                
                // Make sure the elements are visible
                cursorLine.setVisible(true);
                usernameLabel.setVisible(true);
                
                return;
            }
            
            // If we couldn't get specific position, hide the cursor
            cursorLine.setVisible(false);
            usernameLabel.setVisible(false);
        } catch (Exception e) {
            // If there's an error, hide the cursor
            cursorLine.setVisible(false);
            usernameLabel.setVisible(false);
        }
    }
    
    /**
     * Gets caret bounds for a specific position.
     * @param position The character position.
     * @return The bounds, or null if they couldn't be determined.
     */
    private Bounds getCaretBoundsAt(int position) {
        try {
            // Save the current caret position
            int originalPosition = textArea.getCaretPosition();
            
            // Temporarily move the caret to the desired position
            textArea.positionCaret(position);
            
            // Get the bounds of the selected character
            // Since TextArea doesn't have direct getCaretBounds, we need to work around
            // by creating a dummy Text node with the character at that position
            String text = textArea.getText();
            if (text.isEmpty() || position >= text.length()) {
                // Use a space if we're at the end
                Text dummyText = new Text(" ");
                return dummyText.getBoundsInLocal();
            }
            
            // Create a Text node with the character at the position
            Text dummyText = new Text(String.valueOf(text.charAt(position)));
            Bounds bounds = dummyText.getBoundsInLocal();
            
            // Restore the original caret position
            textArea.positionCaret(originalPosition);
            
            return bounds;
        } catch (Exception e) {
            return null;
        }
    }
    
    /**
     * Removes this cursor marker from its parent.
     */
    public void remove() {
        parent.getChildren().removeAll(cursorLine, usernameLabel);
    }
    
    /**
     * Gets the user ID associated with this cursor marker.
     * @return The user ID.
     */
    public String getUserId() {
        return userId;
    }
    
    /**
     * Gets the cursor color.
     * @return The cursor color.
     */
    public Color getColor() {
        return color;
    }
    
    /**
     * Gets the cursor position.
     * @return The cursor position.
     */
    public int getPosition() {
        return position;
    }
    
    /**
     * Sets the cursor position.
     * @param position The new position.
     */
    public void setPosition(int position) {
        this.position = position;
    }
    
    /**
     * Sets the cursor height.
     * @param height The new height.
     */
    public void setCursorHeight(double height) {
        cursorLine.setEndY(cursorLine.getStartY() + height);
    }
    
    /**
     * Stops the cursor animation when the marker is no longer needed.
     */
    public void dispose() {
        blinkAnimation.stop();
    }
} 