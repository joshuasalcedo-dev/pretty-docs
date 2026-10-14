package io.joshuasalcedo.pretty.model;

import lombok.Builder;
import lombok.Data;

import java.util.List; /**
 * Represents a reference to a constructor.
 */
@Data
@Builder
public class ConstructorReference {
    private String typeName;
    private String qualifiedTypeName;
    private List<TypeReference> parameterTypes;
    private int line;
    private int column;
}
