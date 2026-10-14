package io.joshuasalcedo.pretty.model;

import lombok.Builder;
import lombok.Data;

import java.util.Map; /**
 * Represents a Java annotation.
 */
@Data
@Builder
public class AnnotationStructure {
    private String name; // Simple name
    private String qualifiedName; // With package
    private Map<String, String> values; // Annotation attribute values
    private int position; // Position in source code
}
