package io.joshuasalcedo.prettyjavadocs;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Main entry point for the PrettyJavadocs library.
 * This class provides a fluent API for generating structured documentation
 * from Java source files.
 */
public class PrettyJavadocs {
    
    private PrettyJavadocs() {
        // Private constructor to prevent instantiation
    }
    
    /**
     * Creates a new builder for configuring the documentation generation process.
     *
     * @return A new builder instance
     */
    public static Builder builder() {
        return new Builder();
    }
    
    /**
     * Builder for configuring the documentation generation process.
     */
    public static class Builder {
        private Path sourcePath;
        private boolean inferMissingDocs = true;
        private OutputFormat outputFormat = OutputFormat.JSON;
        private boolean includePrivateMembers = false;
        
        /**
         * Sets the source path for Java files.
         *
         * @param sourcePath The directory containing Java source files
         * @return This builder instance for method chaining
         */
        public Builder withSource(Path sourcePath) {
            this.sourcePath = sourcePath;
            return this;
        }
        
        /**
         * Controls whether to infer documentation for elements without explicit Javadoc.
         *
         * @param inferMissingDocs true to enable inference, false to disable
         * @return This builder instance for method chaining
         */
        public Builder inferMissingDocs(boolean inferMissingDocs) {
            this.inferMissingDocs = inferMissingDocs;
            return this;
        }
        
        /**
         * Sets the output format for the generated documentation.
         *
         * @param outputFormat The output format
         * @return This builder instance for method chaining
         */
        public Builder outputFormat(OutputFormat outputFormat) {
            this.outputFormat = outputFormat;
            return this;
        }
        
        /**
         * Controls whether to include private members in the documentation.
         *
         * @param includePrivateMembers true to include private members, false to exclude
         * @return This builder instance for method chaining
         */
        public Builder includePrivateMembers(boolean includePrivateMembers) {
            this.includePrivateMembers = includePrivateMembers;
            return this;
        }
        
        /**
         * Builds a PrettyJavadocsRunner with the configured settings.
         *
         * @return A new runner instance
         */
        public PrettyJavadocsRunner build() {
            // Validation
            if (sourcePath == null) {
                throw new IllegalStateException("Source path must be specified");
            }
            
            return new PrettyJavadocsRunner(sourcePath, inferMissingDocs, outputFormat, includePrivateMembers);
        }
    }
    
    /**
     * Runner for executing the documentation generation process.
     */
    public static class PrettyJavadocsRunner {
        private final Path sourcePath;
        private final boolean inferMissingDocs;
        private final OutputFormat outputFormat;
        private final boolean includePrivateMembers;
        
        private PrettyJavadocsRunner(Path sourcePath, boolean inferMissingDocs, 
                                    OutputFormat outputFormat, boolean includePrivateMembers) {
            this.sourcePath = sourcePath;
            this.inferMissingDocs = inferMissingDocs;
            this.outputFormat = outputFormat;
            this.includePrivateMembers = includePrivateMembers;
        }
        
        /**
         * Generates documentation and returns the result.
         *
         * @return The documentation result
         */
        public DocumentationResult generate() {
            // This will be implemented with the actual parsing and transformation logic
            // For now, it's just a placeholder
            return new DocumentationResultImpl();
        }
        
        /**
         * Generates documentation and writes it to the specified file.
         *
         * @param outputPath The path to write the output to
         */
        public void generateToFile(Path outputPath) {
            DocumentationResult result = generate();
            // Write the result to the file
            // This will be implemented later
        }
    }
    
    /**
     * Implementation of the DocumentationResult interface.
     * This is just a placeholder for now.
     */
    private static class DocumentationResultImpl implements DocumentationResult {
        @Override
        public List<ClassInfo> getClasses() {
            return List.of();
        }
        
        @Override
        public Map<String, Object> getRawData() {
            return Map.of();
        }
    }
}