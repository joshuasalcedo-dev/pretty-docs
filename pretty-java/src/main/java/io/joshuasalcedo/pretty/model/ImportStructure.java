package io.joshuasalcedo.pretty.model;

import lombok.Builder;
import lombok.Data; /**
 * Represents a Java import statement.
 */
@Data
@Builder
public class ImportStructure {
    private String qualifiedName;
    private boolean isStatic;
    private boolean isWildcard; // import java.util.* or import static java.util.Collections.*
    private int lineNumber;
}
