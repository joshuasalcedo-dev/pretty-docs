package io.joshuasalcedo.prettyjavadocs.core;

import io.joshuasalcedo.prettyjavadocs.DocumentationResult;
import io.joshuasalcedo.prettyjavadocs.OutputFormat;
import io.joshuasalcedo.prettyjavadocs.core.DocumentationResultImpl;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Runner for executing the documentation generation process.
 * This class handles the actual parsing and transformation logic.
 */
public class PrettyJavadocsRunner {
    private final Path sourcePath;
    private final boolean inferMissingDocs;
    private final OutputFormat outputFormat;
    private final boolean includePrivateMembers;
    
    /**
     * Creates a new runner with the specified settings.
     *
     * @param sourcePath The directory containing Java source files
     * @param inferMissingDocs Whether to infer documentation for elements without explicit Javadoc
     * @param outputFormat The output format
     * @param includePrivateMembers Whether to include private members in the documentation
     */
    PrettyJavadocsRunner(Path sourcePath, boolean inferMissingDocs,
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
     * @throws IOException If an I/O error occurs
     */
    public DocumentationResult generate() throws IOException {
        // Validate source path
        if (!java.nio.file.Files.isDirectory(sourcePath)) {
            throw new IllegalArgumentException("Source path must be a directory: " + sourcePath);
        }
        
        // Generate documentation using the appropriate generator
        DocumentationResultImpl result = DocumentationResultImpl.generate(
            sourcePath, 
            inferMissingDocs, 
            includePrivateMembers
        );
        
        return result;
    }
    
    /**
     * Generates documentation and writes it to the specified file.
     *
     * @param outputPath The path to write the output to
     * @throws IOException If an I/O error occurs
     */
    public void generateToFile(Path outputPath) throws IOException {
        DocumentationResult result = generate();
        
        // Create parent directories if they don't exist
        Path parentDir = outputPath.getParent();
        if (parentDir != null && !java.nio.file.Files.exists(parentDir)) {
            java.nio.file.Files.createDirectories(parentDir);
        }
        
        // Write the result to the file based on the output format
        if (result instanceof DocumentationResultImpl) {
            DocumentationResultImpl resultImpl = (DocumentationResultImpl) result;
            
            switch (outputFormat) {
                case JSON:
                    resultImpl.writeToJsonFile(outputPath, true);
                    break;
                    
                case MARKDOWN:
                case HTML:
                    // These formats are not yet implemented
                    throw new UnsupportedOperationException(
                        "Output format not yet implemented: " + outputFormat
                    );
                    
                default:
                    throw new IllegalStateException("Unknown output format: " + outputFormat);
            }
        } else {
            throw new IllegalStateException("Unexpected documentation result type: " + result.getClass());
        }
    }
}