package io.joshuasalcedo.prettyjavadocs.model;

import io.joshuasalcedo.prettyjavadocs.transform.TransformContext;

import javax.xml.transform.TransformerException;

/**
 * Interface for transformers that convert document elements to output formats.
 * Transformers are responsible for generating different representations of the
 * documentation (e.g., JSON, HTML, Markdown).
 *
 * @param <T> The type of document element this transformer can handle
 * @param <R> The type of output this transformer produces
 */
public interface Transformer<T extends DocumentElement, R> {
    
    /**
     * Checks if this transformer can handle the given document element.
     *
     * @param element The document element to check
     * @return true if this transformer can handle the document element
     */
    boolean canHandle(DocumentElement element);
    
    /**
     * Transforms the given document element into the output format.
     *
     * @param element The document element to transform
     * @param context Additional context for the transformation process
     * @return The transformed output
     * @throws TransformerException If there is an error during transformation
     */
    R transform(T element, TransformContext context) throws TransformerException;
    
    /**
     * Returns the type of document element this transformer can handle.
     *
     * @return The class of document element this transformer can handle
     */
    Class<T> getDocumentElementType();
    
    /**
     * Returns the type of output this transformer produces.
     *
     * @return The class of output this transformer produces
     */
    Class<R> getOutputType();
}