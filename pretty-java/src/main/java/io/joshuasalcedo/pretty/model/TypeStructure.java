package io.joshuasalcedo.pretty.model;

import lombok.Builder;
import lombok.Data;

import java.util.List; /**
 * Represents any Java type (class, interface, enum, record, annotation).
 */
@Data
@Builder
public class TypeStructure {
    private String name; // Simple name
    private String qualifiedName; // With package
    private TypeKind kind; // ENUM, CLASS, INTERFACE, ANNOTATION, RECORD
    private List<ModifierStructure> modifiers; // public, private, abstract, etc.
    private List<AnnotationStructure> annotations;
    private List<TypeParameterStructure> typeParameters; // Generics: <T>, <E extends Exception>, etc.
    private TypeStructure superclass; // Extends
    private List<TypeStructure> interfaces; // Implements
    private List<FieldStructure> fields;
    private List<MethodStructure> methods;
    private List<ConstructorStructure> constructors;
    private List<TypeStructure> nestedTypes; // Inner classes, etc.
    private JavadocStructure javadoc;
    private List<CommentStructure> comments;
    private List<EnumConstantStructure> enumConstants; // For enums only
    private List<String> usedBy; // List of classes/methods that use this type
    
    // For record types
    private List<RecordComponentStructure> recordComponents;

    // Members inherited from supertypes, including types outside the project (resolved mode only)
    private List<MethodReference> inheritedMethods;
    private List<FieldReference> inheritedFields;

    // Position information
    private int startLine;
    private int endLine;
    private int startColumn;
    private int endColumn;
}
