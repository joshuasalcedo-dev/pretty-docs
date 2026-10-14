package io.joshuasalcedo.prettyjavadocs.exception;

/**
 * Exception thrown when there is an error related to source files.
 */
public class SourceException extends Exception {
    
    /**
     * Creates a new SourceException with the specified message.
     *
     * @param message The exception message
     */
    public SourceException(String message) {
        super(message);
    }
    
    /**
     * Creates a new SourceException with the specified message and cause.
     *
     * @param message The exception message
     * @param cause The cause of the exception
     */
    public SourceException(String message, Throwable cause) {
        super(message, cause);
    }
}