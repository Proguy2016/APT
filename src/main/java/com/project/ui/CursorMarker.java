package com.project.ui;

import javafx.geometry.Bounds;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Line;

/**
 * A class representing a cursor marker for another user.
 */
public class CursorMarker extends StackPane {
    private final Line cursorLine;
    
    /**
     * Creates a new cursor marker with the given username and color.
     * @param username The username to display (not shown, just for reference)
     * @param color The color of the cursor
     */
    public CursorMarker(String username, Color color) {
        // Create the cursor line (vertical line)
        cursorLine = new Line(0, 0, 0, 20);
        cursorLine.setStroke(color);
        cursorLine.setStrokeWidth(2);
        
        // Add only the line to the stack pane
        getChildren().add(cursorLine);
        
        // Make it ignore mouse events
        setMouseTransparent(true);
        
        // Hide initially
        setVisible(false);
    }
    
    /**
     * Updates the position of the cursor marker.
     * @param caretBounds The bounds of the caret position
     */
    public void updatePosition(Bounds caretBounds) {
        if (caretBounds != null) {
            setLayoutX(caretBounds.getMinX());
            setLayoutY(caretBounds.getMinY());
            
            // Update line height to match current line height
            cursorLine.setEndY(caretBounds.getHeight());
            
            setVisible(true);
        } else {
            setVisible(false);
        }
    }
} 