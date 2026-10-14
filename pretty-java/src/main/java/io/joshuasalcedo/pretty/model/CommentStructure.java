package io.joshuasalcedo.pretty.model;

import lombok.Builder;
import lombok.Data; /**
 * Represents a Java comment.
 */
@Data
@Builder
public class CommentStructure {
    private CommentKind kind; // BLOCK, LINE, JAVADOC
    private String content;
    private int startLine;
    private int endLine;
    private int startColumn;
    private int endColumn;
}
