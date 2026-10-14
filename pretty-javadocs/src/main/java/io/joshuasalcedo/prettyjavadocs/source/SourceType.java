package io.joshuasalcedo.prettyjavadocs.source;

/**
 * Enumeration representing the types of source files.
 * This can be used to identify the type of source content being processed.
 */
public enum SourceType {
    /**
     * Represents a Java source file (.java).
     */
    JAVA,
    
    /**
     * Represents a Maven POM file (pom.xml).
     */
    MAVEN_POM,
    
    /**
     * Represents a Git repository information.
     */
    GIT,
    
    /**
     * Represents a Markdown file (.md, .markdown).
     */
    MARKDOWN,
    
    /**
     * Represents an HTML file (.html, .htm).
     */
    HTML,
    
    /**
     * Represents a Kotlin source file (.kt).
     */
    KOTLIN,
    
    /**
     * Represents a Gradle build file (build.gradle, build.gradle.kts).
     */
    GRADLE,
    
    /**
     * Represents a properties file (.properties).
     */
    PROPERTIES,
    
    /**
     * Represents a YAML file (.yml, .yaml).
     */
    YAML,
    
    /**
     * Represents a JSON file (.json).
     */
    JSON,
    
    /**
     * Represents an XML file (.xml) that is not a Maven POM.
     */
    XML,
    
    /**
     * Represents a source file of an unknown or custom type.
     */
    OTHER
}