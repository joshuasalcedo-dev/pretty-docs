package io.joshuasalcedo.pretty.spring;

import io.joshuasalcedo.pretty.model.AnnotationStructure;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads annotations from the parsed model, where values are kept as written in source.
 */
final class Annotations {

    private static final Pattern STRING = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern CLASS_LITERAL = Pattern.compile("([A-Za-z_$][\\w.$]*)\\.class");
    private static final Pattern BOOLEAN_FALSE = Pattern.compile("^\\s*false\\s*$");

    private Annotations() {
    }

    /**
     * True when the annotation is {@code qualifiedName}: by qualified name when the parser resolved
     * it, by simple name otherwise (sources that do not compile, wildcard imports in source mode).
     */
    static boolean is(AnnotationStructure annotation, String qualifiedName) {
        if (annotation.getQualifiedName() != null) {
            return annotation.getQualifiedName().equals(qualifiedName);
        }
        return annotation.getName().equals(simpleName(qualifiedName));
    }

    static Optional<AnnotationStructure> find(List<AnnotationStructure> annotations, String... qualifiedNames) {
        for (AnnotationStructure annotation : annotations) {
            for (String name : qualifiedNames) {
                if (is(annotation, name)) {
                    return Optional.of(annotation);
                }
            }
        }
        return Optional.empty();
    }

    static boolean has(List<AnnotationStructure> annotations, String... qualifiedNames) {
        return find(annotations, qualifiedNames).isPresent();
    }

    /** The raw value of the first present attribute, e.g. {@code value} then {@code name}. */
    static Optional<String> attribute(AnnotationStructure annotation, String... names) {
        if (annotation.getValues() == null) {
            return Optional.empty();
        }
        for (String name : names) {
            String value = annotation.getValues().get(name);
            if (value != null) {
                return Optional.of(value);
            }
        }
        return Optional.empty();
    }

    /** String literals in an attribute ({@code "a"} or {@code {"a", "b"}}). */
    static List<String> strings(AnnotationStructure annotation, String... names) {
        return attribute(annotation, names).map(Annotations::strings).orElse(List.of());
    }

    static List<String> strings(String raw) {
        List<String> result = new ArrayList<>();
        Matcher matcher = STRING.matcher(raw);
        while (matcher.find()) {
            result.add(matcher.group(1).replace("\\\"", "\"").replace("\\\\", "\\"));
        }
        return result;
    }

    /** Class literals in an attribute ({@code Foo.class} or {@code {A.class, b.C.class}}), as written. */
    static List<String> classes(AnnotationStructure annotation, String... names) {
        List<String> result = new ArrayList<>();
        attribute(annotation, names).ifPresent(raw -> {
            Matcher matcher = CLASS_LITERAL.matcher(raw);
            while (matcher.find()) {
                result.add(matcher.group(1));
            }
        });
        return result;
    }

    /** Enum constants of {@code enumType} in an attribute, e.g. RequestMethod.GET -> GET. */
    static List<String> constants(AnnotationStructure annotation, String enumType, String... names) {
        List<String> result = new ArrayList<>();
        attribute(annotation, names).ifPresent(raw -> {
            Matcher matcher = Pattern.compile("(?:" + enumType + "\\.)?\\b([A-Z][A-Z_]*)\\b").matcher(raw);
            while (matcher.find()) {
                result.add(matcher.group(1));
            }
        });
        return result;
    }

    static boolean isFalse(AnnotationStructure annotation, String name) {
        return attribute(annotation, name).map(v -> BOOLEAN_FALSE.matcher(v).matches()).orElse(false);
    }

    /** {@code @Name(values)} as it would be written, for display. */
    static String display(AnnotationStructure annotation) {
        if (annotation.getValues() == null || annotation.getValues().isEmpty()) {
            return "@" + annotation.getName();
        }
        StringBuilder text = new StringBuilder("@").append(annotation.getName()).append('(');
        annotation.getValues().forEach((k, v) -> {
            if (text.charAt(text.length() - 1) != '(') {
                text.append(", ");
            }
            text.append("value".equals(k) && annotation.getValues().size() == 1 ? v : k + " = " + v);
        });
        return text.append(')').toString();
    }

    static String simpleName(String qualifiedName) {
        return qualifiedName.substring(qualifiedName.lastIndexOf('.') + 1);
    }
}
