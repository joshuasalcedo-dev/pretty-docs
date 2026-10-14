package io.joshuasalcedo.prettyjavadocs.source;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Context for source providers, containing configuration and shared state
 * for the source extraction process.
 */
public class SourceContext {
    private final Map<String, Object> properties = new HashMap<>();
    private final Path rootPath;
    
    /**
     * Creates a new source context with the specified root path.
     *
     * @param rootPath The root path for all source operations
     */
    public SourceContext(Path rootPath) {
        this.rootPath = rootPath;
    }
    
    /**
     * Returns the root path for all source operations.
     *
     * @return The root path
     */
    public Path getRootPath() {
        return rootPath;
    }
    
    /**
     * Sets a property in the context.
     *
     * @param key The property key
     * @param value The property value
     * @return This context instance for method chaining
     */
    public SourceContext setProperty(String key, Object value) {
        properties.put(key, value);
        return this;
    }
    
    /**
     * Returns a property from the context.
     *
     * @param key The property key
     * @return Optional containing the property value if present
     */
    public Optional<Object> getProperty(String key) {
        return Optional.ofNullable(properties.get(key));
    }
    
    /**
     * Returns a typed property from the context.
     *
     * @param key The property key
     * @param type The expected type of the property
     * @param <T> The type parameter
     * @return Optional containing the property value if present and of the correct type
     */
    @SuppressWarnings("unchecked")
    public <T> Optional<T> getProperty(String key, Class<T> type) {
        Object value = properties.get(key);
        if (value != null && type.isInstance(value)) {
            return Optional.of((T) value);
        }
        return Optional.empty();
    }
    
    /**
     * Returns all properties in the context.
     *
     * @return Unmodifiable map of all properties
     */
    public Map<String, Object> getProperties() {
        return Map.copyOf(properties);
    }
}