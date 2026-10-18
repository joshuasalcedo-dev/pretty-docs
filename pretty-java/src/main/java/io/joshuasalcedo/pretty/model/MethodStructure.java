package io.joshuasalcedo.pretty.model;

import lombok.Builder;
import lombok.Data;

import java.util.List; /**
 * Represents a Java method.
 */
@Data
@Builder
public class MethodStructure {
    private String name;
    private TypeReference returnType;
    private List<ModifierStructure> modifiers;
    private List<AnnotationStructure> annotations;
    private List<ParameterStructure> parameters;
    private List<TypeReference> thrownExceptions;
    private List<TypeParameterStructure> typeParameters; // <T> void method()
    private String body;
    private JavadocStructure javadoc;
    private String signature; // the declaration as written, for languages other than Java (null for Java)
    private List<CommentStructure> comments;
    
    // Usage information
    private List<MethodReference> calledBy; // Methods that call this method
    private List<MethodReference> calls; // Methods called by this method
    private List<FieldReference> fieldReads; // Fields read by this method
    private List<FieldReference> fieldWrites; // Fields written by this method
    
    // Complexity metrics
    private int cyclomaticComplexity;
    private int lineCount;
    
    // Position information
    private int startLine;
    private int endLine;
    private int startColumn;
    private int endColumn;
}
