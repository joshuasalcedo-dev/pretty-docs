package io.joshuasalcedo.prettyjavadocs.source;

import io.joshuasalcedo.prettyjavadocs.exception.SourceException;

import java.nio.file.Path;
import java.util.List;

/**
 * A source provider extracts source content from different types of sources.
 * This could be Java source files, Maven POM files, Git repositories, etc.
 */
public interface SourceProvider {
    
    /**
     * Checks if this provider can handle the given source path.`
     *
     * @param sourcePath The path to check
     * @return true if this provider can handle the source
     */
    boolean canHandle(Path sourcePath);
    
    /**
     * Provides a list of source files from the given source path.
     *
     * @param sourcePath The path to extract sources from
     * @param context Additional context for the source extraction process
     * @return A list of source files
     * @throws SourceException If there is an error extracting the sources
     */
    List<SourceFile> provideSources(Path sourcePath, SourceContext context) throws SourceException;
}