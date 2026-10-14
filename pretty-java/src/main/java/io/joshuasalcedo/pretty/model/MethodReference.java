package io.joshuasalcedo.pretty.model;

import lombok.Builder;
import lombok.Data;

import java.util.List; /**
 * Represents a reference to a method.
 */
@Data
@Builder
public class MethodReference {
    private String methodName;
    private String declaringTypeName;
    private String qualifiedDeclaringTypeName;
    private List<TypeReference> parameterTypes;
    private int line;
    private int column;
}
