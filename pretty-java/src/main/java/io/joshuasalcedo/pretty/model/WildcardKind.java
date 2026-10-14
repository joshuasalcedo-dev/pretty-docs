package io.joshuasalcedo.pretty.model;

/**
 * Kind of wildcard in generics.
 */
public enum WildcardKind {
    UNBOUNDED, // ?
    EXTENDS,   // ? extends X
    SUPER      // ? super X
}
