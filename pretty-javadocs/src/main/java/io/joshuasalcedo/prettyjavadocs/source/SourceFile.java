package io.joshuasalcedo.prettyjavadocs.source;

import io.joshuasalcedo.prettyjavadocs.exception.SourceException;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Represents a source file that can be processed by the parsers.
 * This abstraction allows handling different types of source content
 * (Java files, XML files, etc.) in a unified way.
 */
public interface SourceFile {
    
    /**
     * Returns the path to the source file.
     *
     * @return The path to the source file
     */
    Path getPath();
    
    /**
     * Returns the name of the source file.
     *
     * @return The name of the source file
     */
    String getName();
    
    /**
     * Returns the content of the source file.
     *
     * @return The content of the source file
     * @throws SourceException If there is an error reading the content
     */
    String getContent() throws SourceException;
    
    /**
     * Returns the type of the source file.
     *
     * @return The type of the source file
     */
    SourceType getType();
    
    /**
     * Returns metadata associated with this source file.
     *
     * @param key The metadata key
     * @return Optional containing the metadata value if present
     */
    Optional<Object> getMetadata(String key);
    
    /**
     * Adds metadata to this source file.
     *
     * @param key The metadata key
     * @param value The metadata value
     */
    void addMetadata(String key, Object value);
}