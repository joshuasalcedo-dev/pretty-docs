package io.joshuasalcedo.prettyjavadocs.model;

/**
 * Enumeration representing the types of documentation elements.
 * This can be used to identify the role of each element in the documentation structure.
 */
public enum ElementType {
    /**
     * Represents a Java package.
     */
    PACKAGE,
    
    /**
     * Represents a Java class.
     */
    CLASS,
    
    /**
     * Represents a Java interface.
     */
    INTERFACE,
    
    /**
     * Represents a Java enum.
     */
    ENUM,
    
    /**
     * Represents a Java annotation.
     */
    ANNOTATION,
    
    /**
     * Represents a Java method.
     */
    METHOD,
    
    /**
     * Represents a Java constructor.
     */
    CONSTRUCTOR,
    
    /**
     * Represents a Java field.
     */
    FIELD,
    
    /**
     * Represents a Java enum constant.
     */
    ENUM_CONSTANT,
    
    /**
     * Represents a method parameter.
     */
    PARAMETER,
    
    /**
     * Represents a type parameter (generics).
     */
    TYPE_PARAMETER,
    
    /**
     * Represents a module (Java 9+).
     */
    MODULE,
    
    /**
     * Represents a project (collection of modules or packages).
     */
    PROJECT,
    
    /**
     * Represents a documentation section that doesn't map directly to a Java element.
     */
    SECTION,
    
    /**
     * Represents an unknown or custom element type.
     */
    OTHER
}