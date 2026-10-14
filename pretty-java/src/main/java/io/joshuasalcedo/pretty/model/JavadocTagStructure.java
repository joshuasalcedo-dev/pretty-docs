package io.joshuasalcedo.pretty.model;

import lombok.Builder;
import lombok.Data; /**
 * Represents a Javadoc tag like @param, @return, etc.
 */
@Data
@Builder
public class JavadocTagStructure {
    private String name; // @param, @return, etc.
    private String text; // Text content
    // For structured tags like @param
    private String paramName; // For @param tags
    private int position; // Position in the javadoc
}
