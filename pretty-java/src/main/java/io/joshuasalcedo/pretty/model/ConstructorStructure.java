package io.joshuasalcedo.pretty.model;

import lombok.Builder;
import lombok.Data;

import java.util.List; /**
 * Represents a Java constructor.
 */
@Data
@Builder
public class ConstructorStructure {
    private String name;
    private List<ModifierStructure> modifiers;
    private List<AnnotationStructure> annotations;
    private List<ParameterStructure> parameters;
    private List<TypeReference> thrownExceptions;
    private List<TypeParameterStructure> typeParameters;
    private String body;
    private JavadocStructure javadoc;
    private String signature; // the declaration as written, for languages other than Java (null for Java)
    private List<CommentStructure> comments;
    
    // Usage information
    private List<MethodReference> calledBy; // Methods that call this constructor
    private List<ConstructorReference> calls; // Other constructors called by this constructor
    
    // Position information
    private int startLine;
    private int endLine;
}
