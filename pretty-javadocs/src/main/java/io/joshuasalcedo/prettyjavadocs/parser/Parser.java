package io.joshuasalcedo.prettyjavadocs.parser;

import io.joshuasalcedo.prettyjavadocs.exception.ParserException;
import io.joshuasalcedo.prettyjavadocs.model.DocumentElement;
import io.joshuasalcedo.prettyjavadocs.source.SourceFile;

/**
 * Interface for parsers that extract structured documentation from source files.
 * Each parser implementation is responsible for handling a specific type of source.
 *
 * @param <T> The type of DocumentElement this parser produces
 */
public interface Parser<T extends DocumentElement> {
    
    /**
     * Checks if this parser can handle the given source file.
     *
     * @param sourceFile The source file to check
     * @return true if this parser can handle the source file
     */
    boolean canHandle(SourceFile sourceFile);
    
    /**
     * Parses the given source file and returns a structured document element.
     *
     * @param sourceFile The source file to parse
     * @param context Additional context for the parsing process
     * @return The parsed document element
     * @throws ParserException If there is an error parsing the source file
     */
    T parse(SourceFile sourceFile, ParserContext context) throws ParserException;
    
    /**
     * Returns the type of document element this parser produces.
     *
     * @return The class of the document element this parser produces
     */
    Class<T> getDocumentElementType();
}