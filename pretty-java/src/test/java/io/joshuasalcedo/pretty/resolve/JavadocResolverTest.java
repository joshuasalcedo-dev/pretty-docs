package io.joshuasalcedo.pretty.resolve;

import io.joshuasalcedo.pretty.JavaParser;
import io.joshuasalcedo.pretty.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class JavadocResolverTest {

    @TempDir
    Path dir;

    @Test
    void resolvesNamesInheritsDocsAndListsInheritedMembers() throws IOException {
        write("com/example/shapes/Shape.java", """
                package com.example.shapes;
                /** A shape. */
                public interface Shape {
                    /**
                     * Computes the area.
                     * @param scale multiplier
                     * @return the area in square units
                     */
                    double area(double scale);

                    /** Human-readable name. */
                    String label();
                }
                """);
        write("com/example/shapes/Base.java", """
                package com.example.shapes;
                public abstract class Base implements Shape {
                    protected int id;
                    public int id() { return id; }
                }
                """);
        write("com/example/shapes/Circle.java", """
                package com.example.shapes;
                import java.util.*;
                public class Circle extends Base {
                    /** {@inheritDoc} For circles this is pi r squared. */
                    @Override public double area(double scale) { return 0; }
                    @Override public String label() { return "circle"; }
                    public List<Shape> neighbours(Map<String, Base> index) { return null; }
                    private Undefined broken() { return null; } // unresolvable, must not stop resolution
                }
                """);

        List<JavaFileStructure> files = JavaParser.parseDirectory(dir);
        TypeStructure circle = type(files, "Circle");
        MethodStructure neighbours = method(circle, "neighbours");
        assertNull(neighbours.getReturnType().getQualifiedName(), "wildcard import: unknown without a compiler");
        assertNull(circle.getSuperclass().getQualifiedName(), "same package: unknown without a compiler");

        List<String> warnings = new ArrayList<>();
        Resolution resolution = JavadocResolver.resolve(sources(), List.of(), StandardCharsets.UTF_8, warnings::add).orElseThrow();
        int resolved = JavadocResolver.apply(resolution, files);

        assertTrue(resolved > 0);
        assertEquals("com.example.shapes.Base", circle.getSuperclass().getQualifiedName());
        assertEquals("java.util.List", neighbours.getReturnType().getQualifiedName());
        assertEquals("com.example.shapes.Shape", neighbours.getReturnType().getTypeArguments().getFirst().getQualifiedName());
        TypeReference index = neighbours.getParameters().getFirst().getType();
        assertEquals("java.util.Map", index.getQualifiedName());
        assertEquals("com.example.shapes.Base", index.getTypeArguments().get(1).getQualifiedName());
        assertFalse(warnings.isEmpty(), "the unresolvable Undefined is reported");

        JavadocStructure area = method(circle, "area").getJavadoc();
        assertEquals("Computes the area. For circles this is pi r squared.", area.getDescription());
        assertEquals("com.example.shapes.Shape", area.getInheritedFrom());
        assertEquals(java.util.Arrays.asList("scale", null), area.getTags().stream().map(JavadocTagStructure::getParamName).toList());
        assertEquals("the area in square units", area.getTags().get(1).getText());

        JavadocStructure label = method(circle, "label").getJavadoc();
        assertEquals("Human-readable name.", label.getDescription(), "no own doc: inherited whole");

        assertTrue(circle.getInheritedMethods().stream().anyMatch(m -> m.getMethodName().equals("id")
                && m.getQualifiedDeclaringTypeName().equals("com.example.shapes.Base")));
        assertTrue(circle.getInheritedMethods().stream().anyMatch(m -> m.getMethodName().equals("hashCode")
                && m.getQualifiedDeclaringTypeName().equals("java.lang.Object")), "JDK members are included");
        assertTrue(circle.getInheritedMethods().stream().noneMatch(m -> m.getMethodName().equals("area")), "overridden methods are not inherited");
        assertEquals(List.of("id"), circle.getInheritedFields().stream().map(FieldReference::getFieldName).toList());
    }

    @Test
    void resolvesAgainstClasspath() throws IOException {
        write("com/example/App.java", """
                package com.example;
                import org.junit.jupiter.api.*;
                public class App { public TestInfo info() { return null; } }
                """);
        Path junitJar = Path.of(org.junit.jupiter.api.TestInfo.class.getProtectionDomain().getCodeSource().getLocation().getPath()
                .replaceFirst("^/(\\w:)", "$1"));

        List<JavaFileStructure> files = JavaParser.parseDirectory(dir);
        Resolution resolution = JavadocResolver.resolve(sources(), List.of(junitJar), StandardCharsets.UTF_8, w -> { }).orElseThrow();
        JavadocResolver.apply(resolution, files);

        assertEquals("org.junit.jupiter.api.TestInfo", method(type(files, "App"), "info").getReturnType().getQualifiedName());
    }

    @Test
    void mergeKeepsOwnDocWithoutInheritDoc() {
        JavadocStructure own = JavadocStructure.builder().description("Mine.").tags(List.of()).build();
        JavadocStructure theirs = JavadocStructure.builder().description("Theirs.").tags(List.of()).build();

        assertSame(own, JavadocResolver.merge(own, new Resolution.InheritedDoc("x.Y", theirs)));
        assertEquals(Map.of(), new Resolution(Map.of()).types());
    }

    private List<Path> sources() throws IOException {
        try (var walk = Files.walk(dir)) {
            return walk.filter(p -> p.toString().endsWith(".java")).toList();
        }
    }

    private void write(String path, String content) throws IOException {
        Path file = dir.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private static TypeStructure type(List<JavaFileStructure> files, String name) {
        return files.stream().flatMap(f -> f.getTypes().stream()).filter(t -> t.getName().equals(name)).findFirst().orElseThrow();
    }

    private static MethodStructure method(TypeStructure type, String name) {
        return type.getMethods().stream().filter(m -> m.getName().equals(name)).findFirst().orElseThrow();
    }
}
