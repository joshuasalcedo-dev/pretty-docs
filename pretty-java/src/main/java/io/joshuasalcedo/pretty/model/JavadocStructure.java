package io.joshuasalcedo.pretty.model;

import lombok.Builder;
import lombok.Data;

import java.util.List; /**
 * Represents a Javadoc comment.
 */
@Data
@Builder
public class JavadocStructure {
    private String description;
    private List<JavadocTagStructure> tags;
    private int startLine;
    private int endLine;
    private String inheritedFrom; // qualified name of the type this doc was inherited from (resolved mode only)
}
