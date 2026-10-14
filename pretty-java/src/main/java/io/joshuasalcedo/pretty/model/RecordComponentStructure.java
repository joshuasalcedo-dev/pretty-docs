package io.joshuasalcedo.pretty.model;

import lombok.Builder;
import lombok.Data;

import java.util.List; /**
 * Represents a record component.
 */
@Data
@Builder
public class RecordComponentStructure {
    private String name;
    private TypeReference type;
    private List<AnnotationStructure> annotations;
    private JavadocStructure javadoc;
}
