package io.joshuasalcedo.pretty.model;

/**
 * Kind of a documented type. The last four are TypeScript declarations (see pretty-ts), which the
 * site documents like types: a type alias, a function (its overloads as methods), a variable (as
 * one field) and a namespace (its declarations as nested types).
 */
public enum TypeKind {
    CLASS,
    INTERFACE,
    ENUM,
    ANNOTATION,
    RECORD,
    TYPE_ALIAS,
    FUNCTION,
    VARIABLE,
    NAMESPACE
}
