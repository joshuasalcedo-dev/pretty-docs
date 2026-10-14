package io.joshuasalcedo.pretty.model;

import lombok.Builder;
import lombok.Data; /**
 * Represents a reference to a field.
 */
@Data
@Builder
public class FieldReference {
    private String fieldName;
    private String declaringTypeName;
    private String qualifiedDeclaringTypeName;
    private boolean isRead; // true if read, false if write
    private int line;
    private int column;
}
