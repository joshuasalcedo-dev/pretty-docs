package io.joshuasalcedo.prettyjavadocs;

/**
 * Represents a tag in a Javadoc comment.
 */
public interface JavadocTag {
    
    /**
     * Returns the name of the tag (without the @ symbol).
     *
     * @return The tag name
     */
    String getName();
    
    /**
     * Returns the value of the tag.
     *
     * @return The tag value
     */
    String getValue();
}