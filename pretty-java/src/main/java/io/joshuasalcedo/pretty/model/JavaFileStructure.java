package io.joshuasalcedo.pretty.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Represents the structure of a Java source file with all its components.
 */
@Data
@Builder
public class JavaFileStructure {
    private String fileName;
    private String filePath;
    private String packageName;
    private List<ImportStructure> imports;
    private List<TypeStructure> types; // Classes, interfaces, enums, records, annotations
    private List<CommentStructure> fileComments; // Comments at file level (not attached to any type)

    // Line counts, cloc-style: a line with code and a comment counts as code
    private int totalLines;
    private int codeLines;
    private int commentLines; // comment-only lines (javadoc included)
    private int blankLines;
}

