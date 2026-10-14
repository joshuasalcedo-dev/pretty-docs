package io.joshuasalcedo.pretty.model;

import lombok.Builder;
import lombok.Data;

import java.util.List; /**
 * Represents a method or constructor parameter.
 */
@Data
@Builder
public class ParameterStructure {
    private String name;
    private TypeReference type;
    private List<AnnotationStructure> annotations;
    private boolean isVarArgs; // ...
    private boolean isFinal;
    
    // Usage information
    private List<String> usageLocations; // Where in the method this parameter is used
}
