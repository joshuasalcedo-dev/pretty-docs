package io.joshuasalcedo.pretty.model;

import lombok.Builder;
import lombok.Data; /**
 * Represents a Java modifier like public, static, final, etc.
 */
@Data
@Builder
public class ModifierStructure {
    private ModifierKind kind;
    private int position; // Position in source code
}
