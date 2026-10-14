package io.joshuasalcedo.pretty.plugin;

import io.joshuasalcedo.pretty.model.ModifierKind;
import io.joshuasalcedo.pretty.model.ModifierStructure;

import java.util.List;
import java.util.Locale;

/**
 * Java access levels, from least to most visible.
 */
public enum Visibility {
    PRIVATE, PACKAGE, PROTECTED, PUBLIC;

    /**
     * @param value public, protected, package or private (case-insensitive)
     * @return The matching level
     * @throws IllegalArgumentException for any other value
     */
    public static Visibility parse(String value) {
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IllegalArgumentException("visibility must be one of public, protected, package, private but was: " + value);
        }
    }

    /**
     * The access level a set of parsed modifiers declares. No access modifier at all means an
     * implicitly public interface or annotation member; the parser marks other members PACKAGE_PRIVATE.
     */
    public static Visibility of(List<ModifierStructure> modifiers) {
        Visibility result = PUBLIC;
        for (ModifierStructure modifier : modifiers) {
            if (modifier.getKind() == ModifierKind.PUBLIC) {
                return PUBLIC;
            } else if (modifier.getKind() == ModifierKind.PROTECTED) {
                result = PROTECTED;
            } else if (modifier.getKind() == ModifierKind.PRIVATE) {
                result = PRIVATE;
            } else if (modifier.getKind() == ModifierKind.PACKAGE_PRIVATE) {
                result = PACKAGE;
            }
        }
        return result;
    }

    /** True when an element with {@code access} is shown at this threshold. */
    public boolean includes(Visibility access) {
        return access.ordinal() >= ordinal();
    }

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }
}
