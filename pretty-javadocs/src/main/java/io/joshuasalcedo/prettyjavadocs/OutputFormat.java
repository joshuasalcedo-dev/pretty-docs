package io.joshuasalcedo.prettyjavadocs;

/**
 * Enum representing the supported output formats for documentation.
 */
public enum OutputFormat {
    /**
     * JSON format, suitable for processing by frontend applications.
     */
    JSON,
    
    /**
     * Markdown format, suitable for display in GitHub or other Markdown viewers.
     */
    MARKDOWN,
    
    /**
     * HTML format, suitable for direct display in browsers.
     */
    HTML
}