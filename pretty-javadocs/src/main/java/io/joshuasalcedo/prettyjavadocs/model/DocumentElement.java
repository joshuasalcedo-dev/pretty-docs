package io.joshuasalcedo.prettyjavadocs.model;

import java.util.Map;
import java.util.Optional;

/**
 * Base interface for all documentation elements.
 * This represents a structural component of the documentation,
 * such as a class, method, package, etc.
 */
public interface DocumentElement {
    
    /**
     * Returns a unique identifier for this element.
     *
     * @return The element's unique identifier
     */
    String getId();
    
    /**
     * Returns the type of this element.
     *
     * @return The element type
     */
    ElementType getType();
    
    /**
     * Returns the parent element, if any.
     *
     * @return Optional containing the parent element if present
     */
    Optional<DocumentElement> getParent();
    
    /**
     * Sets the parent element.
     *
     * @param parent The parent element
     */
    void setParent(DocumentElement parent);
    
    /**
     * Returns all metadata associated with this element.
     *
     * @return A map of metadata keys to values
     */
    Map<String, Object> getMetadata();
    
    /**
     * Returns a specific metadata value.
     *
     * @param key The metadata key
     * @return Optional containing the metadata value if present
     */
    Optional<Object> getMetadata(String key);
    
    /**
     * Adds metadata to this element.
     *
     * @param key The metadata key
     * @param value The metadata value
     */
    void addMetadata(String key, Object value);
}