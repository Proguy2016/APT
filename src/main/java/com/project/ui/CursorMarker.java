package com.project.ui;

import javafx.geometry.Bounds;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Line;
import javafx.scene.control.Label;
import javafx.geometry.Pos;
import javafx.scene.effect.DropShadow;

/**
 * A class representing a cursor marker for another user.
 */
public class CursorMarker extends StackPane {
    private final Line cursorLine;
    private final Label label;
    private final Color cursorColor;
    
    /**
     * Creates a new cursor marker with the given username and color.
     * @param username The username to display
     * @param color The color of the cursor
     */
    public CursorMarker(String username, Color color) {
        super(); // Call StackPane's default constructor
        this.cursorColor = color;
        
        // Create cursor line with better visibility
        cursorLine = new Line(0, 0, 0, 20);
        cursorLine.setStroke(color);
        cursorLine.setStrokeWidth(2.5);
        
        // Create label with improved styling
        label = new Label(username);
        label.setTextFill(Color.WHITE);
        label.setStyle("-fx-background-color: " + toRGBCode(color) + "; -fx-padding: 2 5 2 5; -fx-background-radius: 3;");
        
        // Add shadow for better visibility
        DropShadow shadow = new DropShadow();
        shadow.setColor(Color.color(0, 0, 0, 0.5));
        shadow.setRadius(3);
        label.setEffect(shadow);
        
        // Position label above cursor line
        setAlignment(Pos.TOP_LEFT);
        label.setTranslateY(-20); // Position above cursor
        
        getChildren().addAll(cursorLine, label);
        setMouseTransparent(true);
        setVisible(false);
    }
    
    /**
     * Updates the position of the cursor marker.
     * @param caretBounds The bounds of the caret position
     */
    public void updatePosition(Bounds caretBounds) {
        if (caretBounds != null) {
            // Set position with slight offset to ensure visibility
            setLayoutX(caretBounds.getMinX());
            setLayoutY(caretBounds.getMinY());
            
            // Update line height to match current line height
            cursorLine.setEndY(caretBounds.getHeight());
            
            // Make sure cursor is visible
            setVisible(true);
            toFront(); // Ensure cursor is displayed on top
        } else {
            setVisible(false);
        }
    }

    /**
     * Updates the username displayed by this cursor marker.
     * @param username The new username to display
     */
    public void setUsername(String username) {
        label.setText(username);
    }
    
    /**
     * Converts a Color to its RGB hex code string representation
     * @param color The color to convert
     * @return The RGB code as a String
     */
    private String toRGBCode(Color color) {
        return String.format("#%02X%02X%02X",
            (int)(color.getRed() * 255),
            (int)(color.getGreen() * 255),
            (int)(color.getBlue() * 255));
    }
} 