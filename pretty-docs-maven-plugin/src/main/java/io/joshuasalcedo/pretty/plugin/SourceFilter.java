package io.joshuasalcedo.pretty.plugin;

import io.joshuasalcedo.pretty.model.ConstructorStructure;
import io.joshuasalcedo.pretty.model.JavaFileStructure;
import io.joshuasalcedo.pretty.model.MethodStructure;
import io.joshuasalcedo.pretty.model.TypeStructure;

import java.util.List;

/**
 * Removes elements below the configured visibility and, optionally, method bodies.
 * Mutates the structures in place.
 */
final class SourceFilter {

    private final Visibility visibility;
    private final boolean includeMethodBodies;

    SourceFilter(Visibility visibility, boolean includeMethodBodies) {
        this.visibility = visibility;
        this.includeMethodBodies = includeMethodBodies;
    }

    /** Filters each file; files left without types are dropped, except {@code package-info.java}. */
    List<JavaFileStructure> apply(List<JavaFileStructure> files) {
        return files.stream()
                .peek(file -> file.setTypes(types(file.getTypes())))
                .filter(file -> !file.getTypes().isEmpty() || "package-info.java".equals(file.getFileName()))
                .toList();
    }

    private List<TypeStructure> types(List<TypeStructure> types) {
        return types.stream()
                .filter(type -> visibility.includes(Visibility.of(type.getModifiers())))
                .peek(this::members)
                .toList();
    }

    private void members(TypeStructure type) {
        type.setFields(type.getFields().stream()
                .filter(field -> visibility.includes(Visibility.of(field.getModifiers())))
                .toList());
        type.setMethods(type.getMethods().stream()
                .filter(method -> visibility.includes(Visibility.of(method.getModifiers())))
                .peek(this::body)
                .toList());
        type.setConstructors(type.getConstructors().stream()
                .filter(ctor -> visibility.includes(Visibility.of(ctor.getModifiers())))
                .peek(this::body)
                .toList());
        type.setNestedTypes(types(type.getNestedTypes()));
    }

    private void body(MethodStructure method) {
        if (!includeMethodBodies) {
            method.setBody(null);
        }
    }

    private void body(ConstructorStructure ctor) {
        if (!includeMethodBodies) {
            ctor.setBody(null);
        }
    }
}
