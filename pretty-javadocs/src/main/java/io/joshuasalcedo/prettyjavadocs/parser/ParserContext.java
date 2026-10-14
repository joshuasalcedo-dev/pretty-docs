package io.joshuasalcedo.prettyjavadocs.parser;

import io.joshuasalcedo.prettyjavadocs.source.SourceContext;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Context for parsers, containing configuration and shared state
 * for the parsing process.
 */
public class ParserContext {
    
    private final Map<String, Object> properties = new HashMap<>();
    private final SourceContext sourceContext;
    
    /**
     * Creates a new parser context with the specified source context.
     *
     * @param sourceContext The source context
     */
    public ParserContext(SourceContext sourceContext) {
        this.sourceContext = sourceContext;
    }
    
    /**
     * Creates a new parser context with a null source context.
     * This is useful for testing or when a source context is not needed.
     */
    public ParserContext() {
        this.sourceContext = null;
    }
    
    /**
     * Returns the source context.
     *
     * @return The source context, or null if not provided
     */
    public SourceContext getSourceContext() {
        return sourceContext;
    }
    
    /**
     * Sets a property in the context.
     *
     * @param key The property key
     * @param value The property value
     * @return This context instance for method chaining
     */
    public ParserContext setProperty(String key, Object value) {
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
     * Creates a new parser context builder.
     *
     * @return A new builder instance
     */
    public static Builder builder() {
        return new Builder();
    }
    
    /**
     * Common property keys used in parser contexts.
     */
    public static final class Keys {
        /**
         * Property key for controlling whether to infer documentation for elements without explicit Javadoc.
         */
        public static final String INFER_MISSING_DOCS = "inferMissingDocs";
        
        /**
         * Property key for controlling whether to include private members in the documentation.
         */
        public static final String INCLUDE_PRIVATE_MEMBERS = "includePrivateMembers";
        
        /**
         * Property key for controlling whether to include method bodies in the documentation.
         */
        public static final String INCLUDE_METHOD_BODIES = "includeMethodBodies";
        
        /**
         * Property key for controlling whether to expand type parameters in generics.
         */
        public static final String EXPAND_TYPE_PARAMETERS = "expandTypeParameters";
        
        /**
         * Property key for controlling the maximum depth of nested elements to parse.
         */
        public static final String MAX_DEPTH = "maxDepth";
        
        private Keys() {
            // Private constructor to prevent instantiation
        }
    }
    
    /**
     * Builder for ParserContext instances.
     */
    public static class Builder {
        private SourceContext sourceContext;
        private final Map<String, Object> properties = new HashMap<>();
        
        private Builder() {
        }
        
        /**
         * Sets the source context.
         *
         * @param sourceContext The source context
         * @return This builder instance for method chaining
         */
        public Builder sourceContext(SourceContext sourceContext) {
            this.sourceContext = sourceContext;
            return this;
        }
        
        /**
         * Sets a property in the context.
         *
         * @param key The property key
         * @param value The property value
         * @return This builder instance for method chaining
         */
        public Builder property(String key, Object value) {
            properties.put(key, value);
            return this;
        }
        
        /**
         * Sets whether to infer documentation for elements without explicit Javadoc.
         *
         * @param inferMissingDocs Whether to infer missing documentation
         * @return This builder instance for method chaining
         */
        public Builder inferMissingDocs(boolean inferMissingDocs) {
            return property(Keys.INFER_MISSING_DOCS, inferMissingDocs);
        }
        
        /**
         * Sets whether to include private members in the documentation.
         *
         * @param includePrivateMembers Whether to include private members
         * @return This builder instance for method chaining
         */
        public Builder includePrivateMembers(boolean includePrivateMembers) {
            return property(Keys.INCLUDE_PRIVATE_MEMBERS, includePrivateMembers);
        }
        
        /**
         * Sets whether to include method bodies in the documentation.
         *
         * @param includeMethodBodies Whether to include method bodies
         * @return This builder instance for method chaining
         */
        public Builder includeMethodBodies(boolean includeMethodBodies) {
            return property(Keys.INCLUDE_METHOD_BODIES, includeMethodBodies);
        }
        
        /**
         * Sets whether to expand type parameters in generics.
         *
         * @param expandTypeParameters Whether to expand type parameters
         * @return This builder instance for method chaining
         */
        public Builder expandTypeParameters(boolean expandTypeParameters) {
            return property(Keys.EXPAND_TYPE_PARAMETERS, expandTypeParameters);
        }
        
        /**
         * Sets the maximum depth of nested elements to parse.
         *
         * @param maxDepth The maximum depth
         * @return This builder instance for method chaining
         */
        public Builder maxDepth(int maxDepth) {
            return property(Keys.MAX_DEPTH, maxDepth);
        }
        
        /**
         * Builds a ParserContext with the configured settings.
         *
         * @return A new ParserContext instance
         */
        public ParserContext build() {
            ParserContext context = new ParserContext(sourceContext);
            properties.forEach(context::setProperty);
            return context;
        }
    }
}