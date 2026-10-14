package io.joshuasalcedo.pretty.resolve;

import io.joshuasalcedo.pretty.model.FieldReference;
import io.joshuasalcedo.pretty.model.JavadocStructure;
import io.joshuasalcedo.pretty.model.MethodReference;

import java.util.List;
import java.util.Map;

/**
 * What the javadoc engine resolved, keyed by the canonical name of each analysed type.
 *
 * @param types canonical type name (e.g. {@code a.b.Outer.Inner}) to its resolution
 */
public record Resolution(Map<String, TypeInfo> types) {

    /**
     * @param simpleNames      simple name to canonical name for every type in this type's declarations;
     *                         names used for two different types are left out
     * @param inheritedDocs    method key ({@code name(Type,Type[])}) to the doc it inherits
     * @param inheritedMethods non-private methods inherited and not overridden
     * @param inheritedFields  non-private fields inherited and not hidden
     */
    public record TypeInfo(Map<String, String> simpleNames,
                           Map<String, InheritedDoc> inheritedDocs,
                           List<MethodReference> inheritedMethods,
                           List<FieldReference> inheritedFields) {
    }

    /**
     * @param from    canonical name of the type declaring the overridden method
     * @param javadoc its documentation
     */
    public record InheritedDoc(String from, JavadocStructure javadoc) {
    }
}
