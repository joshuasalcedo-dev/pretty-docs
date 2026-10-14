package io.joshuasalcedo.pretty.model;

/**
 * Types of Java modifiers.
 */
public enum ModifierKind {
    PUBLIC,
    PROTECTED,
    PRIVATE,
    PACKAGE_PRIVATE, // Default
    STATIC,
    FINAL,
    ABSTRACT,
    SYNCHRONIZED,
    VOLATILE,
    TRANSIENT,
    NATIVE,
    STRICTFP,
    DEFAULT, // For interface methods
    SEALED,
    NON_SEALED
}
