package io.joshuasalcedo.prettyjavadocs.source;

import io.joshuasalcedo.prettyjavadocs.exception.SourceException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Implementation of SourceFile for Java source files.
 * This represents a Java source file that can be parsed by the JavaSourceParser.
 */
public class JavaSourceFile implements SourceFile {
    
    private final Path path;
    private String content;
    private final Map<String, Object> metadata = new HashMap<>();
    
    /**
     * Creates a new JavaSourceFile with the specified path.
     *
     * @param path The path to the Java source file
     */
    public JavaSourceFile(Path path) {
        this.path = path;
    }
    
    @Override
    public Path getPath() {
        return path;
    }
    
    @Override
    public String getName() {
        return path.getFileName().toString();
    }
    
    @Override
    public String getContent() throws SourceException {
        if (content == null) {
            try {
                content = Files.readString(path);
            } catch (IOException e) {
                throw new SourceException("Error reading Java source file: " + path, e);
            }
        }
        return content;
    }
    
    @Override
    public SourceType getType() {
        return SourceType.JAVA;
    }
    
    @Override
    public Optional<Object> getMetadata(String key) {
        return Optional.ofNullable(metadata.get(key));
    }
    
    @Override
    public void addMetadata(String key, Object value) {
        metadata.put(key, value);
    }
}