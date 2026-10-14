package io.joshuasalcedo.prettyjavadocs;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Represents information about a Java class or interface.
 */
public interface ClassInfo {
    
    /**
     * Returns the simple name of the class.
     *
     * @return The class name
     */
    String getName();
    
    /**
     * Returns the package name of the class.
     *
     * @return The package name
     */
    String getPackageName();
    
    /**
     * Returns the fully qualified name of the class (package + class name).
     *
     * @return The fully qualified name
     */
    default String getFullyQualifiedName() {
        return getPackageName() + "." + getName();
    }
    
    /**
     * Returns the type of the class (class, interface, enum, annotation).
     *
     * @return The class type
     */
    String getClassType();
    
    /**
     * Returns information about all methods in the class.
     *
     * @return List of MethodInfo objects
     */
    List<MethodInfo> getMethods();
    
    /**
     * Returns information about the class Javadoc.
     *
     * @return Optional containing JavadocInfo if present
     */
    Optional<JavadocInfo> getJavadoc();
    
    /**
     * Returns all comments associated with the class.
     *
     * @return List of comments
     */
    List<String> getComments();
    
    /**
     * Returns all metadata associated with the class.
     *
     * @return Map of metadata
     */
    Map<String, Object> getMetadata();
}