package io.joshuasalcedo.pretty.model;

import lombok.Builder;
import lombok.Data;

import java.util.List; /**
 * Represents a type reference with generics.
 */
@Data
@Builder
public class TypeReference {
    private String name; // Simple name
    private String qualifiedName; // With package if available
    private List<TypeReference> typeArguments; // For generic types like List<String>
    private boolean isArray;
    private int arrayDimensions; // For multi-dimensional arrays
    private boolean isPrimitive;
    private boolean isWildcard; // ?
    private WildcardKind wildcardKind; // UNBOUNDED, EXTENDS, SUPER
    private TypeReference wildcardBound; // For ? extends X or ? super X
}
