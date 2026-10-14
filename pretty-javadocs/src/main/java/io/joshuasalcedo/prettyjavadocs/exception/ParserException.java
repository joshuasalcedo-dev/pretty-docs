package io.joshuasalcedo.prettyjavadocs.exception;

/**
 * Exception thrown when there is an error during the parsing process.
 * This is used to encapsulate errors that occur when extracting information
 * from source files.
 */
public class ParserException extends Exception {
    
    /**
     * Creates a new ParserException with the specified message.
     *
     * @param message The exception message
     */
    public ParserException(String message) {
        super(message);
    }
    
    /**
     * Creates a new ParserException with the specified message and cause.
     *
     * @param message The exception message
     * @param cause The cause of the exception
     */
    public ParserException(String message, Throwable cause) {
        super(message, cause);
    }
}