package io.joshuasalcedo.pretty.model;

import lombok.Builder;
import lombok.Data;

import java.util.List; /**
 * Represents an enum constant.
 */
@Data
@Builder
public class EnumConstantStructure {
    private String name;
    private List<AnnotationStructure> annotations;
    private List<String> arguments; // Constructor arguments
    private JavadocStructure javadoc;
    private List<CommentStructure> comments;
    private int ordinal; // Position in the enum
    private int startLine;
    private int endLine;
}
