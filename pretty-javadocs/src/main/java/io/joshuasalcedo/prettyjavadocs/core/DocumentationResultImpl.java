package io.joshuasalcedo.prettyjavadocs.core;

import io.joshuasalcedo.prettyjavadocs.ClassInfo;
import io.joshuasalcedo.prettyjavadocs.DocumentationResult;
import io.joshuasalcedo.prettyjavadocs.parser.JavaSourceParser;
import io.joshuasalcedo.prettyjavadocs.parser.JsonTransformer;


import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Implementation of the DocumentationResult interface.
 * This class manages the collection of parsed documentation elements
 * and provides methods for accessing and transforming them.
 */
public class DocumentationResultImpl implements DocumentationResult {
    
    private final List<ClassInfo> classes = new ArrayList<>();
    private final Map<String, Object> metadata = new HashMap<>();
    
    /**
     * Creates a new empty documentation result.
     */
    public DocumentationResultImpl() {
    }
    
    /**
     * Adds a class to the documentation result.
     *
     * @param classInfo The class info to add
     * @return This documentation result instance for method chaining
     */
    public DocumentationResultImpl addClass(ClassInfo classInfo) {
        classes.add(classInfo);
        return this;
    }
    
    /**
     * Adds metadata to the documentation result.
     *
     * @param key The metadata key
     * @param value The metadata value
     * @return This documentation result instance for method chaining
     */
    public DocumentationResultImpl addMetadata(String key, Object value) {
        metadata.put(key, value);
        return this;
    }
    
    @Override
    public List<ClassInfo> getClasses() {
        return Collections.unmodifiableList(classes);
    }
    
    @Override
    public Map<String, Object> getRawData() {
        Map<String, Object> rawData = new HashMap<>();
        
        // Add classes
        Map<String, ClassInfo> classesMap = new HashMap<>();
        for (ClassInfo classInfo : classes) {
            classesMap.put(classInfo.getFullyQualifiedName(), classInfo);
        }
        rawData.put("classes", classesMap);
        
        // Add metadata
        rawData.put("metadata", metadata);
        
        return rawData;
    }
    
    /**
     * Generates documentation for a Java source directory.
     *
     * @param sourcePath The directory containing Java source files
     * @param inferMissingDocs Whether to infer documentation for elements without explicit Javadoc
     * @param includePrivateMembers Whether to include private members in the documentation
     * @return A new documentation result containing the parsed documentation
     * @throws IOException If an I/O error occurs
     */
    public static DocumentationResultImpl generate(Path sourcePath, boolean inferMissingDocs, boolean includePrivateMembers) throws IOException {
        DocumentationResultImpl result = new DocumentationResultImpl();
        
        // Add metadata
        result.addMetadata("generatedAt", new Date().toString());
        result.addMetadata("sourcePath", sourcePath.toString());
        result.addMetadata("inferMissingDocs", inferMissingDocs);
        result.addMetadata("includePrivateMembers", includePrivateMembers);
        
        // Find all Java files
        List<Path> javaFiles = findJavaFiles(sourcePath);
        result.addMetadata("fileCount", javaFiles.size());
        
        // Parse each Java file
        JavaSourceParser parser = new JavaSourceParser();
        
        for (Path javaFile : javaFiles) {
            try {
                ClassInfo classInfo = parser.parseJavaFile(javaFile);
                result.addClass(classInfo);
            } catch (Exception e) {
                // Log error but continue with other files
                System.err.println("Error parsing file: " + javaFile + ": " + e.getMessage());
            }
        }
        
        return result;
    }
    
    /**
     * Finds all Java files in a directory and its subdirectories.
     *
     * @param sourcePath The directory to search
     * @return List of paths to Java files
     * @throws IOException If an I/O error occurs
     */
    private static List<Path> findJavaFiles(Path sourcePath) throws IOException {
        if (!Files.isDirectory(sourcePath)) {
            throw new IllegalArgumentException("Source path must be a directory: " + sourcePath);
        }
        
        try (Stream<Path> pathStream = Files.walk(sourcePath)) {
            return pathStream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().toLowerCase().endsWith(".java"))
                    .collect(Collectors.toList());
        }
    }
    
    /**
     * Converts this documentation result to a JSON string.
     *
     * @param prettyPrint Whether to enable pretty-printing
     * @return The JSON representation of this documentation result
     */
    public String toJson(boolean prettyPrint) {
        JsonTransformer transformer = new JsonTransformer(prettyPrint);
        return transformer.transform(this);
    }
    
    /**
     * Writes this documentation result to a JSON file.
     *
     * @param outputPath The path to write the output to
     * @param prettyPrint Whether to enable pretty-printing
     * @throws IOException If an I/O error occurs
     */
    public void writeToJsonFile(Path outputPath, boolean prettyPrint) throws IOException {
        String json = toJson(prettyPrint);
        Files.writeString(outputPath, json);
    }
}