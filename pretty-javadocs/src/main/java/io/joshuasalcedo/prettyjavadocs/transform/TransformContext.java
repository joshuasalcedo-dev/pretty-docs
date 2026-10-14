package io.joshuasalcedo.prettyjavadocs.transform;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Context for transformers, containing configuration and shared state
 * for the transformation process.
 */
public class TransformContext {
    
    private final Map<String, Object> properties = new HashMap<>();
    
    /**
     * Creates a new empty transform context.
     */
    public TransformContext() {
    }
    
    /**
     * Sets a property in the context.
     *
     * @param key The property key
     * @param value The property value
     * @return This context instance for method chaining
     */
    public TransformContext setProperty(String key, Object value) {
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
    
    /**
     * Common property keys used in transform contexts.
     */
    public static final class Keys {
        /**
         * Property key for controlling whether to format the output with indentation and line breaks.
         */
        public static final String PRETTY_PRINT = "prettyPrint";
        
        /**
         * Property key for controlling the maximum depth of nested elements to include.
         */
        public static final String MAX_DEPTH = "maxDepth";
        
        /**
         * Property key for controlling whether to include private members in the output.
         */
        public static final String INCLUDE_PRIVATE_MEMBERS = "includePrivateMembers";
        
        /**
         * Property key for controlling whether to include method bodies in the output.
         */
        public static final String INCLUDE_METHOD_BODIES = "includeMethodBodies";
        
        /**
         * Property key for controlling whether to include documentation inferred from code.
         */
        public static final String INCLUDE_INFERRED_DOCS = "includeInferredDocs";
        
        private Keys() {
            // Private constructor to prevent instantiation
        }
    }
}