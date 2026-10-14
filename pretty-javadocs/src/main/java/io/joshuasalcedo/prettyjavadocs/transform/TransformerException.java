package io.joshuasalcedo.prettyjavadocs.transform;

/**
 * Exception thrown when there is an error during the transformation process.
 * This is used to encapsulate errors that occur when converting documentation
 * elements to output formats.
 */
public class TransformerException extends Exception {
    
    /**
     * Creates a new TransformerException with the specified message.
     *
     * @param message The exception message
     */
    public TransformerException(String message) {
        super(message);
    }
    
    /**
     * Creates a new TransformerException with the specified message and cause.
     *
     * @param message The exception message
     * @param cause The cause of the exception
     */
    public TransformerException(String message, Throwable cause) {
        super(message, cause);
    }
}