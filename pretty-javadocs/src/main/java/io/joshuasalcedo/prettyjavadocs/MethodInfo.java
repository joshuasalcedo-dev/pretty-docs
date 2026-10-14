package io.joshuasalcedo.prettyjavadocs;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Represents information about a Java method.
 */
public interface MethodInfo {
    
    /**
     * Returns the name of the method.
     *
     * @return The method name
     */
    String getName();
    
    /**
     * Returns the return type of the method.
     *
     * @return The return type
     */
    String getReturnType();
    
    /**
     * Returns the access modifier of the method (public, private, protected, package-private).
     *
     * @return The access modifier
     */
    String getAccessModifier();
    
    /**
     * Returns whether the method is static.
     *
     * @return true if the method is static, false otherwise
     */
    boolean isStatic();
    
    /**
     * Returns information about all parameters of the method.
     *
     * @return List of ParameterInfo objects
     */
    List<ParameterInfo> getParameters();
    
    /**
     * Returns information about the method Javadoc.
     *
     * @return Optional containing JavadocInfo if present
     */
    Optional<JavadocInfo> getJavadoc();
    
    /**
     * Returns all comments associated with the method.
     *
     * @return List of comments
     */
    List<String> getComments();
    
    /**
     * Returns the method body.
     *
     * @return The method body
     */
    String getBody();
    
    /**
     * Returns all metadata associated with the method.
     *
     * @return Map of metadata
     */
    Map<String, Object> getMetadata();
}