package io.joshuasalcedo.pretty.model;

import lombok.Builder;
import lombok.Data;

import java.util.List; /**
 * Represents a type parameter (generic type).
 */
@Data
@Builder
public class TypeParameterStructure {
    private String name; // T, E, etc.
    private List<TypeReference> bounds; // extends Comparable<T>, etc.
}
