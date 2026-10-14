package io.joshuasalcedo.pretty.model;

import lombok.Builder;
import lombok.Data;

import java.util.List; /**
 * Represents a field in a Java class.
 */
@Data
@Builder
public class FieldStructure {
    private String name;
    private TypeReference type;
    private List<ModifierStructure> modifiers;
    private List<AnnotationStructure> annotations;
    private String initializer; // The initialization expression, if any
    private JavadocStructure javadoc;
    private List<CommentStructure> comments;
    private boolean isEnumConstant;
    
    // Usage information
    private List<MethodReference> readBy; // Methods that read this field
    private List<MethodReference> writtenBy; // Methods that write to this field
    
    // Position information
    private int startLine;
    private int endLine;
}
