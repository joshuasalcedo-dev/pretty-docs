package io.joshuasalcedo.pretty.plugin.model;

import lombok.Builder;
import lombok.Data;

/**
 * Counts over a module's documented (visible) elements.
 */
@Data
@Builder
public class ModuleStatistics {
    private int files;
    private int packages;
    private int types;
    private int methods; // including constructors
    private int fields;
    private int documented; // types, methods, constructors and fields with a javadoc
    private int documentable; // types, methods, constructors and fields
    private int linesOfCode; // code lines (same as codeLines; kept for compatibility)
    private int totalLines;
    private int codeLines;
    private int commentLines;
    private int blankLines;
    private java.util.Map<String, Integer> typesByKind; // CLASS, INTERFACE, ENUM, RECORD, ANNOTATION
    private double averageComplexity; // over methods with a body
    private int maxComplexity;
}
