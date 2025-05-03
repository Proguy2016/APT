package com.project.ui;

import javafx.geometry.Bounds;
import javafx.scene.control.TextArea;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Line;
import javafx.scene.text.Text;
import javafx.animation.FadeTransition;
import javafx.util.Duration;
import javafx.geometry.Point2D;

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
    private static final double CURSOR_WIDTH = 2.0; // Slightly thicker for better visibility
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
        blinkAnimation.setFromValue(1.0); // Full opacity
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
        cursorLine.setVisible(false); // Hide initially
        
        // Create a label for the user ID
        usernameLabel = new Text(userId);
        usernameLabel.setFill(color);
        usernameLabel.setOpacity(1.0); // Full opacity for better visibility
        usernameLabel.setVisible(false); // Hide initially
        
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
        try {
            // Make sure the position is valid
            int textLength = textArea.getText().length();
            int safePosition = Math.min(position, textLength);
            
            // Get points for cursor position
            Point2D cursorPoint = getCursorPoint(safePosition);
            if (cursorPoint != null) {
                double x = cursorPoint.getX();
                double y = cursorPoint.getY();
                
                // Calculate offset from top of textArea (accounting for scrolling)
                double visibleY = y - textArea.getScrollTop();
                double visibleX = x;
                
                // Only show cursor if it's in the visible area of the TextArea
                if (visibleY >= 0 && visibleY <= textArea.getHeight()) {
                    // Position the cursor line
                    cursorLine.setStartX(visibleX);
                    cursorLine.setStartY(visibleY);
                    cursorLine.setEndX(visibleX);
                    cursorLine.setEndY(visibleY + CURSOR_HEIGHT);
                    
                    // Position the username label
                    usernameLabel.setLayoutX(visibleX);
                    usernameLabel.setLayoutY(visibleY + LABEL_OFFSET_Y);
                    
                    // Make sure the elements are visible
                    cursorLine.setVisible(true);
                    usernameLabel.setVisible(true);
                    return;
                }
            }
            
            // If we couldn't position cursor or it's out of view, hide it
            cursorLine.setVisible(false);
            usernameLabel.setVisible(false);
        } catch (Exception e) {
            // If there's an error, hide the cursor
            cursorLine.setVisible(false);
            usernameLabel.setVisible(false);
        }
    }
    
    /**
     * Gets cursor position point for a specific character position.
     * @param position The character position.
     * @return The point, or null if it couldn't be determined.
     */
    private Point2D getCursorPoint(int position) {
        try {
            // Save the current position
            int originalPosition = textArea.getCaretPosition();
            
            // Move caret to desired position and wait for layout
            textArea.positionCaret(position);
            
            // Get the position of the character at this index
            // This is a more direct approach to get the actual pixel position
            
            // Calculate the row and column
            String text = textArea.getText();
            int row = 0;
            int col = 0;
            
            for (int i = 0; i < position && i < text.length(); i++) {
                if (text.charAt(i) == '\n') {
                    row++;
                    col = 0;
                } else {
                    col++;
                }
            }
            
            // Get a sample character to measure dimensions
            Text charText = new Text("I");
            charText.setFont(textArea.getFont());
            double charWidth = charText.getBoundsInLocal().getWidth();
            double lineHeight = charText.getBoundsInLocal().getHeight() * 1.2;
            
            // Get the top-left corner of the TextArea within the scene
            Bounds textAreaBounds = textArea.getBoundsInParent();
            double textAreaX = textAreaBounds.getMinX() + 5; // Left padding
            double textAreaY = textAreaBounds.getMinY() + 5; // Top padding
            
            // Calculate cursor position based on row/column
            double cursorX = textAreaX + (col * charWidth);
            double cursorY = textAreaY + (row * lineHeight);
            
            // Restore original position
            textArea.positionCaret(originalPosition);
            
            return new Point2D(cursorX, cursorY);
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